pub mod discovery;
pub mod input;
#[cfg(windows)]
pub mod laser;
#[cfg(windows)]
mod laser_cursor;
pub mod laser_trail;
pub mod presentation;
pub mod protocol;
pub mod text_sync;

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
use presentation::{DryRunLaser, PresentationController, encode_token};
use protocol::{Command, PROTOCOL, Reply};
use std::{
    collections::{HashMap, VecDeque},
    net::{IpAddr, SocketAddr},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::{
    net::UdpSocket,
    sync::Semaphore,
    time::{MissedTickBehavior, interval, timeout},
};

const UDP_MAGIC: &[u8; 4] = b"PM01";
const UDP_PACKET_LEN: usize = 40;

pub struct AppState {
    pub presentation: Arc<PresentationController>,
    text_sync: Option<text_sync::TextSync>,
    failures: Mutex<HashMap<IpAddr, VecDeque<Instant>>>,
    udp_port: u16,
    slots: Arc<Semaphore>,
    shutdown: tokio::sync::watch::Sender<bool>,
}

impl AppState {
    pub fn new(input: Arc<dyn InputSink>, udp_port: u16) -> Arc<Self> {
        Self::with_presentation(
            PresentationController::new(input, Arc::new(DryRunLaser)),
            udp_port,
        )
    }

    pub fn with_presentation(
        presentation: Arc<PresentationController>,
        udp_port: u16,
    ) -> Arc<Self> {
        Self::with_text_sync(presentation, udp_port, None)
    }

    pub fn with_text_sync(
        presentation: Arc<PresentationController>,
        udp_port: u16,
        text_sync: Option<text_sync::TextSync>,
    ) -> Arc<Self> {
        Arc::new(Self {
            presentation,
            text_sync,
            failures: Mutex::new(HashMap::new()),
            udp_port,
            slots: Arc::new(Semaphore::new(16)),
            shutdown: tokio::sync::watch::channel(false).0,
        })
    }

    pub fn shutdown(&self) {
        self.shutdown.send_replace(true);
        self.presentation.shutdown();
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

    #[cfg(test)]
    fn register_udp(&self, peer: IpAddr) -> [u8; 16] {
        self.presentation.register(peer).token
    }
}

pub fn router(state: Arc<AppState>) -> Router {
    Router::new().route("/", get(|| async { "手机键鼠 Rust 接收端正在运行。请使用安卓 App 连接。" }))
        .route("/health", get(|| async { axum::Json(serde_json::json!({"status":"ok","protocol":PROTOCOL,"version":env!("CARGO_PKG_VERSION")})) }))
        .route("/ws", get(upgrade))
        .with_state(state)
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
        let dx = i32::from_be_bytes(buffer[28..32].try_into().unwrap());
        let dy = i32::from_be_bytes(buffer[32..36].try_into().unwrap());
        let wheel = i32::from_be_bytes(buffer[36..40].try_into().unwrap());
        state.presentation.udp(
            peer.ip(),
            buffer[4..20].try_into().unwrap(),
            sequence,
            dx,
            dy,
            wheel,
        );
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
    ws.max_message_size(131072)
        .max_frame_size(131072)
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
    let mut shutdown = state.shutdown.subscribe();
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
    let registration = state.presentation.register(peer);
    let session_id = registration.id;
    struct Cleanup(Arc<PresentationController>, presentation::SessionId);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            self.0.disconnect(self.1);
        }
    }
    let _cleanup = Cleanup(state.presentation.clone(), session_id);
    let mut presentation_events = registration.events;
    let mut features = vec![
        "laser",
        "laser_size",
        "laser_trail_length",
        "laser_range_300",
        "shift_enter_v1",
    ];
    if state.text_sync.is_some() {
        features.push("text_sync_v1");
        features.push("text_source_v1");
    }
    if !reply(
        &mut ws,
        Reply::Ready {
            protocol: PROTOCOL,
            version: env!("CARGO_PKG_VERSION"),
            udp_port: state.udp_port,
            udp_token: encode_token(&registration.token),
            features,
        },
    )
    .await
    {
        return;
    }

    let mut heartbeat = interval(Duration::from_secs(15));
    heartbeat.set_missed_tick_behavior(MissedTickBehavior::Skip);
    heartbeat.tick().await;
    let mut last_received = Instant::now();
    let mut window = Instant::now();
    let mut count = 0_u32;
    loop {
        tokio::select! {
            _ = shutdown.changed() => { break; }
            result = presentation_events.changed() => {
                if result.is_err() { break; }
                let snapshot = presentation_events.borrow_and_update().clone();
                if !reply(&mut ws, Reply::PresentationState { state: snapshot, id: None }).await { break; }
            }
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
                    state.presentation.ping(session_id);
                    if !reply(&mut ws, Reply::Pong { id }).await { break; }
                    continue;
                }
                let text_action = match &command {
                    Command::TextRead { .. } => Some(text_sync::TextAction::Read),
                    Command::TextSource { .. } => Some(text_sync::TextAction::Source),
                    Command::TextEdit { target, revision, text, selection_start, selection_end, .. } => Some(text_sync::TextAction::Edit {
                        target: *target, revision: *revision, text: text.clone(), start: *selection_start, end: *selection_end,
                    }),
                    Command::TextEnter { target, revision, .. } => Some(text_sync::TextAction::Enter { target: *target, revision: *revision }),
                    Command::TextShiftEnter { target, revision, .. } => Some(text_sync::TextAction::ShiftEnter { target: *target, revision: *revision }),
                    _ => None,
                };
                if let Some(action) = text_action {
                    let id=command.id().expect("text request id");
                    if let Some(service)=state.text_sync.as_ref() {
                        let result=service.request(action).await;
                        if !reply(&mut ws, Reply::TextState { id, state: Box::new(result.state), error: result.error }).await { break; }
                    } else if !error(&mut ws,"text_sync","接收端不支持文字同步。",Some(id)).await { break; }
                    continue;
                }
                let change = match &command {
                    Command::PresentationSet { mode, settings, id } => Some((state.presentation.switch(session_id, *mode, *settings), *id)),
                    Command::LaserSettings { settings, id } => Some((state.presentation.configure(session_id, *settings), *id)),
                    _ => None,
                };
                if let Some((result, id)) = change {
                    match result {
                        Ok(snapshot) => { if !reply(&mut ws, Reply::PresentationState { state: snapshot, id: Some(id) }).await { break; } },
                        Err(message) => { if !error(&mut ws, "presentation", message, Some(id)).await { break; } },
                    }
                    continue;
                }
                if state.presentation.execute(session_id, &command).is_err() {
                    if !error(&mut ws, "windows", "操作未完成，请检查目标窗口权限。", command.id()).await { break; }
                } else {
                    if let Command::Text { id, .. } = command {
                        if !reply(&mut ws, Reply::TextSent { id }).await { break; }
                    }
                }
            }
        }
    }
    let _ = ws.send(Message::Close(None)).await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::Button;
    use futures_util::{SinkExt, StreamExt};
    use std::sync::Mutex as StdMutex;
    use tokio::time::sleep;

    #[tokio::test]
    async fn presentation_handshake_ack_ownership_and_forced_state_use_real_websockets() {
        async fn read_matching(
            socket: &mut tokio_tungstenite::WebSocketStream<
                tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
            >,
            id: Option<u64>,
            reason: Option<&str>,
        ) -> serde_json::Value {
            timeout(Duration::from_secs(2), async {
                loop {
                    let frame = socket.next().await.unwrap().unwrap();
                    if !frame.is_text() {
                        continue;
                    }
                    let value: serde_json::Value =
                        serde_json::from_str(frame.to_text().unwrap()).unwrap();
                    if id.is_some_and(|id| value["id"] == id)
                        || reason.is_some_and(|reason| value["reason"] == reason)
                        || (id.is_none() && reason.is_none())
                    {
                        return value;
                    }
                }
            })
            .await
            .unwrap()
        }
        let input = Arc::new(RecordingSink(StdMutex::new(Vec::new())));
        let state = AppState::new(input, 8765);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server_state = state.clone();
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                router(server_state).into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap();
        });
        let (mut a, _) = tokio_tungstenite::connect_async(format!("ws://{address}/ws"))
            .await
            .unwrap();
        a.send(tokio_tungstenite::tungstenite::Message::Text(
            r#"{"type":"hello","protocol":1}"#.into(),
        ))
        .await
        .unwrap();
        let ready_a = read_matching(&mut a, None, None).await;
        assert_eq!(
            ready_a["features"],
            serde_json::json!([
                "laser",
                "laser_size",
                "laser_trail_length",
                "laser_range_300",
                "shift_enter_v1"
            ])
        );
        let set = |id, mode| {
            tokio_tungstenite::tungstenite::Message::Text(serde_json::json!({"type":"presentation_set","id":id,"mode":mode,"settings":{"color":16724016,"trail":true,"fade_ms":600}}).to_string().into())
        };
        a.send(set(10, "laser")).await.unwrap();
        let laser = read_matching(&mut a, Some(10), None).await;
        assert_eq!(laser["type"], "presentation_state");
        assert_eq!(laser["mode"], "laser");
        assert_ne!(laser["udp_token"], ready_a["udp_token"]);
        let (mut b, _) = tokio_tungstenite::connect_async(format!("ws://{address}/ws"))
            .await
            .unwrap();
        b.send(tokio_tungstenite::tungstenite::Message::Text(
            r#"{"type":"hello","protocol":1}"#.into(),
        ))
        .await
        .unwrap();
        read_matching(&mut b, None, None).await;
        b.send(set(20, "laser")).await.unwrap();
        assert_eq!(
            read_matching(&mut b, Some(20), None).await["code"],
            "presentation"
        );
        state.presentation.force_exit("tray");
        let off = read_matching(&mut a, None, Some("tray")).await;
        assert_eq!(off["mode"], "mouse");
        assert_ne!(off["udp_token"], laser["udp_token"]);
        b.send(set(21, "laser")).await.unwrap();
        assert_eq!(read_matching(&mut b, Some(21), None).await["mode"], "laser");
        a.close(None).await.unwrap();
        b.send(tokio_tungstenite::tungstenite::Message::Text(
            r#"{"type":"ping","id":22}"#.into(),
        ))
        .await
        .unwrap();
        assert_eq!(read_matching(&mut b, Some(22), None).await["type"], "pong");
        b.send(set(23, "mouse")).await.unwrap();
        assert_eq!(read_matching(&mut b, Some(23), None).await["mode"], "mouse");
        b.close(None).await.unwrap();
        server.abort();
    }

    #[tokio::test]
    async fn text_sync_is_advertised_and_routes_read_and_edit_requests() {
        struct FakeText {
            state: text_sync::TextSnapshot,
        }
        impl text_sync::TextAdapter for FakeText {
            fn read_source(&mut self) -> text_sync::TextSnapshot {
                text_sync::TextSnapshot {
                    source: self.state.source.clone(),
                    source_icon: self.state.source_icon.clone(),
                    window_title: self.state.window_title.clone(),
                    ..text_sync::TextSnapshot::unavailable("ordinary")
                }
            }
            fn read(&mut self) -> text_sync::TextSnapshot {
                self.state.clone()
            }
            fn edit(
                &mut self,
                _: &text_sync::TextSnapshot,
                text: &str,
                start: usize,
                end: usize,
            ) -> Result<crate::text_sync::EditOutcome, &'static str> {
                self.state.text = text.to_string();
                self.state.revision += 1;
                self.state.selection_start = Some(start);
                self.state.selection_end = Some(end);
                Ok(crate::text_sync::EditOutcome::Exact)
            }
            fn enter(&mut self, _: &text_sync::TextSnapshot) -> Result<(), &'static str> {
                Ok(())
            }
            fn shift_enter(&mut self, _: &text_sync::TextSnapshot) -> Result<(), &'static str> {
                self.state.text.push('\n');
                self.state.revision += 1;
                self.state.selection_start = Some(self.state.text.encode_utf16().count());
                self.state.selection_end = self.state.selection_start;
                Ok(())
            }
        }
        let input = Arc::new(RecordingSink(StdMutex::new(Vec::new())));
        let text_sync = text_sync::TextSync::spawn(|| {
            Ok(FakeText {
                state: text_sync::TextSnapshot {
                    target: 9,
                    revision: 2,
                    status: "editable",
                    source: "fake".into(),
                    source_icon: Some("application-icon".into()),
                    window_title: Some("会议安排 - fake".into()),
                    text: "电脑文字".into(),
                    selection_start: Some(4),
                    selection_end: Some(4),
                    ..text_sync::TextSnapshot::default()
                },
            })
        });
        let presentation = PresentationController::new(input, Arc::new(DryRunLaser));
        let state = AppState::with_text_sync(presentation, 8765, Some(text_sync));
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
        use futures_util::{SinkExt, StreamExt};
        socket
            .send(tokio_tungstenite::tungstenite::Message::Text(
                r#"{"type":"hello","protocol":1}"#.into(),
            ))
            .await
            .unwrap();
        let ready: serde_json::Value = serde_json::from_str(
            socket
                .next()
                .await
                .unwrap()
                .unwrap()
                .into_text()
                .unwrap()
                .as_ref(),
        )
        .unwrap();
        assert!(
            ready["features"]
                .as_array()
                .unwrap()
                .iter()
                .any(|feature| feature == "text_sync_v1")
        );
        socket
            .send(tokio_tungstenite::tungstenite::Message::Text(
                r#"{"type":"text_read","id":40}"#.into(),
            ))
            .await
            .unwrap();
        let read: serde_json::Value = serde_json::from_str(
            socket
                .next()
                .await
                .unwrap()
                .unwrap()
                .into_text()
                .unwrap()
                .as_ref(),
        )
        .unwrap();
        assert_eq!(read["type"], "text_state");
        assert_eq!(read["text"], "电脑文字");
        assert_eq!(read["source_icon"], "application-icon");
        assert_eq!(read["window_title"], "会议安排 - fake");
        assert_eq!(read["revision"], 2);
        socket
            .send(tokio_tungstenite::tungstenite::Message::Text(
                serde_json::json!({
                    "type": "text_edit", "id": 41, "target": 9, "revision": 2,
                    "text": "手机文字", "selection_start": 4, "selection_end": 4,
                })
                .to_string()
                .into(),
            ))
            .await
            .unwrap();
        let edit: serde_json::Value = serde_json::from_str(
            socket
                .next()
                .await
                .unwrap()
                .unwrap()
                .into_text()
                .unwrap()
                .as_ref(),
        )
        .unwrap();
        assert_eq!(edit["type"], "text_state");
        assert_eq!(edit["text"], "手机文字");
        assert_eq!(edit["revision"], 3);
        assert!(
            ready["features"]
                .as_array()
                .unwrap()
                .iter()
                .any(|feature| feature == "shift_enter_v1")
        );
        for (id, revision, expected) in [(143, 2, "手机文字"), (144, 3, "手机文字\n")] {
            socket.send(tokio_tungstenite::tungstenite::Message::Text(
                serde_json::json!({"type":"text_shift_enter","id":id,"target":9,"revision":revision}).to_string().into()
            )).await.unwrap();
            let answer: serde_json::Value = serde_json::from_str(
                socket
                    .next()
                    .await
                    .unwrap()
                    .unwrap()
                    .into_text()
                    .unwrap()
                    .as_ref(),
            )
            .unwrap();
            assert_eq!(answer["id"], id);
            assert_eq!(answer["text"], expected);
            if revision == 2 {
                assert_eq!(answer["error"], "conflict");
            } else {
                assert!(answer.get("error").is_none());
                assert_eq!(answer["revision"], 4);
            }
        }
        assert!(
            ready["features"]
                .as_array()
                .unwrap()
                .iter()
                .any(|feature| feature == "text_source_v1")
        );
        socket
            .send(tokio_tungstenite::tungstenite::Message::Text(
                r#"{"type":"text_source","id":42}"#.into(),
            ))
            .await
            .unwrap();
        let source: serde_json::Value = serde_json::from_str(
            socket
                .next()
                .await
                .unwrap()
                .unwrap()
                .into_text()
                .unwrap()
                .as_ref(),
        )
        .unwrap();
        assert_eq!(source["type"], "text_state");
        assert_eq!(source["id"], 42);
        assert_eq!(source["status"], "ordinary");
        assert_eq!(source["source"], "fake");
        assert_eq!(source["source_icon"], "application-icon");
        assert_eq!(source["window_title"], "会议安排 - fake");
        assert_eq!(source["text"], "");
        assert_eq!(source["target"], 0);
        assert_eq!(source["revision"], 0);
        assert!(source["selection_start"].is_null());
        server.abort();
    }

    #[tokio::test]
    async fn ordinary_text_enter_and_backspace_work_when_text_sync_falls_back() {
        use tokio_tungstenite::tungstenite::Message as ClientMessage;
        struct KeyboardSink(StdMutex<Vec<String>>);
        impl InputSink for KeyboardSink {
            fn execute(&self, command: &Command) -> anyhow::Result<()> {
                let value = match command {
                    Command::Text { text, .. } => format!("text:{text}"),
                    Command::Key {
                        key: protocol::Key::Enter,
                    } => "enter".into(),
                    Command::Key {
                        key: protocol::Key::Backspace,
                    } => "backspace".into(),
                    _ => return Ok(()),
                };
                self.0.lock().unwrap().push(value);
                Ok(())
            }
        }
        struct UnreadableText(Arc<StdMutex<text_sync::TextSnapshot>>);
        impl text_sync::TextAdapter for UnreadableText {
            fn read(&mut self) -> text_sync::TextSnapshot {
                self.0.lock().unwrap().clone()
            }
            fn edit(
                &mut self,
                _: &text_sync::TextSnapshot,
                _: &str,
                _: usize,
                _: usize,
            ) -> Result<crate::text_sync::EditOutcome, &'static str> {
                panic!("ordinary input must not replace a document through text sync")
            }
            fn enter(&mut self, _: &text_sync::TextSnapshot) -> Result<(), &'static str> {
                panic!("ordinary Enter must use the keyboard")
            }
        }
        async fn next_json(
            socket: &mut tokio_tungstenite::WebSocketStream<
                tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
            >,
        ) -> serde_json::Value {
            let frame = timeout(Duration::from_secs(2), socket.next())
                .await
                .unwrap()
                .unwrap()
                .unwrap();
            serde_json::from_str(frame.into_text().unwrap().as_ref()).unwrap()
        }
        let sink = Arc::new(KeyboardSink(StdMutex::new(Vec::new())));
        let snapshot = Arc::new(StdMutex::new(text_sync::TextSnapshot {
            target: 9,
            revision: 1,
            status: "unsupported",
            source: "Code".into(),
            ..text_sync::TextSnapshot::default()
        }));
        let worker_snapshot = snapshot.clone();
        let sync = text_sync::TextSync::spawn(move || Ok(UnreadableText(worker_snapshot)));
        let state = AppState::with_text_sync(
            PresentationController::new(sink.clone(), Arc::new(DryRunLaser)),
            8765,
            Some(sync),
        );
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
        assert_eq!(next_json(&mut socket).await["type"], "ready");
        for status in ["unsupported", "too_long", "no_input", "unavailable"] {
            snapshot.lock().unwrap().status = status;
            socket
                .send(ClientMessage::Text(
                    r#"{"type":"text_read","id":40}"#.into(),
                ))
                .await
                .unwrap();
            let read = next_json(&mut socket).await;
            assert_eq!(read["type"], "text_state");
            assert_eq!(read["status"], status);
            assert_eq!(read["text"], "");
            for command in [
                r#"{"type":"text","text":"你好😀","id":41}"#,
                r#"{"type":"key","key":"enter"}"#,
                r#"{"type":"key","key":"backspace"}"#,
                r#"{"type":"ping","id":42}"#,
            ] {
                socket
                    .send(ClientMessage::Text(command.into()))
                    .await
                    .unwrap();
            }
            assert_eq!(next_json(&mut socket).await["type"], "text_sent");
            assert_eq!(next_json(&mut socket).await["type"], "pong");
        }
        assert_eq!(
            *sink.0.lock().unwrap(),
            ["text:你好😀", "enter", "backspace"].repeat(4)
        );
        socket.close(None).await.unwrap();
        server.abort();
    }

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
