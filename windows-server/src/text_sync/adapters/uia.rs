//! UI Automation adapter. COM objects stay on this adapter's MTA thread.
//! Never infer text from key logs.
use super::{
    accessibility_page, block_context,
    editor_policy::{is_code_input_proxy, is_placeholder_content, is_rich_editor},
    ia2, input_rule, list_edit,
    source::{SharedSourceCache, SourceCache, source_owner, window_title},
    text_range::text_range,
};
use crate::text_sync::{
    MAX_DOCUMENT_UNITS, RequestDeadline, TextAdapter, TextSnapshot, normalize, replacement,
};
use crate::{
    input::{InputSink, WindowsInput},
    protocol::{Command, Key},
};
use std::time::{Duration, Instant};
use windows::{
    Win32::{
        System::{
            Com::{
                CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx,
                CoUninitialize,
            },
            Variant::{VT_BOOL, VariantClear, VariantToBoolean},
        },
        UI::Accessibility::*,
    },
    core::{BSTR, Interface},
};
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
};

pub struct UiAutomationAdapter {
    automation: IUIAutomation,
    element: Option<IUIAutomationElement>,
    snapshot: TextSnapshot,
    next_target: u64,
    sources: SharedSourceCache,
    page_position: accessibility_page::PositionReader,
    deadline: RequestDeadline,
    _apartment: Apartment,
}
struct Apartment;
impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe {
            CoUninitialize();
        }
    }
}
fn range_error() -> windows::core::Error {
    windows::core::Error::from_hresult(windows::core::HRESULT(0x80004005u32 as i32))
}
impl UiAutomationAdapter {
    pub fn new() -> Result<Self, &'static str> {
        Self::with_sources(SourceCache::shared())
    }
    pub(super) fn with_sources(sources: SharedSourceCache) -> Result<Self, &'static str> {
        unsafe {
            CoInitializeEx(None, COINIT_MULTITHREADED)
                .ok()
                .map_err(|_| "unavailable")?;
            let automation: IUIAutomation =
                match CoCreateInstance(&CUIAutomation8, None, CLSCTX_INPROC_SERVER) {
                    Ok(value) => value,
                    Err(_) => {
                        CoUninitialize();
                        return Err("unavailable");
                    }
                };
            if let Ok(v2) = automation.cast::<IUIAutomation2>() {
                let _ = v2.SetAutoSetFocus(false);
                let _ = v2.SetConnectionTimeout(300);
                let _ = v2.SetTransactionTimeout(500);
            }
            Ok(Self {
                automation,
                element: None,
                snapshot: TextSnapshot::default(),
                next_target: 0,
                sources,
                page_position: accessibility_page::PositionReader::default(),
                deadline: RequestDeadline::default(),
                _apartment: Apartment,
            })
        }
    }
    fn same_focus(&self, element: &IUIAutomationElement) -> bool {
        unsafe {
            self.automation
                .GetFocusedElement()
                .and_then(|current| self.automation.CompareElements(&current, element))
                .is_ok_and(|same| same.as_bool())
        }
    }
    fn patterns(
        element: &IUIAutomationElement,
    ) -> (
        Option<IUIAutomationTextPattern>,
        Option<IUIAutomationValuePattern>,
    ) {
        unsafe {
            (
                element.GetCurrentPatternAs(UIA_TextPatternId).ok(),
                element.GetCurrentPatternAs(UIA_ValuePatternId).ok(),
            )
        }
    }
    fn uses_text_edit_provider(element: &IUIAutomationElement) -> bool {
        unsafe {
            element
                .GetCurrentPatternAs::<IUIAutomationTextEditPattern>(UIA_TextEditPatternId)
                .is_ok()
        }
    }
    fn content(element: &IUIAutomationElement) -> windows::core::Result<String> {
        let (text, value) = Self::patterns(element);
        unsafe {
            // Edit controls expose their actual value through ValuePattern. Some
            // browser-hosted inputs also expose a TextPattern whose document range
            // contains the placeholder (for example, an empty Codex composer), so
            // never let that range override an available ValuePattern value.
            if let Some(value) = value {
                if let Ok(current) = value.CurrentValue() {
                    return Ok(Self::clean_content(element, &current.to_string()));
                }
            }
            text.ok_or_else(range_error).and_then(|text| {
                let current = text
                    .DocumentRange()?
                    .GetText((MAX_DOCUMENT_UNITS + 1) as i32)?
                    .to_string();
                Ok(Self::clean_content(element, &current))
            })
        }
    }
    fn clean_content(element: &IUIAutomationElement, raw: &str) -> String {
        let value = normalize(raw);
        let name = unsafe {
            element
                .CurrentName()
                .ok()
                .map(|name| normalize(&name.to_string()))
                .unwrap_or_default()
        };
        let class_name = unsafe {
            element
                .CurrentClassName()
                .ok()
                .map(|class| class.to_string().to_ascii_lowercase())
                .unwrap_or_default()
        };
        // Chromium's ProseMirror provider exposes an empty editor's placeholder
        // as `name + "\\n"` through ValuePattern. Treat that presentation hint as
        // empty content so it never appears on the phone or participates in edits.
        if is_placeholder_content(&value, &name, &class_name)
            || (block_context::is_editor(&class_name)
                && !value.is_empty()
                && ia2::generated_placeholder(element, &value))
        {
            String::new()
        } else {
            value
        }
    }
    fn sample(&self, element: &IUIAutomationElement) -> windows::core::Result<TextSnapshot> {
        self.deadline.check().map_err(|_| range_error())?;
        unsafe {
            // Password checks precede all content/name reads.
            if element.CurrentIsPassword()?.as_bool() {
                return Ok(TextSnapshot::unavailable("password"));
            }
            let pid = element.CurrentProcessId()? as u32;
            let owner = source_owner(
                pid,
                element.CurrentNativeWindowHandle().ok().map(|hwnd| hwnd.0),
            );
            let source = self.sources.borrow_mut().get(owner.pid);
            let title = window_title(owner.pid, owner.window);
            let decorate = |mut snapshot: TextSnapshot| {
                snapshot.source = source.name.clone();
                snapshot.source_icon = source.icon.clone();
                snapshot.window_title = title.clone();
                snapshot
            };
            let kind = element.CurrentControlType()?;
            let (pattern, value) = Self::patterns(element);
            let direct_write = value.as_ref().is_some_and(|value| {
                value
                    .CurrentIsReadOnly()
                    .is_ok_and(|read_only| !read_only.as_bool())
            });
            let range_write = pattern.as_ref().is_some_and(|pattern| {
                if pattern.SupportedTextSelection().ok() == Some(SupportedTextSelection_None) {
                    return false;
                }
                let Ok(range) = pattern.DocumentRange() else {
                    return false;
                };
                let Ok(mut attr) = range.GetAttributeValue(UIA_IsReadOnlyAttributeId) else {
                    return false;
                };
                let writable = attr.Anonymous.Anonymous.vt == VT_BOOL
                    && VariantToBoolean(&attr).is_ok_and(|value| !value.as_bool());
                let _ = VariantClear(&mut attr);
                writable
            });
            let passive_selection = Self::uses_text_edit_provider(element);
            // HTML search boxes can be ComboBox controls. Rich editors such as
            // QQ's ProseMirror expose Group instead of Edit. For these types,
            // require actual keyboard focus and writable text capabilities.
            if !is_text_input(
                kind,
                element
                    .CurrentHasKeyboardFocus()
                    .is_ok_and(|focused| focused.as_bool()),
                direct_write,
                range_write,
                passive_selection,
            ) {
                return Ok(decorate(TextSnapshot::unavailable("no_input")));
            }
            let class_name = element
                .CurrentClassName()
                .ok()
                .map(|name| name.to_string())
                .unwrap_or_default();
            let code_proxy = is_code_input_proxy(&class_name);
            if ["windowsterminal", "cmd", "powershell", "pwsh", "conhost"]
                .contains(&source.name.to_lowercase().as_str())
            {
                return Ok(decorate(TextSnapshot::unavailable("unsupported")));
            }
            if pattern.is_none() && value.is_none() {
                return Ok(decorate(TextSnapshot::unavailable("unsupported")));
            }
            // Chromium exposes a platform Document's URL through ValuePattern.
            // It is navigation metadata, not a text field. Keep the host's app
            // identity for ordinary realtime input, without mirroring that URL
            // or the entire settings page as editable/readonly user text.
            if kind == UIA_DocumentControlTypeId
                && !direct_write
                && value
                    .as_ref()
                    .and_then(|value| value.CurrentValue().ok())
                    .is_some_and(|value| {
                        is_navigation_document(
                            kind == UIA_DocumentControlTypeId,
                            direct_write,
                            &value.to_string(),
                        )
                    })
            {
                return Ok(decorate(TextSnapshot::unavailable("no_input")));
            }
            let text = match Self::content(element) {
                Ok(text) => text,
                Err(_) => return Ok(decorate(TextSnapshot::unavailable("unavailable"))),
            };
            if text.encode_utf16().count() > MAX_DOCUMENT_UNITS {
                return Ok(decorate(TextSnapshot::unavailable("too_long")));
            }
            // Do not poll the selection of an IME-aware provider, including
            // before its first composition range appears. Testing for a live
            // range races the first keystroke, and Chromium also caches that
            // range after commitment. Passive reads must never change TSF's
            // candidate/caret state; the phone retains its local caret.
            // IAccessible2 reads the provider's cached text/caret. It does not
            // query live TSF composition ranges or call UIA GetSelection on an
            // IME-aware control, which previously disturbed candidate windows.
            let accessible = if (passive_selection || code_proxy) && !text.is_empty() {
                ia2::read(element, &text)
            } else {
                None
            };
            let mut snapshot = document_snapshot(
                source.name.clone(),
                text,
                element.CurrentIsEnabled()?.as_bool() && (direct_write || range_write),
                passive_selection || code_proxy,
                || pattern.as_ref().and_then(|pattern| selection(pattern).ok()),
            );
            if let Some(value) = accessible.as_ref() {
                snapshot.selection_start = Some(value.start);
                snapshot.selection_end = Some(value.end);
            }
            if code_proxy {
                let name = normalize(&element.CurrentName()?.to_string());
                // Empty proxies and their instructions must never look synced.
                // For a real page require a reliable selection, position anchor
                // and selectable text ranges; otherwise keep ordinary input.
                if !accessibility_page::genuine(&snapshot.text, &name, accessible.as_ref())
                    || pattern.is_none()
                    || !range_write
                {
                    return Ok(decorate(TextSnapshot::unavailable("unsupported")));
                }
                let Some(position) = owner
                    .window
                    .and_then(|window| self.page_position.read(&self.automation, window))
                else {
                    return Ok(decorate(TextSnapshot::unavailable("unsupported")));
                };
                let Some(first_line) =
                    accessibility_page::origin(accessible.as_ref().unwrap(), position)
                else {
                    return Ok(decorate(TextSnapshot::unavailable("unsupported")));
                };
                snapshot.accessibility_page_start_line = Some(first_line);
                snapshot.document_identity = Some(name);
                snapshot.desktop_caret = Some(position);
            }
            if block_context::is_editor(&class_name) {
                (snapshot.context_before, snapshot.context_after) =
                    block_context::context(&self.automation, element, Self::content);
            }
            Ok(decorate(snapshot))
        }
    }
    fn guarded_element(
        &self,
        expected: &TextSnapshot,
    ) -> Result<IUIAutomationElement, &'static str> {
        self.deadline.check()?;
        let element = self.element.as_ref().ok_or("conflict")?;
        if self.snapshot.target != expected.target
            || !self.same_focus(element)
            || Self::content(element).ok().as_deref() != Some(expected.text.as_str())
        {
            return Err("conflict");
        }
        if expected.accessibility_page_start_line.is_some() {
            let current = self.sample(element).map_err(|_| "conflict")?;
            if current.document_identity != expected.document_identity
                || current.accessibility_page_start_line != expected.accessibility_page_start_line
                || current.desktop_caret != expected.desktop_caret
            {
                return Err("conflict");
            }
        }
        self.deadline.check()?;
        if !self.same_focus(element) {
            return Err("conflict");
        }
        Ok(element.clone())
    }
}
impl TextAdapter for UiAutomationAdapter {
    fn set_deadline(&mut self, deadline: Option<Instant>) {
        self.deadline.set(deadline);
    }
    fn read(&mut self) -> TextSnapshot {
        if self.deadline.check().is_err() {
            return TextSnapshot::unavailable("unavailable");
        }
        let focused = unsafe { self.automation.GetFocusedElement() }.ok();
        let mut next = match focused.as_ref() {
            Some(element) => self
                .sample(element)
                .unwrap_or_else(|_| TextSnapshot::unavailable("unavailable")),
            None => TextSnapshot::unavailable(
                if unsafe { windows_sys::Win32::UI::WindowsAndMessaging::GetForegroundWindow() }
                    .is_null()
                {
                    "no_input"
                } else {
                    "unavailable"
                },
            ),
        };
        let same = match (&self.element, &focused) {
            (Some(old), Some(new)) => unsafe { self.automation.CompareElements(old, new) }
                .is_ok_and(|same| same.as_bool()),
            _ => false,
        };
        if !same {
            self.next_target = self.next_target.wrapping_add(1).max(1);
        }
        next.target = self.next_target;
        next.revision = self.snapshot.revision;
        if !next.same_document(&self.snapshot) {
            next.revision = next.revision.wrapping_add(1);
        }
        self.element = focused;
        self.snapshot = next.clone();
        next
    }
    fn edit(
        &mut self,
        expected: &TextSnapshot,
        text: &str,
        start: usize,
        end: usize,
    ) -> Result<crate::text_sync::EditOutcome, &'static str> {
        let element = self.guarded_element(expected)?;
        let passive_selection = Self::uses_text_edit_provider(&element);
        let (pattern, value) = Self::patterns(&element);
        let rich_editor = unsafe { element.CurrentClassName() }
            .is_ok_and(|class| is_rich_editor(&class.to_string()));
        let mut natural_caret = None;
        let mut marker_deletion = None;
        let mut list_markers = Vec::new();
        if text != expected.text {
            let direct = value.filter(|value| {
                expected.accessibility_page_start_line.is_none()
                    && !(rich_editor && pattern.is_some())
                    && unsafe { value.CurrentIsReadOnly() }.is_ok_and(|value| !value.as_bool())
            });
            if let Some(value) = direct {
                self.deadline.check()?;
                unsafe { value.SetValue(&BSTR::from(text)) }.map_err(|_| "write_failed")?;
            } else {
                let pattern = pattern.as_ref().ok_or("unsupported")?;
                let (from, to, insert) = replacement(&expected.text, text);
                if rich_editor
                    && expected.accessibility_page_start_line.is_none()
                    && insert.is_empty()
                    && from < to
                    && expected.text.contains(['•', '·'])
                {
                    list_markers = ia2::list_markers(&element, &expected.text).unwrap_or_default();
                    marker_deletion =
                        list_edit::marker_deletion(&expected.text, text, start, end, &list_markers);
                }
                // In generic controls Enter/Tab can submit or change focus. Only
                // Notepad's known multiline editor gets newline key injection.
                if insert.contains(['\n', '\t'])
                    && expected.accessibility_page_start_line.is_none()
                    && !self
                        .sources
                        .borrow_mut()
                        .get(unsafe { element.CurrentProcessId() }.map_err(|_| "conflict")? as u32)
                        .name
                        .eq_ignore_ascii_case("notepad")
                {
                    return Err("linebreak_unsupported");
                }
                let (select_from, select_to) = marker_deletion
                    .as_ref()
                    .map_or((from, to), |plan| (plan.caret, plan.caret));
                let range = text_range(pattern, select_from, select_to, self.deadline)
                    .map_err(|_| "range_unsupported")?;
                if !self.same_focus(&element) || modifiers_held() {
                    return Err("conflict");
                }
                self.deadline.check()?;
                unsafe { range.Select() }.map_err(|_| "range_unsupported")?;
                if let Some(plan) = marker_deletion.as_ref() {
                    // Generated markers are not editable text. Select the real
                    // paragraph start, and verify it before one native Backspace.
                    let deadline =
                        Instant::now() + self.deadline.remaining(Duration::from_millis(320))?;
                    loop {
                        self.deadline.check()?;
                        if !self.same_focus(&element) {
                            return Err("conflict");
                        }
                        if ia2::read(&element, &expected.text).is_some_and(|current| {
                            current.start == plan.caret && current.end == plan.caret
                        }) {
                            break;
                        }
                        if Instant::now() >= deadline {
                            return Err("range_unsupported");
                        }
                        std::thread::sleep(Duration::from_millis(16));
                    }
                }
                if expected.accessibility_page_start_line.is_some() {
                    // Native Monaco reflects DOM selection into its model
                    // asynchronously, with an ignore window after rendering.
                    // Confirm the requested range before injecting a replacement.
                    let deadline =
                        Instant::now() + self.deadline.remaining(Duration::from_millis(320))?;
                    let mut retried = false;
                    loop {
                        if !self.same_focus(&element) {
                            return Err("conflict");
                        }
                        let current =
                            ia2::read(&element, &expected.text).ok_or("range_unsupported")?;
                        if current.text != expected.text {
                            return Err("conflict");
                        }
                        let anchored = self.sample(&element).ok().is_some_and(|value| {
                            value.document_identity == expected.document_identity
                                && value.accessibility_page_start_line
                                    == expected.accessibility_page_start_line
                        });
                        if anchored
                            && (
                                current.start.min(current.end),
                                current.start.max(current.end),
                            ) == (from, to)
                        {
                            break;
                        }
                        if Instant::now() >= deadline {
                            return Err("range_unsupported");
                        }
                        std::thread::sleep(Duration::from_millis(20));
                        if !retried
                            && deadline.saturating_duration_since(Instant::now())
                                < Duration::from_millis(180)
                        {
                            unsafe { range.Select() }.map_err(|_| "range_unsupported")?;
                            retried = true;
                        }
                    }
                }
                self.deadline.check()?;
                if !self.same_focus(&element)
                    || Self::content(&element).ok().as_deref() != Some(expected.text.as_str())
                {
                    return Err("conflict");
                }
                let command = if insert.is_empty() {
                    Command::Key {
                        key: Key::Backspace,
                    }
                } else {
                    Command::Text {
                        text: insert.into(),
                        id: 0,
                    }
                };
                WindowsInput.execute(&command).map_err(|_| "write_failed")?;
                natural_caret = Some(from + insert.encode_utf16().count());
            }
            let mut confirmed = false;
            // Code's text page, cached IA2 caret and status-bar anchor can arrive
            // in separate accessibility updates. Wait for all of them to agree;
            // a briefly stale line number must not reject a completed edit.
            let deadline = Instant::now()
                + self.deadline.remaining(Duration::from_millis(
                    if expected.accessibility_page_start_line.is_some() {
                        500
                    } else if marker_deletion.is_some() {
                        320
                    } else {
                        150
                    },
                ))?;
            loop {
                if !self.same_focus(&element) {
                    return Err("conflict");
                }
                let matches = if let Some(first_line) = expected.accessibility_page_start_line {
                    self.sample(&element).ok().is_some_and(|current| {
                        current.accessibility_page_start_line == Some(first_line)
                            && current.document_identity == expected.document_identity
                            && accessibility_page::confirms(
                                text,
                                &current.text,
                                natural_caret.unwrap_or(start.max(end)),
                            )
                    })
                } else {
                    Self::content(&element).ok().as_deref() == Some(text)
                };
                if matches && marker_deletion.is_none() {
                    confirmed = true;
                    break;
                }
                if let Some(plan) = marker_deletion.as_ref()
                    && let Ok(actual) = Self::content(&element)
                    && let Some(caret) = plan.confirmed_caret(&actual)
                    && (actual.is_empty()
                        || ia2::read(&element, &actual)
                            .is_some_and(|current| current.start == caret && current.end == caret))
                {
                    return Ok(crate::text_sync::EditOutcome::NormalizedAt {
                        text: actual,
                        caret,
                    });
                }
                if rich_editor
                    && expected.accessibility_page_start_line.is_none()
                    && start == end
                    && natural_caret == Some(start)
                    && let Ok(actual) = Self::content(&element)
                    && (list_edit::filler_normalization(
                        &expected.text,
                        text,
                        &actual,
                        start,
                        &list_markers,
                    ) || input_rule::list_normalization(&expected.text, text, &actual, start)
                        || input_rule::empty_list_filler_normalization(
                            &expected.text,
                            text,
                            &actual,
                            start,
                        ))
                {
                    // The editor changed the document through its own input
                    // rule. Preserve its natural caret; requested offsets belong
                    // to the pre-conversion text and must not be selected now.
                    return Ok(crate::text_sync::EditOutcome::Normalized(actual));
                }
                if Instant::now() >= deadline {
                    break;
                }
                std::thread::sleep(Duration::from_millis(15));
            }
            if !confirmed {
                return Err("body_unconfirmed");
            }
        }
        if let Some(pattern) = pattern
            && !(text.is_empty() && start == 0 && end == 0)
            && !(start == end && natural_caret == Some(start))
        {
            let range = text_range(&pattern, start.min(end), start.max(end), self.deadline)
                .map_err(|_| "range_unsupported")?;
            if !self.same_focus(&element) {
                return Err("conflict");
            }
            self.deadline.check()?;
            unsafe { range.Select() }.map_err(|_| "range_unsupported")?;
            // Chromium may apply the UIA selection asynchronously. Returning
            // the old caret here makes the next phone edit use a stale
            // revision and immediately enter conflict protection. Wait for
            // the provider to report the requested selection before acking.
            let deadline = Instant::now() + self.deadline.remaining(Duration::from_millis(320))?;
            loop {
                if !self.same_focus(&element) {
                    return Err("conflict");
                }
                let selected = if passive_selection {
                    // Cached IA2 offsets do not disturb TSF candidate state.
                    // Some hosts expose no IA2 caret; the phone retains its
                    // requested caret and the next replacement selects its range.
                    let Some(current) = ia2::read(&element, text) else {
                        break;
                    };
                    Some((
                        current.start.min(current.end),
                        current.start.max(current.end),
                    ))
                } else {
                    Self::patterns(&element)
                        .0
                        .and_then(|current| selection(&current).ok())
                };
                if selected == Some((start.min(end), start.max(end))) {
                    break;
                }
                if Instant::now() >= deadline {
                    return Err("selection_unconfirmed");
                }
                std::thread::sleep(Duration::from_millis(16));
            }
        }
        Ok(crate::text_sync::EditOutcome::Exact)
    }
    fn enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str> {
        self.send_enter(expected, Key::Enter)
    }
    fn shift_enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str> {
        self.send_enter(expected, Key::ShiftEnter)
    }
}
impl UiAutomationAdapter {
    fn send_enter(&mut self, expected: &TextSnapshot, key: Key) -> Result<(), &'static str> {
        let element = self.guarded_element(expected)?;
        if !self.same_focus(&element) || modifiers_held() {
            return Err("conflict");
        }
        WindowsInput
            .execute(&Command::Key { key })
            .map_err(|_| "write_failed")
    }
}

fn modifiers_held() -> bool {
    [VK_CONTROL, VK_MENU, VK_SHIFT, VK_LWIN, VK_RWIN]
        .iter()
        .any(|key| unsafe { GetAsyncKeyState(i32::from(*key)) } as u16 & 0x8000 != 0)
}
fn selection(pattern: &IUIAutomationTextPattern) -> windows::core::Result<(usize, usize)> {
    unsafe {
        let ranges = pattern.GetSelection()?;
        if ranges.Length()? != 1 {
            return Err(range_error());
        }
        let selected = ranges.GetElement(0)?;
        let prefix = pattern.DocumentRange()?.Clone()?;
        prefix.MoveEndpointByRange(
            TextPatternRangeEndpoint_End,
            &selected,
            TextPatternRangeEndpoint_Start,
        )?;
        let start = normalize(&prefix.GetText((MAX_DOCUMENT_UNITS + 1) as i32)?.to_string())
            .encode_utf16()
            .count();
        prefix.MoveEndpointByRange(
            TextPatternRangeEndpoint_End,
            &selected,
            TextPatternRangeEndpoint_End,
        )?;
        let end = normalize(&prefix.GetText((MAX_DOCUMENT_UNITS + 1) as i32)?.to_string())
            .encode_utf16()
            .count();
        Ok((start, end))
    }
}

fn is_text_input(
    kind: UIA_CONTROLTYPE_ID,
    focused: bool,
    direct_write: bool,
    range_write: bool,
    text_edit_provider: bool,
) -> bool {
    if kind == UIA_EditControlTypeId || kind == UIA_DocumentControlTypeId {
        return true;
    }
    if !focused {
        return false;
    }
    if kind == UIA_ComboBoxControlTypeId {
        return direct_write || range_write;
    }
    (kind == UIA_GroupControlTypeId
        || kind == UIA_CustomControlTypeId
        || kind == UIA_PaneControlTypeId)
        && text_edit_provider
        && range_write
}

fn is_navigation_document(document: bool, writable: bool, value: &str) -> bool {
    if !document || writable {
        return false;
    }
    let value = value.trim().to_ascii_lowercase();
    let Some((scheme, rest)) = value.split_once(':') else {
        return false;
    };
    scheme
        .as_bytes()
        .first()
        .is_some_and(u8::is_ascii_alphabetic)
        && scheme
            .bytes()
            .all(|ch| ch.is_ascii_alphanumeric() || matches!(ch, b'+' | b'-' | b'.'))
        && (rest.starts_with("//") || matches!(scheme, "about" | "data" | "blob"))
}

fn document_snapshot(
    source: String,
    text: String,
    writable: bool,
    passive_selection: bool,
    read_selection: impl FnOnce() -> Option<(usize, usize)>,
) -> TextSnapshot {
    let selection = if text.is_empty() {
        Some((0, 0))
    } else if passive_selection {
        None
    } else {
        read_selection()
    };
    TextSnapshot {
        source,
        text,
        status: if writable { "editable" } else { "read_only" },
        selection_start: selection.map(|(start, _)| start),
        selection_end: selection.map(|(_, end)| end),
        ..TextSnapshot::default()
    }
}

#[cfg(test)]
mod tests {
    use super::{
        document_snapshot, is_code_input_proxy, is_navigation_document, is_placeholder_content,
        is_rich_editor, is_text_input,
    };
    use windows::Win32::UI::Accessibility::*;

    #[test]
    fn editable_search_combo_and_focused_rich_group_are_inputs_without_accepting_containers() {
        assert!(is_text_input(
            UIA_ComboBoxControlTypeId,
            true,
            true,
            false,
            true
        ));
        assert!(is_text_input(
            UIA_GroupControlTypeId,
            true,
            false,
            true,
            true
        ));
        assert!(is_text_input(
            UIA_CustomControlTypeId,
            true,
            false,
            true,
            true
        ));
        assert!(!is_text_input(
            UIA_ComboBoxControlTypeId,
            true,
            false,
            false,
            false
        ));
        assert!(!is_text_input(
            UIA_GroupControlTypeId,
            false,
            false,
            true,
            true
        ));
        assert!(!is_text_input(
            UIA_GroupControlTypeId,
            true,
            false,
            false,
            true
        ));
        assert!(!is_text_input(
            UIA_GroupControlTypeId,
            true,
            true,
            true,
            false
        ));
        assert!(!is_text_input(
            UIA_ButtonControlTypeId,
            true,
            true,
            true,
            true
        ));
        assert!(is_text_input(
            UIA_EditControlTypeId,
            true,
            false,
            false,
            false
        ));
        assert!(is_text_input(
            UIA_DocumentControlTypeId,
            true,
            false,
            false,
            false
        ));
    }

    #[test]
    fn code_editor_input_proxies_are_not_mistaken_for_complete_documents() {
        assert!(is_code_input_proxy("native-edit-context"));
        assert!(is_code_input_proxy("native-edit-context focused"));
        assert!(is_code_input_proxy("inputarea monaco-mouse-cursor-text"));
        assert!(!is_code_input_proxy("Edit"));
        assert!(!is_code_input_proxy("prosemirror prosemirror-focused"));
        assert!(!is_code_input_proxy("inputarea"));
        assert!(!is_code_input_proxy("custom-native-edit-context-copy"));
    }

    #[test]
    fn web_document_navigation_uri_is_not_input_text_but_real_url_fields_are_preserved() {
        assert!(is_navigation_document(
            true,
            false,
            "http://tauri.localhost/"
        ));
        assert!(is_navigation_document(true, false, "about:blank"));
        assert!(is_navigation_document(
            true,
            false,
            "app://localhost/index.html"
        ));
        assert!(is_navigation_document(
            true,
            false,
            "blob:https://example.com/123"
        ));
        assert!(!is_navigation_document(
            false,
            false,
            "http://127.0.0.1:8080/v1"
        ));
        assert!(!is_navigation_document(false, true, "https://example.com"));
        assert!(!is_navigation_document(true, true, "https://example.com"));
        assert!(!is_navigation_document(true, false, "会议安排"));
    }

    #[test]
    fn ime_aware_provider_never_polls_selection_even_before_first_composition() {
        let state = document_snapshot(
            "ChatGPT".into(),
            "词已经选完".into(),
            true,
            true,
            || panic!("An IME-aware provider must never be polled for selection"),
        );
        assert_eq!(state.status, "editable");
        assert_eq!(state.text, "词已经选完");
        assert_eq!(state.selection_start, None);
        assert_eq!(state.selection_end, None);

        let state = document_snapshot(
            "ChatGPT".into(),
            "词已经选完".into(),
            false,
            true,
            || panic!("A read-only document must not query an IME selection either"),
        );
        assert_eq!(state.status, "read_only");
    }

    #[test]
    fn ordinary_document_reads_caret_and_empty_document_uses_zero_selection() {
        let state = document_snapshot(
            "ChatGPT".into(),
            "词已经选完".into(),
            true,
            false,
            || Some((2, 2)),
        );
        assert_eq!(state.selection_start, Some(2));
        assert_eq!(state.selection_end, Some(2));
        let empty = document_snapshot("ChatGPT".into(), String::new(), true, true, || {
            panic!("An empty document does not need a provider selection query")
        });
        assert_eq!(empty.status, "editable");
        assert_eq!(empty.selection_start, Some(0));
        assert_eq!(empty.selection_end, Some(0));
    }

    #[test]
    fn recognizes_empty_prosemirror_placeholder_without_hiding_real_text() {
        assert!(is_placeholder_content(
            "与 Codex 协作\n",
            "与 Codex 协作",
            "prosemirror prosemirror-focused",
        ));
        assert!(!is_placeholder_content(
            "真实输入\n",
            "与 Codex 协作",
            "prosemirror prosemirror-focused",
        ));
        assert!(!is_placeholder_content(
            "与 Codex 协作\n",
            "与 Codex 协作",
            "Edit"
        ));
    }

    #[test]
    fn wolai_generated_placeholder_is_not_editable_content() {
        assert!(is_placeholder_content(
            "\u{fffc}",
            "",
            "content-editable _2VNwp"
        ));
        assert!(is_placeholder_content(
            "\u{fffc}\n",
            "",
            "content-editable _2VNwp"
        ));
        assert!(!is_placeholder_content(
            "真实文字\u{fffc}",
            "",
            "content-editable _2VNwp"
        ));
        assert!(!is_placeholder_content("\u{fffc}", "", "content-image"));
        assert!(is_rich_editor("ProseMirror prosemirror-focused"));
        assert!(is_rich_editor("content-editable _2VNwp"));
        assert!(!is_rich_editor("Edit"));
    }

    #[test]
    fn qq_empty_trailing_break_is_not_real_text_or_a_user_newline() {
        let empty = "prosemirror exeditor-qq-msg-editor is-empty";
        assert!(is_placeholder_content("\n", "", empty));
        assert!(!is_placeholder_content("文字\n", "", empty));
        assert!(!is_placeholder_content("\n\n", "", empty));
        assert!(!is_placeholder_content(
            "\n",
            "",
            "prosemirror exeditor-qq-msg-editor"
        ));
        assert!(!is_placeholder_content("\n", "", "edit is-empty"));
    }
}
