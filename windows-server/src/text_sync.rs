//! A bounded, opt-in text session. COM objects never leave their owning worker.
use serde::Serialize;
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};
use tokio::sync::oneshot;

pub const MAX_SYNC_UNITS: usize = 16_000;
pub const MAX_DOCUMENT_UNITS: usize = 1_000_000;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(2);
pub mod paragraph;

#[derive(Clone, Copy, Default)]
pub(crate) struct RequestDeadline(Option<Instant>);
impl RequestDeadline {
    pub fn set(&mut self, deadline: Option<Instant>) {
        self.0 = deadline;
    }
    pub fn check(self) -> Result<(), &'static str> {
        if self.0.is_some_and(|deadline| Instant::now() >= deadline) {
            Err("unconfirmed")
        } else {
            Ok(())
        }
    }
    pub fn remaining(self, maximum: Duration) -> Result<Duration, &'static str> {
        self.check()?;
        Ok(self.0.map_or(maximum, |deadline| {
            maximum.min(deadline.saturating_duration_since(Instant::now()))
        }))
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct TextSnapshot {
    pub target: u64,
    pub revision: u64,
    /// Content/target generation, unaffected by transient selection samples.
    #[serde(skip_serializing_if = "is_zero")]
    pub content_revision: u64,
    pub status: &'static str,
    pub source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_icon: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub window_title: Option<String>,
    pub text: String,
    /// Neighboring independent editor blocks are previews, never edit payload.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context_before: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context_after: Option<String>,
    /// Enter creates another independent editable block in this editor.
    #[serde(skip_serializing_if = "is_false")]
    pub independent_block: bool,
    pub selection_start: Option<usize>,
    pub selection_end: Option<usize>,
    /// Acknowledgement only: the adapter verified an editor input-rule rewrite.
    #[serde(skip_serializing_if = "is_false")]
    pub edit_normalized: bool,
    /// Semantic caret after a verified structural edit, only on its acknowledgement.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub edit_caret: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub paragraph_start: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub paragraph_number: Option<usize>,
    /// Present only when the provider exposes a moving accessibility page.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub accessibility_page_start_line: Option<usize>,
    #[serde(skip)]
    pub document_identity: Option<String>,
    #[serde(skip)]
    pub desktop_caret: Option<(usize, usize)>,
}
fn is_false(value: &bool) -> bool {
    !*value
}
fn is_zero(value: &u64) -> bool {
    *value == 0
}
impl TextSnapshot {
    /// Display metadata must not invalidate an in-flight phone edit (for
    /// example, a document's title may acquire an unsaved-changes marker).
    pub(crate) fn same_document(&self, other: &Self) -> bool {
        self.same_content(other)
            && self.selection_start == other.selection_start
            && self.selection_end == other.selection_end
    }
    pub(crate) fn same_content(&self, other: &Self) -> bool {
        self.target == other.target
            && self.status == other.status
            && self.text == other.text
            && self.independent_block == other.independent_block
            && self.paragraph_start == other.paragraph_start
            && self.accessibility_page_start_line == other.accessibility_page_start_line
            && self.document_identity == other.document_identity
            && self.desktop_caret == other.desktop_caret
    }
    pub(crate) fn advance_revisions(&mut self, previous: &Self) {
        self.revision = previous.revision;
        if !self.same_document(previous) {
            self.revision = self.revision.wrapping_add(1).max(1);
        }
        self.content_revision = previous.content_revision;
        if self.content_revision == 0 || !self.same_content(previous) {
            self.content_revision = self.content_revision.wrapping_add(1).max(1);
        }
    }
    pub fn unavailable(status: &'static str) -> Self {
        Self {
            status,
            ..Self::default()
        }
    }
}

pub fn normalize(text: &str) -> String {
    text.replace("\r\n", "\n").replace('\r', "\n")
}

pub fn utf16_boundary(text: &str, offset: usize) -> Option<usize> {
    let mut units = 0;
    for (byte, ch) in text.char_indices() {
        if units == offset {
            return Some(byte);
        }
        units += ch.len_utf16();
    }
    (units == offset).then_some(text.len())
}

pub fn validate_edit(text: &str, start: usize, end: usize) -> Result<(), &'static str> {
    if text.encode_utf16().count() > MAX_SYNC_UNITS
        || text
            .chars()
            .any(|ch| ch.is_control() && !matches!(ch, '\n' | '\t'))
        || utf16_boundary(text, start).is_none()
        || utf16_boundary(text, end).is_none()
    {
        return Err("同步文字或选区不符合要求。");
    }
    Ok(())
}

#[cfg(windows)]
pub mod adapters;

/// One replacement, in UTF-16 offsets; never split a surrogate pair.
pub fn replacement<'a>(old: &str, new: &'a str) -> (usize, usize, &'a str) {
    let prefix = old
        .chars()
        .zip(new.chars())
        .take_while(|(a, b)| a == b)
        .map(|(a, _)| a.len_utf8())
        .sum::<usize>();
    let suffix = old[prefix..]
        .chars()
        .rev()
        .zip(new[prefix..].chars().rev())
        .take_while(|(a, b)| a == b)
        .map(|(a, _)| a.len_utf8())
        .sum::<usize>();
    (
        old[..prefix].encode_utf16().count(),
        old[..old.len() - suffix].encode_utf16().count(),
        &new[prefix..new.len() - suffix],
    )
}

#[derive(Clone, Debug)]
pub enum TextAction {
    Read,
    Source,
    Edit {
        target: u64,
        revision: u64,
        text: String,
        start: usize,
        end: usize,
    },
    Enter {
        target: u64,
        revision: u64,
    },
    ShiftEnter {
        target: u64,
        revision: u64,
    },
}
/// Adapter contract for reading and editing the currently focused text
/// control. The protocol/session layer never depends on a concrete UI API.
pub trait TextAdapter: 'static {
    fn set_deadline(&mut self, _deadline: Option<Instant>) {}
    fn read(&mut self) -> TextSnapshot;
    /// Application metadata only; never fall back to reading the text control.
    fn read_source(&mut self) -> TextSnapshot {
        TextSnapshot::unavailable("ordinary")
    }
    fn read_for_write(&mut self, _: u64, _: u64) -> TextSnapshot {
        self.read()
    }
    /// Replacements select their own range; Enter still depends on desktop caret.
    fn read_for_edit(&mut self, target: u64, revision: u64) -> TextSnapshot {
        self.read_for_write(target, revision)
    }
    fn edit(
        &mut self,
        expected: &TextSnapshot,
        text: &str,
        start: usize,
        end: usize,
    ) -> Result<EditOutcome, &'static str>;
    fn enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str>;
    fn shift_enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
        Err("unsupported")
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EditOutcome {
    Exact,
    /// Actual text verified by the adapter, still requiring a fresh read-back.
    Normalized(String),
    NormalizedAt {
        text: String,
        caret: usize,
    },
}
impl EditOutcome {
    pub(crate) fn normalization(&self) -> Option<(&str, Option<usize>)> {
        match self {
            Self::Exact => None,
            Self::Normalized(text) => Some((text, None)),
            Self::NormalizedAt { text, caret } => Some((text, Some(*caret))),
        }
    }
}
pub struct TextResult {
    pub state: TextSnapshot,
    pub error: Option<&'static str>,
}

pub fn execute(adapter: &mut dyn TextAdapter, action: TextAction) -> TextResult {
    execute_until(adapter, action, Instant::now() + REQUEST_TIMEOUT)
}

fn execute_until(
    adapter: &mut dyn TextAdapter,
    action: TextAction,
    deadline: Instant,
) -> TextResult {
    adapter.set_deadline(Some(deadline));
    let result = execute_action(adapter, action, deadline);
    adapter.set_deadline(None);
    result
}

fn execute_action(
    adapter: &mut dyn TextAdapter,
    action: TextAction,
    deadline: Instant,
) -> TextResult {
    let action_name = match &action {
        TextAction::Read => "read",
        TextAction::Source => "source",
        TextAction::Edit { .. } => "edit",
        TextAction::Enter { .. } => "enter",
        TextAction::ShiftEnter { .. } => "shift_enter",
    };
    if Instant::now() >= deadline {
        return TextResult {
            state: TextSnapshot::unavailable("unavailable"),
            error: Some("unconfirmed"),
        };
    }
    if matches!(action, TextAction::Source) {
        return TextResult {
            state: adapter.read_source(),
            error: None,
        };
    }
    let before = match &action {
        TextAction::Read => adapter.read(),
        TextAction::Source => unreachable!(),
        TextAction::Edit {
            target, revision, ..
        } => adapter.read_for_edit(*target, *revision),
        TextAction::Enter { target, revision } | TextAction::ShiftEnter { target, revision } => {
            adapter.read_for_write(*target, *revision)
        }
    };
    let (target, revision) = match &action {
        TextAction::Source => unreachable!(),
        TextAction::Read => {
            return TextResult {
                state: before,
                error: None,
            };
        }
        TextAction::Edit {
            target, revision, ..
        }
        | TextAction::Enter { target, revision }
        | TextAction::ShiftEnter { target, revision } => (*target, *revision),
    };
    if before.status != "editable" || before.target != target || before.revision != revision {
        diagnose_rejection(
            "preflight",
            action_name,
            target,
            revision,
            &before,
            "conflict",
        );
        return TextResult {
            state: before,
            error: Some("conflict"),
        };
    }
    if Instant::now() >= deadline {
        return TextResult {
            state: before,
            error: Some("unconfirmed"),
        };
    }
    let expected_text = match &action {
        TextAction::Edit { text, .. } => Some(text.clone()),
        _ => None,
    };
    let expected_enter = matches!(
        &action,
        TextAction::Enter { .. } | TextAction::ShiftEnter { .. }
    );
    let result = match action {
        TextAction::Edit {
            text, start, end, ..
        } => {
            validate_edit(&text, start, end).and_then(|_| adapter.edit(&before, &text, start, end))
        }
        TextAction::Enter { .. } => adapter.enter(&before).map(|_| EditOutcome::Exact),
        TextAction::ShiftEnter { .. } => adapter.shift_enter(&before).map(|_| EditOutcome::Exact),
        TextAction::Read | TextAction::Source => unreachable!(),
    };
    if Instant::now() >= deadline {
        return TextResult {
            state: before,
            error: Some("unconfirmed"),
        };
    }
    let normalization = result.as_ref().ok().and_then(EditOutcome::normalization);
    let normalized_text = normalization.map(|(text, _)| text.to_string());
    let normalized_caret = normalization.and_then(|(_, caret)| caret);
    let confirmation_text = normalized_text.as_ref().or(expected_text.as_ref());
    let mut after = adapter.read();
    // SendInput acknowledges insertion in the input stream, not document processing.
    // Read back the actual target, allowing its UI thread a bounded time to consume it.
    let uncertain_edit = expected_text
        .as_ref()
        .is_some_and(|text| *text != before.text)
        && matches!(
            result,
            Err("unconfirmed" | "body_unconfirmed" | "selection_unconfirmed")
        );
    if result.is_ok() || uncertain_edit {
        if let Some(text) = confirmation_text {
            for _ in 0..6 {
                if Instant::now() >= deadline
                    || after.target != target
                    || after.status != "editable"
                    || after.text == *text
                {
                    break;
                }
                std::thread::sleep(Duration::from_millis(25));
                after = adapter.read();
            }
        } else if expected_enter {
            // Enter is asynchronous too: a target may clear, submit, or move
            // focus only after the input message reaches its UI thread.
            for _ in 0..12 {
                // Block editors briefly focus a non-editor while constructing
                // the next block. That transient state is not the completed
                // Enter result and must not disable the phone's IME.
                if Instant::now() >= deadline
                    || (after.status == "editable"
                        && (after.target != target || after.text != before.text))
                {
                    break;
                }
                std::thread::sleep(Duration::from_millis(25));
                after = adapter.read();
            }
        }
    }
    let text_confirmed = confirmation_text.is_some_and(|text| {
        after.target == target && after.status == "editable" && after.text == *text
    });
    // A timed-out provider read does not invalidate a later exact read-back.
    // Verify only; never inject the replacement a second time.
    let adapter_error = result.err();
    let mut error = adapter_error
        .filter(|_| !(uncertain_edit && text_confirmed))
        .or_else(|| {
            confirmation_text.and_then(|text| {
                (after.target != target || after.status != "editable" || after.text != *text)
                    .then_some("unconfirmed")
            })
        });
    if normalized_caret.is_some_and(|caret| utf16_boundary(&after.text, caret).is_none()) {
        error = Some("unconfirmed");
    }
    after.edit_normalized = error.is_none() && text_confirmed && normalized_text.is_some();
    after.edit_caret = if after.edit_normalized {
        normalized_caret
    } else {
        None
    };
    if let Some(error) = error {
        diagnose_rejection("write", action_name, target, revision, &after, error);
    }
    TextResult {
        state: after,
        error,
    }
}

fn diagnose_rejection(
    stage: &str,
    action: &str,
    target: u64,
    revision: u64,
    snapshot: &TextSnapshot,
    error: &str,
) {
    // Diagnose focus/caret races without retaining any user text or titles.
    eprintln!(
        "text-sync rejected stage={stage} action={action} error={error} target={target}->{} revision={revision}->{} content_revision={} status={} caret={:?}:{:?} paragraph={:?} page={:?} block={}",
        snapshot.target,
        snapshot.revision,
        snapshot.content_revision,
        snapshot.status,
        snapshot.selection_start,
        snapshot.selection_end,
        snapshot.paragraph_start,
        snapshot.accessibility_page_start_line,
        snapshot.independent_block,
    );
}

struct Job {
    action: TextAction,
    expires: Instant,
    reply: oneshot::Sender<TextResult>,
}
pub struct TextSync {
    sender: mpsc::SyncSender<Job>,
}
impl TextSync {
    pub fn spawn<B: TextAdapter>(
        factory: impl FnOnce() -> Result<B, &'static str> + Send + 'static,
    ) -> Self {
        let (sender, receiver) = mpsc::sync_channel::<Job>(8);
        std::thread::Builder::new()
            .name("text-sync".into())
            .spawn(move || {
                let mut backend = factory();
                while let Ok(job) = receiver.recv() {
                    if job.reply.is_closed() || Instant::now() >= job.expires {
                        continue;
                    }
                    let result = match &mut backend {
                        Ok(backend) => execute_until(backend, job.action, job.expires),
                        Err(_) => TextResult {
                            state: TextSnapshot::unavailable("unavailable"),
                            error: Some("unavailable"),
                        },
                    };
                    let _ = job.reply.send(result);
                }
            })
            .expect("text worker");
        Self { sender }
    }
    pub async fn request(&self, action: TextAction) -> TextResult {
        let (reply, receiver) = oneshot::channel();
        let job = Job {
            action,
            reply,
            expires: Instant::now() + REQUEST_TIMEOUT,
        };
        if self.sender.try_send(job).is_ok()
            && let Ok(Ok(result)) = tokio::time::timeout(REQUEST_TIMEOUT, receiver).await
        {
            return result;
        }
        TextResult {
            state: TextSnapshot::unavailable("unavailable"),
            error: Some("unconfirmed"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn content_generation_survives_missing_and_round_trip_carets_but_not_content_changes() {
        let mut current = TextSnapshot {
            target: 7,
            status: "editable",
            text: "你好".into(),
            selection_start: Some(2),
            selection_end: Some(2),
            ..TextSnapshot::default()
        };
        current.advance_revisions(&TextSnapshot::default());
        let original = current.clone();
        for caret in [None, Some(0), Some(2)] {
            let previous = current.clone();
            current.selection_start = caret;
            current.selection_end = caret;
            current.advance_revisions(&previous);
            assert_eq!(current.content_revision, original.content_revision);
            assert_ne!(current.revision, previous.revision);
        }
        for text in ["新内容", "你好"] {
            let previous = current.clone();
            current.text = text.into();
            current.advance_revisions(&previous);
            assert_ne!(current.content_revision, previous.content_revision);
        }
        for change in ["target", "status", "page", "identity"] {
            let previous = current.clone();
            match change {
                "target" => current.target += 1,
                "status" => current.status = "read_only",
                "page" => current.accessibility_page_start_line = Some(20),
                "identity" => current.document_identity = Some("other".into()),
                _ => unreachable!(),
            }
            current.advance_revisions(&previous);
            assert_ne!(current.content_revision, previous.content_revision);
        }
    }

    #[test]
    fn independent_block_metadata_is_optional_and_changes_the_guarded_document() {
        let mut snapshot = TextSnapshot::unavailable("editable");
        assert!(
            serde_json::to_value(&snapshot)
                .unwrap()
                .get("independent_block")
                .is_none()
        );
        let previous = snapshot.clone();
        snapshot.independent_block = true;
        assert_eq!(
            serde_json::to_value(&snapshot).unwrap()["independent_block"],
            true
        );
        assert!(!snapshot.same_document(&previous));
    }

    #[test]
    fn normalized_edits_require_exact_fresh_readback_and_only_mark_the_ack() {
        struct Normalizer {
            state: TextSnapshot,
            readback: &'static str,
            writes: std::rc::Rc<std::cell::Cell<usize>>,
            caret: Option<usize>,
        }
        impl TextAdapter for Normalizer {
            fn read(&mut self) -> TextSnapshot {
                self.state.clone()
            }
            fn edit(
                &mut self,
                _: &TextSnapshot,
                _: &str,
                _: usize,
                _: usize,
            ) -> Result<EditOutcome, &'static str> {
                self.writes.set(self.writes.get() + 1);
                self.state.text = self.readback.into();
                self.state.selection_start = Some(2);
                self.state.selection_end = Some(2);
                self.state.revision += 1;
                Ok(if let Some(caret) = self.caret {
                    EditOutcome::NormalizedAt {
                        text: "• ".into(),
                        caret,
                    }
                } else {
                    EditOutcome::Normalized("• ".into())
                })
            }
            fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
                unreachable!()
            }
        }
        for (actual, caret) in [
            ("• ", None),
            ("• ", Some(2)),
            ("• ", Some(3)),
            ("• 别的内容", Some(2)),
        ] {
            let writes = std::rc::Rc::new(std::cell::Cell::new(0));
            let mut adapter = paragraph::ParagraphAdapter::new(Normalizer {
                state: TextSnapshot {
                    target: 4,
                    revision: 1,
                    status: "editable",
                    text: "-".into(),
                    selection_start: Some(1),
                    selection_end: Some(1),
                    ..TextSnapshot::default()
                },
                readback: actual,
                writes: writes.clone(),
                caret,
            });
            let before = adapter.read();
            let result = execute(
                &mut adapter,
                TextAction::Edit {
                    target: before.target,
                    revision: before.revision,
                    text: "- ".into(),
                    start: 2,
                    end: 2,
                },
            );
            let confirmed = actual == "• " && caret != Some(3);
            assert_eq!(result.error.is_none(), confirmed);
            assert_eq!(result.state.edit_normalized, confirmed);
            assert_eq!(
                result.state.edit_caret,
                if confirmed { caret } else { None }
            );
            assert_eq!(writes.get(), 1);
            let next = execute(&mut adapter, TextAction::Read);
            assert!(!next.state.edit_normalized);
            assert!(next.state.edit_caret.is_none());
            assert!(
                !serde_json::to_value(next.state)
                    .unwrap()
                    .as_object()
                    .unwrap()
                    .contains_key("edit_normalized")
            );
        }
    }

    #[test]
    fn source_requests_never_read_or_modify_the_text_control() {
        struct MetadataOnly;
        impl TextAdapter for MetadataOnly {
            fn read(&mut self) -> TextSnapshot {
                panic!("ordinary input must not query the text provider");
            }
            fn read_source(&mut self) -> TextSnapshot {
                TextSnapshot {
                    source: "QQ".into(),
                    window_title: Some("聊天".into()),
                    ..TextSnapshot::unavailable("ordinary")
                }
            }
            fn edit(
                &mut self,
                _: &TextSnapshot,
                _: &str,
                _: usize,
                _: usize,
            ) -> Result<crate::text_sync::EditOutcome, &'static str> {
                panic!("metadata must not write");
            }
            fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
                panic!("metadata must not send keys");
            }
        }
        let mut adapter = paragraph::ParagraphAdapter::new(MetadataOnly);
        let result = execute(&mut adapter, TextAction::Source);
        assert!(result.error.is_none());
        assert_eq!(result.state.source, "QQ");
        assert_eq!(result.state.window_title.as_deref(), Some("聊天"));
        assert_eq!(result.state.status, "ordinary");
        assert!(result.state.text.is_empty());
        assert_eq!(result.state.target, 0);
        assert_eq!(result.state.revision, 0);
        assert!(result.state.selection_start.is_none());
        assert!(result.state.selection_end.is_none());
    }

    #[test]
    fn display_metadata_does_not_change_document_identity() {
        let before = TextSnapshot {
            target: 4,
            revision: 2,
            status: "editable",
            source: "notepad".into(),
            text: "文字".into(),
            selection_start: Some(2),
            selection_end: Some(2),
            window_title: Some("会议安排".into()),
            ..TextSnapshot::default()
        };
        let mut after = before.clone();
        after.window_title = Some("*会议安排 - Notepad".into());
        after.source_icon = Some("icon".into());
        after.context_before = Some("只读上一段".into());
        after.context_after = Some("只读下一段".into());
        assert!(after.same_document(&before));
        after.text.push('啊');
        assert!(!after.same_document(&before));
        after = before.clone();
        after.selection_start = Some(0);
        assert!(!after.same_document(&before));
        after = before.clone();
        after.target += 1;
        assert!(!after.same_document(&before));
        after = before.clone();
        after.status = "password";
        assert!(!after.same_document(&before));
        assert!(
            serde_json::to_value(TextSnapshot::unavailable("password"))
                .unwrap()
                .get("window_title")
                .is_none()
        );
    }
    #[test]
    fn unicode_diff_and_empty_deletion_are_valid() {
        assert_eq!(replacement("你好啊", "你好"), (2, 3, ""));
        assert_eq!(replacement("你😀好", "你😄好"), (1, 3, "😄"));
        assert_eq!(replacement("abc", "a中bc"), (1, 1, "中"));
        assert_eq!(replacement("abc", ""), (0, 3, ""));
        assert!(validate_edit("😀", 1, 2).is_err());
        assert!(validate_edit("", 0, 0).is_ok());
        assert!(validate_edit("bad\0", 0, 0).is_err());
        assert_eq!(normalize("a\r\nb\r"), "a\nb\n");
    }
    struct Fake {
        state: TextSnapshot,
        writes: usize,
    }
    impl TextAdapter for Fake {
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
            self.writes += 1;
            self.state.text = text.into();
            self.state.revision += 1;
            self.state.selection_start = Some(start);
            self.state.selection_end = Some(end);
            Ok(crate::text_sync::EditOutcome::Exact)
        }
        fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
            self.writes += 1;
            self.state.text.clear();
            self.state.revision += 1;
            Ok(())
        }
    }
    #[test]
    fn stale_revisions_new_targets_and_passwords_never_write() {
        let mut backend = Fake {
            state: TextSnapshot {
                target: 4,
                revision: 2,
                status: "editable",
                text: "你好啊".into(),
                ..TextSnapshot::default()
            },
            writes: 0,
        };
        let edit = |target, revision| TextAction::Edit {
            target,
            revision,
            text: "你好".into(),
            start: 2,
            end: 2,
        };
        assert_eq!(execute(&mut backend, edit(4, 1)).error, Some("conflict"));
        assert_eq!(execute(&mut backend, edit(3, 2)).error, Some("conflict"));
        assert_eq!(backend.writes, 0);
        assert!(execute(&mut backend, edit(4, 2)).error.is_none());
        assert_eq!(backend.state.text, "你好");
        assert_eq!(execute(&mut backend, edit(4, 2)).error, Some("conflict"));
        backend.state.status = "password";
        assert_eq!(execute(&mut backend, edit(4, 3)).error, Some("conflict"));
        assert_eq!(backend.writes, 1);
    }

    #[test]
    fn a_slow_preflight_read_cannot_write_after_the_request_deadline() {
        struct SlowRead(Fake);
        impl TextAdapter for SlowRead {
            fn read(&mut self) -> TextSnapshot {
                std::thread::sleep(Duration::from_millis(30));
                self.0.read()
            }
            fn edit(
                &mut self,
                expected: &TextSnapshot,
                text: &str,
                start: usize,
                end: usize,
            ) -> Result<crate::text_sync::EditOutcome, &'static str> {
                self.0.edit(expected, text, start, end)
            }
            fn enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str> {
                self.0.enter(expected)
            }
        }
        let mut adapter = SlowRead(Fake {
            state: TextSnapshot {
                target: 4,
                revision: 2,
                status: "editable",
                text: "before".into(),
                ..TextSnapshot::default()
            },
            writes: 0,
        });
        let result = execute_until(
            &mut adapter,
            TextAction::Edit {
                target: 4,
                revision: 2,
                text: "after".into(),
                start: 5,
                end: 5,
            },
            Instant::now() + Duration::from_millis(10),
        );
        assert_eq!(result.error, Some("unconfirmed"));
        assert_eq!(adapter.0.writes, 0);
        assert_eq!(adapter.0.state.text, "before");
    }

    #[test]
    fn enter_waits_for_the_target_text_to_change() {
        let mut backend = Fake {
            state: TextSnapshot {
                target: 7,
                revision: 3,
                status: "editable",
                text: "电脑文字".into(),
                ..TextSnapshot::default()
            },
            writes: 0,
        };
        let result = execute(
            &mut backend,
            TextAction::Enter {
                target: 7,
                revision: 3,
            },
        );
        assert!(result.error.is_none());
        assert_eq!(result.state.text, "");
        assert_eq!(backend.writes, 1);
    }

    #[test]
    fn enter_waits_through_transient_focus_until_the_new_block_is_editable() {
        struct Blocks {
            before: TextSnapshot,
            next: std::collections::VecDeque<TextSnapshot>,
            sent: usize,
        }
        impl TextAdapter for Blocks {
            fn read(&mut self) -> TextSnapshot {
                if self.sent == 0 {
                    self.before.clone()
                } else {
                    self.next.pop_front().unwrap()
                }
            }
            fn edit(
                &mut self,
                _: &TextSnapshot,
                _: &str,
                _: usize,
                _: usize,
            ) -> Result<EditOutcome, &'static str> {
                panic!("Enter must never replace the previous block")
            }
            fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
                self.sent += 1;
                Ok(())
            }
        }
        let before = TextSnapshot {
            target: 7,
            revision: 1,
            status: "editable",
            text: "上一段".into(),
            ..TextSnapshot::default()
        };
        let after = TextSnapshot {
            target: 10,
            revision: 4,
            status: "editable",
            selection_start: Some(0),
            selection_end: Some(0),
            context_before: Some("上一段".into()),
            context_after: Some("下一段".into()),
            ..TextSnapshot::default()
        };
        let mut backend = Blocks {
            before,
            sent: 0,
            next: [
                TextSnapshot {
                    target: 8,
                    revision: 2,
                    ..TextSnapshot::unavailable("no_input")
                },
                TextSnapshot {
                    target: 9,
                    revision: 3,
                    ..TextSnapshot::unavailable("unavailable")
                },
                after.clone(),
            ]
            .into(),
        };
        let result = execute(
            &mut backend,
            TextAction::Enter {
                target: 7,
                revision: 1,
            },
        );
        assert_eq!(result.error, None);
        assert_eq!(result.state.target, after.target);
        assert_eq!(result.state.status, "editable");
        assert_eq!(result.state.context_before, after.context_before);
        assert_eq!(result.state.context_after, after.context_after);
        assert_eq!(backend.sent, 1);
    }

    struct DelayedVerification {
        before: TextSnapshot,
        after: TextSnapshot,
        stale_reads: usize,
        writes: usize,
        error: &'static str,
    }
    impl TextAdapter for DelayedVerification {
        fn read(&mut self) -> TextSnapshot {
            if self.writes == 0 {
                return self.before.clone();
            }
            if self.stale_reads > 0 {
                self.stale_reads -= 1;
                self.before.clone()
            } else {
                self.after.clone()
            }
        }
        fn edit(
            &mut self,
            _: &TextSnapshot,
            _: &str,
            _: usize,
            _: usize,
        ) -> Result<crate::text_sync::EditOutcome, &'static str> {
            self.writes += 1;
            Err(self.error)
        }
        fn enter(&mut self, _: &TextSnapshot) -> Result<(), &'static str> {
            unreachable!()
        }
    }
    fn delayed(error: &'static str) -> DelayedVerification {
        let before = TextSnapshot {
            target: 7,
            revision: 3,
            status: "editable",
            text: "旧文字".into(),
            selection_start: Some(3),
            selection_end: Some(3),
            ..TextSnapshot::default()
        };
        DelayedVerification {
            after: TextSnapshot {
                text: "新😀".into(),
                revision: 4,
                ..before.clone()
            },
            before,
            stale_reads: 2,
            writes: 0,
            error,
        }
    }
    fn delayed_edit(text: &str) -> TextAction {
        TextAction::Edit {
            target: 7,
            revision: 3,
            text: text.into(),
            start: 3,
            end: 3,
        }
    }
    #[test]
    fn late_exact_readback_confirms_a_timed_out_provider_without_replaying() {
        for error in ["unconfirmed", "body_unconfirmed", "selection_unconfirmed"] {
            let mut backend = delayed(error);
            let result = execute(&mut backend, delayed_edit("新😀"));
            assert!(result.error.is_none());
            assert_eq!(result.state.text, "新😀");
            assert_eq!(backend.writes, 1);
        }
    }
    #[test]
    fn unconfirmed_content_changed_targets_and_real_write_failures_remain_errors() {
        for change in ["wrong_text", "wrong_target", "write_failed", "caret_only"] {
            let mut backend = delayed("body_unconfirmed");
            backend.stale_reads = 0;
            let text = if change == "caret_only" {
                "旧文字"
            } else {
                "新😀"
            };
            match change {
                "wrong_text" => backend.after.text = "其他文字".into(),
                "wrong_target" => backend.after.target = 8,
                "write_failed" => backend.error = "write_failed",
                "caret_only" => {
                    backend.error = "selection_unconfirmed";
                    backend.after.text = text.into();
                }
                _ => unreachable!(),
            }
            assert!(execute(&mut backend, delayed_edit(text)).error.is_some());
            assert_eq!(backend.writes, 1);
        }
    }
}
