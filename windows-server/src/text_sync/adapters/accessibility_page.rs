//! Reject proxy hints and anchor paged text to a real desktop position.
use super::ia2::AccessibleDocument;
use crate::text_sync::utf16_boundary;
use windows::{Win32::UI::Accessibility::*, core::BSTR};

#[derive(Default)]
pub(super) struct PositionReader {
    cached: std::cell::RefCell<Option<(usize, IUIAutomationElement)>>,
}
impl PositionReader {
    pub fn read(
        &self,
        automation: &IUIAutomation,
        window: windows_sys::Win32::Foundation::HWND,
    ) -> Option<(usize, usize)> {
        unsafe {
            let key = window as usize;
            let cached = self
                .cached
                .borrow()
                .as_ref()
                .filter(|(root, _)| *root == key)
                .map(|(_, element)| element.clone());
            if let Some(element) = cached
                && element
                    .CurrentIsOffscreen()
                    .is_ok_and(|value| !value.as_bool())
                && let Some(position) = element
                    .CurrentName()
                    .ok()
                    .and_then(|name| position(&name.to_string()))
            {
                return Some(position);
            }
            *self.cached.borrow_mut() = None;
            let root = automation
                .ElementFromHandle(windows::Win32::Foundation::HWND(window))
                .ok()?;
            let condition = automation
                .CreatePropertyCondition(
                    UIA_AutomationIdPropertyId,
                    &windows::Win32::System::Variant::VARIANT::from(BSTR::from(
                        "status.editor.selection",
                    )),
                )
                .ok()?;
            let element = root.FindFirst(TreeScope_Descendants, &condition).ok()?;
            if element.CurrentIsOffscreen().ok()?.as_bool() {
                return None;
            }
            let position = position(&element.CurrentName().ok()?.to_string())?;
            *self.cached.borrow_mut() = Some((key, element));
            Some(position)
        }
    }
}

pub(super) fn position(label: &str) -> Option<(usize, usize)> {
    let first = label.find(|ch: char| ch.is_ascii_digit())?;
    let line_end = first + label[first..].find(|ch: char| !ch.is_ascii_digit())?;
    let second = line_end + label[line_end..].find(|ch: char| ch.is_ascii_digit())?;
    let separator = &label[line_end..second];
    if !separator.contains([',', '，', '、', '،']) {
        return None;
    }
    let column_end = second
        + label[second..]
            .find(|ch: char| !ch.is_ascii_digit())
            .unwrap_or(label.len() - second);
    let line = label[first..line_end].parse().ok()?;
    let column = label[second..column_end].parse().ok()?;
    (line > 0 && column > 0).then_some((line, column))
}

pub(super) fn origin(document: &AccessibleDocument, desktop: (usize, usize)) -> Option<usize> {
    let caret = utf16_boundary(&document.text, document.caret)?;
    let preceding_lines = document.text[..caret]
        .bytes()
        .filter(|ch| *ch == b'\n')
        .count();
    let first_line = desktop.0.checked_sub(preceding_lines)?;
    (first_line > 0).then_some(first_line)
}

pub(super) fn genuine(text: &str, name: &str, accessible: Option<&AccessibleDocument>) -> bool {
    !text.is_empty()
        && text.trim_end_matches('\n') != name.trim_end_matches('\n')
        && accessible.is_some_and(|value| value.text == text)
}

/// A page may lose its last lines after an insertion. Confirm only if the
/// changed range is still visible and all returned characters match exactly.
pub(super) fn confirms(expected: &str, actual: &str, changed_end: usize) -> bool {
    actual == expected
        || (!actual.is_empty()
            && actual.ends_with('\n')
            && expected.starts_with(actual)
            && actual.encode_utf16().count() >= changed_end)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn page_confirmation_requires_the_edit_and_all_visible_surrounding_text() {
        assert!(confirms("new\nkept\nold-tail\n", "new\nkept\n", 4));
        assert!(!confirms("new\nkept\nold-tail\n", "new\n", 8));
        assert!(!confirms("new\nkept\n", "new\nchanged\n", 4));
        assert!(!confirms("new\nkept\n", "new", 3));
    }
    #[test]
    fn localized_positions_anchor_pages_and_reject_multicursor_status() {
        assert_eq!(position("Ln 3501, Col 4"), Some((3501, 4)));
        assert_eq!(
            position("第 3501 行，第 4 列（已选择 2 个字符）"),
            Some((3501, 4))
        );
        assert!(position("2 selections (4 characters selected)").is_none());
        assert!(position("Ln 0, Col 1").is_none());
        let value = AccessibleDocument {
            text: "一\n二\n三".into(),
            start: 4,
            end: 4,
            caret: 4,
        };
        assert_eq!(origin(&value, (3501, 1)), Some(3499));
        assert!(origin(&value, (2, 1)).is_none());
        assert!(genuine(&value.text, "Editor content", Some(&value)));
        assert!(!genuine(&value.text, &value.text, Some(&value)));
        assert!(!genuine(&value.text, "Editor content", None));
        assert!(!genuine("", "Editor content", Some(&value)));
    }
}
