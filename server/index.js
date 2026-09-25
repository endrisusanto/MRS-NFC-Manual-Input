// MeRS WebSocket Relay Gateway
"use strict";
const http = require("http");
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
      hostname: "107.102.8.148",
      port: 80,
      path: "/MERS" + urlPath,
      method: method || "GET",
      headers: {
        "Accept": "text/html,application/json,*/*",
        "Content-Type": "application/x-www-form-urlencoded",
        ...(bodyBuf ? { "Content-Length": bodyBuf.length } : {}),
        ...(cookie ? { "Cookie": cookie } : {}),
      },
    };

    const req = http.request(options, (res) => {
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
  if (mersSessions.has(genId)) return mersSessions.get(genId);

  // Login to MERS
  const body = `identity=${encodeURIComponent(genId)}&password=${encodeURIComponent(password)}`;
  const res = await mersRequest({ method: "POST", urlPath: "/auth/login", body });

  const setCookies = [].concat(res.headers["set-cookie"] || []);
  const cookie = setCookies.map(c => c.split(";")[0]).join("; ");

  if (!cookie) throw new Error("Login gagal — cookie tidak diterima dari MERS");

  // Extract userId from dashboard (which contains profile/history links)
  let userId = null;
  try {
    const profileRes = await mersRequest({ method: "GET", urlPath: "/dashboard", cookie });
    const uidMatch = profileRes.body.match(/\/reports\/generate\/[^/]+\/[^/]+\/(\d+)\//);
    if (uidMatch) userId = uidMatch[1];
  } catch (_) {}

  const session = { cookie, userId };
  mersSessions.set(genId, session);
  console.log(`[MERS Proxy] Session created for genId=${genId}, userId=${userId}`);
  return session;
}

function isExpiredSessionResponse(r) {
  const location = String(r.headers.location || "");
  const head = String(r.body || "").slice(0, 2000);
  return /\/auth\/login|\/login/i.test(location) || (/name=["']identity["']/i.test(head) && /password/i.test(head));
}

async function withSession(genId, password, fn) {
  const key = String(genId);
  for (let attempt = 0; attempt < 2; attempt++) {
    const session = await ensureSession(key, String(password || ""));
    const result = await fn(session);
    if (!isExpiredSessionResponse(result)) return { session, result };
    mersSessions.delete(key);
  }
  throw new Error("Session MERS expired dan login ulang gagal");
}

// POST /mers-proxy/login
app.post("/mers-proxy/login", async (req, res) => {
  const { genId, password } = req.body;
  if (!genId || !password) return res.json({ success: false, message: "genId dan password wajib diisi" });

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
  const { date, meal_id, genId, password } = req.query;
  if (!date || !meal_id || !genId) return res.json({ success: false, message: "Parameter kurang" });

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
  const { date, meal_id, genId, password } = req.query;
  if (!date || !meal_id || !genId) return res.json({ success: false, message: "Parameter kurang" });

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
  const { genId, password, xtanggal, xjadwal, menusaya } = req.body;
  if (!genId || !xtanggal || !xjadwal || !menusaya) return res.json({ success: false, message: "Parameter kurang" });

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
  const { genId, password, xid } = req.body;
  if (!genId || !xid) return res.json({ success: false, message: "Parameter kurang" });

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
  const { genId, password, from, to } = req.query;
  if (!genId) return res.json({ success: false, message: "genId wajib" });

  try {
    const { session, result: r } = await withSession(genId, password, session => {
      const userId  = session.userId || genId;
      const today   = new Date().toISOString().split("T")[0];
      const dateFrom = from || today;
      const dateTo   = to   || today;

      // Fallback: If we didn't find the internal ID (e.g. it's still the 8-digit NIK), use 'all'
      const reportType = (userId.length >= 8) ? 'all' : userId;

      return mersRequest({
        method: "GET",
        urlPath: `/reports/generate/${dateFrom}/${dateTo}/${reportType}/final-order`,
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
        if (cells[4] !== genId) continue;
        const xidMatch = tr[1].match(/(?:xid=|data-xid=["']?|hapusPesanan[/?])(\d+)/i);
        rows.push({
          tanggal: cells[0],
          jadwal:  cells[1],
          loket:   cells[2],
          nama:    cells[3],
          gen:     cells[4],
          part:    cells[5],
          menu:    cells[6],
          status:  cells[7] || "",
          xid:     xidMatch ? xidMatch[1] : null,
        });
      }
    }

    res.json({ success: true, rows });
  } catch (e) {
    res.json({ success: false, message: e.message, rows: [] });
  }
});

// GET /mers-proxy/widget-sync?genId=GEN — Widget auto-sync (uses master account)
app.get("/mers-proxy/widget-sync", async (req, res) => {
  const { genId, device = "loket-pc-1" } = req.query;
  if (!genId) return res.json({ success: false, message: "genId wajib" });

  try {
    const uid = genUidMap[String(genId)] || (/^\d{10}$/.test(String(genId)) ? String(genId) : "");
    if (!uid) return res.json({ success: false, message: "GEN tidak ada di map UID", orders: [] });
    const data = await requestAgent(String(device), { action: "cek_pesanan", uid });
    if (!data.success) return res.json({ success: false, message: data.message || "Cek pesanan gagal", orders: [] });

    const sourceOrders = data?.data?.orders || [];
    const name = sourceOrders[0]?.first_name || String(genId);
    const orders = sourceOrders.map(order => ({
      meal: order.schedule_meal_name || "",
      menu: order.menu_name || "",
      tanggal: order.schedule_date || "",
      loket: order.loket_name || order.order_loket || "",
      status: order.order_ambil ? "Sudah Diambil" : "Belum Diambil",
    }));
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
