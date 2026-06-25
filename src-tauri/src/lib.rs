use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

const SCANNER_URL: &str = "http://107.102.8.148/MERS/nfc_scanner.html";
const CEK_PESANAN_URL: &str = "http://107.102.8.148/MERS/cek_menu.html";
const LOGIN_IDENTITY: &str = "16756586";
const LOGIN_PASSWORD: &str = "27051994";

fn init_script() -> String {
    let bridge = include_str!("../../bridge.js");

    format!(
        r#"
(() => {{
  function autoLogin() {{
    const identity = document.querySelector('#identity, [name="identity"], #username, [name="username"]');
    const password = document.querySelector('#password, [name="password"]');
    const form = identity && (identity.form || document.querySelector('form'));
    if (!identity || !password || !form || form.dataset.mersAutoLogin === '1') return;
    form.dataset.mersAutoLogin = '1';
    identity.value = {identity};
    password.value = {password};
    form.submit();
  }}

  function injectDesktopShell() {{
    if (!location.href.startsWith('http://107.102.8.148/')) return;
    if (location.pathname.includes('/auth/login') || document.getElementById('mers-desktop-shell')) return;
    const style = document.createElement('style');
    style.textContent = `
      #mers-desktop-shell {{
        position: fixed; inset: 0; z-index: 2147483647; display: grid; place-items: center;
        background: radial-gradient(circle at top, rgba(255,255,255,.10), transparent 34%), #030303;
        color: #f5f5f5; backdrop-filter: blur(18px);
        font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      }}
      #mers-desktop-shell .panel {{
        width: min(860px, calc(100vw - 48px)); padding: 34px;
        background: rgba(255,255,255,.075); border: 1px solid rgba(255,255,255,.18);
        box-shadow: 0 24px 80px rgba(0,0,0,.55); border-radius: 18px;
      }}
      #mers-desktop-shell h1 {{ margin: 0 0 8px; font-size: 28px; font-weight: 850; letter-spacing: 0; text-align: center; }}
      #mers-desktop-shell p {{ margin: 0 0 28px; color: #bdbdbd; text-align: center; }}
      #mers-desktop-shell input {{
        display: block; width: min(360px, 100%); height: 58px; margin: 0 auto 26px; text-align: center;
        border-radius: 12px; border: 1px solid rgba(255,255,255,.28); background: rgba(0,0,0,.42);
        color: #fff; font: 800 26px ui-monospace, Consolas, monospace; outline: none;
      }}
      #mers-desktop-shell input:focus {{ border-color: rgba(255,255,255,.72); }}
      #mers-desktop-shell .cards {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }}
      #mers-desktop-shell .card, #mers-desktop-shell .loket {{
        border: 1px solid rgba(255,255,255,.18); background: rgba(255,255,255,.08); color: #fff;
        border-radius: 16px; cursor: pointer; transition: transform .12s ease, background .12s ease, border-color .12s ease;
      }}
      #mers-desktop-shell .card {{ min-height: 168px; padding: 24px; text-align: left; }}
      #mers-desktop-shell .card:hover, #mers-desktop-shell .loket:hover {{ transform: translateY(-2px); background: rgba(255,255,255,.14); border-color: rgba(255,255,255,.42); }}
      #mers-desktop-shell .card strong {{ display: block; font-size: 24px; margin-bottom: 10px; }}
      #mers-desktop-shell .card span {{ color: #cfcfcf; font-size: 14px; line-height: 1.45; }}
      #mers-desktop-shell .lokets {{ display: none; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-top: 20px; }}
      #mers-desktop-shell .loket {{ height: 82px; font-size: 22px; font-weight: 850; }}
      #mers-desktop-shell .tap-screen {{ display: none; }}
      #mers-desktop-shell[data-step="tap"] .tap-screen {{ display: block; }}
      #mers-desktop-shell[data-step="tap"] .home-screen {{ display: none; }}
      #mers-desktop-shell[data-step="tap"] .lokets {{ display: grid; }}
      #mers-desktop-shell .selected-id {{
        margin: 0 0 18px; padding: 16px; border-radius: 14px;
        background: rgba(0,0,0,.32); border: 1px solid rgba(255,255,255,.14); text-align: center;
      }}
      #mers-desktop-shell .selected-id b {{ display: block; margin-top: 4px; font: 850 30px ui-monospace, Consolas, monospace; }}
      #mers-desktop-shell .back {{
        margin-top: 18px; height: 42px; padding: 0 16px; border-radius: 10px;
        border: 1px solid rgba(255,255,255,.18); background: transparent; color: #fff; cursor: pointer;
      }}
      #mers-desktop-shell .hint {{ min-height: 22px; margin-top: 18px; color: #d4d4d4; text-align: center; }}
      #mers-desktop-home {{
        position: fixed; z-index: 2147483646; right: 18px; bottom: 18px; height: 42px; padding: 0 16px;
        border-radius: 999px; border: 1px solid rgba(255,255,255,.22); background: rgba(0,0,0,.62);
        color: #fff; font-weight: 750; cursor: pointer; backdrop-filter: blur(12px);
      }}
    `;
    document.head.appendChild(style);

    const shell = document.createElement('div');
    shell.id = 'mers-desktop-shell';
    shell.dataset.step = 'home';
    shell.innerHTML = `
      <div class="panel">
        <div class="home-screen">
          <h1>MeRS Desktop</h1>
          <p>Masukkan ID 8 digit yang akan dieksekusi.</p>
          <input id="mers-desktop-uid" inputmode="numeric" maxlength="8" autocomplete="off" placeholder="16756586">
          <div class="cards">
            <button class="card" data-action="tap"><strong>Tap In Scanner</strong><span>Masuk ke pilihan loket untuk ID ini.</span></button>
            <button class="card" data-action="cek"><strong>Cek Menu Pesanan</strong><span>Langsung tampilkan detail pesanan ID ini.</span></button>
          </div>
        </div>
        <div class="tap-screen">
          <h1>Tap In Scanner</h1>
          <div class="selected-id">ID yang akan dieksekusi<b id="mers-selected-id"></b></div>
          <div class="lokets">
            <button class="loket" data-loket="1">Loket 1</button>
            <button class="loket" data-loket="2">Loket 2</button>
            <button class="loket" data-loket="3">Loket 3</button>
            <button class="loket" data-loket="4">Loket 4</button>
            <button class="loket" data-loket="5">Loket 5</button>
            <button class="loket" data-loket="6">Loket 6</button>
          </div>
          <button class="back" type="button">Kembali</button>
        </div>
        <div class="hint" id="mers-desktop-hint"></div>
      </div>
    `;
    document.body.appendChild(shell);

    const home = document.createElement('button');
    home.id = 'mers-desktop-home';
    home.textContent = 'Beranda';
    home.addEventListener('click', () => {{
      shell.style.display = 'grid';
      shell.dataset.step = 'home';
      hint.textContent = '';
      shell.querySelector('#mers-desktop-uid').focus();
    }});
    document.body.appendChild(home);

    const hint = shell.querySelector('#mers-desktop-hint');
    const uidValue = () => {{
      const input = shell.querySelector('#mers-desktop-uid');
      input.value = input.value.replace(/\D/g, '').slice(0, 8);
      const uid = input.value.trim();
      if (/^\d{{8}}$/.test(uid)) return uid;
      hint.textContent = 'ID harus 8 digit angka.';
      input.focus();
      return '';
    }};

    function showContent() {{
      shell.style.display = 'none';
    }}

    function runWhenReady(callback) {{
      let tries = 0;
      const timer = setInterval(() => {{
        tries += 1;
        if (typeof window.processNFC === 'function' || tries > 80) {{
          clearInterval(timer);
          callback();
        }}
      }}, 100);
    }}

    function runPending() {{
      const pending = JSON.parse(window.name || 'null');
      if (!pending) return;
      window.name = '';
      showContent();
      runWhenReady(() => {{
        if (pending.mode === 'tap') {{
          const loket = document.querySelector(`.loket-btn[data-loket="${{pending.loket}}"]`);
          if (loket) loket.click();
          setTimeout(() => window.dispatchEvent(new CustomEvent('mrs-nfc-call', {{ detail: {{ uid: pending.uid }} }})), 700);
        }} else {{
          window.dispatchEvent(new CustomEvent('mrs-nfc-call', {{ detail: {{ uid: pending.uid }} }}));
        }}
      }});
    }}

    shell.querySelector('#mers-desktop-uid').addEventListener('input', uidValue);
    shell.querySelector('[data-action="cek"]').addEventListener('click', () => {{
      const uid = uidValue();
      if (!uid) return;
      window.name = JSON.stringify({{ mode: 'cek', uid }});
      if (location.href !== {cek_pesanan_url}) location.href = {cek_pesanan_url};
      else runPending();
    }});
    shell.querySelector('[data-action="tap"]').addEventListener('click', () => {{
      const uid = uidValue();
      if (!uid) return;
      shell.dataset.uid = uid;
      shell.querySelector('#mers-selected-id').textContent = uid;
      hint.textContent = 'Pilih loket.';
      shell.dataset.step = 'tap';
    }});
    shell.querySelector('.back').addEventListener('click', () => {{
      hint.textContent = '';
      shell.dataset.step = 'home';
      shell.querySelector('#mers-desktop-uid').focus();
    }});
    shell.querySelectorAll('[data-loket]').forEach(button => button.addEventListener('click', () => {{
      const uid = shell.dataset.uid || uidValue();
      if (!uid) return;
      window.name = JSON.stringify({{ mode: 'tap', uid, loket: button.dataset.loket }});
      if (location.href !== {scanner_url}) location.href = {scanner_url};
      else runPending();
    }});
    runPending();
    shell.querySelector('#mers-desktop-uid').focus();
  }}

  document.addEventListener('DOMContentLoaded', () => {{
    autoLogin();
    injectDesktopShell();
  }});
  setInterval(autoLogin, 1000);
}})();
{bridge}
"#,
        identity = serde_json::to_string(LOGIN_IDENTITY).unwrap(),
        password = serde_json::to_string(LOGIN_PASSWORD).unwrap(),
        scanner_url = serde_json::to_string(SCANNER_URL).unwrap(),
        cek_pesanan_url = serde_json::to_string(CEK_PESANAN_URL).unwrap(),
        bridge = bridge
    )
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?.join("webview");
            std::fs::create_dir_all(&data_dir)?;

            WebviewWindowBuilder::new(
                app,
                "main",
                WebviewUrl::App("index.html".into()),
            )
            .title("MeRS NFC Desktop")
            .inner_size(1280.0, 800.0)
            .data_directory(data_dir)
            .initialization_script(init_script())
            .build()?;

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
