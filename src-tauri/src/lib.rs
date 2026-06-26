use reqwest::header::SET_COOKIE;
use std::time::Duration;
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};
use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};
use serde::Deserialize;
use std::sync::atomic::{AtomicBool, Ordering};

const MERS_BASE_URL: &str = "http://107.102.8.148/MERS";
const LOGIN_IDENTITY: &str = "16756586";
const LOGIN_PASSWORD: &str = "27051994";

static RECONNECT_REQUESTED: AtomicBool = AtomicBool::new(false);

#[derive(Deserialize, Debug)]
struct WsIncomingCommand {
    #[serde(rename = "type")]
    msg_type: String,
    action: String,
    uid: String,
    loket: String,
}

fn server_url(server: &str) -> String {
    let trimmed = server.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        MERS_BASE_URL.to_string()
    } else {
        trimmed.to_string()
    }
}

fn scanner_uid(uid: &str) -> String {
    let raw = uid.trim();
    if raw.chars().all(|c| c.is_ascii_digit()) {
        if let Ok(value) = raw.parse::<u128>() {
            let mut hex = format!("{value:X}");
            if hex.len() % 2 != 0 {
                hex = format!("0{hex}");
            }
            return hex;
        }
    }
    raw.to_uppercase()
}

async fn login_cookie(base_url: &str) -> Result<String, String> {
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(3)) // ponytail: avoid hanging
        .build()
        .map_err(|e| e.to_string())?;

    let res = client
        .post(format!("{base_url}/auth/login"))
        .form(&[("identity", LOGIN_IDENTITY), ("password", LOGIN_PASSWORD)])
        .send()
        .await
        .map_err(|e| format!("Login MeRS gagal: {e}"))?;

    for header in res.headers().get_all(SET_COOKIE) {
        let Ok(cookie) = header.to_str() else { continue };
        if cookie.contains("ci_session") {
            if let Some(value) = cookie.split(';').next() {
                return Ok(value.to_string());
            }
        }
    }

    Err("Login MeRS gagal: cookie sesi tidak diterima.".to_string())
}

fn response_body(text: String) -> serde_json::Value {
    serde_json::from_str(&text).unwrap_or_else(|_| serde_json::json!({ "raw": text }))
}

// Helper functions for shared execution
async fn run_cek_pesanan(uid: &str, server: &str) -> Result<serde_json::Value, String> {
    let base_url = server_url(server);
    let cookie = login_cookie(&base_url).await?;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(3)) // ponytail: avoid hanging
        .build()
        .map_err(|e| e.to_string())?;

    let text = client
        .get(format!("{base_url}/cekorder.php?check_order={}", uid.trim()))
        .header("Cookie", cookie)
        .send()
        .await
        .map_err(|e| format!("Cek pesanan gagal: {e}"))?
        .text()
        .await
        .map_err(|e| e.to_string())?;

    Ok(response_body(text))
}

async fn loket_schedule(base_url: &str, cookie: &str, loket: &str) -> Result<serde_json::Value, String> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(3)) // ponytail: avoid hanging
        .build()
        .map_err(|e| e.to_string())?;

    let text = client
        .get(format!("{base_url}/cekorder.php?loket={}", loket.trim()))
        .header("Cookie", cookie)
        .send()
        .await
        .map_err(|e| format!("Cek loket gagal: {e}"))?
        .text()
        .await
        .map_err(|e| e.to_string())?;

    Ok(response_body(text))
}

async fn run_tap_in(uid: &str, loket: &str, server: &str) -> Result<serde_json::Value, String> {
    let base_url = server_url(server);
    let cookie = login_cookie(&base_url).await?;
    let schedule = loket_schedule(&base_url, &cookie, loket).await?;
    let payload = format!("{}:{}", scanner_uid(uid), loket.trim());
    
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(3)) // ponytail: avoid hanging
        .build()
        .map_err(|e| e.to_string())?;

    let text = client
        .post(format!("{base_url}/cekorder.php"))
        .header("Cookie", cookie)
        .form(&[("data", payload)])
        .send()
        .await
        .map_err(|e| format!("Tap in gagal: {e}"))?
        .text()
        .await
        .map_err(|e| e.to_string())?;

    Ok(serde_json::json!({
        "schedule": schedule,
        "tap": response_body(text),
    }))
}

// --- TAURI COMMANDS ---

#[tauri::command]
async fn ping_server(server: String) -> Result<bool, String> {
    let base_url = server_url(&server);
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(3))
        .build()
        .map_err(|e| e.to_string())?;

    Ok(client
        .head(format!("{base_url}/cekorder.php?ping=1"))
        .send()
        .await
        .map(|res| res.status().is_success())
        .unwrap_or(false))
}

#[tauri::command]
async fn cek_pesanan(uid: String, server: String) -> Result<serde_json::Value, String> {
    run_cek_pesanan(&uid, &server).await
}

#[tauri::command]
async fn tap_in(uid: String, loket: String, server: String) -> Result<serde_json::Value, String> {
    run_tap_in(&uid, &loket, &server).await
}

#[tauri::command]
fn get_agent_config(app_handle: tauri::AppHandle) -> Result<serde_json::Value, String> {
    let config_dir = app_handle.path().app_data_dir().unwrap_or_default();
    let config_file = config_dir.join("agent_config.json");
    if let Ok(content) = std::fs::read_to_string(&config_file) {
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(&content) {
            return Ok(val);
        }
    }
    Ok(serde_json::json!({
        "gateway_url": "wss://makan.endrisusanto.my.id/ws",
        "device_id": "loket-pc-1",
        "server_url": "http://107.102.8.148/MERS"
    }))
}

#[tauri::command]
fn save_agent_config(
    app_handle: tauri::AppHandle,
    gateway_url: String,
    device_id: String,
    server_url: String,
) -> Result<(), String> {
    let config_dir = app_handle.path().app_data_dir().unwrap_or_default();
    let _ = std::fs::create_dir_all(&config_dir);
    let config_file = config_dir.join("agent_config.json");
    let new_config = serde_json::json!({
        "gateway_url": gateway_url.trim(),
        "device_id": device_id.trim(),
        "server_url": server_url.trim()
    });
    std::fs::write(&config_file, serde_json::to_string_pretty(&new_config).unwrap())
        .map_err(|e| format!("Gagal menyimpan konfigurasi: {e}"))?;

    RECONNECT_REQUESTED.store(true, Ordering::Relaxed);
    Ok(())
}

// --- BACKGROUND WEBSOCKET CLIENT ---

fn start_ws_client_loop(app_handle: tauri::AppHandle) {
    tauri::async_runtime::spawn(async move {
        let config_dir = app_handle.path().app_data_dir().unwrap_or_default();
        let _ = std::fs::create_dir_all(&config_dir);
        let config_file = config_dir.join("agent_config.json");

        // Write default config if not exists
        if !config_file.exists() {
            let default_config = serde_json::json!({
                "gateway_url": "wss://makan.endrisusanto.my.id/ws",
                "device_id": "loket-pc-1",
                "server_url": "http://107.102.8.148/MERS"
            });
            let _ = std::fs::write(&config_file, serde_json::to_string_pretty(&default_config).unwrap());
        }

        loop {
            // Read config dynamically to allow hot-reloading changes
            let (gateway_url, device_id, server_url) = match std::fs::read_to_string(&config_file) {
                Ok(content) => {
                    let json: serde_json::Value = serde_json::from_str(&content).unwrap_or_default();
                    let url = json.get("gateway_url").and_then(|v| v.as_str()).unwrap_or("wss://makan.endrisusanto.my.id/ws").to_string();
                    let dev = json.get("device_id").and_then(|v| v.as_str()).unwrap_or("loket-pc-1").to_string();
                    let srv = json.get("server_url").and_then(|v| v.as_str()).unwrap_or("http://107.102.8.148/MERS").to_string();
                    (url, dev, srv)
                }
                Err(_) => {
                    ("wss://makan.endrisusanto.my.id/ws".to_string(), "loket-pc-1".to_string(), "http://107.102.8.148/MERS".to_string())
                }
            };

            println!("[Agent WS] Connecting to cloud WebSocket gateway: {}", gateway_url);
            match connect_async(&gateway_url).await {
                Ok((ws_stream, _)) => {
                    println!("[Agent WS] Connected successfully!");
                    let (mut write, mut read) = ws_stream.split();

                    // Send register/join message
                    let join_msg = serde_json::json!({
                        "type": "join",
                        "role": "agent",
                        "device": device_id
                    });
                    if let Err(e) = write.send(Message::Text(join_msg.to_string())).await {
                        println!("[Agent WS] Join failed: {}", e);
                        tokio::time::sleep(Duration::from_secs(5)).await;
                        continue;
                    }

                    loop {
                        tokio::select! {
                            msg_result = read.next() => {
                                match msg_result {
                                    Some(Ok(Message::Text(text))) => {
                                        if let Ok(cmd) = serde_json::from_str::<WsIncomingCommand>(&text) {
                                            if cmd.msg_type == "command" {
                                                println!("[Agent WS] Received command: {} for UID {}", cmd.action, cmd.uid);
                                                
                                                // Execute request locally on intranet MeRS PHP
                                                let response_json = match cmd.action.as_str() {
                                                    "cek_pesanan" => {
                                                        match run_cek_pesanan(&cmd.uid, &server_url).await {
                                                            Ok(val) => val,
                                                            Err(err) => serde_json::json!({ "success": false, "message": err })
                                                        }
                                                    }
                                                    "tap_in" => {
                                                        match run_tap_in(&cmd.uid, &cmd.loket, &server_url).await {
                                                            Ok(val) => val,
                                                            Err(err) => serde_json::json!({ "success": false, "message": err })
                                                        }
                                                    }
                                                    _ => serde_json::json!({ "success": false, "message": "Command tidak dikenali" })
                                                };

                                                // Send response JSON back to Cloud Gateway
                                                if let Err(e) = write.send(Message::Text(response_json.to_string())).await {
                                                    println!("[Agent WS] Failed to send response back to gateway: {}", e);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    Some(Ok(_)) => {}
                                    Some(Err(e)) => {
                                        println!("[Agent WS] Read socket error: {}", e);
                                        break;
                                    }
                                    None => {
                                        break;
                                    }
                                }
                            }
                            _ = tokio::time::sleep(Duration::from_secs(2)) => {
                                if RECONNECT_REQUESTED.load(Ordering::Relaxed) {
                                    RECONNECT_REQUESTED.store(false, Ordering::Relaxed);
                                    println!("[Agent WS] Configuration changed. Reconnecting...");
                                    break;
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    println!("[Agent WS] Connection failed: {}. Retrying in 5 seconds...", e);
                }
            }
            tokio::time::sleep(Duration::from_secs(5)).await;
        }
    });
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![ping_server, cek_pesanan, tap_in, get_agent_config, save_agent_config])
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?.join("webview");
            std::fs::create_dir_all(&data_dir)?;

            WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("MeRS NFC Desktop")
                .inner_size(1280.0, 800.0)
                .min_inner_size(420.0, 360.0)
                .resizable(true)
                .data_directory(data_dir)
                .build()?;

            // Spawn background task for Cloud WebSocket relay communication
            let handle = app.handle().clone();
            start_ws_client_loop(handle);

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
