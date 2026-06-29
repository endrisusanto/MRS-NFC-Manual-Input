use futures_util::{SinkExt, StreamExt};
use reqwest::header::SET_COOKIE;
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{atomic::{AtomicBool, AtomicU8, Ordering}, Mutex};
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager, WebviewUrl, WebviewWindowBuilder};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};

// ponytail: simple in-memory session cache — genId → cookie
static ORDER_SESSIONS: Mutex<Option<HashMap<String, (String, Option<String>)>>> = Mutex::new(None);
// ponytail: menu stock changes slowly enough; 60s cache avoids hammering MERS.
static ORDER_MENU_CACHE: Mutex<Option<HashMap<String, (Instant, serde_json::Value)>>> = Mutex::new(None);

fn order_sessions() -> std::sync::MutexGuard<'static, Option<HashMap<String, (String, Option<String>)>>> {
    let mut g = ORDER_SESSIONS.lock().unwrap();
    if g.is_none() { *g = Some(HashMap::new()); }
    g
}

fn order_menu_cache() -> std::sync::MutexGuard<'static, Option<HashMap<String, (Instant, serde_json::Value)>>> {
    let mut g = ORDER_MENU_CACHE.lock().unwrap();
    if g.is_none() { *g = Some(HashMap::new()); }
    g
}

const MERS_BASE_URL: &str = "http://107.102.8.148/MERS";
const LOGIN_IDENTITY: &str = "16756586";
const LOGIN_PASSWORD: &str = "27051994";

static RECONNECT_REQUESTED: AtomicBool = AtomicBool::new(false);
static WS_STATUS: AtomicU8 = AtomicU8::new(0); // 0 = offline, 1 = connecting, 2 = online

#[derive(Deserialize, Debug)]
struct WsIncomingCommand {
    #[serde(rename = "type")]
    msg_type: String,
    action: String,
    uid: Option<String>,
    loket: Option<String>,
    #[serde(rename = "requestId")]
    request_id: Option<String>,
    #[serde(rename = "genId")]
    gen_id: Option<String>,
    password: Option<String>,
    dates: Option<Vec<String>>,
    date: Option<String>,
    from: Option<String>,
    to: Option<String>,
    #[serde(rename = "mealId")]
    meal_id: Option<String>,
    #[serde(rename = "menuId")]
    menu_id: Option<String>,
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
    if raw.contains(':') {
        return reverse_hex_bytes(&raw.replace(':', ""));
    }
    if let Some(hex) = raw.strip_prefix('#') {
        return hex.to_uppercase();
    }
    if let Some(input) = raw.strip_prefix('~') {
        let hex = if input.chars().all(|c| c.is_ascii_digit()) {
            input
                .parse::<u128>()
                .map(|value| format!("{value:X}"))
                .unwrap_or_else(|_| input.to_string())
        } else {
            input.to_string()
        };
        return reverse_hex_bytes(&hex);
    }
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

fn reverse_hex_bytes(hex: &str) -> String {
    let mut raw = hex.trim().to_uppercase();
    if raw.len() % 2 != 0 {
        raw = format!("0{raw}");
    }
    (0..raw.len())
        .step_by(2)
        .rev()
        .map(|i| &raw[i..i + 2])
        .collect()
}

#[cfg(test)]
mod tests {
    use super::{apply_report_menu_names, enrich_order_from_schedule, html_menu_labels, menu_detail, menu_name, order_history_rows, report_menu_names, scanner_uid, MenuLabel};
    use std::collections::HashMap;

    #[test]
    fn scanner_uid_matches_extension_byte_order() {
        assert_eq!(scanner_uid("2A:DA:1A:65"), "651ADA2A");
        assert_eq!(scanner_uid("~2ADA1A65"), "651ADA2A");
        assert_eq!(scanner_uid("#2ADA1A65"), "2ADA1A65");
    }

    #[test]
    fn menu_name_prefers_main_name_then_html_name() {
        let names = HashMap::from([("49959".to_string(), MenuLabel { name: "Menu HTML".to_string(), detail: String::new() })]);
        let reports = HashMap::from([("50065".to_string(), "AYAM BAKAR KALASAN".to_string())]);
        assert_eq!(menu_name(&serde_json::json!({"main_name": "AYAM"}), &names, &reports, "49959"), "AYAM");
        assert_eq!(menu_name(&serde_json::json!({}), &names, &reports, "49959"), "Menu HTML");
        assert_eq!(menu_name(&serde_json::json!({}), &names, &reports, "50065"), "AYAM BAKAR KALASAN");
        assert_eq!(menu_name(&serde_json::json!({}), &names, &reports, "1"), "Menu #1");
    }

    #[test]
    fn html_menu_names_reads_label_input() {
        let names = html_menu_labels(r#"<label><input name="menusaya" value="49959">Ayam Goreng</label>"#);
        assert_eq!(names.get("49959").unwrap().name, "Ayam Goreng");
    }

    #[test]
    fn html_menu_names_reads_label_input_unquoted() {
        let names = html_menu_labels(r#"<label><input name=menusaya value=49959>Ayam Goreng</label>"#);
        assert_eq!(names.get("49959").unwrap().name, "Ayam Goreng");
    }

    #[test]
    fn html_menu_labels_reads_card_detail() {
        let html = r#"
          <div class="menu-card">
            <input name="menusaya" value="50068">
            <h3 class="menu-title">KAKAP BUMBU KEMANGI</h3>
            <div class="menu-item-name">Nasi Putih</div>
            <div class="menu-item-name">Tahu Bacem</div>
          </div>
        "#;
        let label = html_menu_labels(html).get("50068").unwrap().clone();
        assert_eq!(label.name, "KAKAP BUMBU KEMANGI");
        assert_eq!(label.detail, "Nasi Putih | Tahu Bacem");
    }

    #[test]
    fn html_menu_labels_reads_id_title_and_menu_info() {
        let html = r#"
          <label>
            <input name="menusaya" value="49959">
            <h3 id="menu-title-49959">AYAM BAKAR</h3>
            <p class="menu-info">Nasi | Sayur Asem | Buah</p>
          </label>
        "#;
        let label = html_menu_labels(html).get("49959").unwrap().clone();
        assert_eq!(label.name, "AYAM BAKAR");
        assert_eq!(label.detail, "Nasi | Sayur Asem | Buah");
    }

    #[test]
    fn menu_detail_prefers_json_components() {
        let detail = menu_detail(&serde_json::json!({
            "carbo_name": "Nasi Putih",
            "main_name": "Fuyunghai",
            "soup_name": "Bening Bayam",
            "option1_name": "Tumis Tempe Cabe ijo",
            "option2_name": "Pangsit Isi Tahu",
            "option3_name": "Sambal Tomat",
            "fruit_name": "Jeruk",
            "additional_name": "pudding melon"
        }), &HashMap::new(), "49904");
        assert_eq!(detail, "Nasi Putih | Fuyunghai | Bening Bayam | Tumis Tempe Cabe ijo | Pangsit Isi Tahu | Sambal Tomat | Jeruk | pudding melon");
    }

    #[test]
    fn report_menu_names_reads_final_order_rows() {
        let html = r#"
          <tr>
            <td>30 Juni 2026</td><td>Makan Siang</td><td>1,2</td><td>Vendor</td>
            <td>AYAM BAKAR KALASAN</td><td>350</td><td>231</td><td></td><td>0</td>
            <td>[<a href="http://x/finalorder/view/50065">Rincian</a>]</td>
          </tr>
        "#;
        let names = report_menu_names(html);
        assert_eq!(names.get("50065").unwrap(), "AYAM BAKAR KALASAN");
    }

    #[test]
    fn order_history_rows_parse_indonesian_report() {
        let html = r#"
          <tr>
            <td>02 Juli 2026</td><td>Makan Malam</td><td>4</td><td>Endri Susanto</td>
            <td>16756586</td><td>PE</td><td>TELUR BALADO</td>
            <td><span class="badge bg-warning"><i class="ti"></i>Belum Diambil</span></td>
          </tr>
        "#;
        let rows = order_history_rows(html, None);
        assert_eq!(rows[0]["tanggal_iso"], "2026-07-02");
        assert_eq!(rows[0]["jadwal"], "Makan Malam");
        assert_eq!(rows[0]["menu"], "TELUR BALADO");
        assert_eq!(rows[0]["status"], "Belum Diambil");
    }

    #[test]
    fn enrich_order_from_schedule_fills_matching_menu_detail() {
        let mut order = serde_json::json!({ "menu_name": "FUYUNGHAI" });
        let schedule = serde_json::json!({
            "menu_name": "FUYUNGHAI",
            "carbo_name": "Nasi Putih",
            "main_name": "Fuyunghai",
            "soup_name": "Bening Bayam"
        });
        enrich_order_from_schedule(&mut order, &schedule);
        assert_eq!(order["carbo_name"], "Nasi Putih");
        assert_eq!(order["main_name"], "Fuyunghai");

        let mut other = serde_json::json!({ "menu_name": "AYAM KECAP" });
        enrich_order_from_schedule(&mut other, &schedule);
        assert!(other["carbo_name"].is_null());
    }

    #[test]
    fn apply_report_menu_names_replaces_menu_id_fallback() {
        let reports = HashMap::from([("49959".to_string(), "UDANG GORENG TEPUNG".to_string())]);
        let mut value = serde_json::json!({
            "menus": [
                { "id": "49959", "name": "Menu #49959" },
                { "id": "49960", "name": "AYAM KECAP" }
            ]
        });
        apply_report_menu_names(&mut value, &reports);
        assert_eq!(value["menus"][0]["name"], "UDANG GORENG TEPUNG");
        assert_eq!(value["menus"][1]["name"], "AYAM KECAP");
    }
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
        let Ok(cookie) = header.to_str() else {
            continue;
        };
        if cookie.contains("ci_session") {
            if let Some(value) = cookie.split(';').next() {
                return Ok(value.to_string());
            }
        }
    }

    Err("Login MeRS gagal: cookie sesi tidak diterima.".to_string())
}

async fn order_login_cookie(base_url: &str, gen_id: &str, password: &str) -> Result<(String, Option<String>), String> {
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|e| e.to_string())?;

    let res = client
        .post(format!("{base_url}/auth/login"))
        .form(&[("identity", gen_id), ("password", password)])
        .send()
        .await
        .map_err(|e| format!("Login MERS gagal: {e}"))?;

    let mut cookie = String::new();
    for header in res.headers().get_all(SET_COOKIE) {
        if let Ok(c) = header.to_str() {
            if c.contains("ci_session") {
                if let Some(v) = c.split(';').next() {
                    cookie = v.to_string();
                }
            }
        }
    }
    if cookie.is_empty() {
        return Err("Login gagal: cookie tidak diterima. Periksa GEN ID dan password.".to_string());
    }

    // Try to extract userId from order page
    let mut user_id: Option<String> = None;
    if let Ok(page) = client
        .get(format!("{base_url}/order/pilihmenu"))
        .header("Cookie", &cookie)
        .send()
        .await
    {
        if let Ok(text) = page.text().await {
            // Pattern: /reports/generate/DATE/DATE/USER_ID/
            let re = regex::Regex::new(r"/reports/generate/[^/]+/[^/]+/(\d+)/").unwrap();
            if let Some(caps) = re.captures(&text) {
                user_id = Some(caps[1].to_string());
            }
        }
    }

    Ok((cookie, user_id))
}

async fn ensure_order_session(base_url: &str, gen_id: &str, password: &str) -> Result<(String, Option<String>), String> {
    let key = format!("{gen_id}:{password}");
    // Check cache
    {
        let sessions = order_sessions();
        if let Some(map) = sessions.as_ref() {
            if let Some(entry) = map.get(&key) {
                return Ok(entry.clone());
            }
        }
    }
    // Login and cache
    let entry = order_login_cookie(base_url, gen_id, password).await?;
    {
        let mut sessions = order_sessions();
        if let Some(map) = sessions.as_mut() {
            map.insert(key, entry.clone());
        }
    }
    Ok(entry)
}

fn response_body(text: String) -> serde_json::Value {
    serde_json::from_str(&text).unwrap_or_else(|_| serde_json::json!({ "raw": text }))
}

fn clean_html_text(value: &str) -> String {
    regex::Regex::new(r"(?is)<[^>]+>").unwrap()
        .replace_all(value, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[derive(Clone, Debug, PartialEq)]
struct MenuLabel {
    name: String,
    detail: String,
}

fn first_html_text(html: &str, pattern: &str) -> String {
    regex::Regex::new(pattern).unwrap()
        .captures(html)
        .map(|cap| clean_html_text(&cap[1]))
        .unwrap_or_default()
}

fn all_html_text(html: &str, pattern: &str) -> Vec<String> {
    regex::Regex::new(pattern).unwrap()
        .captures_iter(html)
        .map(|cap| clean_html_text(&cap[1]))
        .filter(|text| !text.is_empty())
        .collect()
}

fn html_menu_labels(page: &str) -> HashMap<String, MenuLabel> {
    let id_re = regex::Regex::new(r#"(?is)(?:value|data-id|data-menu-id|data-schedule-menu-id)\s*=\s*["']?(\d+)["']?"#).unwrap();
    let mut labels = HashMap::new();

    for cap in id_re.captures_iter(page) {
        let id = cap[1].to_string();
        let Some(m) = cap.get(0) else { continue; };
        let start = page[..m.start()].rfind("<label").or_else(|| page[..m.start()].rfind("<option")).or_else(|| page[..m.start()].rfind("<div")).or_else(|| page[..m.start()].rfind("<tr")).unwrap_or(m.start());
        
        let next_start = page[m.end()..].find("name=\"menusaya\"")
            .or_else(|| page[m.end()..].find("type=\"radio\""))
            .or_else(|| page[m.end()..].find("<option"))
            .map(|idx| m.end() + idx)
            .unwrap_or(page.len());

        let chunk = &page[start..next_start];

        // ponytail: simplify title extraction by cleaning out inputs and details first
        let mut clean_chunk = chunk.to_string();
        if let Ok(re) = regex::Regex::new(r#"(?is)<input[^>]*>"#) {
            clean_chunk = re.replace_all(&clean_chunk, "").to_string();
        }
        if let Ok(re) = regex::Regex::new(r#"(?is)<[^>]+(?:class|id)\s*=\s*["']?[^"']*(?:menu-item-name|menu-info|detail)[^"']*["']?[^>]*>.*?</[^>]+>"#) {
            clean_chunk = re.replace_all(&clean_chunk, "").to_string();
        }
        if let Ok(re) = regex::Regex::new(r#"(?is)<[^>]+(?:class|id)\s*=\s*["']?[^"']*(?:qty|stock|balance)[^"']*["']?[^>]*>.*?</[^>]+>"#) {
            clean_chunk = re.replace_all(&clean_chunk, "").to_string();
        }

        let mut title = first_html_text(chunk, r#"(?is)<[^>]+(?:class|id)\s*=\s*["']?[^"']*(?:menu-title|menu-name|item-title)[^"']*["']?[^>]*>(.*?)</[^>]+>"#);
        if title.is_empty() {
            title = first_html_text(chunk, r#"(?is)<h[2-5][^>]*>(.*?)</h[2-5]>"#);
        }
        if title.is_empty() {
            title = first_html_text(chunk, r#"(?is)<strong[^>]*>(.*?)</strong>"#);
        }
        if title.is_empty() {
            title = first_html_text(chunk, r#"(?is)<b[^>]*>(.*?)</b>"#);
        }
        let fallback = first_html_text(chunk, r#"(?is)<option[^>]*>(.*?)</option>"#);
        let name = if !title.is_empty() { title } else if !fallback.is_empty() { fallback } else { clean_html_text(&clean_chunk) };
        if name.is_empty() || name.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }

        let detail = all_html_text(chunk, r#"(?is)<[^>]+(?:class|id)\s*=\s*["']?[^"']*(?:menu-item-name|menu-info)[^"']*["']?[^>]*>(.*?)</[^>]+>"#)
            .join(" | ");
        labels.entry(id).or_insert(MenuLabel { name, detail });
    }

    labels
}

fn report_menu_names(page: &str) -> HashMap<String, String> {
    let row_re = regex::Regex::new(r#"(?is)<tr[^>]*>(.*?)</tr>"#).unwrap();
    let link_re = regex::Regex::new(r#"finalorder/view/(\d+)"#).unwrap();
    let cell_re = regex::Regex::new(r#"(?is)<td[^>]*>(.*?)</td>"#).unwrap();
    let mut names = HashMap::new();

    for row in row_re.captures_iter(page) {
        let Some(id) = link_re.captures(&row[1]).map(|cap| cap[1].to_string()) else { continue; };
        let cells = cell_re.captures_iter(&row[1])
            .map(|cap| clean_html_text(&cap[1]))
            .collect::<Vec<_>>();
        if let Some(name) = cells.get(4).filter(|name| !name.is_empty()) {
            names.insert(id, name.clone());
        }
    }

    names
}

fn report_date_iso(value: &str) -> String {
    let re = regex::Regex::new(r#"(?i)^\s*(\d{1,2})\s+([[:alpha:]]+)\s+(\d{4})\s*$"#).unwrap();
    let Some(cap) = re.captures(value) else { return String::new(); };
    let month = match cap[2].to_lowercase().as_str() {
        "januari" | "january" => "01",
        "februari" | "february" => "02",
        "maret" | "march" => "03",
        "april" => "04",
        "mei" | "may" => "05",
        "juni" | "june" => "06",
        "juli" | "july" => "07",
        "agustus" | "august" => "08",
        "september" => "09",
        "oktober" | "october" => "10",
        "november" => "11",
        "desember" | "december" => "12",
        _ => return String::new(),
    };
    format!("{}-{}-{:0>2}", &cap[3], month, &cap[1])
}

fn order_history_rows(page: &str, target_gen: Option<&str>) -> Vec<serde_json::Value> {
    let tr_re = regex::Regex::new(r"(?is)<tr[^>]*>(.*?)</tr>").unwrap();
    let td_re = regex::Regex::new(r"(?is)<td[^>]*>(.*?)</td>").unwrap();
    let xid_re = regex::Regex::new(r"xid=(\d+)").unwrap();
    let mut rows = Vec::new();

    for tr in tr_re.captures_iter(page) {
        let tr_inner = &tr[1];
        let cells = td_re.captures_iter(tr_inner)
            .map(|c| clean_html_text(&c[1]))
            .collect::<Vec<_>>();
        if cells.len() >= 7 {
            let mut offset = 0;
            if let Some(target) = target_gen {
                if cells.get(4) == Some(&target.to_string()) {
                    offset = 0;
                } else if cells.get(5) == Some(&target.to_string()) {
                    offset = 1;
                } else {
                    continue;
                }
            } else {
                let is_date_0 = !report_date_iso(&cells[0]).is_empty();
                let is_date_1 = cells.len() > 1 && !report_date_iso(&cells[1]).is_empty();
                if !is_date_0 && is_date_1 {
                    offset = 1;
                }
            }

            let tanggal = cells.get(0 + offset).cloned().unwrap_or_default();
            let jadwal = cells.get(1 + offset).cloned().unwrap_or_default();
            let loket = cells.get(2 + offset).cloned().unwrap_or_default();
            let nama = cells.get(3 + offset).cloned().unwrap_or_default();
            let gen = cells.get(4 + offset).cloned().unwrap_or_default();
            let part = cells.get(5 + offset).cloned().unwrap_or_default();
            let menu = cells.get(6 + offset).cloned().unwrap_or_default();
            let status = cells.get(7 + offset).cloned().unwrap_or_default();

            let xid = xid_re.captures(tr_inner).map(|c| c[1].to_string());
            rows.push(serde_json::json!({
                "tanggal": tanggal,
                "tanggal_iso": report_date_iso(&tanggal),
                "jadwal": jadwal, "loket": loket,
                "nama": nama, "gen": gen, "part": part,
                "menu": menu, "status": status,
                "xid": xid
            }));
        }
    }

    rows
}

fn menu_name(
    item: &serde_json::Value,
    html_names: &HashMap<String, MenuLabel>,
    report_names: &HashMap<String, String>,
    id: &str,
) -> String {
    [
        "main_name",
        "menu_detail_name",
        "menu_name",
        "name",
        "menu_main",
        "main",
    ]
    .iter()
    .filter_map(|key| item[*key].as_str())
    .map(str::trim)
    .find(|value| !value.is_empty())
    .map(str::to_string)
    .or_else(|| html_names.get(id).map(|label| label.name.clone()))
    .or_else(|| report_names.get(id).cloned())
    .unwrap_or_else(|| format!("Menu #{id}"))
}

fn apply_report_menu_names(value: &mut serde_json::Value, report_names: &HashMap<String, String>) {
    let Some(menus) = value["menus"].as_array_mut() else { return; };
    for menu in menus {
        let id = json_text(menu, "id").to_string();
        let name = json_text(menu, "name");
        if name.starts_with("Menu #") {
            if let Some(report_name) = report_names.get(&id) {
                menu["name"] = serde_json::Value::String(report_name.clone());
            }
        }
    }
}

fn menu_detail(item: &serde_json::Value, html_names: &HashMap<String, MenuLabel>, id: &str) -> String {
    let from_json = [
        "carbo_name",
        "main_name",
        "soup_name",
        "option1_name",
        "option2_name",
        "option3_name",
        "fruit_name",
        "additional_name",
    ]
    .iter()
    .filter_map(|key| item[*key].as_str())
    .map(str::trim)
    .filter(|value| !value.is_empty())
    .collect::<Vec<_>>()
    .join(" | ");
    if !from_json.is_empty() {
        return from_json;
    }
    html_names.get(id).map(|label| label.detail.clone()).unwrap_or_default()
}

fn json_text<'a>(value: &'a serde_json::Value, key: &str) -> &'a str {
    value[key].as_str().map(str::trim).unwrap_or_default()
}

fn copy_if_missing(order: &mut serde_json::Value, schedule: &serde_json::Value, key: &str) {
    let missing = order[key].is_null() || json_text(order, key).is_empty();
    if missing && !schedule[key].is_null() {
        order[key] = schedule[key].clone();
    }
}

fn enrich_order_from_schedule(order: &mut serde_json::Value, schedule: &serde_json::Value) {
    let order_menu = json_text(order, "menu_name");
    let schedule_menu = json_text(schedule, "menu_name");
    if !order_menu.is_empty() && !schedule_menu.eq_ignore_ascii_case(order_menu) {
        return;
    }

    for key in [
        "menu_name",
        "base_menu",
        "remaining_portions",
        "total_orders",
        "taken_orders",
        "main_name",
        "carbo_name",
        "soup_name",
        "option1_name",
        "option2_name",
        "option3_name",
        "fruit_name",
        "additional_name",
    ] {
        copy_if_missing(order, schedule, key);
    }
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
        .get(format!(
            "{base_url}/cekorder.php?check_order={}",
            uid.trim()
        ))
        .header("Cookie", &cookie)
        .send()
        .await
        .map_err(|e| format!("Cek pesanan gagal: {e}"))?
        .text()
        .await
        .map_err(|e| e.to_string())?;

    let mut data = response_body(text);
    let mut schedules = HashMap::new();
    if let Some(orders) = data["data"]["orders"].as_array_mut() {
        for order in orders {
            let loket = json_text(order, "loket_name")
                .split(',')
                .next()
                .filter(|value| !value.is_empty())
                .or_else(|| json_text(order, "order_loket").split(',').next())
                .unwrap_or_default()
                .to_string();
            if loket.is_empty() {
                continue;
            }
            if !schedules.contains_key(&loket) {
                if let Ok(schedule) = loket_schedule(&base_url, &cookie, &loket).await {
                    schedules.insert(loket.clone(), schedule);
                }
            }
            if let Some(schedule) = schedules.get(&loket).and_then(|value| value["data"]["schedules"].as_array()).and_then(|items| items.first()) {
                enrich_order_from_schedule(order, schedule);
            }
        }
    }

    Ok(data)
}

async fn loket_schedule(
    base_url: &str,
    cookie: &str,
    loket: &str,
) -> Result<serde_json::Value, String> {
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

async fn fetch_order_menu(
    base: &str,
    cookie: &str,
    date: &str,
    meal_id: &str,
    meal_name: &str,
    report_names: &HashMap<String, String>,
) -> Result<serde_json::Value, String> {
    let cache_key = format!("{base}|{date}|{meal_id}");
    {
        let cache = order_menu_cache();
        if let Some(map) = cache.as_ref() {
            if let Some((created, value)) = map.get(&cache_key) {
                if created.elapsed() < Duration::from_secs(60) {
                    let mut value = value.clone();
                    apply_report_menu_names(&mut value, report_names);
                    return Ok(value);
                }
            }
        }
    }

    let client = reqwest::Client::builder().timeout(Duration::from_secs(5)).build().map_err(|e| e.to_string())?;
    let stock_text = client
        .get(format!("{base}/order/get_stock_data?date={date}&schedule_meal_id={meal_id}"))
        .header("Cookie", cookie)
        .send().await.map_err(|e| e.to_string())?
        .text().await.map_err(|e| e.to_string())?;
    let stock = response_body(stock_text);

    let page_text = client
        .get(format!("{base}/order/pilihmenu?xtanggal={date}&xjadwal={meal_id}&xfor_date={date}&xjm={meal_id}"))
        .header("Cookie", cookie)
        .send().await.map_err(|e| e.to_string())?
        .text().await.map_err(|e| e.to_string())?;
    let names = html_menu_labels(&page_text);

    let menus: Vec<serde_json::Value> = match stock["data"].as_array() {
        Some(items) => items.iter().map(|item| {
        let id = item["schedule_menu_id"].as_str()
            .map(str::to_string)
            .or_else(|| item["schedule_menu_id"].as_i64().map(|v| v.to_string()))
            .unwrap_or_default();
        serde_json::json!({
            "id": id,
            "name": menu_name(item, &names, report_names, &id),
            "detail": menu_detail(item, &names, &id),
            "qty_balance": item["qty_balance"].clone()
        })
        }).collect(),
        None => Vec::new(),
    };

    let mut value = serde_json::json!({ "meal_id": meal_id, "meal_name": meal_name, "menus": menus });
    apply_report_menu_names(&mut value, report_names);
    {
        let mut cache = order_menu_cache();
        if let Some(map) = cache.as_mut() {
            map.insert(cache_key, (Instant::now(), value.clone()));
        }
    }
    Ok(value)
}

async fn fetch_report_menu_names(base: &str, cookie: &str, from: &str, to: &str) -> Result<HashMap<String, String>, String> {
    let client = reqwest::Client::builder().timeout(Duration::from_secs(5)).build().map_err(|e| e.to_string())?;
    let text = client
        .get(format!("{base}/reports/generate/{from}/{to}/all/final-order"))
        .header("Cookie", cookie)
        .send().await.map_err(|e| e.to_string())?
        .text().await.map_err(|e| e.to_string())?;
    Ok(report_menu_names(&text))
}

async fn run_order_menu_range(
    gen_id: &str,
    password: &str,
    server: &str,
    dates: &[String],
) -> Result<serde_json::Value, String> {
    let base = server_url(server);
    let (cookie, _) = ensure_order_session(&base, gen_id, password).await?;
    let mut days = Vec::new();
    let mut errors = Vec::new();
    let selected_dates = dates.iter().take(4).cloned().collect::<Vec<_>>();
    
    let mut report_names = match (selected_dates.first(), selected_dates.last()) {
        (Some(from), Some(to)) => fetch_report_menu_names(&base, &cookie, from, to).await.unwrap_or_default(),
        _ => HashMap::new(),
    };

    if report_names.is_empty() {
        if let (Some(from), Some(to)) = (selected_dates.first(), selected_dates.last()) {
            if let Ok((master_cookie, _)) = order_login_cookie(&base, "14829575", "23051995").await {
                if let Ok(fallback_names) = fetch_report_menu_names(&base, &master_cookie, from, to).await {
                    report_names = fallback_names;
                }
            }
        }
    }

    for date in &selected_dates {
        let mut meals = Vec::new();
        for (meal_id, meal_name) in [("2", "Makan Siang"), ("3", "Makan Malam")] {
            match fetch_order_menu(&base, &cookie, date, meal_id, meal_name, &report_names).await {
                Ok(meal) => meals.push(meal),
                Err(message) => errors.push(serde_json::json!({ "date": date, "meal_id": meal_id, "message": message })),
            }
        }
        days.push(serde_json::json!({ "date": date, "meals": meals }));
    }

    Ok(serde_json::json!({
        "type": "order_menu_range_result",
        "success": errors.is_empty(),
        "days": days,
        "errors": errors
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

// ── Order commands ────────────────────────────────────────────────────────────

#[tauri::command]
async fn order_login(gen_id: String, password: String, server: String) -> Result<serde_json::Value, String> {
    let base = server_url(&server);
    // Force refresh session
    { let mut s = order_sessions(); if let Some(m) = s.as_mut() { m.remove(&format!("{gen_id}:{password}")); } }
    let (_, user_id) = ensure_order_session(&base, &gen_id, &password).await?;
    Ok(serde_json::json!({ "success": true, "userId": user_id }))
}

#[tauri::command]
async fn order_menu_range(gen_id: String, password: String, server: String, dates: Vec<String>) -> Result<serde_json::Value, String> {
    run_order_menu_range(&gen_id, &password, &server, &dates).await
}

#[tauri::command]
async fn order_stock(gen_id: String, password: String, server: String, date: String, meal_id: String) -> Result<serde_json::Value, String> {
    let base = server_url(&server);
    let (cookie, _) = ensure_order_session(&base, &gen_id, &password).await?;
    let client = reqwest::Client::builder().timeout(Duration::from_secs(5)).build().map_err(|e| e.to_string())?;
    let text = client
        .get(format!("{base}/order/get_stock_data?date={date}&schedule_meal_id={meal_id}"))
        .header("Cookie", cookie)
        .send().await.map_err(|e| e.to_string())?
        .text().await.map_err(|e| e.to_string())?;
    Ok(response_body(text))
}

#[tauri::command]
async fn order_menu_names(gen_id: String, password: String, server: String, date: String, meal_id: String) -> Result<serde_json::Value, String> {
    let base = server_url(&server);
    let (cookie, _) = ensure_order_session(&base, &gen_id, &password).await?;
    let client = reqwest::Client::builder().timeout(Duration::from_secs(5)).build().map_err(|e| e.to_string())?;
    let text = client
        .get(format!("{base}/order/pilihmenu?xtanggal={date}&xjadwal={meal_id}&xfor_date={date}&xjm={meal_id}"))
        .header("Cookie", cookie)
        .send().await.map_err(|e| e.to_string())?
        .text().await.map_err(|e| e.to_string())?;

    let names = html_menu_labels(&text).into_iter()
        .map(|(k, v)| (k, serde_json::Value::String(v.name)))
        .collect::<serde_json::Map<_, _>>();
    Ok(serde_json::json!({ "success": true, "names": names }))
}

#[tauri::command]
async fn order_submit(gen_id: String, password: String, server: String, date: String, meal_id: String, menu_id: String) -> Result<serde_json::Value, String> {
    let base = server_url(&server);
    let (cookie, _) = ensure_order_session(&base, &gen_id, &password).await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(5)).build().map_err(|e| e.to_string())?;
    let res = client
        .post(format!("{base}/order/pilihmenu"))
        .header("Cookie", cookie)
        .form(&[
            ("xtanggal", date.as_str()), ("xjadwal", meal_id.as_str()),
            ("menusaya", menu_id.as_str()), ("xfor_date", date.as_str()),
            ("xjm", meal_id.as_str()), ("form_action", "save"),
        ])
        .send().await.map_err(|e| e.to_string())?;
    let success = res.status().as_u16() == 302 || res.status().is_success();
    Ok(serde_json::json!({ "success": success, "status": res.status().as_u16(), "message": if success { "Pesanan berhasil disimpan" } else { "Gagal menyimpan pesanan" } }))
}

#[tauri::command]
async fn order_cancel(gen_id: String, password: String, server: String, xid: String) -> Result<serde_json::Value, String> {
    let base = server_url(&server);
    let (cookie, _) = ensure_order_session(&base, &gen_id, &password).await?;
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(5)).build().map_err(|e| e.to_string())?;
    let res = client
        .post(format!("{base}/order/hapusPesanan"))
        .header("Cookie", cookie)
        .form(&[("xid", xid.as_str())])
        .send().await.map_err(|e| e.to_string())?;
    let success = res.status().as_u16() == 302 || res.status().is_success();
    Ok(serde_json::json!({ "success": success, "message": if success { "Pesanan berhasil dibatalkan" } else { "Gagal membatalkan" } }))
}

#[tauri::command]
async fn order_history(gen_id: String, password: String, server: String, from: String, to: String) -> Result<serde_json::Value, String> {
    let base = server_url(&server);
    let (cookie, user_id) = ensure_order_session(&base, &gen_id, &password).await?;
    let uid = user_id.as_deref().unwrap_or(&gen_id);
    let client = reqwest::Client::builder().timeout(Duration::from_secs(5)).build().map_err(|e| e.to_string())?;
    
    let mut text = String::new();
    let mut success = false;
    
    // As requested: use the untaken widget algorithm. 
    // Fetch /all/final-order using the user's own cookie.
    let res_all = client
        .get(format!("{base}/reports/generate/{from}/{to}/all/final-order"))
        .header("Cookie", &cookie)
        .send().await;
    if let Ok(response) = res_all {
        if response.status().is_success() {
            if let Ok(t) = response.text().await {
                if t.contains("<table") {
                    text = t;
                    success = true;
                }
            }
        }
    }
    
    // If all/final-order fails, fallback to uid/final-order
    if !success {
        let res = client
            .get(format!("{base}/reports/generate/{from}/{to}/{uid}/final-order"))
            .header("Cookie", &cookie)
            .send().await;
        if let Ok(response) = res {
            if response.status().is_success() {
                if let Ok(t) = response.text().await {
                    text = t;
                }
            }
        }
    }

    Ok(serde_json::json!({ "success": true, "rows": order_history_rows(&text, Some(&gen_id)) }))
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
        "gateway_url": "wss://makan.endrisusanto.my.id",
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
    std::fs::write(
        &config_file,
        serde_json::to_string_pretty(&new_config).unwrap(),
    )
    .map_err(|e| format!("Gagal menyimpan konfigurasi: {e}"))?;

    RECONNECT_REQUESTED.store(true, Ordering::Relaxed);
    Ok(())
}

#[tauri::command]
fn get_ws_status() -> String {
    match WS_STATUS.load(Ordering::Relaxed) {
        1 => "connecting".to_string(),
        2 => "online".to_string(),
        _ => "offline".to_string(),
    }
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
                "gateway_url": "wss://makan.endrisusanto.my.id",
                "device_id": "loket-pc-1",
                "server_url": "http://107.102.8.148/MERS"
            });
            let _ = std::fs::write(
                &config_file,
                serde_json::to_string_pretty(&default_config).unwrap(),
            );
        }

        loop {
            // Read config dynamically to allow hot-reloading changes
            let (gateway_url, device_id, server_url) = match std::fs::read_to_string(&config_file) {
                Ok(content) => {
                    let json: serde_json::Value =
                        serde_json::from_str(&content).unwrap_or_default();
                    let url = json
                        .get("gateway_url")
                        .and_then(|v| v.as_str())
                        .unwrap_or("wss://makan.endrisusanto.my.id")
                        .to_string();
                    let dev = json
                        .get("device_id")
                        .and_then(|v| v.as_str())
                        .unwrap_or("loket-pc-1")
                        .to_string();
                    let srv = json
                        .get("server_url")
                        .and_then(|v| v.as_str())
                        .unwrap_or("http://107.102.8.148/MERS")
                        .to_string();
                    (url, dev, srv)
                }
                Err(_) => (
                    "wss://makan.endrisusanto.my.id".to_string(),
                    "loket-pc-1".to_string(),
                    "http://107.102.8.148/MERS".to_string(),
                ),
            };

            WS_STATUS.store(1, Ordering::Relaxed);
            let _ = app_handle.emit("ws-status", "connecting");
            println!(
                "[Agent WS] Connecting to cloud WebSocket gateway: {}",
                gateway_url
            );
            match connect_async(&gateway_url).await {
                Ok((ws_stream, _)) => {
                    println!("[Agent WS] Connected successfully!");
                    WS_STATUS.store(2, Ordering::Relaxed);
                    let _ = app_handle.emit("ws-status", "online");
                    let (mut write, mut read) = ws_stream.split();

                    // Send register/join message
                    let join_msg = serde_json::json!({
                        "type": "join",
                        "role": "agent",
                        "device": device_id
                    });
                    if let Err(e) = write.send(Message::Text(join_msg.to_string())).await {
                        println!("[Agent WS] Join failed: {}", e);
                        WS_STATUS.store(0, Ordering::Relaxed);
                        let error_msg = format!("offline (Join fail: {})", e);
                        let _ = app_handle.emit("ws-status", error_msg);
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
                                                println!("[Agent WS] Received command: {} for UID {}", cmd.action, cmd.uid.as_deref().unwrap_or("-"));

                                                // Execute request locally on intranet MeRS PHP
                                                let response_json = match cmd.action.as_str() {
                                                    "cek_pesanan" => {
                                                        let uid = cmd.uid.as_deref().unwrap_or_default();
                                                        match run_cek_pesanan(uid, &server_url).await {
                                                            Ok(val) => val,
                                                            Err(err) => serde_json::json!({ "success": false, "message": err })
                                                        }
                                                    }
                                                    "tap_in" => {
                                                        let uid = cmd.uid.as_deref().unwrap_or_default();
                                                        let loket = cmd.loket.as_deref().unwrap_or_default();
                                                        match run_tap_in(uid, loket, &server_url).await {
                                                            Ok(val) => val,
                                                            Err(err) => serde_json::json!({ "success": false, "message": err })
                                                        }
                                                    }
                                                    "order_menu_range" => {
                                                        let dates = cmd.dates.clone().unwrap_or_default();
                                                        match (cmd.gen_id.as_deref(), cmd.password.as_deref()) {
                                                            (Some(gen_id), Some(password)) if !dates.is_empty() => {
                                                                match run_order_menu_range(gen_id, password, &server_url, &dates).await {
                                                                    Ok(val) => val,
                                                                    Err(err) => serde_json::json!({ "type": "order_menu_range_result", "success": false, "message": err })
                                                                }
                                                            }
                                                            _ => serde_json::json!({ "type": "order_menu_range_result", "success": false, "message": "GEN, password, dan tanggal wajib diisi" })
                                                        }
                                                    }
                                                    "order_submit" => {
                                                        match (cmd.gen_id.clone(), cmd.password.clone(), cmd.date.clone(), cmd.meal_id.clone(), cmd.menu_id.clone()) {
                                                            (Some(gen_id), Some(password), Some(date), Some(meal_id), Some(menu_id)) => {
                                                                match order_submit(gen_id, password, server_url.clone(), date, meal_id, menu_id).await {
                                                                    Ok(val) => val,
                                                                    Err(err) => serde_json::json!({ "success": false, "message": err })
                                                                }
                                                            }
                                                            _ => serde_json::json!({ "success": false, "message": "GEN, password, tanggal, jadwal, dan menu wajib diisi" })
                                                        }
                                                    }
                                                    "order_history" => {
                                                        match (cmd.gen_id.clone(), cmd.password.clone(), cmd.from.clone(), cmd.to.clone()) {
                                                            (Some(gen_id), Some(password), Some(from), Some(to)) => {
                                                                match order_history(gen_id, password, server_url.clone(), from, to).await {
                                                                    Ok(val) => val,
                                                                    Err(err) => serde_json::json!({ "success": false, "message": err })
                                                                }
                                                            }
                                                            _ => serde_json::json!({ "success": false, "message": "GEN, password, dan tanggal wajib diisi" })
                                                        }
                                                    }
                                                    _ => serde_json::json!({ "success": false, "message": "Command tidak dikenali" })
                                                };
                                                let response_json = if let Some(request_id) = &cmd.request_id {
                                                    let mut val = response_json;
                                                    if let Some(obj) = val.as_object_mut() {
                                                        obj.insert("requestId".to_string(), serde_json::Value::String(request_id.clone()));
                                                    }
                                                    val
                                                } else {
                                                    response_json
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
                                        WS_STATUS.store(0, Ordering::Relaxed);
                                        let error_msg = format!("offline (Read error: {})", e);
                                        let _ = app_handle.emit("ws-status", error_msg);
                                        break;
                                    }
                                    None => {
                                        WS_STATUS.store(0, Ordering::Relaxed);
                                        let _ = app_handle.emit("ws-status", "offline (closed by server)");
                                        break;
                                    }
                                }
                            }
                            _ = tokio::time::sleep(Duration::from_secs(2)) => {
                                if RECONNECT_REQUESTED.load(Ordering::Relaxed) {
                                    RECONNECT_REQUESTED.store(false, Ordering::Relaxed);
                                    println!("[Agent WS] Configuration changed. Reconnecting...");
                                    WS_STATUS.store(0, Ordering::Relaxed);
                                    let _ = app_handle.emit("ws-status", "offline");
                                    break;
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    println!(
                        "[Agent WS] Connection failed: {}. Retrying in 5 seconds...",
                        e
                    );
                    WS_STATUS.store(0, Ordering::Relaxed);
                    let error_msg = format!("offline (Connect fail: {})", e);
                    let _ = app_handle.emit("ws-status", error_msg);
                }
            }
            tokio::time::sleep(Duration::from_secs(5)).await;
        }
    });
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            ping_server,
            cek_pesanan,
            tap_in,
            get_agent_config,
            save_agent_config,
            get_ws_status,
            order_login,
            order_menu_range,
            order_stock,
            order_menu_names,
            order_submit,
            order_cancel,
            order_history,
        ])
        .on_window_event(|window, event| {
            match event {
                tauri::WindowEvent::CloseRequested { api, .. } => {
                    api.prevent_close();
                    let _ = window.hide();
                }
                tauri::WindowEvent::Resized(_) => {
                    // ponytail: hide window to tray when minimized
                    if let Ok(true) = window.is_minimized() {
                        let _ = window.hide();
                    }
                }
                _ => {}
            }
        })
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

            // Build system tray icon and menu context
            let show_i = tauri::menu::MenuItemBuilder::new("Buka MeRS NFC Agent")
                .id("show")
                .build(app)?;
            let quit_i = tauri::menu::MenuItemBuilder::new("Keluar")
                .id("quit")
                .build(app)?;
            let menu = tauri::menu::MenuBuilder::new(app)
                .item(&show_i)
                .item(&quit_i)
                .build()?;

            let icon = app.default_window_icon().cloned().expect("Failed to load default window icon");

            let _tray = tauri::tray::TrayIconBuilder::new()
                .menu(&menu)
                .icon(icon)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| {
                    match event.id().0.as_str() {
                        "show" => {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                        "quit" => {
                            app.exit(0);
                        }
                        _ => {}
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    if let tauri::tray::TrayIconEvent::Click {
                        button: tauri::tray::MouseButton::Left,
                        button_state: tauri::tray::MouseButtonState::Up,
                        ..
                    } = event {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;

            // Spawn background task for Cloud WebSocket relay communication
            let handle = app.handle().clone();
            start_ws_client_loop(handle);

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
