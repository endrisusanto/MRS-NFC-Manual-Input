(function () {
    const LOG_STYLE = 'background: #000; color: #0f0; font-size: 14px; padding: 4px; border: 2px solid #0f0;';
    console.log('%c🚀 [MRS Hook] Bridge Loaded - Brute Force Mode Activated', LOG_STYLE);

    let originalProcessNFC = null;
    const API_LOG_KEY = 'mrs_api_log_v1';

    function pushApiLog(entry) {
        try {
            const logs = JSON.parse(localStorage.getItem(API_LOG_KEY) || '[]');
            logs.unshift(entry);
            localStorage.setItem(API_LOG_KEY, JSON.stringify(logs.slice(0, 50)));
        } catch (e) {
            console.warn('[MRS Hook] Failed to write API log:', e);
        }
    }

    function installFetchLogger() {
        if (window.__mrsFetchLoggerInstalled || typeof window.fetch !== 'function') return;
        window.__mrsFetchLoggerInstalled = true;

        const originalFetch = window.fetch.bind(window);
        window.fetch = async (...args) => {
            const startedAt = new Date().toISOString();
            const input = args[0];
            const init = args[1] || {};
            const url = typeof input === 'string' ? input : input?.url;
            const response = await originalFetch(...args);

            if (url && url.includes('/MERS/')) {
                response.clone().text().then(text => {
                    let body = text;
                    try { body = JSON.parse(text); } catch (e) {}
                    pushApiLog({
                        time: startedAt,
                        page: window.location.href,
                        mode: PAGE_MODE,
                        method: init.method || 'GET',
                        url,
                        requestBody: init.body ? String(init.body) : null,
                        status: response.status,
                        ok: response.ok,
                        response: body,
                    });
                }).catch(() => {});
            }

            return response;
        };

        window.mrsExportApiLog = () => {
            const data = localStorage.getItem(API_LOG_KEY) || '[]';
            const blob = new Blob([data], { type: 'application/json' });
            const link = document.createElement('a');
            link.href = URL.createObjectURL(blob);
            link.download = `mrs-api-log-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
            link.click();
            URL.revokeObjectURL(link.href);
            return JSON.parse(data);
        };

        window.mrsClearApiLog = () => localStorage.removeItem(API_LOG_KEY);
        console.log('[MRS Hook] API logger ready. Run mrsExportApiLog() to download logs.');
    }

    // --- 1. DETEKSI HALAMAN ---
    function detectPage() {
        const hasLoket = (typeof window.selectedLoket !== 'undefined') || !!document.getElementById('step-loket');
        const isScannerUrl = window.location.href.toLowerCase().includes('nfc_scanner') || window.location.href.toLowerCase().includes('loket');
        const isNfcScanner = hasLoket || isScannerUrl;
        
        const isCekMenu = !isNfcScanner && (
                          window.location.href.toLowerCase().includes('cek_menu') || 
                          window.location.href.toLowerCase().includes('cek_pesanan') ||
                          !!document.querySelector('title')?.innerText.includes('Cek Pesanan'));

        if (isNfcScanner) return 'SCANNER';
        if (isCekMenu) return 'CEK_MENU';
        return 'UNKNOWN';
    }

    const PAGE_MODE = detectPage();
    console.log(`%c[MRS Hook] TARGET PAGE: ${PAGE_MODE}`, 'background: #0088ff; color: white; padding: 2px 8px; font-weight: bold;');
    installFetchLogger();

    // --- 2. LOGIKA TRANSFORMASI ---
    function transformUid(uid) {
        let payloadUid = (uid || "").toString().trim();
        if (!payloadUid) return uid;

        if (payloadUid.includes(':')) {
            let rawHex = payloadUid.replace(/:/g, "").toUpperCase();
            if (rawHex.length % 2 !== 0) rawHex = '0' + rawHex;
            let reversed = "";
            for (let i = rawHex.length - 2; i >= 0; i -= 2) reversed += rawHex.substr(i, 2);
            if (PAGE_MODE === 'CEK_MENU') return BigInt("0x" + reversed).toString();
            return reversed;
        } 
        
        if (payloadUid.startsWith('#')) return payloadUid.substring(1).toUpperCase();
        
        if (payloadUid.startsWith('~')) {
            let input = payloadUid.substring(1);
            let hex = /^\d+$/.test(input) ? BigInt(input).toString(16).toUpperCase() : input.toUpperCase();
            if (hex.length % 2 !== 0) hex = '0' + hex;
            let reversed = "";
            for (let i = hex.length - 2; i >= 0; i -= 2) reversed += hex.substr(i, 2);
            return (PAGE_MODE === 'CEK_MENU') ? BigInt("0x" + reversed).toString() : reversed;
        }

        if (PAGE_MODE === 'SCANNER' && /^\d+$/.test(payloadUid)) {
            try {
                let hexVal = BigInt(payloadUid).toString(16).toUpperCase();
                if (hexVal.length % 2 !== 0) hexVal = "0" + hexVal;
                return hexVal;
            } catch (e) { return payloadUid; }
        }

        if (PAGE_MODE === 'CEK_MENU' && /^[0-9A-F]+$/i.test(payloadUid) && !/^\d+$/.test(payloadUid)) {
            try {
                return BigInt("0x" + payloadUid).toString();
            } catch (e) { return payloadUid; }
        }

        return payloadUid;
    }

    // --- 3. THE HOOK FUNCTION ---
    function hookedProcessNFC(uid) {
        const finalUid = transformUid(uid);
        console.log(`%c[MRS Hook] EXECUTING ${PAGE_MODE} MODE: ${uid} -> ${finalUid}`, 'background: #00ff00; color: black; font-weight: bold; padding: 2px 5px;');
        
        if (originalProcessNFC && typeof originalProcessNFC === 'function') {
            return originalProcessNFC.call(window, finalUid);
        } else {
            // Jika asli belum ada, coba panggil langsung via name (fallback)
            console.error('[MRS Hook] Original function missing during call!');
        }
    }

    // --- 4. BRUTE FORCE INJECTION ---
    function applyHook() {
        // Cek apakah window.processNFC ada dan bukan milik kita
        if (typeof window.processNFC === 'function' && window.processNFC !== hookedProcessNFC) {
            originalProcessNFC = window.processNFC;
            window.processNFC = hookedProcessNFC;
            console.log('[MRS Hook] Successfully hijacked window.processNFC');
        }
    }

    // Terus pantau setiap 100ms untuk memastikan tidak ditimpa oleh halaman
    setInterval(applyHook, 100);

    // Event Gateway
    window.addEventListener('mrs-nfc-call', (e) => {
        if (e.detail && e.detail.uid) {
            // Panggil punya kita langsung
            hookedProcessNFC(e.detail.uid);
        }
    });
})();
