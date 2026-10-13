//! Scope large documents to the focused paragraph and its immediate neighbors.
//! Native adapters keep the
//! original document privately; only the selected scope crosses the wire.
use super::{MAX_SYNC_UNITS, TextAdapter, TextSnapshot, utf16_boundary};
use std::time::{Duration, Instant};

pub const FULL_TEXT_UNITS: usize = 500;
pub const FULL_TEXT_LINES: usize = 5;
const CONTEXT_UNITS: usize = 12_000;

pub(crate) fn is_long(text: &str) -> bool {
    text.encode_utf16().count() > FULL_TEXT_UNITS
        || text.bytes().filter(|b| *b == b'\n').count() >= FULL_TEXT_LINES
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TextScope {
    pub start: usize,
    pub end: usize,
    pub paragraph: usize,
}

pub fn scope(text: &str, start: usize, end: usize) -> Option<TextScope> {
    if !is_long(text) {
        return None;
    }
    let first = utf16_boundary(text, start.min(end))?;
    let last = utf16_boundary(text, start.max(end))?;
    let current_left = text[..first].rfind('\n').map_or(0, |index| index + 1);
    let current_right = text[last..]
        .find('\n')
        .map_or(text.len(), |index| last + index);
    let left = if current_left == 0 {
        0
    } else {
        text[..current_left - 1]
            .rfind('\n')
            .map_or(0, |index| index + 1)
    };
    let right = if current_right == text.len() {
        text.len()
    } else {
        text[current_right + 1..]
            .find('\n')
            .map_or(text.len(), |index| current_right + 1 + index)
    };
    let mut from = text[..left].encode_utf16().count();
    let mut to = from + text[left..right].encode_utf16().count();
    if to - from > MAX_SYNC_UNITS {
        if start.abs_diff(end) > MAX_SYNC_UNITS {
            return None;
        }
        from = from.max(start.min(end).saturating_sub(CONTEXT_UNITS / 2));
        to = to.min(from + CONTEXT_UNITS.max(start.abs_diff(end)));
        if to < start.max(end) {
            to = start.max(end);
            from = to.saturating_sub(MAX_SYNC_UNITS);
        }
        while utf16_boundary(text, from).is_none() {
            from += 1;
        }
        while utf16_boundary(text, to).is_none() {
            to -= 1;
        }
    }
    Some(TextScope {
        start: from,
        end: to,
        paragraph: text[..current_left].bytes().filter(|b| *b == b'\n').count() + 1,
    })
}

pub struct ParagraphAdapter<A> {
    adapter: A,
    original: TextSnapshot,
    snapshot: TextSnapshot,
    current: Option<TextScope>,
    confirmation: Option<Confirmation>,
    next_revision: u64,
    history: std::collections::VecDeque<ScopeState>,
}
#[derive(Clone)]
struct ScopeState {
    raw: TextSnapshot,
    visible: TextSnapshot,
    range: Option<TextScope>,
}
struct Confirmation {
    range: Option<TextScope>,
    text: String,
    target: u64,
    document_identity: Option<String>,
    page_start_line: Option<usize>,
    expires: Instant,
}
impl<A: TextAdapter> ParagraphAdapter<A> {
    pub fn new(adapter: A) -> Self {
        Self {
            adapter,
            original: TextSnapshot::default(),
            snapshot: TextSnapshot::default(),
            current: None,
            confirmation: None,
            next_revision: 0,
            history: std::collections::VecDeque::new(),
        }
    }
    fn scoped(&self, mut raw: TextSnapshot, range: TextScope) -> TextSnapshot {
        let (Some(from), Some(to)) = (
            utf16_boundary(&raw.text, range.start),
            utf16_boundary(&raw.text, range.end),
        ) else {
            return TextSnapshot::unavailable("unavailable");
        };
        raw.text = raw.text[from..to].to_string();
        raw.selection_start = raw
            .selection_start
            .map(|v| v.saturating_sub(range.start).min(range.end - range.start));
        raw.selection_end = raw
            .selection_end
            .map(|v| v.saturating_sub(range.start).min(range.end - range.start));
        raw.paragraph_start = Some(range.start);
        raw.paragraph_number =
            Some(raw.accessibility_page_start_line.unwrap_or(1) + range.paragraph - 1);
        raw
    }
    fn adopt(&mut self, raw: TextSnapshot) -> TextSnapshot {
        if self.confirmation.as_ref().is_some_and(|pending| {
            pending.target != raw.target
                || (matches!(raw.status, "editable" | "read_only" | "composing")
                    && pending.document_identity != raw.document_identity)
                || Instant::now() >= pending.expires
        }) {
            self.confirmation = None;
        }
        // A provider can briefly return a stale page origin or content after
        // the native adapter has confirmed an edit. Consume the acknowledgement
        // range only when this sample actually contains the complete edit.
        let confirmed = self.confirmation.as_ref().and_then(|pending| {
            if pending.page_start_line != raw.accessibility_page_start_line {
                return None;
            }
            if !matches!(raw.status, "editable" | "read_only" | "composing") {
                return None;
            }
            let matches = if let Some(range) = pending.range {
                let from = utf16_boundary(&raw.text, range.start)?;
                let to = utf16_boundary(&raw.text, range.end)?;
                raw.text[from..to] == pending.text
            } else {
                raw.text == pending.text
            };
            matches.then_some(pending.range)
        });
        // A pre-scoped snapshot already uses relative text and selection offsets.
        let candidate = if raw.paragraph_start.is_some()
            || !matches!(raw.status, "editable" | "read_only" | "composing")
        {
            None
        } else {
            confirmed.unwrap_or_else(|| scope(&raw.text, raw.selection_start?, raw.selection_end?))
        };
        if confirmed.is_some() {
            self.confirmation = None;
        }
        let mut next =
            candidate.map_or_else(|| raw.clone(), |range| self.scoped(raw.clone(), range));
        // Never guess the current paragraph from the end of a document when
        // a system provider cannot expose the real desktop selection. Keep
        // ordinary keyboard input available until a reliable scope returns.
        let missing_scope = candidate.is_none()
            && confirmed.is_none()
            && raw.paragraph_start.is_none()
            && matches!(raw.status, "editable" | "read_only" | "composing")
            && is_long(&raw.text);
        if missing_scope || next.text.encode_utf16().count() > MAX_SYNC_UNITS {
            next.text.clear();
            next.selection_start = None;
            next.selection_end = None;
            next.paragraph_start = None;
            next.paragraph_number = None;
            next.status = "too_long";
        }
        // The underlying revision also covers changes outside the shown scope.
        next.revision = self.snapshot.revision;
        if !next.same_document(&self.snapshot) || raw.revision != self.original.revision {
            self.next_revision = self.next_revision.wrapping_add(1).max(1);
            next.revision = self.next_revision;
        }
        if !self.history.iter().any(|entry| {
            entry.visible.target == next.target && entry.visible.revision == next.revision
        }) {
            self.history.push_back(ScopeState {
                raw: raw.clone(),
                visible: next.clone(),
                range: candidate,
            });
            if self.history.len() > 8 {
                self.history.pop_front();
            }
        }
        self.original = raw;
        self.current = candidate;
        self.snapshot = next.clone();
        next
    }
}
impl<A: TextAdapter> TextAdapter for ParagraphAdapter<A> {
    fn set_deadline(&mut self, deadline: Option<Instant>) {
        self.adapter.set_deadline(deadline);
    }
    fn read_source(&mut self) -> TextSnapshot {
        // Metadata must bypass paragraph reads and leave the edit scope intact.
        self.adapter.read_source()
    }
    fn read(&mut self) -> TextSnapshot {
        let raw = self.adapter.read();
        self.adopt(raw)
    }
    fn read_for_write(&mut self, target: u64, revision: u64) -> TextSnapshot {
        let previous = self
            .history
            .iter()
            .rev()
            .find(|entry| entry.visible.target == target && entry.visible.revision == revision)
            .cloned();
        let (raw_target, raw_revision) = previous
            .as_ref()
            .map(|entry| (entry.raw.target, entry.raw.revision))
            .unwrap_or((target, revision));
        let raw = self.adapter.read_for_write(raw_target, raw_revision);
        if let Some(previous) = previous
            && raw.same_document(&previous.raw)
            && raw.revision == previous.raw.revision
        {
            // A concurrent reader may have followed the new paragraph already.
            // Restore the acknowledged range only while the real document and
            // absolute desktop selection still match its recorded version.
            let mut visible = previous.visible;
            visible.source = raw.source.clone();
            visible.source_icon = raw.source_icon.clone();
            visible.window_title = raw.window_title.clone();
            self.confirmation = None;
            self.original = raw;
            self.current = previous.range;
            self.snapshot = visible.clone();
            return visible;
        }
        self.adopt(raw)
    }
    fn edit(
        &mut self,
        expected: &TextSnapshot,
        text: &str,
        start: usize,
        end: usize,
    ) -> Result<crate::text_sync::EditOutcome, &'static str> {
        if !expected.same_document(&self.snapshot) || expected.revision != self.snapshot.revision {
            return Err("conflict");
        }
        let mut result = if let Some(range) = self.current {
            let from = utf16_boundary(&self.original.text, range.start).ok_or("conflict")?;
            let to = utf16_boundary(&self.original.text, range.end).ok_or("conflict")?;
            let updated = format!(
                "{}{}{}",
                &self.original.text[..from],
                text,
                &self.original.text[to..]
            );
            if updated.encode_utf16().count() > super::MAX_DOCUMENT_UNITS {
                return Err("too_long");
            }
            self.adapter.edit(
                &self.original,
                &updated,
                range.start + start,
                range.start + end,
            )
        } else {
            self.adapter.edit(&self.original, text, start, end)
        };
        if let Some((actual, caret)) = result
            .as_ref()
            .ok()
            .and_then(super::EditOutcome::normalization)
            && let Some(range) = self.current
        {
            let from = utf16_boundary(&self.original.text, range.start).ok_or("conflict")?;
            let to = utf16_boundary(&self.original.text, range.end).ok_or("conflict")?;
            let prefix = &self.original.text[..from];
            let suffix = &self.original.text[to..];
            let visible = actual
                .strip_prefix(prefix)
                .and_then(|body| body.strip_suffix(suffix))
                .ok_or("unconfirmed")?;
            result = Ok(if let Some(caret) = caret {
                let caret = caret.checked_sub(range.start).ok_or("unconfirmed")?;
                utf16_boundary(visible, caret).ok_or("unconfirmed")?;
                super::EditOutcome::NormalizedAt {
                    text: visible.to_string(),
                    caret,
                }
            } else {
                super::EditOutcome::Normalized(visible.to_string())
            });
        }
        // Confirm in the submitted scope, including when a short document
        // crosses the paragraph threshold. Only the next poll follows the caret.
        if result.is_ok()
            || matches!(
                result,
                Err("unconfirmed" | "body_unconfirmed" | "selection_unconfirmed")
            )
        {
            let confirmed_text = result
                .as_ref()
                .ok()
                .and_then(super::EditOutcome::normalization)
                .map_or(text, |(actual, _)| actual);
            self.confirmation = Some(Confirmation {
                range: self.current.map(|range| TextScope {
                    end: range.start + confirmed_text.encode_utf16().count(),
                    ..range
                }),
                text: confirmed_text.to_string(),
                target: self.original.target,
                document_identity: self.original.document_identity.clone(),
                page_start_line: self.original.accessibility_page_start_line,
                expires: Instant::now() + Duration::from_millis(500),
            });
        }
        result
    }
    fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
        self.adapter.enter(&self.original)
    }
    fn shift_enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
        self.adapter.shift_enter(&self.original)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalized_ack_keeps_the_submitted_scope_and_untouched_document() {
        struct ListRule(Memory);
        impl TextAdapter for ListRule {
            fn read(&mut self) -> TextSnapshot {
                self.0.read()
            }
            fn edit(
                &mut self,
                _: &TextSnapshot,
                text: &str,
                start: usize,
                end: usize,
            ) -> Result<super::super::EditOutcome, &'static str> {
                let marker = text.rfind("- ").unwrap();
                let actual = format!("{}• {}", &text[..marker], &text[marker + 2..]);
                self.0.state.text = actual.clone();
                self.0.state.selection_start = Some(start);
                self.0.state.selection_end = Some(end);
                self.0.state.revision += 1;
                Ok(super::super::EditOutcome::NormalizedAt {
                    text: actual,
                    caret: start,
                })
            }
            fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
                unreachable!()
            }
        }
        for original in [
            "0\n1\n2\n3\n4\n-".to_string(),
            format!("{}\n-", "a".repeat(498)),
        ] {
            let units = original.encode_utf16().count();
            let mut adapter = ParagraphAdapter::new(ListRule(Memory {
                state: TextSnapshot {
                    target: 9,
                    revision: 1,
                    status: "editable",
                    text: original.clone(),
                    selection_start: Some(units),
                    selection_end: Some(units),
                    ..TextSnapshot::default()
                },
            }));
            let before = adapter.read();
            let submitted = format!("{} ", before.text);
            let result = super::super::execute(
                &mut adapter,
                super::super::TextAction::Edit {
                    target: before.target,
                    revision: before.revision,
                    start: submitted.encode_utf16().count(),
                    end: submitted.encode_utf16().count(),
                    text: submitted,
                },
            );
            assert!(result.error.is_none(), "{:?}", result.error);
            assert!(result.state.edit_normalized);
            assert_eq!(result.state.edit_caret, result.state.selection_start);
            assert_eq!(result.state.paragraph_start, before.paragraph_start);
            assert_eq!(
                result.state.text,
                format!("{}• ", before.text.strip_suffix('-').unwrap())
            );
            assert_eq!(
                adapter.adapter.0.state.text,
                format!("{}• ", original.strip_suffix('-').unwrap())
            );
        }
    }
    struct Memory {
        state: TextSnapshot,
    }
    impl TextAdapter for Memory {
        fn read(&mut self) -> TextSnapshot {
            self.state.clone()
        }
        fn edit(
            &mut self,
            _: &TextSnapshot,
            text: &str,
            start: usize,
            end: usize,
        ) -> Result<crate::text_sync::EditOutcome, &'static str> {
            self.state.text = text.into();
            self.state.selection_start = Some(start);
            self.state.selection_end = Some(end);
            self.state.revision += 1;
            Ok(crate::text_sync::EditOutcome::Exact)
        }
        fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
            Ok(())
        }
    }
    #[test]
    fn crossing_line_and_length_thresholds_confirms_the_full_edit_before_scoping() {
        for (original, changed, scoped) in [
            (
                "0\n1\n2\n3\n4".to_string(),
                "0\n1\n2\n3\n4\n5".to_string(),
                "4\n5".to_string(),
            ),
            (
                format!("a\nb\n{}", "x".repeat(496)),
                format!("a\nb\n{}", "x".repeat(497)),
                format!("b\n{}", "x".repeat(497)),
            ),
        ] {
            let original_units = original.encode_utf16().count();
            let mut adapter = ParagraphAdapter::new(Memory {
                state: TextSnapshot {
                    target: 9,
                    revision: 1,
                    status: "editable",
                    text: original,
                    selection_start: Some(original_units),
                    selection_end: Some(original_units),
                    ..TextSnapshot::default()
                },
            });
            let before = adapter.read();
            assert!(before.paragraph_start.is_none());
            let length = changed.encode_utf16().count();
            let result = super::super::execute(
                &mut adapter,
                super::super::TextAction::Edit {
                    target: before.target,
                    revision: before.revision,
                    text: changed.clone(),
                    start: length,
                    end: length,
                },
            );
            assert!(result.error.is_none(), "{:?}", result.error);
            assert_eq!(result.state.text, changed);
            assert!(result.state.paragraph_start.is_none());
            assert_eq!(adapter.adapter.state.text, changed);
            let next = adapter.read();
            assert_eq!(next.text, scoped);
            assert!(next.paragraph_start.is_some());
        }
    }
    #[test]
    fn paragraph_edits_preserve_surrounding_text_and_ack_newlines_before_following_caret() {
        let above = "甲".repeat(300);
        let below = "乙".repeat(300);
        let visible = format!("{above}\n你好😀\n{below}");
        let document = format!("outside-before\n{visible}\noutside-after");
        let offset = "outside-before\n".encode_utf16().count() + above.encode_utf16().count() + 1;
        let visible_offset = above.encode_utf16().count() + 1;
        let mut adapter = ParagraphAdapter::new(Memory {
            state: TextSnapshot {
                target: 9,
                revision: 1,
                status: "editable",
                text: document.clone(),
                selection_start: Some(offset + 4),
                selection_end: Some(offset + 4),
                ..TextSnapshot::default()
            },
        });
        let before = adapter.read();
        assert_eq!(before.text, visible);
        assert_eq!(before.selection_end, Some(visible_offset + 4));
        assert_eq!(before.paragraph_number, Some(3));
        let changed = visible.replacen("你好😀", "你好😀\n新段", 1);
        adapter
            .edit(&before, &changed, visible_offset + 7, visible_offset + 7)
            .unwrap();
        let ack = adapter.read();
        assert_eq!(ack.text, changed);
        assert_eq!(
            adapter.adapter.state.text,
            document.replacen("你好😀", "你好😀\n新段", 1)
        );
        assert_eq!(adapter.read().text, format!("你好😀\n新段\n{below}"));
        let continued = adapter.read_for_write(ack.target, ack.revision);
        assert_eq!(continued.text, changed);
        let changed = changed.replacen("新段", "新段！", 1);
        adapter
            .edit(&continued, &changed, visible_offset + 8, visible_offset + 8)
            .unwrap();
        assert_eq!(adapter.read().text, changed);
        let next = adapter.read();
        assert_eq!(next.text, format!("你好😀\n新段！\n{below}"));
        assert_eq!(next.selection_end, Some(8));
        assert_eq!(next.paragraph_number, Some(4));
    }
    #[test]
    fn long_text_without_a_caret_falls_back_and_recovers_when_selection_returns() {
        for text in ["甲".repeat(501), "1\n2\n3\n4\n5\n6".into()] {
            let mut adapter = ParagraphAdapter::new(Memory {
                state: TextSnapshot {
                    target: 9,
                    revision: 1,
                    status: "editable",
                    source: "test-app".into(),
                    source_icon: Some("icon".into()),
                    window_title: Some("test-document".into()),
                    text,
                    ..TextSnapshot::default()
                },
            });
            let fallback = adapter.read();
            assert_eq!(fallback.status, "too_long");
            assert!(fallback.text.is_empty());
            assert!(fallback.selection_start.is_none());
            assert!(fallback.paragraph_start.is_none());
            assert_eq!(fallback.source, "test-app");
            assert_eq!(fallback.source_icon.as_deref(), Some("icon"));
            assert_eq!(fallback.window_title.as_deref(), Some("test-document"));
            let length = adapter.adapter.state.text.encode_utf16().count();
            adapter.adapter.state.selection_start = Some(length);
            adapter.adapter.state.selection_end = Some(length);
            let recovered = adapter.read();
            assert_eq!(recovered.status, "editable");
            assert!(recovered.paragraph_start.is_some());
            assert!(recovered.revision > fallback.revision);
        }
        let mut short = ParagraphAdapter::new(Memory {
            state: TextSnapshot {
                status: "editable",
                text: "短文本".into(),
                ..TextSnapshot::default()
            },
        });
        assert_eq!(short.read().text, "短文本");
    }
    #[test]
    fn moving_identical_accessibility_pages_never_reuses_an_old_edit_scope() {
        let mut adapter = ParagraphAdapter::new(Memory {
            state: TextSnapshot {
                target: 9,
                revision: 1,
                status: "editable",
                text: "repeat\n".repeat(500),
                selection_start: Some(2),
                selection_end: Some(2),
                accessibility_page_start_line: Some(3501),
                desktop_caret: Some((3501, 3)),
                document_identity: Some("file".into()),
                ..TextSnapshot::default()
            },
        });
        let before = adapter.read();
        assert_eq!(before.paragraph_number, Some(3501));
        adapter.adapter.state.accessibility_page_start_line = Some(4001);
        adapter.adapter.state.desktop_caret = Some((4001, 3));
        let after = adapter.read_for_write(before.target, before.revision);
        assert_eq!(after.text, before.text);
        assert_eq!(after.paragraph_start, before.paragraph_start);
        assert_eq!(after.paragraph_number, Some(4001));
        assert_ne!(after.revision, before.revision);
        assert_eq!(
            adapter.edit(&before, "wrong paragraph", 0, 0),
            Err("conflict")
        );
    }
    #[test]
    fn short_text_blank_paragraphs_and_emoji_context_have_exact_boundaries() {
        assert!(scope("一\n二", 1, 1).is_none());
        let text = "1\n2\n3\n\n5\n6";
        let range = scope(text, 6, 6).unwrap();
        assert_eq!((range.start, range.end, range.paragraph), (4, 8, 4));
        let text = "😀".repeat(15_000);
        let range = scope(&text, 17_002, 17_002).unwrap();
        assert!(range.start <= 17_002 && range.end >= 17_002);
        assert!(range.end - range.start <= MAX_SYNC_UNITS);
        assert!(utf16_boundary(&text, range.start).is_some());
        assert!(utf16_boundary(&text, range.end).is_some());
    }

    #[test]
    fn paragraph_context_keeps_both_neighbors_and_handles_edges_and_cross_paragraph_selection() {
        let text = "0\n1\n2\n3\n4\n5\n6";
        let extract = |start, end| {
            let range = scope(text, start, end).unwrap();
            let from = utf16_boundary(text, range.start).unwrap();
            let to = utf16_boundary(text, range.end).unwrap();
            (text[from..to].to_string(), range.paragraph)
        };
        assert_eq!(extract(6, 6), ("2\n3\n4".into(), 4));
        assert_eq!(extract(0, 0), ("0\n1".into(), 1));
        assert_eq!(extract(text.len(), text.len()), ("5\n6".into(), 7));
        assert_eq!(extract(4, 9), ("1\n2\n3\n4\n5".into(), 3));
        let text = "0\n1\n2\n3\n4\n5\n";
        let range = scope(text, text.len(), text.len()).unwrap();
        assert_eq!(&text[range.start..range.end], "5\n");
        assert_eq!(range.paragraph, 7);
    }

    #[test]
    fn newline_acknowledgement_survives_a_stale_page_origin_and_stale_content_sample() {
        struct Laggy {
            memory: Memory,
            old_text: String,
            stage: u8,
        }
        impl TextAdapter for Laggy {
            fn read(&mut self) -> TextSnapshot {
                let mut raw = self.memory.read();
                match self.stage {
                    2 => raw.accessibility_page_start_line = Some(3500),
                    1 => raw.text = self.old_text.clone(),
                    _ => {}
                }
                self.stage = self.stage.saturating_sub(1);
                raw
            }
            fn edit(
                &mut self,
                expected: &TextSnapshot,
                text: &str,
                start: usize,
                end: usize,
            ) -> Result<crate::text_sync::EditOutcome, &'static str> {
                self.old_text = self.memory.state.text.clone();
                self.memory.edit(expected, text, start, end)?;
                self.stage = 2;
                Ok(crate::text_sync::EditOutcome::Exact)
            }
            fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
                Ok(())
            }
        }
        let mut adapter = ParagraphAdapter::new(Laggy {
            memory: Memory {
                state: TextSnapshot {
                    target: 9,
                    revision: 1,
                    status: "editable",
                    text: "0\n1\n2\n3\n4\n5\n6".into(),
                    selection_start: Some(6),
                    selection_end: Some(6),
                    accessibility_page_start_line: Some(3501),
                    document_identity: Some("fixture".into()),
                    ..TextSnapshot::default()
                },
            },
            old_text: String::new(),
            stage: 0,
        });
        let before = adapter.read();
        assert_eq!(before.text, "2\n3\n4");
        let result = super::super::execute(
            &mut adapter,
            super::super::TextAction::Edit {
                target: before.target,
                revision: before.revision,
                text: "2\n3\n新段😀\n4".into(),
                start: 8,
                end: 8,
            },
        );
        assert!(result.error.is_none());
        assert_eq!(result.state.text, "2\n3\n新段😀\n4");
        assert_eq!(
            adapter.adapter.memory.state.text,
            "0\n1\n2\n3\n新段😀\n4\n5\n6"
        );
        assert_eq!(adapter.read().text, "3\n新段😀\n4");
    }
}
