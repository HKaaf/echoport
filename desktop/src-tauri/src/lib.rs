//! Tauri shell: exposes the core engine to the web UI and handles tray, startup and
//! persisted settings.

use echoport_core::engine::{Engine, EngineStatus, PlayerOptions, ServerOptions};
use echoport_core::{audio, discovery::DiscoveredServer, dsp::EffectSettings, netinfo};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, State, WindowEvent};
use tokio::sync::Mutex;

const MINIMIZED_ARG: &str = "--minimized";

struct AppState {
    engine: Mutex<Engine>,
    id: String,
    config_dir: PathBuf,
    close_to_tray: AtomicBool,
}

type Res<T> = Result<T, String>;

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Info {
    id: String,
    default_name: String,
    version: String,
    launched_minimized: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Devices {
    inputs: Vec<String>,
    outputs: Vec<String>,
    virtual_cable: Option<String>,
}

fn default_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Echoport PC".into())
}

#[tauri::command]
fn get_info(state: State<AppState>) -> Info {
    Info {
        id: state.id.clone(),
        default_name: default_name(),
        version: env!("CARGO_PKG_VERSION").into(),
        launched_minimized: std::env::args().any(|a| a == MINIMIZED_ARG),
    }
}

#[tauri::command]
fn load_settings(state: State<AppState>) -> Option<String> {
    std::fs::read_to_string(state.config_dir.join("settings.json")).ok()
}

#[tauri::command]
fn save_settings(state: State<AppState>, json: String, close_to_tray: bool) -> Res<()> {
    state.close_to_tray.store(close_to_tray, Ordering::Relaxed);
    std::fs::create_dir_all(&state.config_dir).map_err(err)?;
    std::fs::write(state.config_dir.join("settings.json"), json).map_err(err)
}

#[tauri::command]
fn list_devices() -> Devices {
    Devices {
        inputs: audio::input_devices(),
        outputs: audio::output_devices(),
        virtual_cable: audio::virtual_cable(),
    }
}

#[tauri::command]
fn interfaces() -> Vec<netinfo::Interface> {
    netinfo::ipv4_interfaces()
}

#[tauri::command]
async fn discovered(state: State<'_, AppState>) -> Res<Vec<DiscoveredServer>> {
    Ok(state.engine.lock().await.discovered())
}

#[tauri::command]
async fn set_name(state: State<'_, AppState>, name: String) -> Res<()> {
    state.engine.lock().await.set_name(name);
    Ok(())
}

#[tauri::command]
async fn start_server(state: State<'_, AppState>, opts: ServerOptions) -> Res<()> {
    state.engine.lock().await.start_server(opts).await.map_err(err)
}

#[tauri::command]
async fn stop_server(state: State<'_, AppState>) -> Res<()> {
    state.engine.lock().await.stop_server();
    Ok(())
}

#[tauri::command]
async fn set_effects(state: State<'_, AppState>, effects: EffectSettings) -> Res<()> {
    state.engine.lock().await.set_effects(effects);
    Ok(())
}

#[tauri::command]
async fn kick(state: State<'_, AppState>, peer_id: String) -> Res<()> {
    state.engine.lock().await.kick(&peer_id);
    Ok(())
}

#[tauri::command]
async fn connect(state: State<'_, AppState>, opts: PlayerOptions) -> Res<()> {
    state.engine.lock().await.connect(opts).await.map_err(|e| {
        match e.downcast_ref::<echoport_core::client::Refused>() {
            Some(r) => r.code.clone(),
            None => e.to_string(),
        }
    })
}

#[tauri::command]
async fn disconnect(state: State<'_, AppState>) -> Res<()> {
    state.engine.lock().await.disconnect();
    Ok(())
}

#[tauri::command]
async fn set_volume(state: State<'_, AppState>, percent: u32) -> Res<()> {
    state.engine.lock().await.set_volume(percent);
    Ok(())
}

#[tauri::command]
async fn set_buffer(state: State<'_, AppState>, ms: u32) -> Res<()> {
    state.engine.lock().await.set_buffer(ms);
    Ok(())
}

#[tauri::command]
async fn start_recording(app: AppHandle, state: State<'_, AppState>) -> Res<String> {
    let dir = app.path().audio_dir().map_err(err)?.join("Echoport");
    std::fs::create_dir_all(&dir).map_err(err)?;
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let path = dir.join(format!("echoport-{stamp}.wav"));
    state.engine.lock().await.start_recording(path.clone()).map_err(err)?;
    Ok(path.display().to_string())
}

#[tauri::command]
async fn stop_recording(state: State<'_, AppState>) -> Res<()> {
    state.engine.lock().await.stop_recording();
    Ok(())
}

#[tauri::command]
async fn status(state: State<'_, AppState>) -> Res<EngineStatus> {
    Ok(state.engine.lock().await.status())
}

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// A stable random id for this install, kept next to the settings.
fn device_id(config_dir: &PathBuf) -> String {
    let path = config_dir.join("device-id");
    if let Ok(id) = std::fs::read_to_string(&path) {
        if !id.trim().is_empty() {
            return id.trim().to_string();
        }
    }
    let id = uuid::Uuid::new_v4().to_string();
    let _ = std::fs::create_dir_all(config_dir);
    let _ = std::fs::write(&path, &id);
    id
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![MINIMIZED_ARG]),
        ))
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let config_dir = app.path().app_config_dir()?;
            let id = device_id(&config_dir);
            app.manage(AppState {
                engine: Mutex::new(Engine::new(id.clone(), default_name())),
                id,
                config_dir,
                close_to_tray: AtomicBool::new(true),
            });

            let open = MenuItem::with_id(app, "open", "Open Echoport", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().cloned().expect("app icon"))
                .tooltip("Echoport")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, e| match e.id.as_ref() {
                    "open" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, e| {
                    if let TrayIconEvent::DoubleClick { button: MouseButton::Left, .. } = e {
                        show_main(tray.app_handle());
                    }
                })
                .build(app)?;

            // The window starts hidden; the UI shows it unless launched minimized at startup.
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let state = window.state::<AppState>();
                if state.close_to_tray.load(Ordering::Relaxed) {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_info,
            load_settings,
            save_settings,
            list_devices,
            interfaces,
            discovered,
            set_name,
            start_server,
            stop_server,
            set_effects,
            kick,
            connect,
            disconnect,
            set_volume,
            set_buffer,
            start_recording,
            stop_recording,
            status
        ])
        .run(tauri::generate_context!())
        .expect("error while running Echoport");
}
