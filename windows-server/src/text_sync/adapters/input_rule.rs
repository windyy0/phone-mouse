//! Recognize a narrow, lossless Markdown list shortcut at the insertion caret.
use crate::text_sync::{replacement, utf16_boundary};

pub(super) fn list_normalization(
    before: &str,
    requested: &str,
    actual: &str,
    caret: usize,
) -> bool {
    let (from, to, inserted) = replacement(before, requested);
    // A phone batch may include the shortcut and its following body. The
    // injected characters still trigger the rule at the first space.
    if from != to || inserted.is_empty() || from + inserted.encode_utf16().count() != caret {
        return false;
    }
    let Some(at) = utf16_boundary(requested, caret) else {
        return false;
    };
    let left = requested[..at].rfind('\n').map_or(0, |index| index + 1);
    let Some(marker) = requested[left..].get(..2) else {
        return false;
    };
    if !matches!(marker, "- " | "* " | "+ ") {
        return false;
    }
    let trigger = requested[..left].encode_utf16().count() + 2;
    if from >= trigger || caret < trigger {
        return false;
    }
    let prefix = &requested[..left];
    let suffix = &requested[left + 2..];
    // Preserve every surrounding character. Generated bullets are presentation
    // text exposed by Chromium's accessible list item, not a dropped insertion.
    for bullet in ["• ", "· "] {
        if actual == format!("{prefix}{bullet}{suffix}") {
            return true;
        }
        // Empty rich paragraphs may expose one terminal filler line break.
        if suffix.is_empty() && actual == format!("{prefix}{bullet}\n") {
            return true;
        }
    }
    false
}

pub(super) fn empty_list_filler_normalization(
    before: &str,
    requested: &str,
    actual: &str,
    caret: usize,
) -> bool {
    let Some(body) = before.strip_suffix('\n') else {
        return false;
    };
    let last_line = body.rsplit('\n').next().unwrap_or_default();
    let (from, to, inserted) = replacement(before, requested);
    matches!(last_line, "• " | "· ")
        && from == to
        && from == body.encode_utf16().count()
        && !inserted.is_empty()
        && !inserted.contains(['\n', '\t'])
        && from + inserted.encode_utf16().count() == caret
        && requested.strip_suffix('\n') == Some(actual)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn typed_list_shortcut_preserves_all_surrounding_text() {
        assert!(list_normalization("-", "- ", "• ", 2));
        assert!(list_normalization("", "- ", "• \n", 2));
        assert!(list_normalization("+", "+ ", "· ", 2));
        assert!(list_normalization("", "- 正文", "• 正文", 4));
        assert!(list_normalization(
            "😀\n*文本",
            "😀\n* 文本",
            "😀\n• 文本",
            5
        ));
        assert!(list_normalization(
            "• 前项\n-",
            "• 前项\n- ",
            "• 前项\n• ",
            7
        ));
    }

    #[test]
    fn unrelated_changes_or_non_shortcut_edits_cannot_be_acknowledged() {
        for (before, request, actual, caret) in [
            ("-", "- ", "", 2),
            ("-", "- ", "• 别人的文字", 2),
            ("-", "- ", "• \n\n", 2),
            ("a-", "a- ", "a• ", 3),
            ("- 文本", "- 文本!", "• 文本!", 5),
            ("other", "- ", "• ", 2),
            ("- ", "- 文本", "• 文本", 4),
            ("-", "- ", "• ", 0),
            ("😀\n-", "😀\n- ", "😄\n• ", 5),
        ] {
            assert!(
                !list_normalization(before, request, actual, caret),
                "{request:?} -> {actual:?}"
            );
        }
    }

    #[test]
    fn only_an_empty_list_paragraph_filler_can_disappear_on_body_insertion() {
        assert!(empty_list_filler_normalization(
            "• \n",
            "• 正文\n",
            "• 正文",
            4
        ));
        assert!(empty_list_filler_normalization(
            "前项\n• \n",
            "前项\n• 😀\n",
            "前项\n• 😀",
            7
        ));
        assert!(!empty_list_filler_normalization(
            "正文\n",
            "正文!\n",
            "正文!",
            3
        ));
        assert!(!empty_list_filler_normalization(
            "• 原文\n",
            "• 原文!\n",
            "• 原文!",
            5
        ));
        assert!(!empty_list_filler_normalization(
            "• \n",
            "• 正文\n",
            "• 别的内容",
            4
        ));
    }
}
