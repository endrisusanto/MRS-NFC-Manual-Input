(function () {
    const LOG_STYLE = 'background: #000; color: #0f0; font-size: 14px; padding: 4px; border: 2px solid #0f0;';
    console.log('%c🚀 [MRS Hook] Bridge Loaded - Brute Force Mode Activated', LOG_STYLE);

    let originalProcessNFC = null;

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
