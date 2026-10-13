//! Read neighboring independent wolai blocks without broadening the write target.
use crate::text_sync::utf16_boundary;
use windows::Win32::UI::Accessibility::*;

const PREVIEW_UNITS: usize = 1_000;

pub(super) fn is_editor(class: &str) -> bool {
    class
        .split_ascii_whitespace()
        .any(|value| value == "content-editable")
}

pub(super) fn context(
    automation: &IUIAutomation,
    element: &IUIAutomationElement,
    read: impl Fn(&IUIAutomationElement) -> windows::core::Result<String>,
) -> (Option<String>, Option<String>) {
    let locate = || unsafe {
        let walker = automation.RawViewWalker().ok()?;
        let mut block = element.clone();
        for _ in 0..8 {
            let parent = walker.GetParentElement(&block).ok()?;
            if parent.CurrentAutomationId().ok()? == "wolai-main-editor" {
                return Some((walker, block));
            }
            block = parent;
        }
        None
    };
    let Some((walker, block)) = locate() else {
        return (None, None);
    };
    let neighbor = |previous| unsafe {
        let sibling = if previous {
            walker.GetPreviousSiblingElement(&block)
        } else {
            walker.GetNextSiblingElement(&block)
        }
        .ok()?;
        let mut remaining = 48;
        let value = block_text(&walker, &sibling, &read, &mut remaining)?;
        Some(preview(&value, previous))
    };
    (neighbor(true), neighbor(false))
}

fn block_text(
    walker: &IUIAutomationTreeWalker,
    element: &IUIAutomationElement,
    read: &impl Fn(&IUIAutomationElement) -> windows::core::Result<String>,
    remaining: &mut usize,
) -> Option<String> {
    if *remaining == 0 {
        return None;
    }
    *remaining -= 1;
    unsafe {
        if element.CurrentIsPassword().ok()?.as_bool() {
            return None;
        }
        if is_editor(&element.CurrentClassName().ok()?.to_string()) {
            return read(element).ok();
        }
        let mut child = walker.GetFirstChildElement(element).ok();
        while let Some(element) = child {
            if let Some(text) = block_text(walker, &element, read, remaining) {
                return Some(text);
            }
            if *remaining == 0 {
                break;
            }
            child = walker.GetNextSiblingElement(&element).ok();
        }
        None
    }
}

fn preview(text: &str, previous: bool) -> String {
    let units = text.encode_utf16().count();
    if units <= PREVIEW_UNITS {
        return text.to_string();
    }
    if previous {
        let mut start = units - PREVIEW_UNITS;
        while utf16_boundary(text, start).is_none() {
            start += 1;
        }
        text[utf16_boundary(text, start).unwrap()..].to_string()
    } else {
        let mut end = PREVIEW_UNITS;
        while utf16_boundary(text, end).is_none() {
            end -= 1;
        }
        text[..utf16_boundary(text, end).unwrap()].to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bounded_neighbor_previews_keep_utf16_boundaries_and_empty_blocks() {
        let text = format!("{}😀尾", "甲".repeat(1_000));
        assert_eq!(preview(&text, false), "甲".repeat(1_000));
        assert!(preview(&text, true).ends_with("😀尾"));
        assert!(preview(&text, true).encode_utf16().count() <= PREVIEW_UNITS);
        assert_eq!(preview("", true), "");
        assert!(is_editor("content-editable _2VNwp"));
        assert!(!is_editor("content-image"));
    }
}
