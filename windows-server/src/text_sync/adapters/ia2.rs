//! Read-only IAccessible2 access on the exact UIA element, without hooks or a
//! screen-reader installation. ABI: LinuxA11y/IAccessible2/api/AccessibleText.idl.
use crate::text_sync::{MAX_DOCUMENT_UNITS, normalize, utf16_boundary};
use std::ffi::c_void;
use std::time::{Duration, Instant};
use windows::{
    Win32::{
        System::{
            Com::IServiceProvider,
            Variant::{VARIANT, VT_DISPATCH, VT_I4, VT_UNKNOWN, VariantClear},
        },
        UI::Accessibility::*,
    },
    core::{BSTR, GUID, HRESULT, IUnknown_Vtbl, Interface},
};

#[repr(transparent)]
#[derive(Clone)]
struct AccessibleText(windows::core::IUnknown);
// SAFETY: this is a single owning COM pointer with IUnknown as the vtable prefix;
// the IID and method offsets below follow the public IAccessible2 ABI.
unsafe impl Interface for AccessibleText {
    type Vtable = AccessibleTextVtbl;
    const IID: GUID = GUID::from_u128(0x24fd2ffb_3aad_4a08_8335_a3ad89c0fb4b);
}
#[repr(C)]
struct AccessibleTextVtbl {
    base: IUnknown_Vtbl,
    // Unused ABI slots remain pointer-sized; no selection setters are called.
    before_caret: [usize; 2],
    caret: unsafe extern "system" fn(*mut c_void, *mut i32) -> HRESULT,
    extents: usize,
    selection_count: unsafe extern "system" fn(*mut c_void, *mut i32) -> HRESULT,
    offset_at_point: usize,
    selection: unsafe extern "system" fn(*mut c_void, i32, *mut i32, *mut i32) -> HRESULT,
    text: unsafe extern "system" fn(*mut c_void, i32, i32, *mut BSTR) -> HRESULT,
    before_character_count: [usize; 6],
    character_count: unsafe extern "system" fn(*mut c_void, *mut i32) -> HRESULT,
    after_character_count: [usize; 4],
}

#[repr(transparent)]
#[derive(Clone)]
struct AccessibleHypertext(windows::core::IUnknown);
// SAFETY: AccessibleHypertext inherits the complete AccessibleText ABI.
unsafe impl Interface for AccessibleHypertext {
    type Vtable = AccessibleHypertextVtbl;
    const IID: GUID = GUID::from_u128(0x6b4f8bbf_f1f2_418a_b35e_a195bc4103b9);
}
#[repr(C)]
struct AccessibleHypertextVtbl {
    base: AccessibleTextVtbl,
    hyperlink_count: usize,
    hyperlink: unsafe extern "system" fn(*mut c_void, i32, *mut *mut c_void) -> HRESULT,
    hyperlink_index: unsafe extern "system" fn(*mut c_void, i32, *mut i32) -> HRESULT,
}

#[repr(transparent)]
#[derive(Clone)]
struct Accessible2(windows::core::IUnknown);
// SAFETY: Accessible2 inherits MSAA IAccessible, then the 18 public IA2 slots.
unsafe impl Interface for Accessible2 {
    type Vtable = Accessible2Vtbl;
    const IID: GUID = GUID::from_u128(0xe89f726e_c4f4_4c19_bb19_b647d7fa8478);
}
#[repr(C)]
struct Accessible2Vtbl {
    base: IAccessible_Vtbl,
    before_attributes: [usize; 17],
    attributes: unsafe extern "system" fn(*mut c_void, *mut BSTR) -> HRESULT,
}

fn query<T: Interface>(accessible: &IAccessible) -> Option<T> {
    unsafe {
        let service = accessible.cast::<IServiceProvider>().ok()?;
        service
            .QueryService(&Accessible2::IID)
            .or_else(|_| service.QueryService(&IAccessible::IID))
            .ok()
    }
}

struct TextNode {
    raw: String,
    caret: Option<usize>,
    selection: Option<(usize, usize)>,
    block: bool,
    list_item: bool,
    generated: bool,
    children: Vec<(usize, TextNode)>,
}
struct ReadBudget {
    nodes: usize,
    units: usize,
    deadline: Instant,
}

fn text_node(accessible: &IAccessible, depth: usize, budget: &mut ReadBudget) -> Option<TextNode> {
    if depth > 32 || budget.nodes >= 2048 || Instant::now() >= budget.deadline {
        return None;
    }
    budget.nodes += 1;
    unsafe {
        let mut state = accessible.get_accState(&VARIANT::from(0i32)).ok()?;
        let protected = state.Anonymous.Anonymous.vt != VT_I4
            || state.Anonymous.Anonymous.Anonymous.lVal as u32 & 0x20000000 != 0;
        let _ = VariantClear(&mut state);
        if protected {
            return None;
        }
        let text: AccessibleText = query(accessible)?;
        let vtable = text.vtable();
        let pointer = text.as_raw();
        let mut count = -1;
        success((vtable.character_count)(pointer, &mut count))?;
        let count = usize::try_from(count).ok()?;
        budget.units = budget.units.checked_add(count)?;
        if budget.units > MAX_DOCUMENT_UNITS {
            return None;
        }
        let mut raw = BSTR::new();
        let result = (vtable.text)(pointer, 0, count as i32, &mut raw);
        if count != 0 {
            success(result)?;
        }
        let raw = raw.to_string();
        if raw.encode_utf16().count() != count {
            return None;
        }
        let mut caret = -1;
        let caret =
            success((vtable.caret)(pointer, &mut caret)).and_then(|_| usize::try_from(caret).ok());
        let mut selections = -1;
        success((vtable.selection_count)(pointer, &mut selections))?;
        let selection = match selections {
            0 => None,
            1 => {
                let (mut start, mut end) = (-1, -1);
                success((vtable.selection)(pointer, 0, &mut start, &mut end))?;
                Some((usize::try_from(start).ok()?, usize::try_from(end).ok()?))
            }
            _ => return None,
        };
        let mut attributes = BSTR::new();
        if let Some(value) = query::<Accessible2>(accessible) {
            let _ = (value.vtable().attributes)(value.as_raw(), &mut attributes);
        }
        let attributes = attributes.to_string();
        let generated = attributes.split(';').any(|attr| {
            matches!(
                attr,
                r"tag:\:\:before" | r"tag:\:\:after" | "tag:::before" | "tag:::after"
            )
        });
        let block = attributes
            .split(';')
            .any(|attr| matches!(attr, "display:block" | "display:list-item"));
        let list_item = attributes
            .split(';')
            .any(|attr| matches!(attr, "display:list-item" | "tag:li"));
        let mut children = Vec::new();
        if raw.contains('\u{fffc}') {
            let hypertext: AccessibleHypertext = query(accessible)?;
            for (offset, ch) in raw.encode_utf16().enumerate() {
                if ch != 0xfffc {
                    continue;
                }
                let mut index = -1;
                success((hypertext.vtable().hyperlink_index)(
                    hypertext.as_raw(),
                    offset as i32,
                    &mut index,
                ))?;
                if index < 0 {
                    return None;
                }
                let mut pointer = std::ptr::null_mut();
                let result =
                    (hypertext.vtable().hyperlink)(hypertext.as_raw(), index, &mut pointer);
                // Own every returned COM reference even when the provider errs.
                let hyperlink =
                    (!pointer.is_null()).then(|| windows::core::IUnknown::from_raw(pointer));
                success(result)?;
                let child = hyperlink?.cast::<IAccessible>().ok()?;
                children.push((offset, text_node(&child, depth + 1, budget)?));
            }
        }
        Some(TextNode {
            raw,
            caret,
            selection,
            block,
            list_item,
            generated,
            children,
        })
    }
}

struct ProjectedText {
    text: String,
    caret: Option<usize>,
    selection: Option<(usize, usize)>,
    markers: Vec<ListMarker>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct ListMarker {
    pub start: usize,
    pub body_start: usize,
    pub body_end: usize,
    pub empty: bool,
}

/// Expand only explicit hypertext objects. Block boundaries become paragraph
/// breaks; inline spans stay adjacent. UIA must independently return this exact
/// text before the caller accepts any of these offsets.
fn project(node: &TextNode) -> Option<ProjectedText> {
    project_with_empty_blocks(node, false)
}

fn project_with_empty_blocks(node: &TextNode, remove_empty_marker: bool) -> Option<ProjectedText> {
    // Chromium's filler <br> in an otherwise empty block has IA2 text "\n",
    // while UIA omits the filler. Try this interpretation only if the complete
    // resulting document independently matches UIA's actual input text.
    if remove_empty_marker && node.block && node.raw == "\n" && node.children.is_empty() {
        if node.caret.is_some_and(|at| at > 1)
            || node
                .selection
                .is_some_and(|(start, end)| start > 1 || end > 1)
        {
            return None;
        }
        return Some(ProjectedText {
            text: String::new(),
            caret: node.caret.map(|_| 0),
            selection: node.selection.map(|_| (0, 0)),
            markers: Vec::new(),
        });
    }
    let mut output = String::new();
    let mut units = 0usize;
    let count = node.raw.encode_utf16().count();
    let mut offsets = vec![None; count + 1];
    let mut child_ranges = Vec::new();
    let mut child_caret = None;
    let mut markers = Vec::new();
    let mut raw_offset = 0;
    let mut previous_block = false;
    let mut previous_empty_block = false;
    for ch in node.raw.chars() {
        if ch == '\u{fffc}' {
            let (_, child) = node.children.iter().find(|(at, _)| *at == raw_offset)?;
            let value = project_with_empty_blocks(child, remove_empty_marker)?;
            // IA2 puts the generated bullet/number in the list item's raw
            // prefix, followed by an embedded paragraph for its body. Both
            // belong to the same displayed line. Nested lists still need a
            // boundary after that first body paragraph.
            let list_body = node.list_item
                && node
                    .children
                    .first()
                    .is_some_and(|(at, _)| *at == raw_offset);
            if (previous_block || child.block)
                && raw_offset != 0
                && !list_body
                && (!output.ends_with('\n') || previous_empty_block)
            {
                output.push('\n');
                units += 1;
            }
            offsets[raw_offset] = Some(units);
            if list_body && matches!(output.as_str(), "• " | "· " | "•" | "·") {
                markers.push(ListMarker {
                    start: 0,
                    body_start: units,
                    body_end: units + value.text.encode_utf16().count(),
                    empty: matches!(value.text.as_str(), "" | "\n"),
                });
            }
            markers.extend(value.markers.iter().map(|marker| ListMarker {
                start: units + marker.start,
                body_start: units + marker.body_start,
                body_end: units + marker.body_end,
                empty: marker.empty,
            }));
            if let Some(caret) = value.caret {
                if child_caret.replace(units + caret).is_some() {
                    return None;
                }
            }
            child_ranges.push((raw_offset, units, value.selection));
            output.push_str(&value.text);
            units += value.text.encode_utf16().count();
            raw_offset += 1;
            offsets[raw_offset] = Some(units);
            previous_block = child.block;
            previous_empty_block = child.block && value.text.is_empty();
        } else {
            if ch.is_control() && !matches!(ch, '\r' | '\n' | '\t') {
                return None;
            }
            offsets[raw_offset] = Some(units);
            output.push(ch);
            units += ch.len_utf16();
            raw_offset += ch.len_utf16();
            offsets[raw_offset] = Some(units);
            previous_block = false;
            previous_empty_block = false;
        }
    }
    offsets[count] = Some(units);
    let map = |at: usize| -> Option<usize> {
        let expanded = *offsets.get(at)?.as_ref()?;
        let byte = utf16_boundary(&output, expanded)?;
        Some(normalize(&output[..byte]).encode_utf16().count())
    };
    let caret = if let Some(caret) = child_caret {
        let byte = utf16_boundary(&output, caret)?;
        Some(normalize(&output[..byte]).encode_utf16().count())
    } else if let Some(at) = node.caret {
        // An embedded-object offset is a coarse paragraph index, not a caret.
        if node.children.iter().any(|(index, _)| *index == at) {
            None
        } else {
            Some(map(at)?)
        }
    } else {
        None
    };
    let selection = if let Some((start, end)) = node.selection {
        let (from, to) = (start.min(end), start.max(end));
        let endpoint = |at: usize, last: bool| -> Option<usize> {
            let child_at = if last { at.checked_sub(1)? } else { at };
            if let Some((_, base, Some((start, end)))) =
                child_ranges.iter().find(|(index, _, _)| *index == child_at)
            {
                let expanded = base + if last { *end } else { *start };
                let byte = utf16_boundary(&output, expanded)?;
                Some(normalize(&output[..byte]).encode_utf16().count())
            } else {
                map(at)
            }
        };
        Some((
            endpoint(from, false)?,
            if from == to {
                map(to)?
            } else {
                endpoint(to, true)?
            },
        ))
    } else {
        let ranges = child_ranges
            .iter()
            .filter_map(|(_, base, range)| range.map(|(start, end)| (base + start, base + end)))
            .collect::<Vec<_>>();
        if let (Some(first), Some(last)) = (ranges.first(), ranges.last()) {
            let from = utf16_boundary(&output, first.0)?;
            let to = utf16_boundary(&output, last.1)?;
            Some((
                normalize(&output[..from]).encode_utf16().count(),
                normalize(&output[..to]).encode_utf16().count(),
            ))
        } else {
            None
        }
    };
    let normalized_offset = |offset| -> Option<usize> {
        let byte = utf16_boundary(&output, offset)?;
        Some(normalize(&output[..byte]).encode_utf16().count())
    };
    let markers = markers
        .into_iter()
        .map(|marker| {
            Some(ListMarker {
                start: normalized_offset(marker.start)?,
                body_start: normalized_offset(marker.body_start)?,
                body_end: normalized_offset(marker.body_end)?,
                empty: marker.empty,
            })
        })
        .collect::<Option<Vec<_>>>()?;
    Some(ProjectedText {
        text: normalize(&output),
        caret,
        selection,
        markers,
    })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct AccessibleDocument {
    pub text: String,
    pub start: usize,
    pub end: usize,
    pub caret: usize,
}

/// S_FALSE is not a successful text/caret result even though HRESULT::ok accepts it.
fn success(result: HRESULT) -> Option<()> {
    (result.0 == 0).then_some(())
}

pub(super) fn read(
    element: &IUIAutomationElement,
    expected_text: &str,
) -> Option<AccessibleDocument> {
    let node = read_node(element)?;
    let value = matching_projection(&node, expected_text)?;
    let caret = value.caret?;
    let (start, end) = value.selection.unwrap_or((caret, caret));
    canonical(&value.text, start as i32, end as i32, caret as i32)
}

/// Only an explicit accessible list item supplies a structural marker. Plain
/// typed lookalikes have no marker, even when their text is identical.
pub(super) fn list_markers(
    element: &IUIAutomationElement,
    expected_text: &str,
) -> Option<Vec<ListMarker>> {
    let node = read_node(element)?;
    Some(matching_projection(&node, expected_text)?.markers)
}

/// CSS-generated hints have no editable DOM text. Require both the explicit
/// ::before/::after IA2 tag and an exact UIA text match; typed lookalikes stay text.
pub(super) fn generated_placeholder(element: &IUIAutomationElement, expected_text: &str) -> bool {
    read_node(element).is_some_and(|node| {
        only_generated_text(&node) && matching_projection(&node, expected_text).is_some()
    })
}

fn only_generated_text(node: &TextNode) -> bool {
    node.generated
        || (!node.children.is_empty()
            && node
                .raw
                .chars()
                .all(|ch| matches!(ch, '\u{fffc}' | '\n' | '\r'))
            && node
                .children
                .iter()
                .all(|(_, child)| only_generated_text(child)))
}

fn read_node(element: &IUIAutomationElement) -> Option<TextNode> {
    unsafe {
        if element.CurrentIsPassword().ok()?.as_bool() {
            return None;
        }
        let accessible = element
            .GetCurrentPatternAs::<IUIAutomationLegacyIAccessiblePattern>(
                UIA_LegacyIAccessiblePatternId,
            )
            .and_then(|legacy| legacy.GetIAccessible())
            .ok()
            .or_else(|| focused_accessible(element))?;
        let mut budget = ReadBudget {
            nodes: 0,
            units: 0,
            deadline: Instant::now() + Duration::from_millis(250),
        };
        text_node(&accessible, 0, &mut budget)
    }
}

fn matching_projection(node: &TextNode, expected: &str) -> Option<ProjectedText> {
    project(node)
        .filter(|value| value.text == expected)
        .or_else(|| project_with_empty_blocks(node, true).filter(|value| value.text == expected))
}

/// Some Chromium UIA providers do not expose GetIAccessible. Follow MSAA focus
/// inside the same verified host window instead of reading an unrelated app.
fn focused_accessible(element: &IUIAutomationElement) -> Option<IAccessible> {
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        GA_ROOT, GUITHREADINFO, GetAncestor, GetGUIThreadInfo,
    };
    unsafe {
        if !element.CurrentHasKeyboardFocus().ok()?.as_bool() {
            return None;
        }
        let pid = element.CurrentProcessId().ok()? as u32;
        let owner = super::source::source_owner(
            pid,
            element
                .CurrentNativeWindowHandle()
                .ok()
                .map(|handle| handle.0),
        );
        let mut info: GUITHREADINFO = std::mem::zeroed();
        info.cbSize = std::mem::size_of::<GUITHREADINFO>() as u32;
        if GetGUIThreadInfo(0, &mut info) == 0
            || info.hwndFocus.is_null()
            || Some(GetAncestor(info.hwndFocus, GA_ROOT)) != owner.window
        {
            return None;
        }
        let mut pointer = std::ptr::null_mut();
        AccessibleObjectFromWindow(
            windows::Win32::Foundation::HWND(info.hwndFocus),
            0xfffffffc,
            &IAccessible::IID,
            &mut pointer,
        )
        .ok()?;
        if pointer.is_null() {
            return None;
        }
        let mut accessible = IAccessible::from_raw(pointer);
        for _ in 0..16 {
            let mut focus = accessible.accFocus().ok()?;
            let next = match focus.Anonymous.Anonymous.vt {
                VT_DISPATCH => focus
                    .Anonymous
                    .Anonymous
                    .Anonymous
                    .pdispVal
                    .as_ref()
                    .and_then(|value| value.cast::<IAccessible>().ok()),
                VT_UNKNOWN => focus
                    .Anonymous
                    .Anonymous
                    .Anonymous
                    .punkVal
                    .as_ref()
                    .and_then(|value| value.cast::<IAccessible>().ok()),
                VT_I4 if focus.Anonymous.Anonymous.Anonymous.lVal > 0 => accessible
                    .get_accChild(&focus)
                    .ok()
                    .and_then(|value| value.cast::<IAccessible>().ok()),
                _ => None,
            };
            let _ = VariantClear(&mut focus);
            let Some(next) = next else {
                break;
            };
            if next == accessible {
                break;
            }
            accessible = next;
        }
        let name = normalize(
            &accessible
                .get_accName(&VARIANT::from(0i32))
                .ok()?
                .to_string(),
        );
        if name != normalize(&element.CurrentName().ok()?.to_string()) {
            return None;
        }
        Some(accessible)
    }
}

fn canonical(raw: &str, start: i32, end: i32, caret: i32) -> Option<AccessibleDocument> {
    if raw
        .chars()
        .any(|ch| ch == '\u{fffc}' || (ch.is_control() && !matches!(ch, '\r' | '\n' | '\t')))
    {
        return None;
    }
    let offset = |value: i32| {
        let byte = utf16_boundary(raw, usize::try_from(value).ok()?)?;
        Some(normalize(&raw[..byte]).encode_utf16().count())
    };
    let start = offset(start)?;
    let end = offset(end)?;
    let caret = offset(caret)?;
    if caret != start && caret != end {
        return None;
    }
    Some(AccessibleDocument {
        text: normalize(raw),
        start,
        end,
        caret,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn leaf(raw: &str, caret: Option<usize>, block: bool) -> TextNode {
        TextNode {
            raw: raw.into(),
            caret,
            selection: None,
            block,
            list_item: false,
            generated: false,
            children: vec![],
        }
    }
    fn embedded(children: Vec<TextNode>) -> TextNode {
        TextNode {
            raw: "\u{fffc}".repeat(children.len()),
            caret: Some(children.len().saturating_sub(1)),
            selection: None,
            block: true,
            list_item: false,
            generated: false,
            children: children.into_iter().enumerate().collect(),
        }
    }
    fn list_item(marker: &str, body: TextNode) -> TextNode {
        let prefix = marker.encode_utf16().count();
        TextNode {
            raw: format!("{marker}\u{fffc}"),
            caret: body.caret.map(|_| prefix),
            selection: None,
            block: true,
            list_item: true,
            generated: false,
            children: vec![(prefix, body)],
        }
    }
    #[test]
    fn css_generated_hint_is_distinct_from_identical_typed_text() {
        let mut hint = leaf("输入 '/' 选择", Some(9), false);
        assert!(!only_generated_text(&embedded(vec![leaf(
            "输入 '/' 选择",
            Some(9),
            false
        )])));
        hint.generated = true;
        assert!(only_generated_text(&embedded(vec![hint])));
        let mut pseudo = leaf("提示", None, false);
        pseudo.generated = true;
        assert!(!only_generated_text(&embedded(vec![
            pseudo,
            leaf("正文", Some(2), true)
        ])));
    }
    #[test]
    fn list_markers_share_the_body_line_and_empty_item_caret_stays_before_filler() {
        let root = embedded(vec![
            list_item("• ", leaf("甲😀", None, true)),
            list_item("• ", leaf("乙", None, true)),
            list_item("• ", leaf("\n", Some(0), true)),
        ]);
        let value = matching_projection(&root, "• 甲😀\n• 乙\n• \n").unwrap();
        assert_eq!(value.caret, Some(12));
        let at = utf16_boundary(&value.text, value.caret.unwrap()).unwrap();
        assert_eq!(value.text[..at].matches('\n').count(), 2);
        assert_eq!(&value.text[at..], "\n");
        assert_eq!(
            value.markers,
            vec![
                ListMarker {
                    start: 0,
                    body_start: 2,
                    body_end: 5,
                    empty: false
                },
                ListMarker {
                    start: 6,
                    body_start: 8,
                    body_end: 9,
                    empty: false
                },
                ListMarker {
                    start: 10,
                    body_start: 12,
                    body_end: 13,
                    empty: true
                },
            ]
        );
    }
    #[test]
    fn typed_bullet_lookalikes_do_not_supply_structural_markers() {
        assert!(
            matching_projection(&leaf("• \n", Some(2), true), "• \n")
                .unwrap()
                .markers
                .is_empty()
        );
        let root = embedded(vec![
            leaf("前😀", None, true),
            list_item("· ", leaf("\n", Some(0), true)),
        ]);
        let value = matching_projection(&root, "前😀\n· ").unwrap();
        assert_eq!(
            value.markers,
            vec![ListMarker {
                start: 4,
                body_start: 6,
                body_end: 6,
                empty: true
            }]
        );
    }
    #[test]
    fn numbered_item_keeps_soft_linebreaks_and_nested_list_on_its_own_line() {
        let mut first = list_item("10. ", leaf("甲\n乙😀", None, true));
        let nested = embedded(vec![list_item("• ", leaf("子", Some(1), true))]);
        first.raw.push('\u{fffc}');
        first.children.push((5, nested));
        let value = matching_projection(&embedded(vec![first]), "10. 甲\n乙😀\n• 子").unwrap();
        assert_eq!(value.caret, Some(value.text.encode_utf16().count()));
        assert_eq!(value.text.matches('\n').count(), 2);
    }
    #[test]
    fn long_list_context_follows_the_active_item_without_adding_marker_paragraphs() {
        let root = embedded(
            (1..=7)
                .map(|i| list_item("• ", leaf(&format!("第{i}项"), (i == 4).then_some(3), true)))
                .collect(),
        );
        let expected = (1..=7)
            .map(|i| format!("• 第{i}项"))
            .collect::<Vec<_>>()
            .join("\n");
        let value = matching_projection(&root, &expected).unwrap();
        let caret = value.caret.unwrap();
        let scope = crate::text_sync::paragraph::scope(&value.text, caret, caret).unwrap();
        assert_eq!(scope.paragraph, 4);
        assert_eq!(
            &value.text[utf16_boundary(&value.text, scope.start).unwrap()
                ..utf16_boundary(&value.text, scope.end).unwrap()],
            "• 第3项\n• 第4项\n• 第5项"
        );
    }
    #[test]
    fn rich_editor_six_paragraphs_use_the_descendant_caret_in_full_text() {
        let root = embedded(vec![
            leaf("甲", None, true),
            leaf("乙", None, true),
            leaf("丙", None, true),
            leaf("丁", None, true),
            leaf("戊", None, true),
            leaf("尾😀", Some(3), true),
        ]);
        let value = project(&root).unwrap();
        assert_eq!(value.text, "甲\n乙\n丙\n丁\n戊\n尾😀");
        assert_eq!(value.caret, Some(13));
        let scope = crate::text_sync::paragraph::scope(&value.text, 13, 13).unwrap();
        assert_eq!(scope.paragraph, 6);
        assert_eq!(
            &value.text[utf16_boundary(&value.text, scope.start).unwrap()..],
            "戊\n尾😀"
        );
    }
    #[test]
    fn inline_spans_and_nested_blocks_preserve_emoji_offsets_and_blank_paragraphs() {
        let mut inline = embedded(vec![leaf("甲", None, false), leaf("😀", Some(2), false)]);
        inline.block = true;
        let root = embedded(vec![
            leaf("", None, true),
            leaf("", None, true),
            inline,
            leaf("乙", None, true),
        ]);
        let value = project(&root).unwrap();
        assert_eq!(value.text, "\n\n甲😀\n乙");
        assert_eq!(value.caret, Some(5));
    }
    #[test]
    fn selections_inside_two_embedded_paragraphs_keep_precise_endpoints() {
        let mut first = leaf("甲😀乙", None, true);
        first.selection = Some((1, 4));
        let mut second = leaf("丙丁", Some(1), true);
        second.selection = Some((0, 1));
        let mut root = embedded(vec![first, second]);
        root.selection = Some((0, 2));
        let value = project(&root).unwrap();
        assert_eq!(value.text, "甲😀乙\n丙丁");
        assert_eq!(value.selection, Some((1, 6)));
        assert_eq!(value.caret, Some(6));
    }
    #[test]
    fn missing_embedded_text_or_descendant_caret_never_becomes_an_end_of_text_guess() {
        let mut root = embedded(vec![leaf("甲", None, true)]);
        assert!(project(&root).unwrap().caret.is_none());
        root.children.clear();
        assert!(project(&root).is_none());
        assert!(project(&leaf("😀", Some(1), false)).is_none());
    }
    #[test]
    fn chromium_empty_line_fillers_only_disappear_when_uia_confirms_the_complete_text() {
        let root = embedded(vec![
            leaf("甲", None, true),
            leaf("\n", None, true),
            leaf("\n", Some(0), true),
        ]);
        let value = matching_projection(&root, "甲\n\n").unwrap();
        assert_eq!(value.text, "甲\n\n");
        assert_eq!(value.caret, Some(3));
        // A real line break is retained when the other interface exposes it.
        let explicit = embedded(vec![leaf("甲", None, true), leaf("\n", Some(0), true)]);
        let value = matching_projection(&explicit, "甲\n\n").unwrap();
        assert_eq!(value.text, "甲\n\n");
        assert_eq!(value.caret, Some(2));
        assert!(matching_projection(&root, "甲\n乙").is_none());
    }
    #[test]
    fn accessible_offsets_preserve_emoji_and_map_crlf_without_guessing() {
        let raw = "甲\r\n😀乙";
        let value = canonical(raw, 3, 6, 6).unwrap();
        assert_eq!(
            value,
            AccessibleDocument {
                text: "甲\n😀乙".into(),
                start: 2,
                end: 5,
                caret: 5
            }
        );
        assert!(canonical(raw, 4, 4, 4).is_none());
        assert!(canonical(raw, -1, -1, -1).is_none());
        assert!(canonical(raw, 3, 6, 0).is_none());
        assert!(canonical("a\u{fffc}b", 1, 1, 1).is_none());
        assert!(success(HRESULT(1)).is_none());
    }
}
