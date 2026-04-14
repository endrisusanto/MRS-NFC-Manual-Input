(function () {
  'use strict';

  const MAX_RECENT = 8;
  const STORAGE_KEY_RECENT = 'mrs_nfc_recent_v2';
  const STORAGE_KEY_PINNED = 'mrs_nfc_pinned_v2';

  // --- STORAGE UTILS (Using chrome.storage for sync) ---
  function getStorageData(key, callback) {
    chrome.storage.local.get([key], (result) => {
      callback(result[key] ? JSON.parse(result[key]) : []);
    });
  }

  function setStorageData(key, data, callback) {
    chrome.storage.local.set({ [key]: JSON.stringify(data) }, callback);
  }

  function saveRecentID(uid) {
    getStorageData(STORAGE_KEY_RECENT, (recent) => {
      recent = recent.filter(item => item.id !== uid);
      recent.unshift({ id: uid, time: new Date().toLocaleTimeString() });
      if (recent.length > MAX_RECENT) recent.length = MAX_RECENT;
      setStorageData(STORAGE_KEY_RECENT, recent, renderLists);
    });
  }

  function togglePin(uid) {
    getStorageData(STORAGE_KEY_PINNED, (pinned) => {
      const existing = pinned.find(p => p.id === uid);
      if (existing) {
          pinned = pinned.filter(p => p.id !== uid);
          setStorageData(STORAGE_KEY_PINNED, pinned, renderLists);
      } else {
          const alias = window.prompt(`Masukkan Nama Alias untuk UID: ${uid}`, "ID Baru");
          if (alias === null) return;
          pinned.push({ id: uid, alias: alias || uid, time: new Date().toLocaleTimeString() });
          setStorageData(STORAGE_KEY_PINNED, pinned, renderLists);
      }
    });
  }

  // --- UI CONSTRUCTION ---
  function injectUI() {
    if (document.getElementById('mrs-nfc-fab')) return;

    const toast = document.createElement('div');
    toast.id = 'mrs-toast';
    document.body.appendChild(toast);

    const fab = document.createElement('button');
    fab.id = 'mrs-nfc-fab';
    fab.innerHTML = '📟';
    fab.addEventListener('click', togglePanel);
    document.body.appendChild(fab);

    const panel = document.createElement('div');
    panel.id = 'mrs-nfc-panel';
    panel.innerHTML = `
      <div class="mrs-panel-header">
        <span class="mrs-header-title">MeRS Manual Input</span>
        <div class="mrs-close-btn" id="mrs-close-panel">×</div>
      </div>
      <div class="mrs-panel-body">
        <div class="mrs-stats-box">
          <span class="mrs-stats-label">Sisa Porsi (Live)</span>
          <span class="mrs-stats-value" id="mrs-live-quota">---</span>
        </div>

        <div class="mrs-input-group">
          <label class="mrs-input-label">UID / Serial</label>
          <input type="text" id="mrs-manual-uid" class="mrs-input-field" placeholder="1234567890">
          <div style="display:flex; gap:8px;">
            <button id="mrs-pin-uid" class="mrs-primary-btn" style="flex:0; padding:0 15px; background:#fef3c7" title="Pin ID ini">📌</button>
            <button id="mrs-submit-uid" class="mrs-primary-btn" style="flex:1">🔍 TAP IN MeRS!</button>
          </div>
        </div>

        <div class="mrs-history-section" id="mrs-pinned-sec" style="display:none; border-top: none; padding-top: 0;">
          <div class="mrs-history-title" style="color:#b45309">📌 Disematkan (Pinned)</div>
          <div class="mrs-history-list" id="mrs-pinned-list"></div>
        </div>

        <div class="mrs-history-section" id="mrs-history-sec">
          <div class="mrs-history-title">Riwayat Terakhir</div>
          <div class="mrs-history-list" id="mrs-history-list"></div>
        </div>
      </div>
    `;
    document.body.appendChild(panel);

    document.getElementById('mrs-close-panel').addEventListener('click', togglePanel);
    document.getElementById('mrs-submit-uid').addEventListener('click', () => {
        const val = document.getElementById('mrs-manual-uid').value.trim();
        if (val) processUID(val);
    });
    document.getElementById('mrs-pin-uid').addEventListener('click', () => {
        const val = document.getElementById('mrs-manual-uid').value.trim();
        if (val) togglePin(val);
        else showToast("Masukkan nomor dulu!");
    });
    document.getElementById('mrs-manual-uid').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            const val = e.target.value.trim();
            if (val) processUID(val);
        }
    });

    renderLists();
  }

  function togglePanel() {
    const panel = document.getElementById('mrs-nfc-panel');
    const isVisible = panel.style.display === 'flex';
    panel.style.display = isVisible ? 'none' : 'flex';
    if (!isVisible) {
        document.getElementById('mrs-manual-uid').focus();
        renderLists(); // Refresh when opening
    }
  }

  function showToast(msg, duration = 2500) {
    const toast = document.getElementById('mrs-toast');
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add('mrs-show');
    setTimeout(() => toast.classList.remove('mrs-show'), duration);
  }

  function renderLists() {
    // Render Pinned
    getStorageData(STORAGE_KEY_PINNED, (pinned) => {
        const pinnedList = document.getElementById('mrs-pinned-list');
        const pinnedSec = document.getElementById('mrs-pinned-sec');
        if (pinned.length > 0) {
            pinnedSec.style.display = 'block';
            pinnedList.innerHTML = pinned.map(item => `
              <div class="mrs-chip" style="background:#fffbeb; border-color:#f59e0b" data-uid="${item.id}" title="${item.id}">
                <b>${item.alias}</b>
              </div>
            `).join('');
        } else {
            pinnedSec.style.display = 'none';
        }
        bindClicks();
    });

    // Render History
    getStorageData(STORAGE_KEY_RECENT, (recent) => {
        const list = document.getElementById('mrs-history-list');
        if (recent.length === 0) {
            list.innerHTML = '<span style="font-size:11px; color:#999">Belum ada riwayat</span>';
        } else {
            list.innerHTML = recent.map(item => `
              <div class="mrs-chip" data-uid="${item.id}">${item.id}</div>
            `).join('');
        }
        bindClicks();
    });
  }

  function bindClicks() {
    document.querySelectorAll('.mrs-chip').forEach(chip => {
      // Menghapus listener lama
      const newChip = chip.cloneNode(true);
      chip.parentNode.replaceChild(newChip, chip);
      newChip.addEventListener('click', () => {
        processUID(newChip.dataset.uid);
      });
    });
  }

  function processUID(uid) {
    if (!uid) return;
    saveRecentID(uid);
    window.dispatchEvent(new CustomEvent('mrs-nfc-call', { detail: { uid: uid } }));
    showToast(`Mengirim ${uid}...`);
    const input = document.getElementById('mrs-manual-uid');
    if (input) input.value = '';
  }

  function startQuotaPolling() {
      setInterval(() => {
          const stepNfc = document.getElementById('step-nfc');
          if (!stepNfc || stepNfc.classList.contains('hidden')) return; 
          const activeLoketBtn = document.querySelector('.loket-btn.bg-emerald-500');
          if (!activeLoketBtn) return;
          const loketId = activeLoketBtn.getAttribute('data-loket');
          const apiUrl = "http://107.102.8.148/MERS/";
          
          fetch(`${apiUrl}cekorder.php?loket=${loketId}`, {cache: 'no-store'})
              .then(res => res.json())
              .then(data => {
                  if (data.success && data.data && data.data.remaining_count !== undefined) {
                      updateUIQuota(data.data.remaining_count);
                  } else {
                      return fetch(`${apiUrl}display/index/${loketId}`, {cache: 'no-store'}).then(res => res.text());
                  }
              })
              .then(html => {
                  if (typeof html === 'string') {
                      const match = html.match(/Sisa Porsi\D*(\d+)/i);
                      if (match && match[1]) updateUIQuota(match[1]);
                  }
              })
              .catch(() => {});
      }, 5000);
  }

  function updateUIQuota(val) {
      const extCounter = document.getElementById('mrs-live-quota');
      if (extCounter) extCounter.textContent = val;
  }

  injectUI();
  startQuotaPolling();

  // Escucha cambios de almacenamiento para sincronizar en vivo
  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName === 'local') renderLists();
  });
})();
