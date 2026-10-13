//! Application identity comes from the focused control's process. Window titles
//! are separate live metadata; neither lookup touches focus or text selection.
use base64::{Engine, engine::general_purpose::STANDARD};
use image::{ImageEncoder, codecs::png::PngEncoder};
use std::{cell::RefCell, collections::VecDeque, mem::size_of, ptr::null_mut, rc::Rc};
use windows_sys::Win32::{
    Foundation::{CloseHandle, HWND, LPARAM},
    Graphics::Gdi::*,
    System::Threading::{
        OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW,
    },
    UI::{Shell::SHDefExtractIconW, WindowsAndMessaging::*},
};

const ICON_SIZE: usize = 48;
const MAX_ICON_BASE64: usize = 16_384;
const MAX_WINDOW_TITLE_UNITS: usize = 512;

pub(super) struct SourceOwner {
    pub pid: u32,
    pub window: Option<HWND>,
}

fn window_pid(window: HWND) -> u32 {
    let mut pid = 0;
    if !window.is_null() {
        unsafe {
            GetWindowThreadProcessId(window, &mut pid);
        }
    }
    pid
}

struct ProcessSearch {
    pid: u32,
    visited: usize,
    found: bool,
}
unsafe extern "system" fn find_process_child(window: HWND, data: LPARAM) -> i32 {
    let search = unsafe { &mut *(data as *mut ProcessSearch) };
    search.visited += 1;
    if window_pid(window) == search.pid {
        search.found = true;
        return 0;
    }
    i32::from(search.visited < 128)
}

fn window_hosts_process(window: HWND, pid: u32) -> bool {
    if window.is_null() || pid == 0 {
        return false;
    }
    if window_pid(window) == pid {
        return true;
    }
    let mut search = ProcessSearch {
        pid,
        visited: 0,
        found: false,
    };
    unsafe {
        EnumChildWindows(
            window,
            Some(find_process_child),
            &mut search as *mut _ as LPARAM,
        );
    }
    search.found
}

/// WebView2's UIA provider is a different process from the visible application.
/// Follow the actual window hierarchy, rather than guessing from process names
/// or adopting an unrelated foreground window.
pub(super) fn source_owner(pid: u32, native: Option<HWND>) -> SourceOwner {
    unsafe {
        let mut info: GUITHREADINFO = std::mem::zeroed();
        info.cbSize = size_of::<GUITHREADINFO>() as u32;
        let focus = (GetGUIThreadInfo(0, &mut info) != 0).then_some(info.hwndFocus);
        for candidate in [native, focus, Some(GetForegroundWindow())]
            .into_iter()
            .flatten()
        {
            if candidate.is_null() {
                continue;
            }
            let root = GetAncestor(candidate, GA_ROOT);
            if IsWindowVisible(root) != 0 && window_hosts_process(root, pid) {
                return SourceOwner {
                    pid: window_pid(root),
                    window: Some(root),
                };
            }
        }
    }
    SourceOwner { pid, window: None }
}

pub(super) fn window_title(pid: u32, native: Option<HWND>) -> Option<String> {
    unsafe {
        let belongs_to_process = |hwnd: HWND| {
            if hwnd.is_null() {
                return false;
            }
            let mut actual_pid = 0;
            GetWindowThreadProcessId(hwnd, &mut actual_pid);
            actual_pid == pid
        };
        let root = |hwnd: HWND| {
            let window = GetAncestor(hwnd, GA_ROOT);
            belongs_to_process(window).then_some(window)
        };
        let window = if let Some(window) = native.filter(|hwnd| !hwnd.is_null()).and_then(root) {
            window
        } else {
            // Virtual UIA text controls (such as browser inputs) have no HWND.
            // Bind the caption to the foreground focus's process, rather than
            // looking up an arbitrary window that happens to use the same exe.
            let mut info: GUITHREADINFO = std::mem::zeroed();
            info.cbSize = size_of::<GUITHREADINFO>() as u32;
            let focused = if GetGUIThreadInfo(0, &mut info) != 0 && !info.hwndFocus.is_null() {
                info.hwndFocus
            } else {
                GetForegroundWindow()
            };
            if !belongs_to_process(focused) {
                return None;
            }
            root(focused)?
        };
        // Across processes GetWindowText reads the caption without sending
        // WM_GETTEXT to a text control, requesting selection, or activating it.
        let mut text = [0u16; MAX_WINDOW_TITLE_UNITS + 1];
        let length = GetWindowTextW(window, text.as_mut_ptr(), text.len() as i32);
        if length <= 0 {
            return None;
        }
        let title = String::from_utf16_lossy(&text[..length as usize])
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");
        (!title.is_empty()).then_some(title)
    }
}

#[derive(Clone)]
pub(super) struct SourceInfo {
    pub name: String,
    pub icon: Option<String>,
}

#[derive(Default)]
pub(super) struct SourceCache {
    entries: VecDeque<(String, SourceInfo)>,
}
pub(super) type SharedSourceCache = Rc<RefCell<SourceCache>>;
impl SourceCache {
    pub fn shared() -> SharedSourceCache {
        Rc::new(RefCell::new(Self::default()))
    }
    /// Read the foreground application's caption and executable icon without
    /// querying accessibility providers, text controls, or text selection.
    pub fn foreground(&mut self) -> crate::text_sync::TextSnapshot {
        let window = unsafe { GetForegroundWindow() };
        let pid = window_pid(window);
        if pid == 0 {
            return crate::text_sync::TextSnapshot::unavailable("ordinary");
        }
        let owner = source_owner(pid, Some(window));
        let info = self.get(owner.pid);
        crate::text_sync::TextSnapshot {
            source: info.name,
            source_icon: info.icon,
            window_title: window_title(owner.pid, owner.window),
            ..crate::text_sync::TextSnapshot::unavailable("ordinary")
        }
    }
    pub fn get(&mut self, pid: u32) -> SourceInfo {
        let Some(path) = process_path(pid) else {
            return SourceInfo {
                name: "Windows".into(),
                icon: None,
            };
        };
        // Key by executable path rather than PID: Windows can reuse a PID.
        if let Some(index) = self.entries.iter().position(|(key, _)| key == &path) {
            let entry = self.entries.remove(index).unwrap();
            let value = entry.1.clone();
            self.entries.push_back(entry);
            return value;
        }
        let info = SourceInfo {
            name: std::path::Path::new(&path)
                .file_stem()
                .map(|name| name.to_string_lossy().into_owned())
                .unwrap_or_else(|| "Windows".into()),
            icon: executable_icon(&path),
        };
        if self.entries.len() == 32 {
            self.entries.pop_front();
        }
        self.entries.push_back((path, info.clone()));
        info
    }
}

fn process_path(pid: u32) -> Option<String> {
    unsafe {
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if process.is_null() {
            return None;
        }
        let mut path = vec![0u16; 32768];
        let mut length = path.len() as u32;
        let found = QueryFullProcessImageNameW(process, 0, path.as_mut_ptr(), &mut length);
        CloseHandle(process);
        (found != 0).then(|| String::from_utf16_lossy(&path[..length as usize]))
    }
}

struct OwnedIcon(HICON);
impl Drop for OwnedIcon {
    fn drop(&mut self) {
        unsafe {
            DestroyIcon(self.0);
        }
    }
}
struct IconCanvas {
    dc: HDC,
    bitmap: HBITMAP,
    previous: HGDIOBJ,
    bits: *mut u8,
}
impl Drop for IconCanvas {
    fn drop(&mut self) {
        unsafe {
            SelectObject(self.dc, self.previous);
            DeleteDC(self.dc);
            DeleteObject(self.bitmap);
        }
    }
}
impl IconCanvas {
    fn new() -> Option<Self> {
        unsafe {
            let dc = CreateCompatibleDC(null_mut());
            if dc.is_null() {
                return None;
            }
            let mut info: BITMAPINFO = std::mem::zeroed();
            info.bmiHeader.biSize = size_of::<BITMAPINFOHEADER>() as u32;
            info.bmiHeader.biWidth = ICON_SIZE as i32;
            info.bmiHeader.biHeight = -(ICON_SIZE as i32);
            info.bmiHeader.biPlanes = 1;
            info.bmiHeader.biBitCount = 32;
            info.bmiHeader.biCompression = BI_RGB;
            let mut bits = null_mut();
            let bitmap = CreateDIBSection(dc, &info, DIB_RGB_COLORS, &mut bits, null_mut(), 0);
            if bitmap.is_null() {
                DeleteDC(dc);
                return None;
            }
            let previous = SelectObject(dc, bitmap);
            if previous.is_null() || previous as isize == -1 {
                DeleteDC(dc);
                DeleteObject(bitmap);
                return None;
            }
            Some(Self {
                dc,
                bitmap,
                previous,
                bits: bits.cast(),
            })
        }
    }
    fn draw(&self, icon: HICON, background: u8) -> Option<Vec<u8>> {
        unsafe {
            let pixels = std::slice::from_raw_parts_mut(self.bits, ICON_SIZE * ICON_SIZE * 4);
            pixels.fill(background);
            if DrawIconEx(
                self.dc,
                0,
                0,
                icon,
                ICON_SIZE as i32,
                ICON_SIZE as i32,
                0,
                null_mut(),
                DI_NORMAL,
            ) == 0
            {
                return None;
            }
            GdiFlush();
            Some(pixels.to_vec())
        }
    }
}

fn executable_icon(path: &str) -> Option<String> {
    let path: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
    let mut handle = null_mut();
    let result = unsafe {
        SHDefExtractIconW(
            path.as_ptr(),
            0,
            0,
            &mut handle,
            null_mut(),
            ICON_SIZE as u32,
        )
    };
    if handle.is_null() {
        return None;
    }
    let icon = OwnedIcon(handle);
    if result != 0 {
        return None;
    }
    let canvas = IconCanvas::new()?;
    // Two backgrounds recover alpha for both modern and legacy mask icons,
    // avoiding black boxes and premultiplied dark fringes on Android.
    let black = canvas.draw(icon.0, 0)?;
    let white = canvas.draw(icon.0, 255)?;
    let mut rgba = Vec::with_capacity(black.len());
    for (black, white) in black.chunks_exact(4).zip(white.chunks_exact(4)) {
        rgba.extend_from_slice(&straight_rgba(black, white));
    }
    let mut png = Vec::new();
    PngEncoder::new(&mut png)
        .write_image(
            &rgba,
            ICON_SIZE as u32,
            ICON_SIZE as u32,
            image::ExtendedColorType::Rgba8,
        )
        .ok()?;
    let encoded = STANDARD.encode(png);
    (encoded.len() <= MAX_ICON_BASE64).then_some(encoded)
}

fn straight_rgba(black: &[u8], white: &[u8]) -> [u8; 4] {
    let difference = (0..3)
        .map(|channel| white[channel].saturating_sub(black[channel]))
        .max()
        .unwrap();
    let alpha = 255 - difference;
    if alpha == 0 {
        return [0; 4];
    }
    let straight = |channel: usize| {
        ((u32::from(black[channel]) * 255 + u32::from(alpha) / 2) / u32::from(alpha)).min(255) as u8
    };
    [straight(2), straight(1), straight(0), alpha]
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn caption_is_bound_to_control_window_and_refreshes_without_process_cache() {
        struct OwnedWindow(HWND);
        impl Drop for OwnedWindow {
            fn drop(&mut self) {
                unsafe {
                    DestroyWindow(self.0);
                }
            }
        }
        let wide = |text: &str| text.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
        unsafe {
            let class = wide("STATIC");
            let make = |title: &str| {
                let title = wide(title);
                let hwnd = CreateWindowExW(
                    0,
                    class.as_ptr(),
                    title.as_ptr(),
                    WS_OVERLAPPEDWINDOW,
                    0,
                    0,
                    200,
                    100,
                    null_mut(),
                    null_mut(),
                    null_mut(),
                    null_mut(),
                );
                assert!(!hwnd.is_null());
                OwnedWindow(hwnd)
            };
            let first = make("会议安排");
            let second = make("预算");
            let edit = wide("EDIT");
            let private = wide("This edit text is not a window caption");
            let child = CreateWindowExW(
                0,
                edit.as_ptr(),
                private.as_ptr(),
                WS_CHILD,
                0,
                0,
                100,
                20,
                first.0,
                null_mut(),
                null_mut(),
                null_mut(),
            );
            assert!(!child.is_null());
            let pid = std::process::id();
            assert!(window_hosts_process(first.0, pid));
            assert!(!window_hosts_process(first.0, u32::MAX));
            assert_eq!(window_title(pid, Some(child)).as_deref(), Some("会议安排"));
            assert_eq!(window_title(pid, Some(second.0)).as_deref(), Some("预算"));
            let changed = wide("*会议安排 - Notepad");
            assert_ne!(SetWindowTextW(first.0, changed.as_ptr()), 0);
            assert_eq!(
                window_title(pid, Some(child)).as_deref(),
                Some("*会议安排 - Notepad")
            );
            assert!(window_title(u32::MAX, Some(child)).is_none());
        }
    }
    #[test]
    fn transparency_is_preserved_for_alpha_and_mask_icons() {
        assert_eq!(
            straight_rgba(&[0, 0, 0, 0], &[255, 255, 255, 0]),
            [0, 0, 0, 0]
        );
        assert_eq!(
            straight_rgba(&[20, 40, 80, 0], &[20, 40, 80, 0]),
            [80, 40, 20, 255]
        );
        assert_eq!(
            straight_rgba(&[0, 0, 128, 0], &[127, 127, 255, 0]),
            [255, 0, 0, 128]
        );
    }
    #[test]
    fn missing_executable_has_no_icon_and_invalid_process_has_fallback_identity() {
        assert!(executable_icon("Z:\\phone-mouse-missing-application.exe").is_none());
        let info = SourceCache::default().get(0);
        assert_eq!(info.name, "Windows");
        assert!(info.icon.is_none());
    }
}
