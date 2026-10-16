//! Win32 Edit/RichEdit adapter.
//!
//! This adapter is deliberately narrow: it handles controls that expose a
//! real HWND and the classic edit messages. Web views and custom canvases stay
//! on the UI Automation/browser adapter path.

use super::source::{SharedSourceCache, SourceCache, source_owner, window_title};
use crate::{
    input::{InputSink, WindowsInput},
    protocol::{Command, Key},
    text_sync::{MAX_DOCUMENT_UNITS, RequestDeadline, TextAdapter, TextSnapshot, normalize},
};
use std::{
    mem::size_of,
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::HWND,
    UI::WindowsAndMessaging::{
        GUITHREADINFO, GWL_STYLE, GetClassNameW, GetGUIThreadInfo, GetWindowLongPtrW,
        GetWindowThreadProcessId, SMTO_ABORTIFHUNG, SMTO_BLOCK, SMTO_ERRORONEXIT,
        SendMessageTimeoutW, WM_GETTEXT, WM_GETTEXTLENGTH, WM_SETTEXT,
    },
};

const EM_GETSEL: u32 = 0x00B0;
const EM_SETSEL: u32 = 0x00B1;
const ES_MULTILINE: isize = 0x0004;
const ES_PASSWORD: isize = 0x0020;
const ES_READONLY: isize = 0x0800;

pub struct Win32EditAdapter {
    hwnd: Option<HWND>,
    snapshot: TextSnapshot,
    next_target: u64,
    sources: SharedSourceCache,
    deadline: RequestDeadline,
}

impl Default for Win32EditAdapter {
    fn default() -> Self {
        Self::new()
    }
}

impl Win32EditAdapter {
    pub fn new() -> Self {
        Self::with_sources(SourceCache::shared())
    }
    pub(super) fn with_sources(sources: SharedSourceCache) -> Self {
        Self {
            hwnd: None,
            snapshot: TextSnapshot::default(),
            next_target: 0,
            sources,
            deadline: RequestDeadline::default(),
        }
    }

    fn focused() -> Option<HWND> {
        unsafe {
            let mut info: GUITHREADINFO = std::mem::zeroed();
            info.cbSize = size_of::<GUITHREADINFO>() as u32;
            (GetGUIThreadInfo(0, &mut info) != 0 && !info.hwndFocus.is_null())
                .then_some(info.hwndFocus)
        }
    }

    fn class_name(hwnd: HWND) -> String {
        let mut buffer = [0u16; 128];
        let count = unsafe { GetClassNameW(hwnd, buffer.as_mut_ptr(), buffer.len() as i32) };
        String::from_utf16_lossy(&buffer[..count.max(0) as usize])
    }

    fn is_edit_class(class_name: &str) -> bool {
        let class = class_name.to_ascii_lowercase();
        class == "edit" || class.starts_with("richedit")
    }

    fn style(hwnd: HWND) -> isize {
        unsafe { GetWindowLongPtrW(hwnd, GWL_STYLE) }
    }

    fn raw_content(hwnd: HWND, deadline: RequestDeadline) -> Option<String> {
        let length = message(hwnd, WM_GETTEXTLENGTH, 0, 0, deadline).ok()?;
        if length < 0 || length as usize > MAX_DOCUMENT_UNITS {
            return None;
        }
        let mut buffer = vec![0u16; length as usize + 1];
        let copied = message(
            hwnd,
            WM_GETTEXT,
            buffer.len(),
            buffer.as_mut_ptr() as isize,
            deadline,
        )
        .ok()?;
        if copied < 0 {
            return None;
        }
        Some(String::from_utf16_lossy(
            &buffer[..(copied as usize).min(buffer.len())],
        ))
    }

    fn content(hwnd: HWND, deadline: RequestDeadline) -> Option<String> {
        Self::raw_content(hwnd, deadline).map(|text| normalize(&text))
    }

    fn selection(hwnd: HWND, raw: &str, deadline: RequestDeadline) -> Option<(usize, usize)> {
        // Pointer outputs retain 32-bit offsets; the packed return value loses
        // selections past 65,535 units. EM_GETSEL is a system-marshalled message.
        let mut start = 0u32;
        let mut end = 0u32;
        message(
            hwnd,
            EM_GETSEL,
            (&mut start as *mut u32) as usize,
            (&mut end as *mut u32) as isize,
            deadline,
        )
        .ok()?;
        // RichEdit 2+ counts each paragraph break once, but WM_GETTEXT expands
        // it to CRLF. Classic Edit and RichEdit 1 count the returned CRLF pair.
        let content = if Self::single_cr_offsets(hwnd) {
            normalize(raw)
        } else {
            raw.to_string()
        };
        let start = crate::text_sync::utf16_boundary(&content, start as usize)?;
        let end = crate::text_sync::utf16_boundary(&content, end as usize)?;
        Some((
            normalize(&content[..start]).encode_utf16().count(),
            normalize(&content[..end]).encode_utf16().count(),
        ))
    }

    fn single_cr_offsets(hwnd: HWND) -> bool {
        let class = Self::class_name(hwnd).to_ascii_lowercase();
        class.starts_with("richedit") && class != "richedit"
    }

    fn process_id(hwnd: HWND) -> u32 {
        let mut process_id = 0;
        unsafe { GetWindowThreadProcessId(hwnd, &mut process_id) };
        process_id
    }

    fn same_focus(&self, hwnd: HWND) -> bool {
        Self::focused() == Some(hwnd)
    }

    fn guarded(&self, expected: &TextSnapshot) -> Result<HWND, &'static str> {
        let hwnd = self.hwnd.ok_or("conflict")?;
        if self.snapshot.target != expected.target
            || !self.same_focus(hwnd)
            || Self::content(hwnd, self.deadline).as_deref() != Some(expected.text.as_str())
        {
            return Err("conflict");
        }
        self.deadline.check()?;
        if !self.same_focus(hwnd) {
            return Err("conflict");
        }
        Ok(hwnd)
    }
}

impl TextAdapter for Win32EditAdapter {
    fn set_deadline(&mut self, deadline: Option<Instant>) {
        self.deadline.set(deadline);
    }
    fn read(&mut self) -> TextSnapshot {
        let focused = Self::focused();
        let mut next = focused
            .filter(|hwnd| Self::is_edit_class(&Self::class_name(*hwnd)))
            .map(|hwnd| {
                let style = Self::style(hwnd);
                if style & ES_PASSWORD != 0 {
                    return TextSnapshot::unavailable("password");
                }
                let raw = match Self::raw_content(hwnd, self.deadline) {
                    Some(text) => text,
                    None => return TextSnapshot::unavailable("unavailable"),
                };
                let text = normalize(&raw);
                if text.encode_utf16().count() > MAX_DOCUMENT_UNITS {
                    return TextSnapshot::unavailable("too_long");
                }
                let process_id = Self::process_id(hwnd);
                let owner = source_owner(process_id, Some(hwnd));
                let source = self.sources.borrow_mut().get(owner.pid);
                let selection = Self::selection(hwnd, &raw, self.deadline);
                TextSnapshot {
                    source: source.name,
                    source_icon: source.icon,
                    window_title: window_title(owner.pid, owner.window),
                    text,
                    status: if style & ES_READONLY == 0 {
                        "editable"
                    } else {
                        "read_only"
                    },
                    selection_start: selection.map(|(start, _)| start),
                    selection_end: selection.map(|(_, end)| end),
                    ..TextSnapshot::default()
                }
            })
            .unwrap_or_else(|| TextSnapshot::unavailable("no_input"));
        let same = self.hwnd == focused;
        if !same {
            self.next_target = self.next_target.wrapping_add(1).max(1);
        }
        next.target = self.next_target;
        next.advance_revisions(&self.snapshot);
        self.hwnd = focused;
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
        let hwnd = self.guarded(expected)?;
        let style = Self::style(hwnd);
        if text.contains(['\n', '\t']) && style & ES_MULTILINE == 0 {
            return Err("linebreak_unsupported");
        }
        if text != expected.text {
            write_content(hwnd, text, self.deadline)?;
        }
        if !self.same_focus(hwnd) {
            return Err("conflict");
        }
        let raw = Self::raw_content(hwnd, self.deadline).ok_or("unconfirmed")?;
        let offsets = if Self::single_cr_offsets(hwnd) {
            normalize(&raw)
        } else {
            raw
        };
        let start = raw_offset(&offsets, start).ok_or("range_unsupported")?;
        let end = raw_offset(&offsets, end).ok_or("range_unsupported")?;
        message(hwnd, EM_SETSEL, start, end as isize, self.deadline)?;
        Ok(crate::text_sync::EditOutcome::Exact)
    }

    fn enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str> {
        self.send_enter(expected, Key::Enter)
    }
    fn shift_enter(&mut self, expected: &TextSnapshot) -> Result<(), &'static str> {
        self.send_enter(expected, Key::ShiftEnter)
    }
}
impl Win32EditAdapter {
    fn send_enter(&mut self, expected: &TextSnapshot, key: Key) -> Result<(), &'static str> {
        let hwnd = self.guarded(expected)?;
        if !self.same_focus(hwnd) {
            return Err("conflict");
        }
        WindowsInput
            .execute(&Command::Key { key })
            .map_err(|_| "write_failed")
    }
}

/// These messages are below WM_USER and Windows marshals their pointer payloads.
fn message(
    hwnd: HWND,
    id: u32,
    wparam: usize,
    lparam: isize,
    deadline: RequestDeadline,
) -> Result<isize, &'static str> {
    let timeout = deadline
        .remaining(Duration::from_millis(200))?
        .as_millis()
        .max(1) as u32;
    let mut result = 0usize;
    let delivered = unsafe {
        SendMessageTimeoutW(
            hwnd,
            id,
            wparam,
            lparam,
            SMTO_ABORTIFHUNG | SMTO_BLOCK | SMTO_ERRORONEXIT,
            timeout,
            &mut result,
        )
    };
    if delivered == 0 {
        Err("unconfirmed")
    } else {
        Ok(result as isize)
    }
}

fn write_content(hwnd: HWND, text: &str, deadline: RequestDeadline) -> Result<(), &'static str> {
    let wide: Vec<u16> = text
        .replace('\n', "\r\n")
        .encode_utf16()
        .chain(Some(0))
        .collect();
    if message(hwnd, WM_SETTEXT, 0, wide.as_ptr() as isize, deadline)? == 0 {
        return Err("write_failed");
    }
    Ok(())
}

fn raw_offset(raw: &str, offset: usize) -> Option<usize> {
    let mut normalized = 0;
    let mut units = 0;
    let mut chars = raw.chars().peekable();
    while let Some(ch) = chars.next() {
        if normalized == offset {
            return Some(units);
        }
        normalized += if ch == '\r' { 1 } else { ch.len_utf16() };
        units += ch.len_utf16();
        if ch == '\r' && chars.peek() == Some(&'\n') {
            chars.next();
            units += 1;
        }
    }
    (normalized == offset).then_some(units)
}

#[cfg(test)]
mod tests {
    use super::{RequestDeadline, Win32EditAdapter};

    struct Fixture {
        child: std::process::Child,
        hwnd: windows_sys::Win32::Foundation::HWND,
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }
    fn fixture(hang: bool) -> Fixture {
        use std::{
            io::BufRead,
            net::TcpListener,
            os::windows::process::CommandExt,
            process::{Command, Stdio},
            time::{Duration, Instant},
        };
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let child = Command::new(std::env::current_exe().unwrap())
            .args([
                "--ignored",
                "--exact",
                "text_sync::adapters::win32::tests::cross_process_edit_fixture_host",
            ])
            .env(
                "PHONE_MOUSE_EDIT_FIXTURE",
                listener.local_addr().unwrap().to_string(),
            )
            .env(
                "PHONE_MOUSE_EDIT_FIXTURE_HANG",
                if hang { "1" } else { "0" },
            )
            .creation_flags(0x08000000)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let mut fixture = Fixture {
            child,
            hwnd: std::ptr::null_mut(),
        };
        let deadline = Instant::now() + Duration::from_secs(5);
        let stream = loop {
            match listener.accept() {
                Ok((stream, _)) => break stream,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    assert!(Instant::now() < deadline, "fixture process did not start");
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(error) => panic!("{error}"),
            }
        };
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let mut line = String::new();
        std::io::BufReader::new(stream)
            .read_line(&mut line)
            .unwrap();
        fixture.hwnd = line.trim().parse::<usize>().unwrap() as _;
        assert!(!fixture.hwnd.is_null());
        fixture
    }

    #[test]
    #[ignore = "hidden control host, invoked by the cross-process regression tests"]
    fn cross_process_edit_fixture_host() {
        use std::{
            io::Write,
            net::TcpStream,
            time::{Duration, Instant},
        };
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            CreateWindowExW, DestroyWindow, DispatchMessageW, MSG, PM_REMOVE, PeekMessageW,
            TranslateMessage,
        };
        let address = std::env::var("PHONE_MOUSE_EDIT_FIXTURE").unwrap();
        let class: Vec<u16> = "EDIT".encode_utf16().chain(Some(0)).collect();
        let text: Vec<u16> = "original".encode_utf16().chain(Some(0)).collect();
        // No WS_VISIBLE: the regression never steals foreground focus.
        let hwnd = unsafe {
            CreateWindowExW(
                0,
                class.as_ptr(),
                text.as_ptr(),
                super::ES_MULTILINE as u32,
                0,
                0,
                100,
                100,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null(),
            )
        };
        assert!(!hwnd.is_null());
        let mut stream = TcpStream::connect(address).unwrap();
        writeln!(stream, "{}", hwnd as usize).unwrap();
        if std::env::var("PHONE_MOUSE_EDIT_FIXTURE_HANG").unwrap() == "1" {
            std::thread::sleep(Duration::from_secs(3));
        }
        let until = Instant::now() + Duration::from_secs(10);
        while Instant::now() < until {
            unsafe {
                let mut msg: MSG = std::mem::zeroed();
                while PeekMessageW(&mut msg, std::ptr::null_mut(), 0, 0, PM_REMOVE) != 0 {
                    TranslateMessage(&msg);
                    DispatchMessageW(&msg);
                }
            }
            std::thread::sleep(Duration::from_millis(1));
        }
        unsafe {
            DestroyWindow(hwnd);
        }
    }

    #[test]
    fn cross_process_edit_writes_unicode_multiline_text_and_selection() {
        let fixture = fixture(false);
        let deadline = RequestDeadline::default();
        assert_eq!(
            Win32EditAdapter::content(fixture.hwnd, deadline).as_deref(),
            Some("original")
        );
        let write = super::write_content(fixture.hwnd, "甲😀\n乙", deadline);
        // A slow VM can finish processing WM_SETTEXT after its message timeout.
        // Match the session contract: confirm the exact text without replaying
        // the write, while still failing actual provider/write errors.
        assert!(matches!(write, Ok(()) | Err("unconfirmed")), "{write:?}");
        let until = std::time::Instant::now() + std::time::Duration::from_secs(2);
        let mut confirmation = deadline;
        confirmation.set(Some(until));
        let raw = loop {
            if let Some(raw) = Win32EditAdapter::raw_content(fixture.hwnd, confirmation)
                && super::normalize(&raw) == "甲😀\n乙"
            {
                break raw;
            }
            assert!(
                std::time::Instant::now() < until,
                "Cross-process write was not confirmed"
            );
            std::thread::sleep(std::time::Duration::from_millis(10));
        };
        assert_eq!(super::normalize(&raw), "甲😀\n乙");
        super::message(fixture.hwnd, super::EM_SETSEL, 3, 3, deadline).unwrap();
        assert_eq!(
            Win32EditAdapter::selection(fixture.hwnd, &raw, deadline),
            Some((3, 3))
        );
        let mut expired = deadline;
        expired.set(Some(
            std::time::Instant::now() - std::time::Duration::from_millis(1),
        ));
        assert_eq!(
            super::write_content(fixture.hwnd, "must not write", expired),
            Err("unconfirmed")
        );
        assert_eq!(
            Win32EditAdapter::content(fixture.hwnd, deadline).as_deref(),
            Some("甲😀\n乙")
        );
    }

    #[test]
    fn an_unresponsive_process_times_out_and_a_healthy_process_still_reads() {
        let hung = fixture(true);
        let start = std::time::Instant::now();
        assert!(Win32EditAdapter::raw_content(hung.hwnd, RequestDeadline::default()).is_none());
        assert!(start.elapsed() < std::time::Duration::from_secs(1));
        let healthy = fixture(false);
        assert_eq!(
            Win32EditAdapter::content(healthy.hwnd, RequestDeadline::default()).as_deref(),
            Some("original")
        );
    }

    #[test]
    fn recognizes_classic_edit_controls() {
        assert!(Win32EditAdapter::is_edit_class("Edit"));
        assert!(Win32EditAdapter::is_edit_class("RichEdit50W"));
        assert!(!Win32EditAdapter::is_edit_class("Chrome_WidgetWin_1"));
    }
    #[test]
    fn rich_edit_multiline_caret_does_not_subtract_a_character_per_newline() {
        use windows_sys::Win32::{
            Foundation::FreeLibrary,
            System::LibraryLoader::LoadLibraryW,
            UI::WindowsAndMessaging::{
                CreateWindowExW, DestroyWindow, SendMessageW, SetWindowTextW, WS_OVERLAPPEDWINDOW,
            },
        };
        let text = (1..=20)
            .map(|line| format!("第{line}行😀"))
            .collect::<Vec<_>>()
            .join("\n");
        let expected = text.encode_utf16().count();
        let wide = text
            .replace('\n', "\r\n")
            .encode_utf16()
            .chain(Some(0))
            .collect::<Vec<_>>();
        let library = "Msftedit.dll"
            .encode_utf16()
            .chain(Some(0))
            .collect::<Vec<_>>();
        let class = "RichEdit50W"
            .encode_utf16()
            .chain(Some(0))
            .collect::<Vec<_>>();
        unsafe {
            let module = LoadLibraryW(library.as_ptr());
            assert!(!module.is_null());
            let hwnd = CreateWindowExW(
                0,
                class.as_ptr(),
                [0u16].as_ptr(),
                WS_OVERLAPPEDWINDOW | super::ES_MULTILINE as u32,
                0,
                0,
                800,
                600,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null(),
            );
            assert!(!hwnd.is_null());
            assert_ne!(SetWindowTextW(hwnd, wide.as_ptr()), 0);
            SendMessageW(hwnd, super::EM_SETSEL, expected, expected as isize);
            let raw = Win32EditAdapter::raw_content(hwnd, RequestDeadline::default()).unwrap();
            assert!(raw.contains("\r\n"));
            assert_eq!(
                Win32EditAdapter::content(hwnd, RequestDeadline::default()).as_deref(),
                Some(text.as_str())
            );
            assert_eq!(
                Win32EditAdapter::selection(hwnd, &raw, RequestDeadline::default()),
                Some((expected, expected))
            );
            let middle = text[..text.find("第10行").unwrap()].encode_utf16().count();
            SendMessageW(hwnd, super::EM_SETSEL, middle, (middle + 6) as isize);
            assert_eq!(
                Win32EditAdapter::selection(hwnd, &raw, RequestDeadline::default()),
                Some((middle, middle + 6))
            );
            assert!(Win32EditAdapter::single_cr_offsets(hwnd));
            let native = super::raw_offset(&super::normalize(&raw), middle).unwrap();
            SendMessageW(hwnd, super::EM_SETSEL, native, native as isize);
            assert_eq!(
                Win32EditAdapter::selection(hwnd, &raw, RequestDeadline::default()),
                Some((middle, middle))
            );
            DestroyWindow(hwnd);
            FreeLibrary(module);
        }
    }
    #[test]
    fn native_offsets_above_65535_and_crlf_map_to_normalized_document_positions() {
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            CreateWindowExW, DestroyWindow, SendMessageW, SetWindowTextW, WS_OVERLAPPEDWINDOW,
        };
        let text = "甲".repeat(70_000) + "\r\n你好😀";
        let wide = text.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
        let class = "EDIT".encode_utf16().chain(Some(0)).collect::<Vec<_>>();
        unsafe {
            let empty = [0u16];
            let hwnd = CreateWindowExW(
                0,
                class.as_ptr(),
                empty.as_ptr(),
                WS_OVERLAPPEDWINDOW | super::ES_MULTILINE as u32,
                0,
                0,
                100,
                100,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
            assert!(!hwnd.is_null());
            struct Window(windows_sys::Win32::Foundation::HWND);
            impl Drop for Window {
                fn drop(&mut self) {
                    unsafe {
                        DestroyWindow(self.0);
                    }
                }
            }
            let _window = Window(hwnd);
            SendMessageW(hwnd, 0x00C5, 1_000_000, 0);
            assert_ne!(SetWindowTextW(hwnd, wide.as_ptr()), 0);
            SendMessageW(hwnd, super::EM_SETSEL, 70_006, 70_006);
            let raw = Win32EditAdapter::raw_content(hwnd, RequestDeadline::default()).unwrap();
            assert_eq!(
                Win32EditAdapter::selection(hwnd, &raw, RequestDeadline::default()),
                Some((70_005, 70_005))
            );
            assert_eq!(super::raw_offset(&raw, 70_005), Some(70_006));
            assert!(super::raw_offset(&raw, 70_004).is_none());
        }
    }
}
