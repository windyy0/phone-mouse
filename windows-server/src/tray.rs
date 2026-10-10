use std::sync::Arc;

use anyhow::{Context, Result};
use phone_mouse_server::presentation::PresentationController;
use tao::{
    event::Event,
    event_loop::{ControlFlow, EventLoopBuilder},
    platform::windows::EventLoopBuilderExtWindows,
};
use tokio::sync::Notify;
use tray_icon::{
    Icon, TrayIconBuilder,
    menu::{Menu, MenuEvent, MenuItem},
};
use windows_sys::Win32::UI::{
    HiDpi::{GetDpiForSystem, GetSystemMetricsForDpi},
    WindowsAndMessaging::{SM_CXSMICON, SM_CYSMICON},
};

// Tray-specific artwork uses minimal padding for legibility at small sizes.
const TRAY_ICONS: &[(u32, &[u8])] = &[
    (
        16,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/tray/phone-mouse-tray-16.png"
        )),
    ),
    (
        20,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/tray/phone-mouse-tray-20.png"
        )),
    ),
    (
        24,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/tray/phone-mouse-tray-24.png"
        )),
    ),
    (
        32,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/tray/phone-mouse-tray-32.png"
        )),
    ),
    (
        40,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/tray/phone-mouse-tray-40.png"
        )),
    ),
    (
        48,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/tray/phone-mouse-tray-48.png"
        )),
    ),
    (
        64,
        include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/tray/phone-mouse-tray-64.png"
        )),
    ),
];

enum TrayEvent {
    Menu(MenuEvent),
}

pub fn spawn(port: u16, presentation: Arc<PresentationController>) -> Arc<Notify> {
    let shutdown = Arc::new(Notify::new());
    let thread_shutdown = shutdown.clone();
    std::thread::Builder::new()
        .name("phone-mouse-tray".to_string())
        .spawn(move || {
            if let Err(error) = run(thread_shutdown, port, presentation) {
                eprintln!("托盘图标启动失败：{error:#}");
            }
        })
        .expect("failed to start the tray thread");
    shutdown
}

fn run(shutdown: Arc<Notify>, port: u16, presentation: Arc<PresentationController>) -> Result<()> {
    let mut event_loop_builder = EventLoopBuilder::<TrayEvent>::with_user_event();
    event_loop_builder.with_any_thread(true);
    let event_loop = event_loop_builder.build();
    let proxy = event_loop.create_proxy();
    MenuEvent::set_event_handler(Some(move |event| {
        let _ = proxy.send_event(TrayEvent::Menu(event));
    }));

    let menu = Menu::new();
    let exit_presentation = MenuItem::new("退出演示模式", true, None);
    let exit_presentation_id = exit_presentation.id().clone();
    menu.append(&exit_presentation)
        .context("创建演示模式退出入口失败")?;
    let check_update = MenuItem::new("检查更新", true, None);
    let check_update_id = check_update.id().clone();
    menu.append(&check_update).context("创建更新检查入口失败")?;
    let allow_lan = MenuItem::new("允许手机连接", true, None);
    let allow_lan_id = allow_lan.id().clone();
    menu.append(&allow_lan).context("创建联网授权入口失败")?;
    let quit = MenuItem::new("退出手机键鼠", true, None);
    let quit_id = quit.id().clone();
    menu.append(&quit).context("创建托盘菜单失败")?;

    // Tao has initialized DPI awareness before we query the system's small icon
    // metrics. Exact-size PNGs avoid reducing the full desktop image in Explorer.
    let dpi = unsafe { GetDpiForSystem() }.max(96);
    let width = unsafe { GetSystemMetricsForDpi(SM_CXSMICON, dpi) }.max(16) as u32;
    let height = unsafe { GetSystemMetricsForDpi(SM_CYSMICON, dpi) }.max(16) as u32;
    let &(source_size, bytes) = TRAY_ICONS
        .iter()
        .find(|(size, _)| *size >= width.max(height))
        .unwrap_or_else(|| TRAY_ICONS.last().expect("tray icon resources are present"));
    let image = image::load_from_memory(bytes).context("读取托盘图标失败")?;
    let image = if width == source_size && height == source_size {
        image.to_rgba8()
    } else {
        image
            .resize_exact(width, height, image::imageops::FilterType::Lanczos3)
            .to_rgba8()
    };
    let (width, height) = image.dimensions();
    let icon = Icon::from_rgba(image.into_raw(), width, height).context("转换托盘图标失败")?;
    let _tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("手机键鼠")
        .with_icon(icon)
        .build()
        .context("创建托盘图标失败")?;
    println!("托盘图标已创建（{width}×{height}px，系统 DPI {dpi}）。");

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        if let Event::UserEvent(TrayEvent::Menu(event)) = event {
            if event.id == exit_presentation_id {
                presentation.force_exit("tray");
            } else if event.id == check_update_id {
                crate::update::request_manual_check();
            } else if event.id == allow_lan_id {
                std::thread::spawn(move || crate::firewall::request(port, false));
            } else if event.id == quit_id {
                shutdown.notify_one();
                *control_flow = ControlFlow::Exit;
            }
        }
    })
}
