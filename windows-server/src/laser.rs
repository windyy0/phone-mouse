//! Native laser cursor and fading trail, driven by a hidden Win32 message loop.
use crate::laser_cursor::CursorGuard;
pub use crate::laser_cursor::{restore_cursors, run_cursor_guardian};
use crate::{
    input::InputSink,
    laser_trail::{Position, Trail},
    presentation::LaserSurface,
    protocol::{Command, LaserSettings},
};
use anyhow::{Context, Result, bail};
use std::{
    ptr::{null, null_mut},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Sender, SyncSender},
    },
    thread::JoinHandle,
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{
        CloseHandle, HANDLE, HWND, LPARAM, LRESULT, POINT, WAIT_FAILED, WAIT_OBJECT_0, WPARAM,
    },
    Graphics::Gdi::*,
    System::{LibraryLoader::GetModuleHandleW, Threading::*},
    UI::{HiDpi::*, WindowsAndMessaging::*},
};

const COMMAND_MESSAGE: u32 = WM_APP + 1;
const CLASS_NAME: &str = "PhoneMouseNativeLaser";
const FRAME_INTERVAL: Duration = Duration::from_millis(8);

struct FrameClock {
    handle: HANDLE,
    next: Option<Instant>,
}
impl FrameClock {
    fn new() -> Result<Self> {
        // WM_TIMER is a low-priority, coalesced message: its nominal 16 ms
        // interval can become alternating 16/31 ms ticks. Wait on a private
        // high-resolution timer together with the message queue instead.
        let mut handle = unsafe {
            CreateWaitableTimerExW(
                null(),
                null(),
                CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
                TIMER_ALL_ACCESS,
            )
        };
        if handle.is_null() {
            // Older Windows versions retain a regular waitable-timer path.
            handle = unsafe { CreateWaitableTimerExW(null(), null(), 0, TIMER_ALL_ACCESS) };
        }
        if handle.is_null() {
            bail!(
                "创建激光刷新定时器失败：{}",
                std::io::Error::last_os_error()
            );
        }
        Ok(Self { handle, next: None })
    }
    fn start(&mut self) -> Result<()> {
        self.next = Some(Instant::now() + FRAME_INTERVAL);
        self.arm()
    }
    fn arm(&self) -> Result<()> {
        if let Some(next) = self.next {
            let delay = next.saturating_duration_since(Instant::now());
            let due = -((delay.as_nanos() / 100).max(1) as i64);
            if unsafe { SetWaitableTimerEx(self.handle, &due, 0, None, null(), null(), 0) } == 0 {
                bail!(
                    "激光刷新定时器启动失败：{}",
                    std::io::Error::last_os_error()
                );
            }
        }
        Ok(())
    }
    fn advance(&mut self) -> Result<()> {
        if let Some(next) = self.next {
            let now = Instant::now();
            let deadline = next + FRAME_INTERVAL;
            // Never enqueue catch-up frames after a busy/suspended desktop.
            self.next = Some(if deadline > now {
                deadline
            } else {
                now + FRAME_INTERVAL
            });
            self.arm()?;
        }
        Ok(())
    }
    fn stop(&mut self) {
        self.next = None;
        unsafe {
            CancelWaitableTimer(self.handle);
        }
    }
}
impl Drop for FrameClock {
    fn drop(&mut self) {
        self.stop();
        unsafe {
            CloseHandle(self.handle);
        }
    }
}
struct Window(HWND);
impl Drop for Window {
    fn drop(&mut self) {
        unsafe {
            DestroyWindow(self.0);
        }
    }
}

unsafe extern "system" fn window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_NCHITTEST => HTTRANSPARENT as LRESULT,
        WM_MOUSEACTIVATE => MA_NOACTIVATE as LRESULT,
        WM_ERASEBKGND => 1,
        _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
    }
}

enum Action {
    Show(LaserSettings),
    Configure(LaserSettings),
    Move {
        dx: i32,
        dy: i32,
        input: Arc<dyn InputSink>,
    },
    Hide,
    Stop,
}
struct Request {
    action: Action,
    deadline: Instant,
    result: SyncSender<std::result::Result<(), String>>,
}
pub struct NativeLaser {
    hwnd: usize,
    commands: Sender<Request>,
    thread: Mutex<Option<JoinHandle<()>>>,
    fault: Arc<AtomicBool>,
}
impl NativeLaser {
    pub fn spawn() -> Result<Arc<Self>> {
        // Establish process awareness before Tao or any message window exists.
        unsafe {
            SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        }
        let (commands, receiver) = mpsc::channel::<Request>();
        let (ready, startup) = mpsc::sync_channel(1);
        let fault = Arc::new(AtomicBool::new(false));
        let thread_fault = fault.clone();
        let thread = std::thread::Builder::new()
            .name("phone-mouse-laser".into())
            .spawn(move || {
                let result = run(receiver, &ready, &thread_fault);
                if let Err(error) = result {
                    thread_fault.store(true, Ordering::Release);
                    let _ = ready.send(Err(format!("{error:#}")));
                    eprintln!("激光线程异常：{error:#}");
                }
            })?;
        let hwnd = startup
            .recv_timeout(Duration::from_secs(3))
            .context("激光线程启动超时")?
            .map_err(anyhow::Error::msg)?;
        Ok(Arc::new(Self {
            hwnd,
            commands,
            thread: Mutex::new(Some(thread)),
            fault,
        }))
    }
    fn request(&self, action: Action) -> Result<()> {
        let (result, receiver) = mpsc::sync_channel(1);
        self.commands
            .send(Request {
                action,
                deadline: Instant::now() + Duration::from_secs(3),
                result,
            })
            .context("激光线程已退出")?;
        if unsafe { PostMessageW(self.hwnd as HWND, COMMAND_MESSAGE, 0, 0) } == 0 {
            bail!("激光消息窗口不可用");
        }
        receiver
            .recv_timeout(Duration::from_secs(4))
            .context("激光线程响应超时")?
            .map_err(anyhow::Error::msg)
    }
}
impl LaserSurface for NativeLaser {
    fn show(&self, settings: LaserSettings) -> Result<()> {
        self.request(Action::Show(settings))
    }
    fn configure(&self, settings: LaserSettings) -> Result<()> {
        self.request(Action::Configure(settings))
    }
    fn move_pointer(&self, dx: i32, dy: i32, input: Arc<dyn InputSink>) -> Result<()> {
        self.request(Action::Move { dx, dy, input })
    }
    fn hide(&self) -> Result<()> {
        match self.request(Action::Hide) {
            Ok(()) => Ok(()),
            Err(error) => {
                self.fault.store(true, Ordering::Release);
                Err(error)
            }
        }
    }
    fn shutdown(&self) {
        let mut thread = self.thread.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(handle) = thread.take() {
            let _ = self.request(Action::Stop);
            let _ = handle.join();
        }
    }
    fn healthy(&self) -> bool {
        !self.fault.load(Ordering::Acquire)
            && self
                .thread
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
                .is_some_and(|handle| !handle.is_finished())
    }
}
impl Drop for NativeLaser {
    fn drop(&mut self) {
        self.shutdown();
    }
}

struct Renderer {
    clock: FrameClock,
    trail: Trail,
    guard: Option<CursorGuard>,
    settings: LaserSettings,
}
fn sample_pointer() -> Result<(Position, u32)> {
    let mut point = POINT { x: 0, y: 0 };
    if unsafe { GetPhysicalCursorPos(&mut point) } == 0 {
        bail!("读取系统鼠标位置失败");
    }
    let monitor = unsafe { MonitorFromPoint(point, MONITOR_DEFAULTTONEAREST) };
    let (mut dpi_x, mut dpi_y) = (96, 96);
    unsafe {
        GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &mut dpi_x, &mut dpi_y);
    }
    Ok((
        Position {
            x: point.x,
            y: point.y,
        },
        dpi_x.max(96),
    ))
}
impl Renderer {
    fn hide(&mut self) {
        self.clock.stop();
        self.guard.take();
        self.trail.clear();
    }
    fn render(&mut self) -> Result<()> {
        self.render_frame(false)
    }
    fn render_frame(&mut self, force: bool) -> Result<()> {
        if let Some(guard) = self.guard.as_mut() {
            if !guard.healthy()? {
                bail!("鼠标恢复守护已退出");
            }
        }
        let (position, dpi_x) = sample_pointer()?;
        if let Some(guard) = self.guard.as_mut() {
            guard.configure(self.settings, dpi_x)?;
        }
        let now = Instant::now();
        let redraw = self.trail.update(position, dpi_x, now);
        if let Some(guard) = self.guard.as_mut() {
            let role = guard.current_role()?;
            if force || redraw || guard.role_changed(role) {
                guard.paint(role, &self.trail.frame(now), position)?;
            }
        }
        Ok(())
    }
    fn show(&mut self, settings: LaserSettings) -> Result<()> {
        self.settings = settings;
        self.trail.configure(settings);
        if self.guard.is_none() {
            let (_, dpi) = sample_pointer()?;
            self.guard = Some(CursorGuard::start(settings, dpi)?);
        }
        self.render()?;
        self.clock.start()?;
        Ok(())
    }
    fn move_pointer(&mut self, dx: i32, dy: i32, input: &dyn InputSink) -> Result<()> {
        if self.guard.is_none() {
            bail!("激光已停止");
        }
        let staged = (dx != 0 || dy != 0) && self.guard.as_mut().unwrap().prepare_move()?;
        // Keep a visible spot during the OS position change. Installing the
        // corresponding trail immediately afterwards cannot by itself prevent
        // the compositor from observing the position change with an old image.
        input.execute(&Command::MovePixels { dx, dy })?;
        // Restore a staged trail even when Windows clips the move at an edge
        // and Trail::update therefore observes no position change.
        self.render_frame(staged)?;
        // Remote moves drive their own frames; the timer resumes only after
        // motion pauses, for fading, physical mouse moves and role changes.
        self.clock.start()
    }
}
impl Drop for Renderer {
    fn drop(&mut self) {
        self.hide();
    }
}

fn run(
    commands: mpsc::Receiver<Request>,
    ready: &SyncSender<std::result::Result<usize, String>>,
    fault: &AtomicBool,
) -> Result<()> {
    unsafe {
        SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    let class: Vec<u16> = CLASS_NAME.encode_utf16().chain(Some(0)).collect();
    let instance = unsafe { GetModuleHandleW(null()) };
    let mut wc: WNDCLASSW = unsafe { std::mem::zeroed() };
    wc.lpfnWndProc = Some(window_proc);
    wc.hInstance = instance;
    wc.lpszClassName = class.as_ptr();
    if unsafe { RegisterClassW(&wc) } == 0 {
        bail!("注册激光窗口失败");
    }
    let hwnd = unsafe {
        CreateWindowExW(
            WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW,
            class.as_ptr(),
            class.as_ptr(),
            WS_POPUP,
            0,
            0,
            1,
            1,
            null_mut(),
            null_mut(),
            instance,
            null(),
        )
    };
    if hwnd.is_null() {
        bail!("创建激光窗口失败");
    }
    let window = Window(hwnd);
    let mut renderer = Renderer {
        clock: FrameClock::new()?,
        trail: Trail::new(LaserSettings::default()),
        guard: None,
        settings: LaserSettings::default(),
    };
    ready
        .send(Ok(hwnd as usize))
        .map_err(|_| anyhow::anyhow!("激光启动已取消"))?;
    let mut message: MSG = unsafe { std::mem::zeroed() };
    loop {
        let active = renderer.guard.is_some();
        let status = unsafe {
            MsgWaitForMultipleObjectsEx(
                u32::from(active),
                &renderer.clock.handle,
                INFINITE,
                QS_ALLINPUT,
                MWMO_INPUTAVAILABLE,
            )
        };
        if status == WAIT_FAILED {
            bail!("等待激光刷新失败：{}", std::io::Error::last_os_error());
        }
        if active && status == WAIT_OBJECT_0 {
            if let Err(error) = renderer.render().and_then(|_| renderer.clock.advance()) {
                eprintln!("激光绘制失败：{error:#}");
                renderer.hide();
                fault.store(true, Ordering::Release);
            }
        }
        // Drain commands even when the timer and message queue are both ready.
        // Hiding cancels the timer and the thread then blocks only on messages.
        while unsafe { PeekMessageW(&mut message, null_mut(), 0, 0, PM_REMOVE) } != 0 {
            if message.message == WM_QUIT {
                return Ok(());
            }
            match message.message {
                COMMAND_MESSAGE => {
                    while let Ok(request) = commands.try_recv() {
                        if Instant::now() > request.deadline {
                            let _ = request.result.send(Err("激光指令已过期".into()));
                            continue;
                        }
                        let stop = matches!(request.action, Action::Stop);
                        let result = match request.action {
                            Action::Show(settings) => renderer.show(settings),
                            Action::Configure(settings) => {
                                renderer.settings = settings;
                                renderer.trail.configure(settings);
                                renderer.render()
                            }
                            Action::Move { dx, dy, input } => {
                                renderer.move_pointer(dx, dy, input.as_ref())
                            }
                            Action::Hide | Action::Stop => {
                                renderer.hide();
                                Ok(())
                            }
                        };
                        if result.is_err() {
                            renderer.hide();
                            fault.store(true, Ordering::Release);
                        } else if !stop {
                            fault.store(false, Ordering::Release);
                        }
                        let _ = request
                            .result
                            .send(result.map_err(|error| format!("{error:#}")));
                        if stop {
                            drop(renderer);
                            drop(window);
                            unsafe {
                                UnregisterClassW(class.as_ptr(), instance);
                            }
                            return Ok(());
                        }
                    }
                }
                WM_DISPLAYCHANGE | WM_DPICHANGED => {
                    renderer.trail.clear();
                }
                WM_SETTINGCHANGE => {
                    if let Some(guard) = renderer.guard.as_mut() {
                        guard.invalidate();
                    }
                }
                _ => unsafe {
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                },
            }
        }
    }
}
