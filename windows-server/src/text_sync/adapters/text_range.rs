//! Locate UTF-16 text boundaries without equating them with UIA character units.
use crate::text_sync::{MAX_DOCUMENT_UNITS, RequestDeadline, normalize};
use windows::{
    Win32::UI::Accessibility::{
        IUIAutomationTextPattern, IUIAutomationTextRange, TextPatternRangeEndpoint_End,
        TextPatternRangeEndpoint_Start, TextUnit_Character,
    },
    core::{Error, HRESULT, Result},
};

fn range_error() -> Error {
    Error::from_hresult(HRESULT(0x80004005u32 as i32))
}

struct Probe<T> {
    prefix: T,
    units: usize,
    moved: usize,
}

fn find_boundary<T>(
    offset: usize,
    mut measure: impl FnMut(usize) -> Result<Probe<T>>,
) -> Result<T> {
    if offset == 0 {
        let value = measure(0)?;
        return if value.units == 0 {
            Ok(value.prefix)
        } else {
            Err(range_error())
        };
    }
    let mut low = 0;
    let mut high = offset;
    let mut found = None;
    // Rich providers count structural positions that contribute no text.
    // Measure an upper bound before searching; UTF-16 offset alone is not one.
    loop {
        let value = measure(high)?;
        if value.units >= offset {
            high = high.min(value.moved);
            if value.units == offset {
                found = Some(value.prefix);
            }
            break;
        }
        if value.moved < high || high == i32::MAX as usize {
            return Err(range_error());
        }
        low = high + 1;
        high = high.saturating_mul(2).min(i32::MAX as usize);
    }
    // Prefer the first native position with this exact textual prefix.
    while low <= high {
        let characters = low + (high - low) / 2;
        let value = measure(characters)?;
        if value.units >= offset {
            if value.units == offset {
                found = Some(value.prefix);
            }
            if characters == 0 {
                break;
            }
            high = characters - 1;
        } else {
            low = characters + 1;
        }
    }
    found.ok_or_else(range_error)
}

fn prefix_at(
    pattern: &IUIAutomationTextPattern,
    offset: usize,
    deadline: RequestDeadline,
) -> Result<IUIAutomationTextRange> {
    unsafe {
        deadline.check().map_err(|_| range_error())?;
        let document = pattern.DocumentRange()?;
        find_boundary(offset, |characters| {
            deadline.check().map_err(|_| range_error())?;
            let range = document.Clone()?;
            range.MoveEndpointByRange(
                TextPatternRangeEndpoint_End,
                &document,
                TextPatternRangeEndpoint_Start,
            )?;
            let moved = range.MoveEndpointByUnit(
                TextPatternRangeEndpoint_End,
                TextUnit_Character,
                characters as i32,
            )?;
            let units = normalize(&range.GetText((MAX_DOCUMENT_UNITS + 1) as i32)?.to_string())
                .encode_utf16()
                .count();
            Ok(Probe {
                prefix: range,
                units,
                moved: usize::try_from(moved).map_err(|_| range_error())?,
            })
        })
    }
}

pub(super) fn text_range(
    pattern: &IUIAutomationTextPattern,
    start: usize,
    end: usize,
    deadline: RequestDeadline,
) -> Result<IUIAutomationTextRange> {
    unsafe {
        let from = prefix_at(pattern, start, deadline)?;
        let to = prefix_at(pattern, end, deadline)?;
        deadline.check().map_err(|_| range_error())?;
        let range = from.Clone()?;
        range.MoveEndpointByRange(
            TextPatternRangeEndpoint_Start,
            &from,
            TextPatternRangeEndpoint_End,
        )?;
        range.MoveEndpointByRange(
            TextPatternRangeEndpoint_End,
            &to,
            TextPatternRangeEndpoint_End,
        )?;
        Ok(range)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn mapped_boundary(offset: usize, positions: &[usize]) -> Result<usize> {
        find_boundary(offset, |characters| {
            let moved = characters.min(positions.len() - 1);
            Ok(Probe {
                prefix: moved,
                units: positions[moved],
                moved,
            })
        })
    }

    #[test]
    fn markdown_list_boundaries_include_positions_that_add_no_text() {
        // Captured provider: 8 character moves expose only 7 UTF-16 units.
        let positions = [0, 1, 2, 3, 3, 4, 5, 6, 7, 8];
        assert_eq!(mapped_boundary(8, &positions).unwrap(), 9);
        assert_eq!(mapped_boundary(4, &positions).unwrap(), 5);
        assert_eq!(mapped_boundary(3, &positions).unwrap(), 3);
        assert_eq!(mapped_boundary(0, &positions).unwrap(), 0);
    }

    #[test]
    fn formatted_blocks_with_multiple_structural_positions_keep_exact_carets() {
        let positions = [0, 0, 0, 1, 2, 2, 2, 3, 4, 4, 4, 5, 6];
        for (offset, expected) in [(1, 3), (2, 4), (3, 7), (4, 8), (5, 11), (6, 12)] {
            assert_eq!(mapped_boundary(offset, &positions).unwrap(), expected);
        }
    }

    #[test]
    fn emoji_and_combined_characters_never_select_an_unreachable_utf16_boundary() {
        let positions = [0, 2, 3, 5];
        assert!(mapped_boundary(1, &positions).is_err());
        assert!(mapped_boundary(4, &positions).is_err());
        assert_eq!(mapped_boundary(2, &positions).unwrap(), 1);
        assert_eq!(mapped_boundary(3, &positions).unwrap(), 2);
        assert_eq!(mapped_boundary(5, &positions).unwrap(), 3);
    }

    #[test]
    fn missing_boundaries_and_provider_errors_are_not_guessed() {
        assert!(mapped_boundary(4, &[0, 1, 2]).is_err());
        let error = find_boundary::<usize>(2, |_| Err(range_error()));
        assert!(error.is_err());
    }
}
