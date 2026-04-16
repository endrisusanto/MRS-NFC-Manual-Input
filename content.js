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

  // --- BENTO MENU PANEL ---
  let lastScheduleData = null;

  function injectBentoPanel() {
    if (document.getElementById('mrs-bento-panel')) return;
    const bento = document.createElement('div');
    bento.id = 'mrs-bento-panel';
    bento.style.display = 'none';
    bento.innerHTML = `
      <div class="mrs-bento-header">
        <span class="mrs-bento-title" id="mrs-bento-menu-name">Menu Hari Ini</span>
        <span class="mrs-bento-badge" id="mrs-bento-quota">---</span>
      </div>
      <div class="mrs-bento-grid" id="mrs-bento-grid"></div>
    `;
    document.body.appendChild(bento);
  }

  function renderBento(schedule) {
    const bento = document.getElementById('mrs-bento-panel');
    if (!bento) return;

    const BASE_IMG = 'http://107.102.8.148/MERS/assets/images/menu/';
    const items = [
      { label: 'Karbo',      name: schedule.carbo_name,      pic: schedule.carbo_pic,      cat: schedule.carbo_cat,      color: '#fef9c3', accent: '#ca8a04' },
      { label: 'Utama',      name: schedule.main_name,       pic: schedule.menu_detail_pic, cat: schedule.main_cat,       color: '#dcfce7', accent: '#16a34a' },
      { label: 'Sayur',      name: schedule.soup_name,       pic: schedule.soup_pic,        cat: schedule.soup_cat,       color: '#dbeafe', accent: '#2563eb' },
      { label: 'Pendamping', name: schedule.option1_name,    pic: schedule.option1_pic,     cat: schedule.option1_cat,    color: '#fce7f3', accent: '#db2777' },
      { label: 'Sambal',     name: schedule.option3_name,    pic: schedule.option3_pic,     cat: schedule.option3_cat,    color: '#ffedd5', accent: '#ea580c' },
      { label: 'Buah',       name: schedule.fruit_name,      pic: schedule.fruit_pic,       cat: schedule.fruit_cat,      color: '#ede9fe', accent: '#7c3aed' },
      { label: 'Minuman',    name: schedule.additional_name, pic: schedule.additional_pic,  cat: schedule.additional_cat, color: '#e0f2fe', accent: '#0284c7' },
    ].filter(item => item.name && item.name !== '-' && item.name.trim() !== '');

    document.getElementById('mrs-bento-menu-name').textContent = schedule.menu_name || 'Menu Hari Ini';
    document.getElementById('mrs-bento-quota').textContent = `🍽 ${schedule.remaining_portions} porsi`;

    const grid = document.getElementById('mrs-bento-grid');
    grid.innerHTML = items.map(item => `
      <div class="mrs-bento-card" style="background:${item.color}; border-color:${item.accent}">
        <div class="mrs-bento-img-wrap">
          <img src="${BASE_IMG}${item.pic}" alt="${item.name}"
               onerror="this.style.display='none'; this.nextElementSibling.style.display='flex'"
               class="mrs-bento-img">
          <div class="mrs-bento-img-fallback" style="display:none; color:${item.accent}">🍴</div>
        </div>
        <div class="mrs-bento-label" style="color:${item.accent}">${item.label}</div>
        <div class="mrs-bento-name">${item.name}</div>
      </div>
    `).join('');

    bento.style.display = 'flex';
  }

  function hideBento() {
    const bento = document.getElementById('mrs-bento-panel');
    if (bento) bento.style.display = 'none';
  }

  // --- PE UNTAKEN PANEL ---
  let untakenPolling = null;

  function injectUntakenPanel() {
    if (document.getElementById('mrs-untaken-panel')) return;
    const panel = document.createElement('div');
    panel.id = 'mrs-untaken-panel';
    panel.style.display = 'none';
    panel.innerHTML = `
      <div class="mrs-untaken-header">
        <span class="mrs-untaken-title">⚠️ PE Untaken Orders</span>
        <span class="mrs-untaken-badge" id="mrs-pe-count">0</span>
      </div>
      <div class="mrs-untaken-body">
         <table class="mrs-untaken-table">
           <thead>
             <tr>
               <th>Nama</th>
               <th>Jadwal</th>
             </tr>
           </thead>
           <tbody id="mrs-untaken-tbody">
             <tr><td colspan="2" style="text-align:center; padding:20px;">Memuat data...</td></tr>
           </tbody>
         </table>
      </div>
      <div class="mrs-untaken-footer">
        📅 ${new Date().toLocaleDateString('id-ID')} | <span id="mrs-pe-update-time">--:--</span>
      </div>
    `;
    document.body.appendChild(panel);
  }

  function fetchUntakenOrders() {
    const now = new Date();
    const dateStr = now.toISOString().split('T')[0];
    const url = `http://107.102.8.148/MERS/reports/generate/${dateStr}/${dateStr}/all/untaken-order`;

    fetch(url, { cache: 'no-store' })
      .then(res => res.text())
      .then(html => {
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, 'text/html');
        const rows = Array.from(doc.querySelectorAll('#dataTables tbody tr, table tbody tr'));

        const peData = rows.filter(tr => {
          const cells = tr.querySelectorAll('td');
          if (cells.length < 6) return false;
          return cells[5].textContent.trim().toUpperCase() === 'PE';
        }).map(tr => {
          const cells = tr.querySelectorAll('td');
          return {
            nama: cells[3]?.textContent.trim(),
            jadwal: cells[1]?.textContent.trim().replace('Makan ', '')
          };
        });

        renderUntaken(peData);
      })
      .catch(err => {
        console.error('Fetch untaken error:', err);
        const tbody = document.getElementById('mrs-untaken-tbody');
        if (tbody) tbody.innerHTML = '<tr><td colspan="2" style="color:red; text-align:center;">Gagal muat data</td></tr>';
      });
  }

  function renderUntaken(data) {
    const tbody = document.getElementById('mrs-untaken-tbody');
    const badge = document.getElementById('mrs-pe-count');
    const timeEl = document.getElementById('mrs-pe-update-time');
    if (!tbody || !badge) return;

    badge.textContent = data.length;
    timeEl.textContent = new Date().toLocaleTimeString('id-ID', {hour:'2-digit', minute:'2-digit'});

    if (data.length === 0) {
      tbody.innerHTML = '<tr><td colspan="2" style="text-align:center; padding:15px; color:#16a34a;">✅ Semua sudah ambil</td></tr>';
      return;
    }

    tbody.innerHTML = data.map(item => `
      <tr>
        <td class="mrs-td-name">${item.nama}</td>
        <td class="mrs-td-jadwal">${item.jadwal}</td>
      </tr>
    `).join('');
  }

  function startUntakenPolling() {
    fetchUntakenOrders();
    if (untakenPolling) clearInterval(untakenPolling);
    untakenPolling = setInterval(fetchUntakenOrders, 60000);
  }

  function stopUntakenPolling() {
    if (untakenPolling) {
       clearInterval(untakenPolling);
       untakenPolling = null;
    }
  }

  // --- UI CONSTRUCTION ---
  function injectUI() {
    if (document.getElementById('mrs-nfc-fab')) return;

    const toast = document.createElement('div');
    toast.id = 'mrs-toast';
    document.body.appendChild(toast);

    injectBentoPanel();
    injectUntakenPanel();

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

        <div class="mrs-shortcut-group">
          <button id="mrs-open-scanner" class="mrs-shortcut-btn mrs-shortcut-scanner">📲 Scanner</button>
          <button id="mrs-open-menu" class="mrs-shortcut-btn mrs-shortcut-menu">🍽️ Cek Menu</button>
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
    document.getElementById('mrs-open-scanner').addEventListener('click', () => {
        window.open('http://107.102.8.148/MERS/nfc_scanner.html', '_blank');
    });
    document.getElementById('mrs-open-menu').addEventListener('click', () => {
        window.open('http://107.102.8.148/MERS/cek_menu.html', '_blank');
    });

    renderLists();
  }

  function togglePanel() {
    const panel = document.getElementById('mrs-nfc-panel');
    const isVisible = panel.style.display === 'flex';
    panel.style.display = isVisible ? 'none' : 'flex';
    if (!isVisible) {
        document.getElementById('mrs-manual-uid').focus();
        renderLists();
        // Show panels if data is ready
        if (lastScheduleData) renderBento(lastScheduleData);
        document.getElementById('mrs-untaken-panel').style.display = 'flex';
        startUntakenPolling();
    } else {
        hideBento();
        document.getElementById('mrs-untaken-panel').style.display = 'none';
        stopUntakenPolling();
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
              <div class="mrs-chip" style="background:#fffbeb; border-color:#f59e0b; display:flex; align-items:center; gap:8px;" data-uid="${item.id}" title="${item.id}">
                <span style="flex:1"><b>${item.alias}</b></span>
                <span class="mrs-delete-pin" data-uid="${item.id}" style="font-weight:bold; color:#ef4444; padding:0 4px; border-left:1px solid #fed7aa; font-size:14px; cursor:pointer">×</span>
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
      newChip.addEventListener('click', (e) => {
        if (e.target.classList.contains('mrs-delete-pin')) {
            removePinned(e.target.dataset.uid);
            return;
        }
        processUID(newChip.dataset.uid);
      });
    });
  }

  function removePinned(uid) {
    getStorageData(STORAGE_KEY_PINNED, (pinned) => {
        const newData = pinned.filter(p => p.id !== uid);
        setStorageData(STORAGE_KEY_PINNED, newData, () => {
            renderLists();
            showToast("Pin dihapus");
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
                  if (data.success && data.data && Array.isArray(data.data.schedules) && data.data.schedules.length > 0) {
                      // Sum remaining_portions across all schedules for this loket
                      const total = data.data.schedules.reduce((sum, s) => sum + parseInt(s.remaining_portions || 0, 10), 0);
                      // Pass first schedule for bento preview
                      updateUIQuota(total, data.data.schedules[0]);
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

  function updateUIQuota(val, schedule) {
      // 1. Update panel quota badge
      const extCounter = document.getElementById('mrs-live-quota');
      if (extCounter) extCounter.textContent = val;

      // 2. Update the page's odometer counter (NFC Scanner page)
      const odometerEl = document.getElementById('menu-counter-value');
      if (odometerEl) {
          const numVal = parseInt(val, 10);

          // Strategy A: Odometer library native API (best, with animation)
          if (typeof window.Odometer !== 'undefined') {
              try {
                  if (!odometerEl._mrsOdInstance) {
                      odometerEl._mrsOdInstance = new window.Odometer({
                          el: odometerEl,
                          value: numVal,
                          theme: 'car',
                          duration: 800,
                      });
                  }
                  odometerEl._mrsOdInstance.update(numVal);
              } catch(e) {
                  rebuildOdometerDigits(odometerEl, String(val));
              }
          } else {
              // Strategy B: Rebuild inner HTML — 1 digit span per character
              rebuildOdometerDigits(odometerEl, String(val));
          }
      }

      // 3. Render bento panel if schedule provided and panel is open
      if (schedule) {
          lastScheduleData = schedule;
          const panel = document.getElementById('mrs-nfc-panel');
          if (panel && panel.style.display === 'flex') {
              renderBento(schedule);
          }
      }
  }

  // Rebuild odometer HTML structure: one .odometer-digit span per digit
  function rebuildOdometerDigits(el, strVal) {
      const digits = strVal.split('');
      const inner = document.querySelector('#menu-counter-value .odometer-inside');
      if (!inner) {
          // Full fallback: just overwrite element text
          el.textContent = strVal;
          return;
      }
      inner.innerHTML = digits.map(d => `
        <span class="odometer-digit">
          <span class="odometer-digit-spacer">8</span>
          <span class="odometer-digit-inner">
            <span class="odometer-ribbon">
              <span class="odometer-ribbon-inner">
                <span class="odometer-value">${d}</span>
              </span>
            </span>
          </span>
        </span>
      `).join('');
  }

  // Tunggu sampai DOM siap sebelum inject UI
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      injectUI();
      startQuotaPolling();
    });
  } else {
    injectUI();
    startQuotaPolling();
  }

  // Escucha cambios de almacenamiento para sincronizar en vivo
  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName === 'local') renderLists();
  });
})();
