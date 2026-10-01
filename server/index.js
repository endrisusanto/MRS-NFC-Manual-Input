// MeRS WebSocket Relay Gateway
"use strict";
const http = require("http");
const https = require("https");
const path = require("path");
const express = require("express");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 3000;

const app = express();
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  res.setHeader("Access-Control-Allow-Methods", "GET,HEAD,POST,OPTIONS");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Serve MeRS Remote static files
const fs = require("fs");
const staticDir = fs.existsSync(path.join(__dirname, "web"))
  ? path.join(__dirname, "web")
  : path.join(__dirname, "../MRS-NFC-Manual-Input/web");
app.use(express.static(staticDir));
const genUidMap = (() => {
  try {
    const text = fs.readFileSync(path.join(staticDir, "mers-gen-map.js"), "utf8");
    const json = text.match(/MERS_GEN_UID\s*=\s*(\{[\s\S]*?\});/)?.[1];
    return json ? JSON.parse(json) : {};
  } catch (_) {
    return {};
  }
})();

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Maps to track active connections
// deviceId -> WebSocket connection
const agents = new Map();
// deviceId -> Set of WebSockets
const clients = new Map();
// requestId -> requesting mobile WebSocket
const pendingRequests = new Map();

function normalizeDevice(dev) {
  return String(dev || "loket-pc-1").trim().toLowerCase();
}

function getAgentForDevice(device) {
  const norm = normalizeDevice(device);
  if (agents.has(norm)) {
    const a = agents.get(norm);
    if (a && a.readyState === 1) return a;
  }
  // Case-insensitive lookup fallback
  for (const [d, a] of agents.entries()) {
    if (d.toLowerCase() === norm && a && a.readyState === 1) {
      return a;
    }
  }
  // Global single-agent fallback: If there is an active agent, serve it
  for (const a of agents.values()) {
    if (a && a.readyState === 1) {
      return a;
    }
  }
  return null;
}

function addClient(device, ws) {
  const norm = normalizeDevice(device);
  if (!clients.has(norm)) {
    clients.set(norm, new Set());
  }
  clients.get(norm).add(ws);
  console.log(`[WS] Client joined device: ${norm}`);
}

function removeClient(device, ws) {
  const norm = normalizeDevice(device);
  if (clients.has(norm)) {
    clients.get(norm).delete(ws);
    if (clients.get(norm).size === 0) {
      clients.delete(norm);
    }
  }
  for (const [requestId, client] of pendingRequests) {
    if (client === ws) pendingRequests.delete(requestId);
  }
  console.log(`[WS] Client disconnected from device: ${norm}`);
}

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, "http://localhost");
  const path = url.pathname;
  console.log(`[WS] New connection established on path: ${path}`);
  ws.isAlive = true;
  ws.on("pong", () => { ws.isAlive = true; });

  let currentDevice = null;
  let currentRole = null;

  ws.on("message", (raw) => {
    try {
      const msg = JSON.parse(raw.toString());

      if (msg.type === "join") {
        currentDevice = normalizeDevice(msg.device);
        currentRole = msg.role || "mobile";

        if (currentRole === "agent") {
          agents.set(currentDevice, ws);
          console.log(`[WS] Agent registered for device: ${currentDevice}`);
          // Notify any listening clients that the agent is online
          broadcastToClients(currentDevice, { type: "status", status: "online", message: "Agent terhubung." });
        } else {
          addClient(currentDevice, ws);
          // Let client know if the agent is online
          const agent = getAgentForDevice(currentDevice);
          const agentOnline = agent !== null && agent.readyState === 1;
          ws.send(JSON.stringify({
            type: "status",
            success: true,
            status: agentOnline ? "online" : "offline",
            message: agentOnline ? "Agent online." : "Agent offline. Silakan buka aplikasi MeRS Agent di PC kantor."
          }));
        }
      } else if (msg.type === "heartbeat") {
        if (msg.device) currentDevice = normalizeDevice(msg.device);
        if (currentRole === "agent" && currentDevice) {
          agents.set(currentDevice, ws);
        }
        ws.send(JSON.stringify({ type: "pong", pong: true }));
      } else if (msg.type === "command") {
        // Forward client command to the corresponding desktop agent
        const targetAgent = getAgentForDevice(currentDevice);
        if (targetAgent && targetAgent.readyState === 1) { // 1 represents WebSocket.OPEN
          console.log(`[WS] Forwarding command from client to agent (${currentDevice}): ${msg.action} for UID ${msg.uid}`);
          if (msg.requestId) {
            pendingRequests.set(msg.requestId, ws);
            setTimeout(() => pendingRequests.delete(msg.requestId), 30000);
          }
          targetAgent.send(JSON.stringify(msg));
        } else {
          ws.send(JSON.stringify({
            requestId: msg.requestId,
            success: false,
            status: "offline",
            message: "PC Agent MeRS offline atau tidak terdeteksi."
          }));
        }
      } else if (currentRole === "agent") {
        if (msg.requestId && pendingRequests.has(msg.requestId)) {
          const client = pendingRequests.get(msg.requestId);
          pendingRequests.delete(msg.requestId);
          if (typeof client === "function") client(msg);
          else if (client.readyState === 1) client.send(JSON.stringify(msg));
        } else {
          // Legacy agent messages are still broadcast for old clients.
          console.log(`[WS] Forwarding agent response to clients for device: ${currentDevice}`);
          broadcastToClients(currentDevice, msg);
        }
      }
    } catch (err) {
      console.warn("[WS] Error processing message:", err);
    }
  });

  ws.on("close", () => {
    if (currentDevice) {
      if (currentRole === "agent") {
        if (agents.get(currentDevice) === ws) {
          agents.delete(currentDevice);
          console.log(`[WS] Agent disconnected for device: ${currentDevice}`);
          broadcastToClients(currentDevice, { type: "status", status: "offline", message: "Agent terputus." });
        }
      } else {
        removeClient(currentDevice, ws);
      }
    }
  });
});

// Periodic ping keep-alive
const wsKeepAliveInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    try { ws.ping(); } catch (_) {}
  });
}, 30000);
wss.on("close", () => clearInterval(wsKeepAliveInterval));

function broadcastToClients(device, data) {
  const norm = normalizeDevice(device);
  const deviceClients = clients.get(norm);
  const payload = JSON.stringify(data);
  if (deviceClients) {
    for (const client of deviceClients) {
      if (client.readyState === 1) { // OPEN
        client.send(payload);
      }
    }
  } else {
    // If no exact match, broadcast to all active mobile clients
    for (const clientSet of clients.values()) {
      for (const client of clientSet) {
        if (client.readyState === 1) {
          client.send(payload);
        }
      }
    }
  }
}

function requestAgent(device, payload, timeoutMs = 30000) {
  return new Promise((resolve, reject) => {
    const targetAgent = getAgentForDevice(device);
    if (!targetAgent || targetAgent.readyState !== 1) {
      reject(new Error("PC Agent MeRS offline atau tidak terdeteksi."));
      return;
    }
    const requestId = payload.requestId || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const timer = setTimeout(() => {
      pendingRequests.delete(requestId);
      reject(new Error("Timeout menunggu Tauri agent."));
    }, timeoutMs);
    pendingRequests.set(requestId, (msg) => {
      clearTimeout(timer);
      resolve(msg);
    });
    targetAgent.send(JSON.stringify({ type: "command", device, requestId, ...payload }));
  });
}

// ── MERS Proxy ────────────────────────────────────────────────────────────────
// Session store: genId → { cookie, userId }
// ponytail: in-memory is fine for this scale
const mersSessions = new Map();

function mersRequest({ method, urlPath, body, cookie }) {
  return new Promise((resolve, reject) => {
    const bodyBuf = body ? Buffer.from(body, "utf8") : null;
    const options = {
      hostname: "seinp.sec.samsung.net",
      port: 443,
      path: "/MERS" + urlPath,
      method: method || "GET",
      rejectUnauthorized: false,
      headers: {
        "Accept": "text/html,application/json,*/*",
        "Content-Type": "application/x-www-form-urlencoded",
        ...(bodyBuf ? { "Content-Length": bodyBuf.length } : {}),
        ...(cookie ? { "Cookie": cookie } : {}),
      },
    };

    const req = https.request(options, (res) => {
      const chunks = [];
      res.on("data", c => chunks.push(c));
      res.on("end", () => {
        resolve({
          status: res.statusCode,
          headers: res.headers,
          body: Buffer.concat(chunks).toString("utf8"),
        });
      });
    });

    req.on("error", reject);
    req.setTimeout(15000, () => req.destroy(new Error("Timeout menghubungi MERS")));
    if (bodyBuf) req.write(bodyBuf);
    req.end();
  });
}

async function ensureSession(genId, password) {
  const effectiveGen = (genId && password) ? String(genId) : "14829575";
  const effectivePass = (genId && password) ? String(password) : "23051995";

  if (mersSessions.has(effectiveGen)) return mersSessions.get(effectiveGen);

  // Login to MERS
  const body = `identity=${encodeURIComponent(effectiveGen)}&password=${encodeURIComponent(effectivePass)}`;
  const res = await mersRequest({ method: "POST", urlPath: "/auth/login", body });

  const setCookies = [].concat(res.headers["set-cookie"] || []);
  const cookie = setCookies.map(c => c.split(";")[0]).join("; ");

  if (!cookie) {
    if (effectiveGen !== "14829575") {
      // Retry with master account
      return ensureSession("14829575", "23051995");
    }
    throw new Error("Login gagal — cookie tidak diterima dari MERS");
  }

  // Extract userId from dashboard (which contains profile/history links)
  let userId = null;
  try {
    const profileRes = await mersRequest({ method: "GET", urlPath: "/dashboard", cookie });
    const uidMatch = profileRes.body.match(/\/reports\/generate\/[^/]+\/[^/]+\/(\d+)\//);
    if (uidMatch) userId = uidMatch[1];
  } catch (_) {}

  const session = { cookie, userId };
  mersSessions.set(effectiveGen, session);
  console.log(`[MERS Proxy] Session created for genId=${effectiveGen}, userId=${userId}`);
  return session;
}

function isExpiredSessionResponse(r) {
  const location = String(r.headers.location || "");
  const head = String(r.body || "").slice(0, 2000);
  return /\/auth\/login|\/login/i.test(location) || (/name=["']identity["']/i.test(head) && /password/i.test(head));
}

async function withSession(genId, password, fn) {
  const key = (genId && password) ? String(genId) : "14829575";
  for (let attempt = 0; attempt < 2; attempt++) {
    const session = await ensureSession(genId, password);
    const result = await fn(session);
    if (!isExpiredSessionResponse(result)) return { session, result };
    mersSessions.delete(key);
    mersSessions.delete("14829575");
  }
  throw new Error("Session MERS expired dan login ulang gagal");
}

// POST /mers-proxy/login
app.post("/mers-proxy/login", async (req, res) => {
  const { genId, password, device = "loket-pc-1" } = req.body;
  if (!genId || !password) return res.json({ success: false, message: "genId dan password wajib diisi" });

  const agent = getAgentForDevice(device);
  if (agent) {
    try {
      const today = new Date().toISOString().split("T")[0];
      const data = await requestAgent(device, {
        action: "order_history",
        genId,
        password,
        from: today,
        to: today,
      });
      if (data && data.success !== false) {
        return res.json({ success: true, userId: String(genId) });
      } else {
        return res.json({ success: false, message: data.message || "Login gagal" });
      }
    } catch (e) {
      console.warn("[MERS Proxy] Login via agent error:", e.message);
    }
  }

  mersSessions.delete(String(genId)); // force refresh
  try {
    const session = await ensureSession(String(genId), String(password));
    res.json({ success: true, userId: session.userId });
  } catch (e) {
    console.error("[MERS Proxy] Login error:", e.message);
    res.json({ success: false, message: "Login gagal: " + e.message });
  }
});

// GET /mers-proxy/stock?date=DATE&meal_id=2&genId=GEN&password=PASS
app.get("/mers-proxy/stock", async (req, res) => {
  const { date, meal_id = "2", genId, password, device = "loket-pc-1" } = req.query;
  if (!date || !meal_id || !genId) return res.json({ success: false, message: "Parameter kurang" });

  const agent = getAgentForDevice(device);
  if (agent && password) {
    try {
      const rangeRes = await requestAgent(device, {
        action: "order_menu_range",
        genId,
        password,
        dates: [date],
      });
      if (rangeRes && rangeRes.success !== false && rangeRes.days) {
        const day = rangeRes.days.find(d => d.date === date) || rangeRes.days[0];
        const meal = day?.meals?.find(m => String(m.meal_id) === String(meal_id)) || day?.meals?.[0];
        const rawMenus = meal?.menus || [];
        const menus = rawMenus.map(m => ({
          schedule_menu_id: String(m.id || ""),
          id: String(m.id || ""),
          name: m.name || "",
          menu_name: m.name || "",
          detail: m.detail || "",
          qty_balance: typeof m.qty_balance === "number" ? m.qty_balance : parseInt(m.qty_balance, 10) || 0,
          is_available: (typeof m.qty_balance === "number" ? m.qty_balance : parseInt(m.qty_balance, 10) || 0) > 0,
        }));
        return res.json({ success: true, data: menus, menus, items: menus, userId: genId });
      } else if (rangeRes && rangeRes.message) {
        return res.json({ success: false, message: rangeRes.message });
      }
    } catch (e) {
      console.warn("[MERS Proxy] Stock via agent failed, trying fallback:", e.message);
    }
  }

  try {
    const { session, result: r } = await withSession(genId, password, session => mersRequest({
      method: "GET",
      urlPath: `/order/get_stock_data?date=${date}&schedule_meal_id=${meal_id}`,
      cookie: session.cookie,
    }));
    const data = JSON.parse(r.body);
    res.json({ ...data, userId: session.userId });
  } catch (e) {
    res.json({ success: false, message: e.message });
  }
});

// GET /mers-proxy/menu-names?date=DATE&meal_id=2&genId=GEN&password=PASS
// Fetch order page HTML to extract menu names per schedule_menu_id
app.get("/mers-proxy/menu-names", async (req, res) => {
  const { date, meal_id = "2", genId, password, device = "loket-pc-1" } = req.query;
  if (!date || !meal_id || !genId) return res.json({ success: false, message: "Parameter kurang" });

  const agent = getAgentForDevice(device);
  if (agent && password) {
    try {
      const rangeRes = await requestAgent(device, {
        action: "order_menu_range",
        genId,
        password,
        dates: [date],
      });
      if (rangeRes && rangeRes.days) {
        const day = rangeRes.days.find(d => d.date === date) || rangeRes.days[0];
        const meal = day?.meals?.find(m => String(m.meal_id) === String(meal_id)) || day?.meals?.[0];
        const names = {};
        (meal?.menus || []).forEach(m => {
          if (m.id && m.name) names[String(m.id)] = m.name;
        });
        return res.json({ success: true, names, data: names });
      }
    } catch (e) {
      console.warn("[MERS Proxy] Menu names via agent failed, trying fallback:", e.message);
    }
  }

  try {
    const { result: r } = await withSession(genId, password, session => mersRequest({
      method: "GET",
      urlPath: `/order/pilihmenu?xtanggal=${date}&xjadwal=${meal_id}&xfor_date=${date}&xjm=${meal_id}`,
      cookie: session.cookie,
    }));

    const names = {};
    const idRe = /(?:value|data-id|data-menu-id|data-schedule-menu-id)\s*=\s*["']?(\d+)["']?/gi;
    let match;
    while ((match = idRe.exec(r.body)) !== null) {
      const id = match[1];
      const matchIdx = match.index;

      const chunkStart = Math.max(
        r.body.lastIndexOf("<label", matchIdx),
        r.body.lastIndexOf("<option", matchIdx),
        r.body.lastIndexOf("<div", matchIdx),
        r.body.lastIndexOf("<tr", matchIdx),
        0
      );

      const afterMatch = r.body.substring(matchIdx + match[0].length);
      const nextInputIdx = afterMatch.search(/name=["']?menusaya["']?|type=["']?radio["']?|<option/i);
      const chunkEnd = nextInputIdx !== -1 ? matchIdx + match[0].length + nextInputIdx : r.body.length;

      const chunk = r.body.substring(chunkStart, chunkEnd);

      let cleanChunk = chunk
        .replace(/<input[^>]*>/gi, "")
        .replace(/<[^>]+(?:class|id)\s*=\s*["']?[^"']*(?:menu-item-name|menu-info|detail|qty|stock|balance)[^"']*["']?[^>]*>.*?<\/[^>]+>/gi, "");

      let nameMatch = chunk.match(/<[^>]+(?:class|id)\s*=\s*["']?[^"']*(?:menu-title|menu-name|item-title)[^"']*["']?[^>]*>(.*?)<\/[^>]+>/i)
        || chunk.match(/<h[2-5][^>]*>(.*?)<\/h[2-5]>/i)
        || chunk.match(/<strong[^>]*>(.*?)<\/strong>/i)
        || chunk.match(/<b[^>]*>(.*?)<\/b>/i)
        || chunk.match(/<option[^>]*>(.*?)<\/option>/i);

      let name = "";
      if (nameMatch) {
        name = nameMatch[1].replace(/<[^>]+>/g, "").trim();
      } else {
        name = cleanChunk.replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
      }

      if (name && !/^\d+$/.test(name)) {
        names[id] = name;
      }
    }

    if (Object.keys(names).length === 0) {
      try {
        const { result: reportRes } = await withSession("14829575", "23051995", session => mersRequest({
          method: "GET",
          urlPath: `/reports/generate/${date}/${date}/all/final-order`,
          cookie: session.cookie,
        }));

        const optRe = /finalorder\/view\/(\d+)/gi;
        let m;
        while ((m = optRe.exec(reportRes.body)) !== null) {
          const id = m[1];
          const matchIdx = m.index;

          const trStart = reportRes.body.lastIndexOf("<tr", matchIdx);
          if (trStart !== -1) {
            const rowHtml = reportRes.body.substring(trStart, matchIdx + 500);
            const cells = [];
            const cellRe = /<td[^>]*>(.*?)<\/td>/gis;
            let cellMatch;
            while ((cellMatch = cellRe.exec(rowHtml)) !== null) {
              cells.push(cellMatch[1].replace(/<[^>]+>/g, "").trim());
            }
            if (cells.length > 4 && cells[4]) {
              names[id] = cells[4];
            }
          }
        }
      } catch (_) {}
    }

    res.json({ success: true, names });
  } catch (e) {
    res.json({ success: false, message: e.message, names: {} });
  }
});

// POST /mers-proxy/order
app.post("/mers-proxy/order", async (req, res) => {
  const { genId, password, xtanggal, xjadwal, menusaya, device = "loket-pc-1" } = req.body;
  if (!genId || !xtanggal || !xjadwal || !menusaya) return res.json({ success: false, message: "Parameter kurang" });

  const agent = getAgentForDevice(device);
  if (agent) {
    try {
      const data = await requestAgent(device, {
        action: "order_submit",
        genId,
        password,
        date: xtanggal,
        mealId: String(xjadwal),
        menuId: String(menusaya),
      });
      return res.json(data);
    } catch (e) {
      console.warn("[MERS Proxy] Order submit via agent failed, trying fallback:", e.message);
    }
  }

  try {
    const body = `xtanggal=${xtanggal}&xjadwal=${xjadwal}&menusaya=${menusaya}&xfor_date=${xtanggal}&xjm=${xjadwal}&form_action=save`;
    const { result: r } = await withSession(genId, password, session => mersRequest({ method: "POST", urlPath: "/order/pilihmenu", body, cookie: session.cookie }));
    const success = r.status === 302 || r.status === 200;
    res.json({ success, status: r.status, message: success ? "Pesanan berhasil disimpan" : "Gagal menyimpan pesanan" });
  } catch (e) {
    res.json({ success: false, message: e.message });
  }
});

// POST /mers-proxy/cancel
app.post("/mers-proxy/cancel", async (req, res) => {
  const { genId, password, xid, device = "loket-pc-1" } = req.body;
  if (!genId || !xid) return res.json({ success: false, message: "Parameter kurang" });

  const agent = getAgentForDevice(device);
  if (agent) {
    try {
      const data = await requestAgent(device, {
        action: "order_cancel",
        genId,
        password,
        xid: String(xid),
      });
      return res.json(data);
    } catch (e) {
      console.warn("[MERS Proxy] Cancel via agent failed, trying fallback:", e.message);
    }
  }

  try {
    const { result: r } = await withSession(genId, password, session => mersRequest({ method: "POST", urlPath: "/order/hapusPesanan", body: `xid=${xid}`, cookie: session.cookie }));
    const success = r.status === 302 || r.status === 200;
    res.json({ success, status: r.status, message: success ? "Pesanan berhasil dibatalkan" : "Gagal membatalkan" });
  } catch (e) {
    res.json({ success: false, message: e.message });
  }
});

// GET /mers-proxy/history?genId=GEN&password=PASS&from=DATE&to=DATE
app.get("/mers-proxy/history", async (req, res) => {
  const { genId, password, from, to, device = "loket-pc-1" } = req.query;
  if (!genId) return res.json({ success: false, message: "genId wajib" });

  const agent = getAgentForDevice(device);
  if (agent) {
    try {
      const today = new Date().toISOString().split("T")[0];
      const data = await requestAgent(device, {
        action: "order_history",
        genId: String(genId),
        password: String(password || ""),
        from: from || today,
        to: to || today,
      });
      if (data && data.success !== false && Array.isArray(data.rows) && data.rows.length > 0) {
        return res.json(data);
      }
    } catch (e) {
      console.warn("[MERS Proxy] History via agent failed, trying fallback:", e.message);
    }
  }

  try {
    const { session, result: r } = await withSession(genId, password, session => {
      const today   = new Date().toISOString().split("T")[0];
      const dateFrom = from || today;
      const dateTo   = to   || today;

      return mersRequest({
        method: "GET",
        urlPath: `/reports/generate/${dateFrom}/${dateTo}/all/final-order`,
        cookie: session.cookie,
      });
    });

    // Parse HTML table rows → JSON
    const rows = [];
    const trRe = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
    let tr;
    while ((tr = trRe.exec(r.body)) !== null) {
      const cells = [];
      const tdRe = /<td[^>]*>([\s\S]*?)<\/td>/gi;
      let td;
      while ((td = tdRe.exec(tr[1])) !== null) {
        cells.push(td[1].replace(/<[^>]+>/g, "").trim());
      }
      if (cells.length >= 7) {
        let offset = 0;
        if (cells[4] === String(genId)) offset = 0;
        else if (cells[5] === String(genId)) offset = 1;
        else continue;

        const tgl = cells[0 + offset] || "";
        const xidMatch = tr[1].match(/(?:xid=|data-xid=["']?|hapusPesanan[/?])(\d+)/i);
        rows.push({
          tanggal: tgl,
          tanggal_iso: parseIndonesianDate(tgl) || tgl,
          jadwal:  cells[1 + offset] || "",
          loket:   cells[2 + offset] || "",
          nama:    cells[3 + offset] || "",
          gen:     cells[4 + offset] || "",
          part:    cells[5 + offset] || "",
          menu:    cells[6 + offset] || "",
          status:  cells[7 + offset] || "",
          xid:     xidMatch ? xidMatch[1] : null,
        });
      }
    }

    res.json({ success: true, rows });
  } catch (e) {
    res.json({ success: false, message: e.message, rows: [] });
  }
});

function parseIndonesianDate(dateStr) {
  if (!dateStr) return '';
  const clean = dateStr.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/gi, ' ').trim();
  const match = clean.match(/(\d{1,2})\s+([a-z]+)\s+(\d{4})/i);
  if (!match) return '';

  const months = {
    januari: '01', january: '01', jan: '01',
    februari: '02', february: '02', feb: '02',
    maret: '03', march: '03', mar: '03',
    april: '04', apr: '04',
    mei: '05', may: '05',
    juni: '06', june: '06', jun: '06',
    juli: '07', july: '07', jul: '07',
    agustus: '08', august: '08', agu: '08', aug: '08',
    september: '09', sep: '09',
    oktober: '10', october: '10', okt: '10', oct: '10',
    november: '11', nov: '11',
    desember: '12', december: '12', des: '12', dec: '12'
  };

  const day = match[1].padStart(2, '0');
  const mStr = match[2].toLowerCase();
  const month = months[mStr] || '01';
  const year = match[3];

  return `${year}-${month}-${day}`;
}

// GET /mers-proxy/widget-sync?genId=GEN&password=PASS — Widget auto-sync (uses order_history + cek_pesanan)
app.get("/mers-proxy/widget-sync", async (req, res) => {
  const { genId, password, device = "loket-pc-1" } = req.query;
  if (!genId) return res.json({ success: false, message: "genId wajib" });

  try {
    const today = new Date().toISOString().split("T")[0];
    const toDate = new Date(Date.now() + 14 * 86400000).toISOString().split("T")[0];

    let historyRows = [];
    let name = String(genId);

    // 1. Ambil riwayat pesanan (H s/d H+14) via agent (seperti pada Pesan Menu)
    const agent = getAgentForDevice(device);
    if (agent) {
      try {
        const histData = await requestAgent(device, {
          action: "order_history",
          genId: String(genId),
          password: String(password || ""),
          from: today,
          to: toDate,
        });
        if (histData && Array.isArray(histData.rows)) {
          historyRows = histData.rows;
        }
      } catch (e) {
        console.warn("[WidgetSync] order_history via agent failed:", e.message);
      }
    }

    // 2. Ambil juga dari cek_pesanan (NFC live status)
    let nfcOrders = [];
    const uid = genUidMap[String(genId)] || (/^\d{10}$/.test(String(genId)) ? String(genId) : "");
    if (uid && agent) {
      try {
        const data = await requestAgent(String(device), { action: "cek_pesanan", uid });
        if (data && data.success && Array.isArray(data.data?.orders)) {
          nfcOrders = data.data.orders;
          if (nfcOrders[0]?.first_name) {
            name = nfcOrders[0].first_name;
          }
        }
      } catch (e) {
        console.warn("[WidgetSync] cek_pesanan failed:", e.message);
      }
    }

    // 3. Fallback: jika historyRows kosong, coba scrape report via withSession
    if (historyRows.length === 0) {
      try {
        const { session, result: r } = await withSession(String(genId), password, session => {
          const userId = session.userId || genId;
          const reportType = (userId.length >= 8) ? 'all' : userId;
          return mersRequest({
            method: "GET",
            urlPath: `/reports/generate/${today}/${toDate}/${reportType}/final-order`,
            cookie: session.cookie,
          });
        });
        const trRe = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
        let tr;
        while ((tr = trRe.exec(r.body)) !== null) {
          const cells = [];
          const tdRe = /<td[^>]*>([\s\S]*?)<\/td>/gi;
          let td;
          while ((td = tdRe.exec(tr[1])) !== null) {
            cells.push(td[1].replace(/<[^>]+>/g, "").trim());
          }
          if (cells.length >= 7) {
            let offset = 0;
            if (cells[4] === String(genId)) offset = 0;
            else if (cells[5] === String(genId)) offset = 1;
            else continue;

            const tgl = cells[0 + offset] || "";
            historyRows.push({
              tanggal: tgl,
              tanggal_iso: parseIndonesianDate(tgl) || tgl,
              jadwal: cells[1 + offset] || "",
              loket: cells[2 + offset] || "",
              nama: cells[3 + offset] || "",
              gen: cells[4 + offset] || "",
              menu: cells[6 + offset] || "",
              status: cells[7 + offset] || "",
            });
            if (cells[3 + offset] && name === String(genId)) name = cells[3 + offset];
          }
        }
      } catch (_) {}
    }

    // 4. Gabungkan historyRows & nfcOrders
    const ordersMap = new Map();

    for (const row of historyRows) {
      if (row.nama && name === String(genId)) name = row.nama;
      const tglIso = row.tanggal_iso || parseIndonesianDate(row.tanggal) || row.tanggal || "";
      const jdwl = row.jadwal || row.meal || "Makan Siang";
      const key = `${tglIso}|${jdwl}`;
      ordersMap.set(key, {
        meal: jdwl,
        menu: row.menu || "",
        tanggal: tglIso,
        loket: row.loket || "",
        status: row.status ? (row.status.includes("Sudah") ? "Sudah Diambil" : "Belum Diambil") : "Belum Diambil",
      });
    }

    for (const ord of nfcOrders) {
      const tgl = ord.schedule_date || today;
      const meal = ord.schedule_meal_name || "Makan Siang";
      const key = `${tgl}|${meal}`;
      const existing = ordersMap.get(key) || {};
      ordersMap.set(key, {
        meal: meal,
        menu: ord.menu_name || existing.menu || "",
        tanggal: tgl,
        loket: ord.loket_name || ord.order_loket || existing.loket || "",
        status: ord.order_ambil ? "Sudah Diambil" : (existing.status || "Belum Diambil"),
      });
    }

    const orders = Array.from(ordersMap.values()).sort((a, b) => (a.tanggal || "").localeCompare(b.tanggal || ""));
    return res.json({ success: true, name, orders });
  } catch (e) {
    return res.json({ success: false, message: e.message, orders: [] });
  }
});

// Health check endpoint
app.get("/mers-ping", (_, res) => {
  res.json({ success: true, online: true, agentsCount: agents.size });
});
app.head("/mers-ping", (_, res) => res.sendStatus(204));

// Desktop Agent status check
app.get("/agent-status", (req, res) => {
  const device = req.query.device || "loket-pc-1";
  const agent = getAgentForDevice(device);
  const online = agent !== null && agent.readyState === 1;
  res.json({
    success: true,
    online,
    device,
    agentsCount: agents.size,
    message: online ? "Agent online." : "Agent offline."
  });
});

server.listen(PORT, () => {
  console.log(`MeRS Gateway Server running on http://localhost:${PORT}`);
});
