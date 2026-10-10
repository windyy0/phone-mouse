//! Explicit native validation: moves the real cursor and clicks only its own test window.
//! cargo run --release --example laser_smoke
#[cfg(not(windows))]
fn main() {}

#[cfg(windows)]
fn main() -> anyhow::Result<()> {
    use phone_mouse_server::{
        laser::{NativeLaser, run_cursor_guardian},
        presentation::LaserSurface,
        protocol::LaserSettings,
    };
    use std::{
        collections::hash_map::DefaultHasher,
        hash::{Hash, Hasher},
        ptr::null_mut,
        time::{Duration, Instant},
    };
    use windows_sys::Win32::{
        Foundation::{LPARAM, POINT, RECT},
        Graphics::Gdi::*,
        UI::{HiDpi::*, WindowsAndMessaging::*},
    };
    if std::env::args().any(|arg| arg == "--cursor-guardian") {
        return run_cursor_guardian();
    }
    unsafe {
        SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    unsafe {
        SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    // Pixel and curve assertions use a fixed 100% baseline independent of product defaults.
    fn baseline_settings() -> LaserSettings {
        LaserSettings {
            size_percent: 100,
            trail_length_px: 120,
            fade_ms: 600,
            ..LaserSettings::default()
        }
    }
    fn cursor_hash() -> u64 {
        let mut info: ICONINFO = unsafe { std::mem::zeroed() };
        let cursor = unsafe { LoadCursorW(null_mut(), IDC_ARROW) };
        assert_ne!(unsafe { GetIconInfo(cursor, &mut info) }, 0);
        let mut hash = DefaultHasher::new();
        for bitmap in [info.hbmMask, info.hbmColor] {
            if bitmap.is_null() {
                continue;
            }
            let mut object: BITMAP = unsafe { std::mem::zeroed() };
            unsafe {
                GetObjectW(
                    bitmap,
                    std::mem::size_of::<BITMAP>() as i32,
                    (&mut object as *mut BITMAP).cast(),
                );
            }
            let mut bytes = vec![0_u8; (object.bmWidthBytes * object.bmHeight) as usize];
            unsafe {
                GetBitmapBits(bitmap, bytes.len() as i32, bytes.as_mut_ptr().cast());
                DeleteObject(bitmap);
            }
            bytes.hash(&mut hash);
        }
        hash.finish()
    }
    fn assert_cursor_image(
        cursor: HCURSOR,
        color: u32,
        dpi: u32,
        size_percent: u32,
        has_trail: bool,
    ) {
        struct Snapshot(HCURSOR);
        impl Drop for Snapshot {
            fn drop(&mut self) {
                unsafe {
                    DestroyCursor(self.0);
                }
            }
        }
        // The shared system role changes while its trail fades. Freeze one
        // image so GetIconInfo and DrawIconEx compare the same frame.
        let snapshot = Snapshot(unsafe { CopyImage(cursor, IMAGE_CURSOR, 0, 0, 0) });
        assert!(!snapshot.0.is_null());
        let cursor = snapshot.0;
        let mut icon: ICONINFO = unsafe { std::mem::zeroed() };
        assert_ne!(unsafe { GetIconInfo(cursor, &mut icon) }, 0);
        assert!(!icon.hbmColor.is_null());
        let mut bitmap: BITMAP = unsafe { std::mem::zeroed() };
        unsafe {
            GetObjectW(
                icon.hbmColor,
                std::mem::size_of::<BITMAP>() as i32,
                (&mut bitmap as *mut BITMAP).cast(),
            );
        }
        let radius =
            (10.0 * f64::from(dpi.max(96)) / 96.0 * f64::from(size_percent) / 100.0).ceil() as i32;
        if has_trail {
            assert!(bitmap.bmWidth > radius * 2 + 1 || bitmap.bmHeight > radius * 2 + 1);
        } else {
            assert_eq!(
                (bitmap.bmWidth, bitmap.bmHeight),
                (radius * 2 + 1, radius * 2 + 1)
            );
            assert_eq!(
                (icon.xHotspot, icon.yHotspot),
                (radius as u32, radius as u32)
            );
        }
        assert!(
            (icon.xHotspot as i32) < bitmap.bmWidth && (icon.yHotspot as i32) < bitmap.bmHeight
        );
        assert_eq!(bitmap.bmBitsPixel, 32);
        let mut pixels = vec![0_u32; (bitmap.bmWidthBytes * bitmap.bmHeight) as usize / 4];
        unsafe {
            GetBitmapBits(
                icon.hbmColor,
                (pixels.len() * 4) as i32,
                pixels.as_mut_ptr().cast(),
            );
        }
        assert_eq!(
            pixels[icon.yHotspot as usize * (bitmap.bmWidthBytes as usize / 4)
                + icon.xHotspot as usize],
            0xffffffff
        );
        let ring_x = (icon.xHotspot as f64
            + 3.0 * f64::from(dpi.max(96)) / 96.0 * f64::from(size_percent) / 100.0)
            .round() as usize;
        let colored = pixels[icon.yHotspot as usize * (bitmap.bmWidthBytes as usize / 4) + ring_x];
        let alpha = colored >> 24;
        assert!(alpha > 128);
        for shift in [0, 8, 16] {
            assert!(((colored >> shift) & 255).abs_diff((color >> shift) & 255) <= 2);
        }

        assert_eq!(pixels[0] >> 24, 0);
        let scale = f64::from(dpi.max(96)) / 96.0 * f64::from(size_percent) / 100.0;
        let edge_x = icon.xHotspot as usize + (7.5 * scale).ceil() as usize;
        if !has_trail {
            assert_eq!(
                pixels[icon.yHotspot as usize * (bitmap.bmWidthBytes as usize / 4) + edge_x],
                0,
                "the old faint skirt remained in the installed cursor"
            );
        }
        // Exercise Win32's actual alpha + AND-mask composition onto both
        // backgrounds without taking a screenshot or opening a visible window.
        for background in [0x00f8f9fb_u32, 0x00181d24] {
            let mut dib: BITMAPINFO = unsafe { std::mem::zeroed() };
            dib.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
            dib.bmiHeader.biWidth = bitmap.bmWidth;
            dib.bmiHeader.biHeight = -bitmap.bmHeight;
            dib.bmiHeader.biPlanes = 1;
            dib.bmiHeader.biBitCount = 32;
            dib.bmiHeader.biCompression = BI_RGB;
            let mut bits = null_mut();
            let output = unsafe {
                CreateDIBSection(null_mut(), &dib, DIB_RGB_COLORS, &mut bits, null_mut(), 0)
            };
            assert!(!output.is_null());
            let count = (bitmap.bmWidth * bitmap.bmHeight) as usize;
            let destination = unsafe { std::slice::from_raw_parts_mut(bits.cast::<u32>(), count) };
            destination.fill(0xff000000 | background);
            let dc = unsafe { CreateCompatibleDC(null_mut()) };
            assert!(!dc.is_null());
            let previous = unsafe { SelectObject(dc, output) };
            assert_ne!(
                unsafe {
                    DrawIconEx(
                        dc,
                        0,
                        0,
                        cursor,
                        bitmap.bmWidth,
                        bitmap.bmHeight,
                        0,
                        null_mut(),
                        DI_NORMAL,
                    )
                },
                0
            );
            unsafe {
                GdiFlush();
            }
            for (index, (source, actual)) in pixels.iter().zip(destination.iter()).enumerate() {
                let alpha = source >> 24;
                for shift in [0, 8, 16] {
                    let expected = (((source >> shift) & 255) * alpha + 127) / 255
                        + (((background >> shift) & 255) * (255 - alpha) + 127) / 255;
                    assert!(
                        ((actual >> shift) & 255).abs_diff(expected) <= 2,
                        "native cursor composition mismatch: size={size_percent} bg={background:08x} index={index} source={source:08x} actual={actual:08x} channel={shift} expected={expected}"
                    );
                }
            }
            unsafe {
                SelectObject(dc, previous);
                DeleteDC(dc);
                DeleteObject(output);
            }
        }
        let core_radius = 1.2 * f64::from(dpi.max(96)) / 96.0 * f64::from(size_percent) / 100.0;
        let opaque = pixels.iter().filter(|pixel| **pixel >> 24 == 255).count();
        assert!(opaque as f64 >= std::f64::consts::PI * (core_radius - 1.0).max(0.0).powi(2));
        unsafe {
            DeleteObject(icon.hbmColor);
            DeleteObject(icon.hbmMask);
        }
    }
    fn assert_global_cursor(color: u32, has_trail: bool) {
        assert_global_cursor_at_size(color, 100, has_trail);
    }
    fn assert_global_cursor_at_size(color: u32, size_percent: u32, has_trail: bool) {
        let mut cursor: CURSORINFO = unsafe { std::mem::zeroed() };
        cursor.cbSize = std::mem::size_of::<CURSORINFO>() as u32;
        assert_ne!(unsafe { GetCursorInfo(&mut cursor) }, 0);
        assert_ne!(cursor.flags & CURSOR_SHOWING, 0);
        let (mut dpi, mut dpi_y) = (96, 96);
        let monitor = unsafe { MonitorFromPoint(cursor.ptScreenPos, MONITOR_DEFAULTTONEAREST) };
        unsafe {
            GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dpi, &mut dpi_y);
        }
        assert_cursor_image(cursor.hCursor, color, dpi, size_percent, has_trail);
    }
    fn assert_single_hot_spot(cursor: HCURSOR, dpi: u32) -> i32 {
        let mut icon: ICONINFO = unsafe { std::mem::zeroed() };
        assert_ne!(unsafe { GetIconInfo(cursor, &mut icon) }, 0);
        let mut bitmap: BITMAP = unsafe { std::mem::zeroed() };
        assert_ne!(
            unsafe {
                GetObjectW(
                    icon.hbmColor,
                    std::mem::size_of::<BITMAP>() as i32,
                    (&mut bitmap as *mut BITMAP).cast(),
                )
            },
            0
        );
        let mut pixels = vec![0_u32; (bitmap.bmWidthBytes * bitmap.bmHeight) as usize / 4];
        assert_ne!(
            unsafe {
                GetBitmapBits(
                    icon.hbmColor,
                    (pixels.len() * 4) as i32,
                    pixels.as_mut_ptr().cast(),
                )
            },
            0
        );
        unsafe {
            DeleteObject(icon.hbmColor);
            DeleteObject(icon.hbmMask);
        }
        assert!(bitmap.bmWidth <= 256 && bitmap.bmHeight <= 256);
        let stride = bitmap.bmWidthBytes as usize / 4;
        let mut hot_pixels = 0;
        let core_radius = 1.7 * f64::from(dpi.max(96)) / 96.0;
        for row in 0..bitmap.bmHeight as usize {
            for column in 0..bitmap.bmWidth as usize {
                let pixel = pixels[row * stride + column];
                if pixel >> 24 > 16
                    && [pixel & 255, (pixel >> 8) & 255, (pixel >> 16) & 255]
                        .into_iter()
                        .min()
                        .unwrap()
                        > 185
                {
                    hot_pixels += 1;
                    let distance = (column as f64 - f64::from(icon.xHotspot))
                        .hypot(row as f64 - f64::from(icon.yHotspot));
                    assert!(
                        distance <= core_radius,
                        "native trail contains a second hot spot"
                    );
                }
            }
        }
        assert!(hot_pixels > 0);
        assert_eq!(
            pixels[icon.yHotspot as usize * stride + icon.xHotspot as usize],
            0xffffffff
        );
        bitmap.bmWidth.max(bitmap.bmHeight)
    }
    if std::env::args().any(|arg| arg == "--probe") {
        println!("{}", cursor_hash());
        return Ok(());
    }
    let before = cursor_hash();
    let quick = std::env::args().any(|arg| arg == "--quick");
    let mut original = POINT { x: 0, y: 0 };
    unsafe {
        GetPhysicalCursorPos(&mut original);
    }
    struct Restore(POINT);
    impl Drop for Restore {
        fn drop(&mut self) {
            unsafe {
                SetPhysicalCursorPos(self.0.x, self.0.y);
            }
        }
    }
    let _restore = Restore(original);
    let laser = NativeLaser::spawn()?;
    let class: Vec<u16> = "PhoneMouseNativeLaser"
        .encode_utf16()
        .chain(Some(0))
        .collect();
    let hwnd = unsafe { FindWindowW(class.as_ptr(), std::ptr::null()) };
    assert!(!hwnd.is_null());
    let style = unsafe { GetWindowLongPtrW(hwnd, GWL_EXSTYLE) } as u32;
    let required = WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
    assert_eq!(style & required, required);
    let phase = |name: &str| {
        println!("{{\"phase\":\"{name}\",\"pid\":{}}}", std::process::id());
    };
    phase("hidden_initial");
    std::thread::sleep(Duration::from_secs(if quick { 0 } else { 6 }));
    let focus = unsafe { GetForegroundWindow() };
    laser.show(baseline_settings())?;
    assert_eq!(unsafe { GetForegroundWindow() }, focus);
    assert_ne!(cursor_hash(), before);
    assert_eq!(unsafe { IsWindowVisible(hwnd) }, 0);
    phase("laser_idle");
    std::thread::sleep(Duration::from_secs(if quick { 0 } else { 6 }));
    if std::env::args().any(|arg| arg == "--crash") {
        std::process::exit(77);
    }
    phase("laser_moving");
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(if quick { 0 } else { 10 }) {
        let angle = start.elapsed().as_secs_f64() * 3.0;
        unsafe {
            SetPhysicalCursorPos(
                original.x + (angle.cos() * 80.0) as i32,
                original.y + (angle.sin() * 60.0) as i32,
            );
        }
        std::thread::sleep(Duration::from_millis(8));
    }
    laser.configure(LaserSettings {
        color: 0x30a0ff,
        trail: false,
        fade_ms: 100,
        ..baseline_settings()
    })?;
    let size_focus = unsafe { GetForegroundWindow() };
    let mut size_position = POINT { x: 0, y: 0 };
    unsafe {
        GetPhysicalCursorPos(&mut size_position);
    }
    for size_percent in [50, 110, 200, 300, 100] {
        laser.configure(LaserSettings {
            color: 0x30a0ff,
            trail: false,
            size_percent,
            ..baseline_settings()
        })?;
        std::thread::sleep(Duration::from_millis(50));
        assert_global_cursor_at_size(0x30a0ff, size_percent, false);
        let mut actual = POINT { x: 0, y: 0 };
        unsafe {
            GetPhysicalCursorPos(&mut actual);
        }
        assert_eq!((actual.x, actual.y), (size_position.x, size_position.y));
        assert_eq!(unsafe { GetForegroundWindow() }, size_focus);
    }
    println!("{{\"native_size_changes\":\"passed\",\"focus_and_position_preserved\":true}}");
    unsafe extern "system" fn monitor_callback(
        monitor: HMONITOR,
        _: HDC,
        _: *mut RECT,
        data: LPARAM,
    ) -> i32 {
        let monitors = unsafe { &mut *(data as *mut Vec<HMONITOR>) };
        monitors.push(monitor);
        1
    }
    let mut monitors: Vec<HMONITOR> = Vec::new();
    unsafe {
        EnumDisplayMonitors(
            null_mut(),
            std::ptr::null(),
            Some(monitor_callback),
            &mut monitors as *mut _ as LPARAM,
        );
    }
    for monitor in monitors {
        let mut info: MONITORINFO = unsafe { std::mem::zeroed() };
        info.cbSize = std::mem::size_of::<MONITORINFO>() as u32;
        unsafe {
            GetMonitorInfoW(monitor, &mut info);
        }
        let (mut dpi, mut dpi_y) = (96, 96);
        unsafe {
            GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dpi, &mut dpi_y);
        }
        let x = info.rcMonitor.left + (info.rcMonitor.right - info.rcMonitor.left) / 2;
        let y = info.rcMonitor.top + (info.rcMonitor.bottom - info.rcMonitor.top) / 2;
        unsafe {
            SetPhysicalCursorPos(x, y);
        }
        std::thread::sleep(Duration::from_millis(80));
        let mut actual = POINT { x: 0, y: 0 };
        let mut clip: RECT = unsafe { std::mem::zeroed() };
        unsafe {
            GetPhysicalCursorPos(&mut actual);
            GetClipCursor(&mut clip);
        }
        assert_eq!((actual.x, actual.y), (x, y));
        assert_cursor_image(
            unsafe { LoadCursorW(null_mut(), IDC_ARROW) },
            0x30a0ff,
            dpi,
            100,
            false,
        );
        assert_eq!(unsafe { IsWindowVisible(hwnd) }, 0);
        println!("{{\"monitor\":{{\"x\":{x},\"y\":{y},\"dpi\":{dpi}}}}}");
    }
    // The native point remains visible when the taskbar is raised, even with
    // the entire trail window hidden. Its visibility is independent of z-order.
    let taskbar_class: Vec<u16> = "Shell_TrayWnd".encode_utf16().chain(Some(0)).collect();
    let taskbar = unsafe { FindWindowW(taskbar_class.as_ptr(), std::ptr::null()) };
    let mut taskbar_rect: RECT = unsafe { std::mem::zeroed() };
    if !taskbar.is_null()
        && unsafe { IsWindowVisible(taskbar) } != 0
        && unsafe { GetWindowRect(taskbar, &mut taskbar_rect) } != 0
        && taskbar_rect.right - taskbar_rect.left > 2
        && taskbar_rect.bottom - taskbar_rect.top > 2
    {
        let taskbar_focus = unsafe { GetForegroundWindow() };
        let mut previous = POINT { x: 0, y: 0 };
        unsafe {
            GetPhysicalCursorPos(&mut previous);
            SetPhysicalCursorPos(
                taskbar_rect.left + (taskbar_rect.right - taskbar_rect.left) / 2,
                taskbar_rect.top + (taskbar_rect.bottom - taskbar_rect.top) / 2,
            );
        }
        std::thread::sleep(Duration::from_millis(150));
        assert_ne!(
            unsafe {
                SetWindowPos(
                    taskbar,
                    HWND_TOPMOST,
                    0,
                    0,
                    0,
                    0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
                )
            },
            0
        );
        std::thread::sleep(Duration::from_millis(100));
        assert_eq!(unsafe { IsWindowVisible(hwnd) }, 0);
        assert_global_cursor(0x30a0ff, false);
        assert_eq!(unsafe { GetForegroundWindow() }, taskbar_focus);
        unsafe {
            SetPhysicalCursorPos(previous.x, previous.y);
        }
        println!("{{\"native_cursor_over_taskbar\":\"passed\",\"focus_preserved\":true}}");
    }
    // A controlled, non-activating target below the laser receives a real
    // injected click. No click is sent to the user's foreground application.
    static CLICK_EVENTS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    unsafe extern "system" fn target_proc(
        window: windows_sys::Win32::Foundation::HWND,
        message: u32,
        wparam: windows_sys::Win32::Foundation::WPARAM,
        lparam: LPARAM,
    ) -> windows_sys::Win32::Foundation::LRESULT {
        match message {
            WM_LBUTTONDOWN | WM_LBUTTONUP => {
                CLICK_EVENTS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                0
            }
            WM_NCHITTEST => HTCLIENT as isize,
            WM_MOUSEACTIVATE => MA_NOACTIVATE as isize,
            _ => unsafe { DefWindowProcW(window, message, wparam, lparam) },
        }
    }
    let target_class: Vec<u16> = "PhoneMouseLaserValidationTarget"
        .encode_utf16()
        .chain(Some(0))
        .collect();
    let mut wc: WNDCLASSW = unsafe { std::mem::zeroed() };
    wc.lpfnWndProc = Some(target_proc);
    wc.hCursor = unsafe { LoadCursorW(null_mut(), IDC_ARROW) };
    wc.lpszClassName = target_class.as_ptr();
    unsafe {
        RegisterClassW(&wc);
    }
    let mut click_position = POINT { x: 0, y: 0 };
    unsafe {
        GetPhysicalCursorPos(&mut click_position);
    }
    let target = unsafe {
        CreateWindowExW(
            WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
            target_class.as_ptr(),
            target_class.as_ptr(),
            WS_POPUP,
            click_position.x - 40,
            click_position.y - 40,
            80,
            80,
            null_mut(),
            null_mut(),
            null_mut(),
            std::ptr::null(),
        )
    };
    assert!(!target.is_null());
    let target_focus = unsafe { GetForegroundWindow() };
    unsafe {
        SetWindowPos(
            target,
            HWND_TOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW,
        );
    }
    // Wait for the target's show/mouse messages before injecting input. Confine
    // this brief test click so physical movement cannot send it to a user app.
    struct RestoreClip(RECT);
    impl Drop for RestoreClip {
        fn drop(&mut self) {
            unsafe {
                ClipCursor(&self.0);
            }
        }
    }
    let mut previous_clip: RECT = unsafe { std::mem::zeroed() };
    unsafe {
        GetClipCursor(&mut previous_clip);
    }
    let restore_clip = RestoreClip(previous_clip);
    let click_clip = RECT {
        left: click_position.x - 1,
        top: click_position.y - 1,
        right: click_position.x + 2,
        bottom: click_position.y + 2,
    };
    unsafe {
        ClipCursor(&click_clip);
        SetPhysicalCursorPos(click_position.x, click_position.y);
    }
    laser.configure(LaserSettings {
        color: 0x30a0ff,
        trail: true,
        fade_ms: 1200,
        ..baseline_settings()
    })?;
    // Make a short path entirely inside the controlled target. Check both the
    // native point and click-through trails, never clicking a system control.
    unsafe {
        SetPhysicalCursorPos(click_position.x - 1, click_position.y);
    }
    std::thread::sleep(Duration::from_millis(40));
    unsafe {
        SetPhysicalCursorPos(click_position.x + 1, click_position.y);
    }
    std::thread::sleep(Duration::from_millis(40));
    unsafe {
        SetPhysicalCursorPos(click_position.x, click_position.y);
    }
    unsafe {
        SetWindowPos(
            target,
            HWND_TOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
        );
    }
    let settle_deadline = Instant::now() + Duration::from_millis(200);
    while Instant::now() < settle_deadline {
        let mut message: MSG = unsafe { std::mem::zeroed() };
        while unsafe { PeekMessageW(&mut message, null_mut(), 0, 0, PM_REMOVE) } != 0 {
            unsafe {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(unsafe { GetForegroundWindow() }, target_focus);
    assert_eq!(unsafe { IsWindowVisible(hwnd) }, 0);
    assert_global_cursor(0x30a0ff, true);
    println!("{{\"native_cursor_and_trail\":\"passed\",\"window_hidden\":true}}");
    // Measure actual native bitmap changes, not just nominal timer ticks.
    // A separate 1 ms waitable timer samples inside this owned 3x3 target.
    use windows_sys::Win32::{
        Foundation::{CloseHandle, HANDLE},
        System::Threading::*,
    };
    struct SamplingTimer(HANDLE);
    impl Drop for SamplingTimer {
        fn drop(&mut self) {
            unsafe {
                CancelWaitableTimer(self.0);
                CloseHandle(self.0);
            }
        }
    }
    let sampler = SamplingTimer(unsafe {
        CreateWaitableTimerExW(
            std::ptr::null(),
            std::ptr::null(),
            CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
            TIMER_ALL_ACCESS,
        )
    });
    assert!(!sampler.0.is_null());
    let due = -10000;
    assert_ne!(
        unsafe { SetWaitableTimerEx(sampler.0, &due, 1, None, null_mut(), std::ptr::null(), 0) },
        0
    );
    let started = Instant::now();
    let mut next_move = started;
    let mut index = 0;
    let mut previous_hash = cursor_hash();
    let mut previous_frame = None;
    let mut intervals = Vec::new();
    while started.elapsed() < Duration::from_secs(2) {
        unsafe {
            WaitForSingleObject(sampler.0, INFINITE);
        }
        let now = Instant::now();
        if now >= next_move {
            unsafe {
                SetPhysicalCursorPos(
                    click_position.x + index % 3 - 1,
                    click_position.y + (index / 3) % 3 - 1,
                );
            }
            index += 1;
            next_move = now + Duration::from_millis(8);
        }
        let mut message: MSG = unsafe { std::mem::zeroed() };
        while unsafe { PeekMessageW(&mut message, null_mut(), 0, 0, PM_REMOVE) } != 0 {
            unsafe {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        let hash = cursor_hash();
        if hash != previous_hash && started.elapsed() > Duration::from_millis(250) {
            if let Some(last) = previous_frame {
                intervals.push(now.duration_since(last).as_secs_f64() * 1000.0);
            }
            previous_frame = Some(now);
        }
        previous_hash = hash;
    }
    intervals.sort_by(f64::total_cmp);
    assert!(
        intervals.len() > 50,
        "native cursor did not refresh during motion"
    );
    println!(
        "{}",
        serde_json::json!({"native_frame_cadence": {
            "samples": intervals.len(), "median_ms": intervals[intervals.len() / 2],
            "p95_ms": intervals[intervals.len() * 95 / 100], "max_ms": intervals[intervals.len() - 1]
        }})
    );
    // Fast loops stay entirely within this owned non-activating target. Read
    // installed cursor pixels to catch secondary hot cores, not just model data.
    let circle_clip = RECT {
        left: click_position.x - 25,
        top: click_position.y - 25,
        right: click_position.x + 26,
        bottom: click_position.y + 26,
    };
    unsafe {
        ClipCursor(&circle_clip);
    }
    let monitor = unsafe { MonitorFromPoint(click_position, MONITOR_DEFAULTTONEAREST) };
    let (mut circle_dpi, mut circle_dpi_y) = (96, 96);
    unsafe {
        GetDpiForMonitor(
            monitor,
            MDT_EFFECTIVE_DPI,
            &mut circle_dpi,
            &mut circle_dpi_y,
        );
    }
    // Remote moves must install their corresponding frame before returning,
    // rather than relying on the next independent 8 ms polling tick. Inspect
    // the very first short path immediately, with no settling sleep.
    unsafe {
        SetPhysicalCursorPos(click_position.x, click_position.y);
    }
    laser.configure(LaserSettings {
        color: 0x30a0ff,
        trail: false,
        ..baseline_settings()
    })?;
    laser.configure(LaserSettings {
        color: 0x30a0ff,
        ..baseline_settings()
    })?;
    struct StagedInput;
    impl phone_mouse_server::input::InputSink for StagedInput {
        fn execute(&self, command: &phone_mouse_server::protocol::Command) -> anyhow::Result<()> {
            // Inspect the actual installed cursor before the OS movement,
            // including the second move when an old trail already exists.
            assert_global_cursor(0x30a0ff, false);
            phone_mouse_server::input::InputSink::execute(
                &phone_mouse_server::input::WindowsInput,
                command,
            )
        }
    }
    let input: std::sync::Arc<dyn phone_mouse_server::input::InputSink> =
        std::sync::Arc::new(StagedInput);
    laser.move_pointer(-20, 0, input.clone())?;
    laser.move_pointer(20, 0, input)?;
    let mut synchronized: ICONINFO = unsafe { std::mem::zeroed() };
    assert_ne!(
        unsafe { GetIconInfo(LoadCursorW(null_mut(), IDC_ARROW), &mut synchronized) },
        0
    );
    let radius = (10.0 * f64::from(circle_dpi.max(96)) / 96.0).ceil() as u32;
    assert_eq!(
        (synchronized.xHotspot, synchronized.yHotspot),
        (radius + 20, radius)
    );
    unsafe {
        DeleteObject(synchronized.hbmColor);
        DeleteObject(synchronized.hbmMask);
    }
    let mut synchronized_position = POINT { x: 0, y: 0 };
    assert_ne!(
        unsafe { GetPhysicalCursorPos(&mut synchronized_position) },
        0
    );
    assert_eq!(
        (synchronized_position.x, synchronized_position.y),
        (click_position.x, click_position.y)
    );
    println!("{{\"remote_move_frame_synchronized\":\"passed\",\"settling_sleep_ms\":0}}");
    println!(
        "{{\"remote_move_stages_visible_spot\":\"passed\",\"old_trail_removed_before_os_move\":true}}"
    );
    let frozen_clip = RECT {
        left: click_position.x,
        top: click_position.y,
        right: click_position.x + 1,
        bottom: click_position.y + 1,
    };
    assert_ne!(unsafe { ClipCursor(&frozen_clip) }, 0);
    laser.move_pointer(20, 0, std::sync::Arc::new(StagedInput))?;
    let mut clipped_cursor: CURSORINFO = unsafe { std::mem::zeroed() };
    clipped_cursor.cbSize = std::mem::size_of::<CURSORINFO>() as u32;
    assert_ne!(unsafe { GetCursorInfo(&mut clipped_cursor) }, 0);
    let point_side = (10.0 * f64::from(circle_dpi) / 96.0).ceil() as usize * 2 + 1;
    assert!(assert_single_hot_spot(clipped_cursor.hCursor, circle_dpi) as usize > point_side);
    assert_ne!(unsafe { ClipCursor(&circle_clip) }, 0);
    println!("{{\"clipped_remote_move_restores_trail\":\"passed\"}}");
    let mut circle_frames = 0;
    let mut extent = Vec::new();
    for trail_length_px in [20, 300] {
        laser.configure(LaserSettings {
            color: 0x30a0ff,
            trail_length_px,
            ..baseline_settings()
        })?;
        let started = Instant::now();
        let mut maximum_extent = 0;
        while started.elapsed() < Duration::from_millis(500) {
            unsafe {
                WaitForSingleObject(sampler.0, INFINITE);
            }
            let angle = started.elapsed().as_secs_f64() * std::f64::consts::TAU * 8.0;
            unsafe {
                SetPhysicalCursorPos(
                    click_position.x + (angle.cos() * 24.0) as i32,
                    click_position.y + (angle.sin() * 24.0) as i32,
                );
            }
            let mut message: MSG = unsafe { std::mem::zeroed() };
            while unsafe { PeekMessageW(&mut message, null_mut(), 0, 0, PM_REMOVE) } != 0 {
                unsafe {
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            }
            let mut cursor: CURSORINFO = unsafe { std::mem::zeroed() };
            cursor.cbSize = std::mem::size_of::<CURSORINFO>() as u32;
            assert_ne!(unsafe { GetCursorInfo(&mut cursor) }, 0);
            maximum_extent = maximum_extent.max(assert_single_hot_spot(cursor.hCursor, circle_dpi));
            circle_frames += 1;
        }
        extent.push(maximum_extent);
    }
    assert!(
        extent[1] > extent[0],
        "length setting did not change the native trail"
    );
    println!(
        "{}",
        serde_json::json!({"fast_circle": {
            "native_samples": circle_frames, "hot_spots_per_frame": 1,
            "short_extent_px": extent[0], "long_extent_px": extent[1], "turns_per_second": 8
        }})
    );
    unsafe {
        ClipCursor(&click_clip);
    }
    laser.configure(LaserSettings {
        color: 0x30a0ff,
        fade_ms: 1200,
        ..baseline_settings()
    })?;
    drop(sampler);
    unsafe {
        SetPhysicalCursorPos(click_position.x, click_position.y);
    }
    let mut previous_role = IDC_ARROW;
    for next_role in [IDC_IBEAM, IDC_ARROW] {
        unsafe {
            SetClassLongPtrW(
                target,
                GCLP_HCURSOR,
                LoadCursorW(null_mut(), next_role) as isize,
            );
            SetPhysicalCursorPos(click_position.x + 1, click_position.y);
        }
        let deadline = Instant::now() + Duration::from_millis(120);
        while Instant::now() < deadline {
            let mut message: MSG = unsafe { std::mem::zeroed() };
            while unsafe { PeekMessageW(&mut message, null_mut(), 0, 0, PM_REMOVE) } != 0 {
                unsafe {
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_global_cursor(0x30a0ff, true);
        let monitor = unsafe { MonitorFromPoint(click_position, MONITOR_DEFAULTTONEAREST) };
        let (mut dpi, mut dpi_y) = (96, 96);
        unsafe {
            GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dpi, &mut dpi_y);
        }
        assert_cursor_image(
            unsafe { LoadCursorW(null_mut(), previous_role) },
            0x30a0ff,
            dpi,
            100,
            false,
        );
        previous_role = next_role;
        unsafe {
            SetPhysicalCursorPos(click_position.x, click_position.y);
        }
    }
    println!("{{\"cursor_role_switch\":\"passed\"}}");
    let hit = unsafe { WindowFromPoint(click_position) };
    assert_eq!(
        hit, target,
        "The controlled click must hit only the validation window"
    );
    let click_focus = unsafe { GetForegroundWindow() };
    use phone_mouse_server::input::{InputSink, WindowsInput};
    WindowsInput.execute(&phone_mouse_server::protocol::Command::Click {
        button: phone_mouse_server::protocol::Button::Left,
    })?;
    let click_deadline = Instant::now() + Duration::from_millis(500);
    while Instant::now() < click_deadline
        && CLICK_EVENTS.load(std::sync::atomic::Ordering::Relaxed) < 2
    {
        let mut message: MSG = unsafe { std::mem::zeroed() };
        while unsafe { PeekMessageW(&mut message, null_mut(), 0, 0, PM_REMOVE) } != 0 {
            unsafe {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    let clicks = CLICK_EVENTS.load(std::sync::atomic::Ordering::Relaxed);
    drop(restore_clip);
    unsafe {
        DestroyWindow(target);
        UnregisterClassW(target_class.as_ptr(), null_mut());
    }
    assert_eq!(clicks, 2);
    assert_eq!(unsafe { GetForegroundWindow() }, click_focus);
    println!("{{\"click_through\":\"passed\",\"focus_preserved\":true}}");
    let _ = laser.hide();
    assert_eq!(unsafe { IsWindowVisible(hwnd) }, 0);
    assert_eq!(cursor_hash(), before);
    phase("hidden_final");
    std::thread::sleep(Duration::from_secs(if quick { 0 } else { 6 }));
    laser.shutdown();
    assert!(unsafe { FindWindowW(class.as_ptr(), std::ptr::null()) }.is_null());
    println!("{{\"result\":\"passed\"}}");
    Ok(())
}
