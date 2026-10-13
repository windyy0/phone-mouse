//! Translate deletion of a verified generated marker into native list editing.
use super::ia2::ListMarker;
use crate::text_sync::{replacement, utf16_boundary};

pub(super) struct MarkerDeletion {
    pub caret: usize,
    accepted: Vec<(String, usize)>,
}
impl MarkerDeletion {
    pub fn confirmed_caret(&self, actual: &str) -> Option<usize> {
        self.accepted
            .iter()
            .find_map(|(text, caret)| (text == actual).then_some(*caret))
    }
}

pub(super) fn marker_deletion(
    before: &str,
    requested: &str,
    start: usize,
    end: usize,
    markers: &[ListMarker],
) -> Option<MarkerDeletion> {
    let (from, to, inserted) = replacement(before, requested);
    if !inserted.is_empty() || from == to || start != end || start != from {
        return None;
    }
    let marker = markers.iter().find(|marker| {
        from >= marker.start
            && from < marker.body_start
            && to
                <= if marker.empty {
                    marker.body_end
                } else {
                    marker.body_start
                }
    })?;
    let left = utf16_boundary(before, marker.start)?;
    let body = utf16_boundary(before, marker.body_start)?;
    let body_end = utf16_boundary(before, marker.body_end)?;
    let prefix = &before[..left];
    let mut accepted = vec![(format!("{}{}", prefix, &before[body..]), marker.start)];
    if marker.empty {
        // An empty item's filler may disappear when its list wrapper is lifted.
        accepted.push((format!("{}{}", prefix, &before[body_end..]), marker.start));
        if body_end == before.len()
            && let Some(previous) = prefix.strip_suffix('\n')
        {
            // Backspace can also join an empty final item into the previous one.
            accepted.push((previous.to_string(), previous.encode_utf16().count()));
        }
    }
    Some(MarkerDeletion {
        caret: marker.body_start,
        accepted,
    })
}

pub(super) fn filler_normalization(
    before: &str,
    requested: &str,
    actual: &str,
    caret: usize,
    markers: &[ListMarker],
) -> bool {
    let (from, to, inserted) = replacement(before, requested);
    inserted.is_empty()
        && from < to
        && caret == from
        && actual == format!("{requested}\n")
        && markers.iter().any(|marker| {
            !marker.empty
                && from == marker.body_start
                && to == marker.body_end
                && marker.body_end == before.encode_utf16().count()
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn deleting_an_empty_generated_marker_accepts_only_the_native_list_exit() {
        let marker = ListMarker {
            start: 0,
            body_start: 2,
            body_end: 3,
            empty: true,
        };
        for (requested, caret) in [("•\n", 1), (" \n", 0), ("\n", 0), ("", 0)] {
            let plan = marker_deletion("• \n", requested, caret, caret, &[marker]).unwrap();
            assert_eq!(plan.caret, 2);
            assert_eq!(plan.confirmed_caret(""), Some(0));
            assert_eq!(plan.confirmed_caret("\n"), Some(0));
            assert!(plan.confirmed_caret("• \n").is_none());
            assert!(plan.confirmed_caret("别的文字").is_none());
        }
        assert!(marker_deletion("• \n", "•\n", 1, 1, &[]).is_none());
        assert!(marker_deletion("• \n", "• ", 2, 2, &[marker]).is_none());
    }
    #[test]
    fn marker_deletion_preserves_body_and_all_other_paragraphs() {
        let before = "前😀\n• 正文\n尾";
        let marker = ListMarker {
            start: 4,
            body_start: 6,
            body_end: 8,
            empty: false,
        };
        let plan = marker_deletion(before, "前😀\n•正文\n尾", 5, 5, &[marker]).unwrap();
        assert_eq!(plan.confirmed_caret("前😀\n正文\n尾"), Some(4));
        assert!(plan.confirmed_caret("前😀\n正\n尾").is_none());
        assert!(marker_deletion(before, "前😀\n• 文\n尾", 6, 6, &[marker]).is_none());
        assert!(marker_deletion(before, "前😀\n•正文\n尾", 0, 0, &[marker]).is_none());
    }
    #[test]
    fn an_empty_last_item_can_join_without_losing_previous_text() {
        let marker = ListMarker {
            start: 4,
            body_start: 6,
            body_end: 7,
            empty: true,
        };
        let plan = marker_deletion("• 甲\n• \n", "• 甲\n•\n", 5, 5, &[marker]).unwrap();
        assert_eq!(plan.confirmed_caret("• 甲"), Some(3));
        assert!(plan.confirmed_caret("甲").is_none());
    }
    #[test]
    fn deleting_the_last_body_character_can_reintroduce_a_verified_list_filler() {
        let marker = ListMarker {
            start: 0,
            body_start: 2,
            body_end: 4,
            empty: false,
        };
        assert!(filler_normalization("• 😀", "• ", "• \n", 2, &[marker]));
        assert!(!filler_normalization("• 😀", "• ", "• \n", 2, &[]));
        assert!(!filler_normalization(
            "• 😀",
            "• ",
            "• 别的文字",
            2,
            &[marker]
        ));
    }
}
