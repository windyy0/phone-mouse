pub mod discovery;
pub mod input;
pub mod protocol;

use axum::{
    Router,
    extract::{
        ConnectInfo, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use input::InputSink;
use protocol::{Command, PROTOCOL, Reply};
use std::{
    collections::{HashMap, VecDeque},
    net::{IpAddr, SocketAddr},
    sync::atomic::{AtomicU64, Ordering},
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use subtle::ConstantTimeEq;
use tokio::{
    net::UdpSocket,
    sync::Semaphore,
    time::{MissedTickBehavior, interval, timeout},
};

const UDP_MAGIC: &[u8; 4] = b"PM01";
const UDP_PACKET_LEN: usize = 40;
static TOKEN_COUNTER: AtomicU64 = AtomicU64::new(1);

struct UdpSession {
    token: [u8; 16],
    last_seq: u64,
    window: Instant,
    count: u32,
}

pub struct AppState {
    input: Arc<dyn InputSink>,
    failures: Mutex<HashMap<IpAddr, VecDeque<Instant>>>,
    udp_sessions: Mutex<HashMap<IpAddr, UdpSession>>,
    udp_port: u16,
    slots: Arc<Semaphore>,
}

impl AppState {
    pub fn new(input: Arc<dyn InputSink>, udp_port: u16) -> Arc<Self> {
        Arc::new(Self {
            input,
            failures: Mutex::new(HashMap::new()),
            udp_sessions: Mutex::new(HashMap::new()),
            udp_port,
            slots: Arc::new(Semaphore::new(16)),
        })
    }

    fn blocked(&self, peer: IpAddr) -> bool {
        let mut peers = self.failures.lock().unwrap_or_else(|e| e.into_inner());
        peers.retain(|_, attempts| {
            while attempts
                .front()
                .is_some_and(|time| time.elapsed() >= Duration::from_secs(60))
            {
                attempts.pop_front();
            }
            !attempts.is_empty()
        });
        peers
            .get(&peer)
            .is_some_and(|attempts| attempts.len() >= 10)
    }

    fn failed(&self, peer: IpAddr) {
        let mut peers = self.failures.lock().unwrap_or_else(|e| e.into_inner());
        if peers.len() < 4096 || peers.contains_key(&peer) {
            peers.entry(peer).or_default().push_back(Instant::now());
        }
    }

    fn register_udp(&self, peer: IpAddr) -> [u8; 16] {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let counter = TOKEN_COUNTER.fetch_add(1, Ordering::Relaxed);
        let mut token = [0_u8; 16];
        token[..8].copy_from_slice(&(now as u64).to_be_bytes());
        token[8..]
            .copy_from_slice(&(counter ^ ((peer_hash(peer) as u64).rotate_left(17))).to_be_bytes());
        self.udp_sessions
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(
                peer,
                UdpSession {
                    token,
                    last_seq: 0,
                    window: Instant::now(),
                    count: 0,
                },
            );
        token
    }

    fn accept_udp(&self, peer: IpAddr, token: &[u8], sequence: u64) -> bool {
        let mut sessions = self.udp_sessions.lock().unwrap_or_else(|e| e.into_inner());
        let Some(session) = sessions.get_mut(&peer) else {
            return false;
        };
        if token.len() != session.token.len() || !bool::from(session.token.as_slice().ct_eq(token))
        {
            return false;
        }
        if sequence <= session.last_seq {
            return false;
        }
        if session.window.elapsed() >= Duration::from_secs(1) {
            session.window = Instant::now();
            session.count = 0;
        }
        if session.count >= 300 {
            return false;
        }
        session.last_seq = sequence;
        session.count += 1;
        true
    }

    fn clear_udp(&self, peer: IpAddr, token: &[u8; 16]) {
        let mut sessions = self.udp_sessions.lock().unwrap_or_else(|e| e.into_inner());
        if sessions
            .get(&peer)
            .is_some_and(|session| session.token == *token)
        {
            sessions.remove(&peer);
        }
    }
}

fn peer_hash(peer: IpAddr) -> u32 {
    match peer {
        IpAddr::V4(value) => u32::from_be_bytes(value.octets()),
        IpAddr::V6(value) => value.segments().iter().fold(0_u32, |hash, segment| {
            hash.rotate_left(5) ^ u32::from(*segment)
        }),
    }
}

fn encode_token(token: &[u8; 16]) -> String {
    token.iter().map(|byte| format!("{byte:02x}")).collect()
}

pub fn router(state: Arc<AppState>) -> Router {
    Router::new().route("/", get(|| async { "手机键鼠 Rust 接收端正在运行。请使用安卓 App 连接。" }))
        .route("/health", get(|| async { axum::Json(serde_json::json!({"status":"ok","protocol":PROTOCOL,"version":env!("CARGO_PKG_VERSION")})) }))
        .route("/ws", get(upgrade)).with_state(state)
}

/// Receives best-effort relative motion. UDP is intentionally used only for
/// motion and scrolling: an old packet can be dropped instead of blocking all
/// newer input behind a TCP retransmission. Clicks, keys, text, and handshake stay
/// on the ordered WebSocket channel.
pub async fn run_motion_udp(socket: UdpSocket, state: Arc<AppState>) {
    let mut buffer = [0_u8; 128];
    loop {
        let Ok((length, peer)) = socket.recv_from(&mut buffer).await else {
            break;
        };
        if length != UDP_PACKET_LEN || &buffer[..4] != UDP_MAGIC {
            continue;
        }
        let sequence = u64::from_be_bytes(buffer[20..28].try_into().unwrap());
        if !state.accept_udp(peer.ip(), &buffer[4..20], sequence) {
            continue;
        }
        let dx = i32::from_be_bytes(buffer[28..32].try_into().unwrap());
        let dy = i32::from_be_bytes(buffer[32..36].try_into().unwrap());
        let wheel = i32::from_be_bytes(buffer[36..40].try_into().unwrap());
        if dx != 0 || dy != 0 {
            let command = Command::MovePixels { dx, dy };
            if command.validate().is_ok() {
                let _ = state.input.execute(&command);
            }
        }
        if wheel != 0 {
            let command = Command::Scroll { delta: wheel };
            if command.validate().is_ok() {
                let _ = state.input.execute(&command);
            }
        }
    }
}

async fn upgrade(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    ws: WebSocketUpgrade,
) -> Response {
    // Native clients have no browser Origin. Browsers must stay on this host.
    if let Some(origin) = headers.get("origin") {
        let host = headers
            .get("host")
            .and_then(|h| h.to_str().ok())
            .unwrap_or("");
        if origin.to_str().ok() != Some(format!("http://{host}").as_str()) {
            return (StatusCode::FORBIDDEN, "禁止跨站连接。").into_response();
        }
    }
    if state.blocked(peer.ip()) {
        return (StatusCode::TOO_MANY_REQUESTS, "请一分钟后重试。").into_response();
    }
    let Ok(permit) = state.slots.clone().try_acquire_owned() else {
        return (StatusCode::SERVICE_UNAVAILABLE, "连接过多，请稍后重试。").into_response();
    };
    ws.max_message_size(16384)
        .max_frame_size(16384)
        .on_upgrade(move |socket| async move {
            let _permit = permit;
            session(socket, state, peer.ip()).await;
        })
}

async fn reply(ws: &mut WebSocket, value: Reply) -> bool {
    ws.send(Message::Text(
        serde_json::to_string(&value).expect("valid reply").into(),
    ))
    .await
    .is_ok()
}

async fn error(
    ws: &mut WebSocket,
    code: &'static str,
    message: &'static str,
    id: Option<u64>,
) -> bool {
    reply(ws, Reply::Error { code, message, id }).await
}

async fn session(mut ws: WebSocket, state: Arc<AppState>, peer: IpAddr) {
    let first = timeout(Duration::from_secs(5), ws.recv()).await;
    let parsed = match first {
        Ok(Some(Ok(Message::Text(value)))) => serde_json::from_str::<Command>(&value).ok(),
        _ => None,
    };
    if !matches!(parsed, Some(Command::Hello { protocol: PROTOCOL })) {
        state.failed(peer);
        error(
            &mut ws,
            "protocol",
            "握手失败，请使用协议版本匹配的 App 和接收端。",
            None,
        )
        .await;
        let _ = ws.send(Message::Close(None)).await;
        return;
    }
    let udp_token = state.register_udp(peer);
    if !reply(
        &mut ws,
        Reply::Ready {
            protocol: PROTOCOL,
            version: env!("CARGO_PKG_VERSION"),
            udp_port: state.udp_port,
            udp_token: encode_token(&udp_token),
        },
    )
    .await
    {
        state.clear_udp(peer, &udp_token);
        return;
    }

    let mut held_buttons = Vec::new();
    let mut heartbeat = interval(Duration::from_secs(15));
    heartbeat.set_missed_tick_behavior(MissedTickBehavior::Skip);
    heartbeat.tick().await;
    let mut last_received = Instant::now();
    let mut window = Instant::now();
    let mut count = 0_u32;
    loop {
        tokio::select! {
            _ = heartbeat.tick() => {
                if last_received.elapsed() > Duration::from_secs(45) { break; }
                if ws.send(Message::Ping(Vec::new().into())).await.is_err() { break; }
            }
            frame = ws.recv() => {
                let Some(Ok(frame)) = frame else { break; };
                last_received = Instant::now();
                let Message::Text(value) = frame else {
                    if matches!(frame, Message::Close(_)) { break; }
                    continue;
                };
                if window.elapsed() >= Duration::from_secs(1) { window = Instant::now(); count = 0; }
                count += 1;
                if count > 400 {
                    error(&mut ws, "rate", "操作过于频繁，请降低发送频率。", None).await;
                    break;
                }
                let command = match serde_json::from_str::<Command>(&value) {
                    Ok(command) => command,
                    Err(_) => { if !error(&mut ws, "format", "指令格式不正确。", None).await { break; } continue; }
                };
                if let Err(message) = command.validate() {
                    if !error(&mut ws, "input", message, command.id()).await { break; }
                    continue;
                }
                if let Command::Ping { id } = command {
                    if !reply(&mut ws, Reply::Pong { id }).await { break; }
                    continue;
                }
                if state.input.execute(&command).is_err() {
                    if !error(&mut ws, "windows", "操作未完成，请检查目标窗口权限。", command.id()).await { break; }
                } else {
                    match &command {
                        Command::ButtonDown { button } if !held_buttons.contains(button) => held_buttons.push(*button),
                        Command::ButtonUp { button } => held_buttons.retain(|held| held != button),
                        _ => {}
                    }
                    if let Command::Text { id, .. } = command {
                        if !reply(&mut ws, Reply::TextSent { id }).await { break; }
                    }
                }
            }
        }
    }
    for button in held_buttons {
        let _ = state.input.execute(&Command::ButtonUp { button });
    }
    state.clear_udp(peer, &udp_token);
    let _ = ws.send(Message::Close(None)).await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::Button;
    use futures_util::{SinkExt, StreamExt};
    use std::sync::Mutex as StdMutex;
    use tokio::time::sleep;

    struct RecordingSink(StdMutex<Vec<(i32, i32, i32)>>);

    impl InputSink for RecordingSink {
        fn execute(&self, command: &Command) -> anyhow::Result<()> {
            match command {
                Command::MovePixels { dx, dy } => self.0.lock().unwrap().push((*dx, *dy, 0)),
                Command::Scroll { delta } => self.0.lock().unwrap().push((0, 0, *delta)),
                _ => {}
            }
            Ok(())
        }
    }

    struct ButtonRecordingSink(StdMutex<Vec<(Button, bool)>>);

    struct GestureRecordingSink(StdMutex<Vec<(i32, i32)>>);
    impl InputSink for GestureRecordingSink {
        fn execute(&self, command: &Command) -> anyhow::Result<()> {
            if let Command::TouchGesture { horizontal, zoom } = command {
                self.0.lock().unwrap().push((*horizontal, *zoom));
            }
            Ok(())
        }
    }

    #[tokio::test]
    async fn touch_gestures_are_validated_after_handshake() {
        use tokio_tungstenite::tungstenite::Message as ClientMessage;
        let sink = Arc::new(GestureRecordingSink(StdMutex::new(Vec::new())));
        let state = AppState::new(sink.clone(), 8765);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                router(state).into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap();
        });
        let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}/ws"))
            .await
            .unwrap();
        socket
            .send(ClientMessage::Text(
                r#"{"type":"hello","protocol":1}"#.into(),
            ))
            .await
            .unwrap();
        let ready = socket.next().await.unwrap().unwrap().into_text().unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&ready).unwrap()["protocol"],
            PROTOCOL
        );
        for command in [
            r#"{"type":"touch_gesture","horizontal":-120,"zoom":0}"#,
            r#"{"type":"touch_gesture","horizontal":0,"zoom":240}"#,
            r#"{"type":"touch_gesture","horizontal":0,"zoom":-120}"#,
            r#"{"type":"touch_gesture","horizontal":0,"zoom":1}"#,
            r#"{"type":"ping","id":3}"#,
        ] {
            socket
                .send(ClientMessage::Text(command.into()))
                .await
                .unwrap();
        }
        let error = socket.next().await.unwrap().unwrap().into_text().unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&error).unwrap()["type"],
            "error"
        );
        socket.next().await.unwrap().unwrap();
        assert_eq!(
            *sink.0.lock().unwrap(),
            vec![(-120, 0), (0, 240), (0, -120)]
        );
        server.abort();
    }

    #[tokio::test]
    async fn websocket_accepts_pixel_motion_and_rejects_obsolete_move() {
        use tokio_tungstenite::tungstenite::Message as ClientMessage;
        let sink = Arc::new(RecordingSink(StdMutex::new(Vec::new())));
        let state = AppState::new(sink.clone(), 8765);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                router(state).into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap();
        });
        let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}/ws"))
            .await
            .unwrap();
        socket
            .send(ClientMessage::Text(
                r#"{"type":"hello","protocol":1}"#.into(),
            ))
            .await
            .unwrap();
        let ready = socket.next().await.unwrap().unwrap().into_text().unwrap();
        let ready: serde_json::Value = serde_json::from_str(&ready).unwrap();
        assert_eq!(ready["protocol"], PROTOCOL);
        assert_eq!(ready["udp_port"], 8765);
        assert_eq!(ready["udp_token"].as_str().unwrap().len(), 32);
        assert!(ready.get("pixel_move").is_none());
        assert!(ready.get("touch_gestures").is_none());
        for command in [
            r#"{"type":"move_pixels","dx":12,"dy":-5}"#,
            r#"{"type":"move","dx":1,"dy":2}"#,
            r#"{"type":"ping","id":1}"#,
        ] {
            socket
                .send(ClientMessage::Text(command.into()))
                .await
                .unwrap();
        }
        let error = socket.next().await.unwrap().unwrap().into_text().unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&error).unwrap()["code"],
            "format"
        );
        let pong = socket.next().await.unwrap().unwrap().into_text().unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&pong).unwrap(),
            serde_json::json!({"type":"pong","id":1})
        );
        assert_eq!(*sink.0.lock().unwrap(), vec![(12, -5, 0)]);
        server.abort();
    }

    impl InputSink for ButtonRecordingSink {
        fn execute(&self, command: &Command) -> anyhow::Result<()> {
            match command {
                Command::ButtonDown { button } => self.0.lock().unwrap().push((*button, true)),
                Command::ButtonUp { button } => self.0.lock().unwrap().push((*button, false)),
                _ => {}
            }
            Ok(())
        }
    }

    async fn disconnected_button_events(release_left: bool) -> Vec<(Button, bool)> {
        use tokio_tungstenite::tungstenite::Message as ClientMessage;
        let sink = Arc::new(ButtonRecordingSink(StdMutex::new(Vec::new())));
        let state = AppState::new(sink.clone(), 8765);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                router(state).into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap();
        });
        let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}/ws"))
            .await
            .unwrap();
        socket
            .send(ClientMessage::Text(
                r#"{"type":"hello","protocol":1}"#.into(),
            ))
            .await
            .unwrap();
        let ready = socket.next().await.unwrap().unwrap().into_text().unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&ready).unwrap()["type"],
            "ready"
        );
        for button in ["left", "right", "middle"] {
            socket
                .send(ClientMessage::Text(
                    format!(r#"{{"type":"button_down","button":"{button}"}}"#).into(),
                ))
                .await
                .unwrap();
        }
        if release_left {
            socket
                .send(ClientMessage::Text(
                    r#"{"type":"button_up","button":"left"}"#.into(),
                ))
                .await
                .unwrap();
        }
        socket
            .send(ClientMessage::Text(r#"{"type":"ping","id":1}"#.into()))
            .await
            .unwrap();
        socket.next().await.unwrap().unwrap(); // Barrier: all presses have executed.
        drop(socket); // Abrupt disconnect, with no button-up for the remaining holds.
        let expected = 6;
        timeout(Duration::from_secs(2), async {
            loop {
                if sink.0.lock().unwrap().len() == expected {
                    break;
                }
                sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
        server.abort();
        sink.0.lock().unwrap().clone()
    }

    #[tokio::test]
    async fn disconnect_releases_all_held_mouse_buttons() {
        assert_eq!(
            disconnected_button_events(false).await,
            vec![
                (Button::Left, true),
                (Button::Right, true),
                (Button::Middle, true),
                (Button::Left, false),
                (Button::Right, false),
                (Button::Middle, false)
            ]
        );
    }

    #[tokio::test]
    async fn disconnect_releases_only_the_buttons_still_held() {
        assert_eq!(
            disconnected_button_events(true).await,
            vec![
                (Button::Left, true),
                (Button::Right, true),
                (Button::Middle, true),
                (Button::Left, false),
                (Button::Right, false),
                (Button::Middle, false)
            ]
        );
    }

    fn packet(
        token: &[u8; 16],
        sequence: u64,
        dx: i32,
        dy: i32,
        wheel: i32,
    ) -> [u8; UDP_PACKET_LEN] {
        let mut value = [0_u8; UDP_PACKET_LEN];
        value[..4].copy_from_slice(UDP_MAGIC);
        value[4..20].copy_from_slice(token);
        value[20..28].copy_from_slice(&sequence.to_be_bytes());
        value[28..32].copy_from_slice(&dx.to_be_bytes());
        value[32..36].copy_from_slice(&dy.to_be_bytes());
        value[36..40].copy_from_slice(&wheel.to_be_bytes());
        value
    }

    #[tokio::test]
    async fn udp_motion_accepts_newer_packets_and_drops_replays() {
        let sink = Arc::new(RecordingSink(StdMutex::new(Vec::new())));
        let state = AppState::new(sink.clone(), 8765);
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let destination = socket.local_addr().unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let peer = client.local_addr().unwrap().ip();
        let token = state.register_udp(peer);
        let task = tokio::spawn(run_motion_udp(socket, state));

        client
            .send_to(&packet(&token, 1, 4, -2, 0), destination)
            .await
            .unwrap();
        client
            .send_to(&packet(&token, 1, 99, 99, 0), destination)
            .await
            .unwrap();
        client
            .send_to(&packet(&token, 2, 0, 0, -3), destination)
            .await
            .unwrap();
        timeout(Duration::from_secs(2), async {
            while sink.0.lock().unwrap().len() < 2 {
                sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();

        assert_eq!(*sink.0.lock().unwrap(), vec![(4, -2, 0), (0, 0, -3)]);
        task.abort();
    }

    #[tokio::test]
    async fn pixel_udp_motion_checks_format_token_ordering_and_limits() {
        let sink = Arc::new(RecordingSink(StdMutex::new(Vec::new())));
        let state = AppState::new(sink.clone(), 8765);
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let destination = socket.local_addr().unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let token = state.register_udp(client.local_addr().unwrap().ip());
        let task = tokio::spawn(run_motion_udp(socket, state));
        let mut invalid_header = packet(&token, 1, 99, 99, 0);
        invalid_header[..4].copy_from_slice(b"BAD!");
        client.send_to(&invalid_header, destination).await.unwrap();
        client
            .send_to(&packet(&token, 1, 99, 99, 0)[..39], destination)
            .await
            .unwrap();
        let mut oversized = packet(&token, 1, 99, 99, 0).to_vec();
        oversized.push(0);
        client.send_to(&oversized, destination).await.unwrap();
        for (sequence, dx, dy, valid_token) in [
            (1, 99, 99, false),
            (1, 4, -2, true),
            (1, 99, 99, true),
            (2, 2001, 0, true),
            (3, 6, 1, true),
        ] {
            let value = packet(
                if valid_token { &token } else { &[0; 16] },
                sequence,
                dx,
                dy,
                0,
            );
            client.send_to(&value, destination).await.unwrap();
        }
        timeout(Duration::from_secs(2), async {
            while sink.0.lock().unwrap().len() < 2 {
                sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
        assert_eq!(*sink.0.lock().unwrap(), vec![(4, -2, 0), (6, 1, 0)]);
        task.abort();
    }

    #[tokio::test]
    async fn handshake_rejects_wrong_versions_and_input_before_ready() {
        use tokio_tungstenite::tungstenite::Message as ClientMessage;
        let sink = Arc::new(RecordingSink(StdMutex::new(Vec::new())));
        let state = AppState::new(sink.clone(), 8765);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                router(state).into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap();
        });
        for first in [
            r#"{"type":"hello","protocol":0}"#,
            r#"{"type":"auth","protocol":1,"password":""}"#,
            r#"{"type":"move_pixels","dx":99,"dy":99}"#,
        ] {
            let (mut socket, _) = tokio_tungstenite::connect_async(format!("ws://{address}/ws"))
                .await
                .unwrap();
            socket
                .send(ClientMessage::Text(first.into()))
                .await
                .unwrap();
            let reply = timeout(Duration::from_secs(2), socket.next())
                .await
                .unwrap()
                .unwrap()
                .unwrap()
                .into_text()
                .unwrap();
            let reply: serde_json::Value = serde_json::from_str(&reply).unwrap();
            assert_eq!(reply["type"], "error");
            assert_eq!(reply["code"], "protocol");
            assert!(reply.get("udp_token").is_none());
        }
        assert!(sink.0.lock().unwrap().is_empty());
        server.abort();
    }
}
