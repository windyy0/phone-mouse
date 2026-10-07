use crate::protocol::PROTOCOL;
use anyhow::Result;
use serde_json::json;
use std::{net::SocketAddr, time::Duration};
use tokio::{net::UdpSocket, time::interval};

pub const DISCOVERY_PORT: u16 = 37_655;
const REQUEST: &[u8] = b"PHONE_MOUSE_DISCOVER";

/// Responds to Android discovery requests and periodically advertises on the
/// local broadcast address. The response contains no secret or input data.
pub async fn run(server_port: u16, name: String) -> Result<()> {
    let socket = UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)).await?;
    socket.set_broadcast(true)?;
    let response = serde_json::to_vec(&json!({
        "kind": "phone_mouse_server",
        "protocol": PROTOCOL,
        "name": name,
        "port": server_port,
    }))?;
    let broadcast: SocketAddr = ([255, 255, 255, 255], DISCOVERY_PORT).into();
    let mut timer = interval(Duration::from_secs(2));
    let mut buf = [0_u8; 256];
    loop {
        tokio::select! {
            _ = timer.tick() => {
                let _ = socket.send_to(&response, broadcast).await;
            }
            received = socket.recv_from(&mut buf) => {
                let (len, peer) = received?;
                if &buf[..len] == REQUEST {
                    let _ = socket.send_to(&response, peer).await;
                }
            }
        }
    }
}
