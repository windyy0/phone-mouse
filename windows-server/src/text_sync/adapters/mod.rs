//! Focused text-control adapters.
//!
//! The session/protocol layer talks only to [`TextAdapter`]. This router picks
//! the system adapter that can identify the current focused control.

use crate::text_sync::{TextAdapter, TextSnapshot};

mod accessibility_page;
mod block_context;
mod editor_policy;
mod ia2;
mod input_rule;
mod list_edit;
mod source;
mod text_range;
pub mod uia;
pub mod win32;

#[derive(Clone, Copy, Debug, Default)]
enum ActiveAdapter {
    #[default]
    UiAutomation,
    Win32Edit,
}

pub struct AdapterRouter {
    uia: Option<uia::UiAutomationAdapter>,
    win32: win32::Win32EditAdapter,
    active: ActiveAdapter,
    source: source::SharedSourceCache,
}

impl AdapterRouter {
    pub fn new() -> Result<Self, &'static str> {
        let source = source::SourceCache::shared();
        Ok(Self {
            // Win32 can still serve classic Edit/RichEdit controls when COM or
            // a UIA provider is unavailable.
            uia: uia::UiAutomationAdapter::with_sources(source.clone()).ok(),
            win32: win32::Win32EditAdapter::with_sources(source.clone()),
            active: ActiveAdapter::default(),
            source,
        })
    }

    fn read_uia(&mut self) -> Option<TextSnapshot> {
        self.uia.as_mut().map(TextAdapter::read)
    }
    fn read_system(&mut self) -> TextSnapshot {
        let uia = self.read_uia();
        // Verify UIA's no-input/error classification with the native Edit
        // adapter too. A pane-like UIA provider may still wrap a real HWND edit.
        // Password/composing/too-long states remain authoritative.
        if let Some(state) = uia.as_ref()
            && !matches!(
                state.status,
                "unsupported" | "read_only" | "no_input" | "unavailable"
            )
            && !(state.status == "editable"
                && state.selection_start.is_none()
                && crate::text_sync::paragraph::is_long(&state.text))
        {
            self.active = ActiveAdapter::UiAutomation;
            return state.clone();
        }
        let mut win32 = self.win32.read();
        win32.target |= 1 << 49;
        if native_has_text_target(&win32) {
            self.active = ActiveAdapter::Win32Edit;
            return win32;
        }
        self.active = ActiveAdapter::UiAutomation;
        uia.unwrap_or(win32)
    }
}

impl TextAdapter for AdapterRouter {
    fn set_deadline(&mut self, deadline: Option<std::time::Instant>) {
        if let Some(uia) = self.uia.as_mut() {
            uia.set_deadline(deadline);
        }
        self.win32.set_deadline(deadline);
    }
    fn read_source(&mut self) -> TextSnapshot {
        self.source.borrow_mut().foreground()
    }
    fn read(&mut self) -> TextSnapshot {
        self.read_system()
    }

    fn edit(
        &mut self,
        expected: &TextSnapshot,
        text: &str,
        start: usize,
        end: usize,
    ) -> Result<crate::text_sync::EditOutcome, &'static str> {
        match self.active {
            ActiveAdapter::UiAutomation => self
                .uia
                .as_mut()
                .ok_or("unavailable")?
                .edit(expected, text, start, end),
            ActiveAdapter::Win32Edit => {
                let mut raw = expected.clone();
                raw.target &= !(1 << 49);
                self.win32.edit(&raw, text, start, end)
            }
        }
    }

    fn enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str> {
        match self.active {
            ActiveAdapter::UiAutomation => self.uia.as_mut().ok_or("unavailable")?.enter(expected),
            ActiveAdapter::Win32Edit => {
                let mut raw = expected.clone();
                raw.target &= !(1 << 49);
                self.win32.enter(&raw)
            }
        }
    }

    fn shift_enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str> {
        match self.active {
            ActiveAdapter::UiAutomation => self
                .uia
                .as_mut()
                .ok_or("unavailable")?
                .shift_enter(expected),
            ActiveAdapter::Win32Edit => {
                let mut raw = expected.clone();
                raw.target &= !(1 << 49);
                self.win32.shift_enter(&raw)
            }
        }
    }
}

fn native_has_text_target(state: &TextSnapshot) -> bool {
    !matches!(state.status, "no_input" | "unsupported")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn no_native_input_does_not_override_a_real_uia_field_or_its_metadata() {
        assert!(!native_has_text_target(&TextSnapshot::unavailable(
            "no_input"
        )));
        assert!(!native_has_text_target(&TextSnapshot::unavailable(
            "unsupported"
        )));
        for status in [
            "editable",
            "read_only",
            "password",
            "too_long",
            "unavailable",
        ] {
            assert!(native_has_text_target(&TextSnapshot::unavailable(status)));
        }
    }
}
