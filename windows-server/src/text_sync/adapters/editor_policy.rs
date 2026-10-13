//! Application-specific text presentation rules, separate from UIA mechanics.
use super::block_context;

pub(super) fn is_placeholder_content(value: &str, name: &str, class_name: &str) -> bool {
    // QQ's empty ProseMirror document exposes its sole trailingBreak as a
    // newline, which disappears on insertion and reappears on deletion.
    let empty_rich_filler = value == "\n"
        && class_name
            .split_ascii_whitespace()
            .any(|class| class == "prosemirror")
        && class_name
            .split_ascii_whitespace()
            .any(|class| class == "is-empty");
    // Empty wolai blocks expose their generated ::before placeholder as an
    // embedded object. Never send that presentation object as editable text.
    empty_rich_filler
        || (block_context::is_editor(class_name)
            && !value.is_empty()
            && value
                .chars()
                .all(|ch| matches!(ch, '\u{fffc}' | '\n' | '\r')))
        || (class_name.contains("prosemirror")
            && value.ends_with('\n')
            && value.trim_end_matches('\n') == name.trim_end_matches('\n')
            && !name.trim_end_matches('\n').is_empty())
}

pub(super) fn is_rich_editor(class_name: &str) -> bool {
    class_name.to_ascii_lowercase().contains("prosemirror") || block_context::is_editor(class_name)
}

pub(super) fn is_code_input_proxy(class_name: &str) -> bool {
    let classes = class_name.split_ascii_whitespace().collect::<Vec<_>>();
    classes.contains(&"native-edit-context")
        || (classes.contains(&"inputarea") && classes.contains(&"monaco-mouse-cursor-text"))
}
