document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('uid-input');
    const sendBtn = document.getElementById('send-btn');
    const pinBtn = document.getElementById('pin-btn');
    const recentList = document.getElementById('recent-list');
    const pList = document.getElementById('pinned-list');
    const pSec = document.getElementById('pinned-sec');

    const STORAGE_KEY_RECENT = 'mrs_nfc_recent_v2';
    const STORAGE_KEY_PINNED = 'mrs_nfc_pinned_v2';

    // --- STORAGE UTILS ---
    function getData(key, callback) {
        chrome.storage.local.get([key], (res) => {
            callback(res[key] ? JSON.parse(res[key]) : []);
        });
    }

    function setData(key, data, callback) {
        chrome.storage.local.set({ [key]: JSON.stringify(data) }, callback);
    }

    // --- RENDER ---
    function render() {
        // Pinned
        getData(STORAGE_KEY_PINNED, (pinned) => {
            if (pinned.length > 0) {
                pSec.style.display = 'block';
                pList.innerHTML = pinned.map(p => `
                    <div class="chip" data-uid="${p.id}" style="border-color:#f59e0b; display:flex; align-items:center; gap:8px;">
                        <span style="flex:1"><b>${p.alias}</b></span>
                        <span class="delete-pin" data-uid="${p.id}" style="font-weight:bold; color:#ef4444; padding:0 4px; border-left:1px solid #ccc; font-size:14px; line-height:1">×</span>
                    </div>
                `).join('');
            } else {
                pSec.style.display = 'none';
            }
            bindItemClicks();
        });

        // Recent
        getData(STORAGE_KEY_RECENT, (recent) => {
            recentList.innerHTML = recent.length ? recent.map(r => `<div class="chip" data-uid="${r.id}">${r.id}</div>`).join('') : '<span style="font-size:10px; color:#999">Kosong</span>';
            bindItemClicks();
        });
    }

    function bindItemClicks() {
        document.querySelectorAll('.chip').forEach(c => {
            const newC = c.cloneNode(true);
            c.parentNode.replaceChild(newC, c);
            
            newC.addEventListener('click', (e) => {
                if (e.target.classList.contains('delete-pin')) {
                    removePinned(e.target.dataset.uid);
                    return;
                }
                input.value = newC.dataset.uid;
                sendUID();
            });
        });
    }

    // --- ACTIONS ---
    async function sendUID() {
        const uid = input.value.trim();
        if (!uid) return showFeedback('UID Kosong!', true);

        const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
        if (!tab) return showFeedback('Tab Tidak Aktif', true);

        try {
            await chrome.scripting.executeScript({
                target: { tabId: tab.id },
                world: 'MAIN',
                func: (u) => {
                    if (typeof window.processNFC === 'function') window.processNFC(u);
                    else window.dispatchEvent(new CustomEvent('mrs-nfc-call', { detail: { uid: u } }));
                },
                args: [uid]
            });

            // Save to shared storage
            getData(STORAGE_KEY_RECENT, (recent) => {
                recent = recent.filter(x => x.id !== uid);
                recent.unshift({ id: uid, time: new Date().toLocaleTimeString() });
                if (recent.length > 8) recent.length = 8;
                setData(STORAGE_KEY_RECENT, recent, render);
            });

            showFeedback('Terkirim!');
            input.value = '';
        } catch (e) { showFeedback('Gagal!', true); }
    }

    function togglePin() {
        const uid = input.value.trim();
        if (!uid) return showFeedback('Isi UID dulu!', true);

        getData(STORAGE_KEY_PINNED, (pinned) => {
            const existing = pinned.find(p => p.id === uid);
            if (existing) {
                removePinned(uid);
            } else {
                const alias = window.prompt(`Nama Alias untuk ${uid}:`, "Karyawan");
                if (alias === null) return;
                pinned.push({ id: uid, alias: alias || uid });
                setData(STORAGE_KEY_PINNED, pinned, render);
                showFeedback('Berhasil di-Pin!');
            }
        });
    }

    function removePinned(uid) {
        getData(STORAGE_KEY_PINNED, (pinned) => {
            const newData = pinned.filter(p => p.id !== uid);
            setData(STORAGE_KEY_PINNED, newData, () => {
                render();
                showFeedback('Pin dihapus');
            });
        });
    }

    // Init
    render();
    sendBtn.addEventListener('click', sendUID);
    pinBtn.addEventListener('click', togglePin);
    input.addEventListener('keypress', (e) => { if (e.key === 'Enter') sendUID(); });

    document.getElementById('open-scanner')?.addEventListener('click', () => window.open('http://107.102.8.148/MERS/nfc_scanner.html', '_blank'));
    document.getElementById('open-menu')?.addEventListener('click', () => window.open('http://107.102.8.148/MERS/cek_menu.html', '_blank'));

    function showFeedback(msg, err = false) {
        const fb = document.getElementById('feedback');
        fb.textContent = msg;
        fb.style.display = 'block';
        fb.className = err ? 'feedback-err' : 'feedback-ok';
        setTimeout(() => fb.style.display = 'none', 3000);
    }

    // Sync in real-time
    chrome.storage.onChanged.addListener(() => render());
});
