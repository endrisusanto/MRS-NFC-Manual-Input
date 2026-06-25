use reqwest::header::SET_COOKIE;
use std::time::Duration;
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

const MERS_BASE_URL: &str = "http://107.102.8.148/MERS";
const LOGIN_IDENTITY: &str = "16756586";
const LOGIN_PASSWORD: &str = "27051994";

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

#[tauri::command]
async fn ping_server(server: String) -> Result<bool, String> {
    let base_url = server_url(&server);
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(4))
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
    let base_url = server_url(&server);
    let cookie = login_cookie(&base_url).await?;
    let text = reqwest::Client::new()
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
    let text = reqwest::Client::new()
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

#[tauri::command]
async fn tap_in(uid: String, loket: String, server: String) -> Result<serde_json::Value, String> {
    let base_url = server_url(&server);
    let cookie = login_cookie(&base_url).await?;
    let schedule = loket_schedule(&base_url, &cookie, &loket).await?;
    let payload = format!("{}:{}", scanner_uid(&uid), loket.trim());
    let text = reqwest::Client::new()
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

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![ping_server, cek_pesanan, tap_in])
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

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
