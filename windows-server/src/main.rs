#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

use anyhow::{Context, Result, bail};
use axum::serve::ListenerExt;
use clap::Parser;
use phone_mouse_server::{
    AppState,
    input::{DryRun, InputSink, WindowsInput},
    router, run_motion_udp,
};
use std::{
    net::{IpAddr, Ipv4Addr},
    sync::Arc,
};
use tokio::net::UdpSocket;

#[cfg(windows)]
mod firewall;
#[cfg(windows)]
mod tray;
#[cfg(windows)]
mod update;

#[derive(Parser)]
#[command(version, about = "手机键鼠 · Rust Windows 接收端")]
struct Options {
    #[arg(long, default_value = "0.0.0.0")]
    host: IpAddr,
    #[arg(long, default_value_t = 8765)]
    port: u16,
    /// 只验证协议，不向 Windows 注入输入，供联调检查使用。
    #[arg(long)]
    dry_run: bool,
    /// Used only by the receiver's one-time, elevated network setup helper.
    #[arg(long, hide = true, conflicts_with = "check_lan")]
    allow_lan: bool,
    /// Read-only diagnostics; never requests elevation or changes rules.
    #[arg(long, hide = true)]
    check_lan: bool,
}

#[tokio::main(worker_threads = 2)]
async fn main() -> Result<()> {
    let options = Options::parse();
    #[cfg(windows)]
    {
        if options.allow_lan {
            firewall::allow(options.port)?;
            return Ok(());
        }
        if options.check_lan {
            println!(
                "{}",
                serde_json::to_string(&firewall::inspect(options.port)?)?
            );
            return Ok(());
        }
    }
    if !cfg!(windows) && !options.dry_run {
        bail!("接收端需要 Windows。");
    }
    let input: Arc<dyn InputSink> = if options.dry_run {
        Arc::new(DryRun)
    } else {
        Arc::new(WindowsInput)
    };
    #[cfg(windows)]
    let tray_shutdown = tray::spawn(options.port);
    #[cfg(windows)]
    if !options.dry_run {
        update::spawn();
    }
    #[cfg(windows)]
    if !options.dry_run
        && !matches!(
            firewall::inspect(options.port),
            Ok(firewall::LanStatus::Ready)
        )
    {
        std::thread::spawn(move || firewall::request(options.port, true));
        println!("等待联网授权，可从托盘菜单「允许手机连接」重试。");
        // Do not start listeners after a declined grant, which would otherwise
        // trigger a second Windows firewall prompt. The tray remains available.
        tokio::select! {
            _ = firewall::wait_for_permission(options.port) => {},
            _ = tray_shutdown.notified() => return Ok(()),
            _ = tokio::signal::ctrl_c() => return Ok(()),
        }
    }
    let udp_socket = UdpSocket::bind((options.host, options.port))
        .await
        .context("监听移动数据失败，端口可能已被占用")?;
    let listener = tokio::net::TcpListener::bind((options.host, options.port))
        .await
        .context("监听失败，端口可能已被占用")?;
    let port = listener.local_addr()?.port();
    println!("\n手机键鼠 · Rust 接收端 v{}", env!("CARGO_PKG_VERSION"));
    if options.host == IpAddr::V4(Ipv4Addr::UNSPECIFIED) {
        if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
            for (name, ip) in interfaces
                .into_iter()
                .filter(|(_, ip)| ip.is_ipv4() && !ip.is_loopback())
            {
                println!("  电脑地址：{ip}:{port}  ({name})");
            }
        }
    } else {
        println!("  电脑地址：{}:{port}", options.host);
    }
    println!(
        "  自动发现：已开启（UDP {}）\n  保持程序运行；按 Ctrl+C 停止。\n",
        phone_mouse_server::discovery::DISCOVERY_PORT
    );
    if options.dry_run {
        println!("  当前是联调模式，鼠标和键盘操作不会实际执行。\n");
    }
    let listener = listener.tap_io(|socket| {
        let _ = socket.set_nodelay(true);
    });
    let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows 电脑".to_string());
    let state = AppState::new(input, udp_socket.local_addr()?.port());
    let udp_state = state.clone();
    tokio::spawn(async move {
        if let Err(error) = phone_mouse_server::discovery::run(port, name).await {
            eprintln!("自动发现服务未启动：{error}");
        }
    });
    tokio::spawn(run_motion_udp(udp_socket, udp_state));
    axum::serve(
        listener,
        router(state).into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(async move {
        #[cfg(windows)]
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = tray_shutdown.notified() => {}
        }
        #[cfg(not(windows))]
        {
            let _ = tokio::signal::ctrl_c().await;
        }
    })
    .await?;
    Ok(())
}
