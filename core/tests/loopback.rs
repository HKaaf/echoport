//! End-to-end: a server and a player talk over 127.0.0.1 using the real protocol.

use echoport_core::client::{Client, ClientConfig, Refused};
use echoport_core::protocol::{Source, CHANNELS, FRAME_SAMPLES};
use echoport_core::server::{Server, ServerConfig};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

fn server_cfg(port: u16, pin: Option<&str>) -> ServerConfig {
    ServerConfig { id: "srv".into(), name: "Test PC".into(), port, pin: pin.map(Into::into), source: Source::Mic }
}

fn client_cfg(pin: Option<&str>) -> ClientConfig {
    ClientConfig { id: "ply".into(), name: "Test phone".into(), platform: "test".into(), pin: pin.map(Into::into), buffer_ms: 10 }
}

#[tokio::test]
async fn audio_flows_from_server_to_player() {
    let port = 47311;
    let server = Server::start(server_cfg(port, None)).await.unwrap();
    let addr: SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();
    let client = Client::connect(&[addr], client_cfg(None)).await.unwrap();
    assert_eq!(client.status().server_name, "Test PC");

    // Give the player's UDP "hi" time to arrive, then send frames.
    tokio::time::sleep(Duration::from_millis(300)).await;
    let frames = server.frame_sender();
    let frame: Vec<i16> = vec![8192; FRAME_SAMPLES * CHANNELS as usize];
    for _ in 0..20 {
        frames.send(Arc::new(frame.clone())).unwrap();
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    tokio::time::sleep(Duration::from_millis(200)).await;

    let mut out = vec![0f32; FRAME_SAMPLES * 2];
    client.jitter().lock().unwrap().pop(&mut out);
    assert!(out.iter().all(|s| (s - 0.25).abs() < 0.001), "player got {:?}", &out[..4]);
    assert_eq!(server.peers().len(), 1);
}

#[tokio::test]
async fn wrong_pin_is_refused() {
    let port = 47312;
    let _server = Server::start(server_cfg(port, Some("1234"))).await.unwrap();
    let addr: SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();

    let err = Client::connect(&[addr], client_cfg(Some("9999"))).await.err().unwrap();
    assert_eq!(err.downcast_ref::<Refused>().unwrap().code, "bad_pin");
    let err = Client::connect(&[addr], client_cfg(None)).await.err().unwrap();
    assert_eq!(err.downcast_ref::<Refused>().unwrap().code, "pin_required");
    assert!(Client::connect(&[addr], client_cfg(Some("1234"))).await.is_ok());
}
