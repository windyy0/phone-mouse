//! Read neighboring independent wolai blocks without broadening the write target.
use crate::text_sync::utf16_boundary;
use windows::Win32::UI::Accessibility::*;

const PREVIEW_UNITS: usize = 1_000;

/// DOM block identity survives replacement of its contenteditable child.
/// The root provider and process prevent matching another tab or window.
pub(super) struct Identity {
    root: IUIAutomationElement,
    process: i32,
    id: String,
}

impl Identity {
    pub(super) fn same(&self, other: &Self, automation: &IUIAutomation) -> bool {
        same_identity(
            &self.id,
            self.process,
            &other.id,
            other.process,
            unsafe { automation.CompareElements(&self.root, &other.root) }
                .is_ok_and(|same| same.as_bool()),
        )
    }
}

fn same_identity(
    old: &str,
    old_process: i32,
    new: &str,
    new_process: i32,
    same_root: bool,
) -> bool {
    !old.is_empty() && old == new && old_process == new_process && same_root
}

fn locate(
    automation: &IUIAutomation,
    element: &IUIAutomationElement,
) -> Option<(
    IUIAutomationTreeWalker,
    IUIAutomationElement,
    IUIAutomationElement,
)> {
    unsafe {
        let walker = automation.RawViewWalker().ok()?;
        let mut block = element.clone();
        for _ in 0..8 {
            let parent = walker.GetParentElement(&block).ok()?;
            if parent.CurrentAutomationId().ok()? == "wolai-main-editor" {
                return Some((walker, block, parent));
            }
            block = parent;
        }
        None
    }
}

pub(super) fn identity(
    automation: &IUIAutomation,
    element: &IUIAutomationElement,
) -> Option<Identity> {
    unsafe {
        if element.CurrentIsPassword().ok()?.as_bool()
            || !is_editor(&element.CurrentClassName().ok()?.to_string())
        {
            return None;
        }
        let (walker, block, root) = locate(automation, element)?;
        let id = block.CurrentAutomationId().ok()?.to_string();
        // Never identify a paragraph by its text, position or CSS class.
        if !id.starts_with("id-") || id.len() <= 3 || id.len() > 256 {
            return None;
        }
        // A table or composite block may contain several editors. Sharing
        // the outer block ID must never alias two different cells/fields.
        let mut remaining = 48;
        let mut found = None;
        find_single_editor(&walker, &block, &mut remaining, &mut found)?;
        if !automation
            .CompareElements(found.as_ref()?, element)
            .ok()?
            .as_bool()
        {
            return None;
        }
        Some(Identity {
            root,
            process: element.CurrentProcessId().ok()?,
            id,
        })
    }
}

fn find_single_editor(
    walker: &IUIAutomationTreeWalker,
    element: &IUIAutomationElement,
    remaining: &mut usize,
    found: &mut Option<IUIAutomationElement>,
) -> Option<()> {
    if *remaining == 0 {
        return None;
    }
    *remaining -= 1;
    unsafe {
        if is_editor(&element.CurrentClassName().ok()?.to_string()) {
            if found.is_some() {
                return None;
            }
            *found = Some(element.clone());
            return Some(());
        }
        let mut child = walker.GetFirstChildElement(element).ok();
        while let Some(element) = child {
            find_single_editor(walker, &element, remaining, found)?;
            child = walker.GetNextSiblingElement(&element).ok();
        }
    }
    Some(())
}

pub(super) fn is_editor(class: &str) -> bool {
    class
        .split_ascii_whitespace()
        .any(|value| value == "content-editable")
}

pub(super) fn context(
    automation: &IUIAutomation,
    element: &IUIAutomationElement,
    read: impl Fn(&IUIAutomationElement) -> windows::core::Result<String>,
) -> (bool, Option<String>, Option<String>) {
    let Some((walker, block, _)) = locate(automation, element) else {
        return (false, None, None);
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
    (true, neighbor(true), neighbor(false))
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
    fn only_the_same_named_block_in_the_same_root_and_process_can_rebind() {
        assert!(same_identity("id-block-a", 20, "id-block-a", 20, true));
        assert!(!same_identity("id-block-a", 20, "id-block-b", 20, true));
        assert!(!same_identity("id-block-a", 20, "id-block-a", 21, true));
        assert!(!same_identity("id-block-a", 20, "id-block-a", 20, false));
        assert!(!same_identity("", 20, "", 20, true));
    }
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
