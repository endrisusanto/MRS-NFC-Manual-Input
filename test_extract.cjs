const http = require('http');

function req(options, body) {
  return new Promise((resolve, reject) => {
    const request = http.request(options, response => {
      let data = '';
      response.on('data', chunk => data += chunk);
      response.on('end', () => resolve({ body: data, headers: response.headers, statusCode: response.statusCode }));
    });
    request.on('error', reject);
    if (body) request.write(body);
    request.end();
  });
}

async function run() {
  console.log("Logging in...");
  const body = "identity=16756586&password=27051994";
  const loginRes = await req({
    hostname: '107.102.8.148', port: 80, path: '/MERS/auth/login', method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': body.length }
  }, body);

  let cookie = loginRes.headers['set-cookie'] ? loginRes.headers['set-cookie'].map(c => c.split(';')[0]).join('; ') : '';
  console.log("Cookie:", cookie);

  console.log("Fetching /order/pilihmenu...");
  const menuRes = await req({
    hostname: '107.102.8.148', port: 80, path: '/MERS/order/pilihmenu', method: 'GET',
    headers: { 'Cookie': cookie }
  });

  const m1 = /\/user\/edit\/(\d+)/.exec(menuRes.body);
  const m2 = /finalorder\/view\/(\d+)/.exec(menuRes.body);
  const m3 = /user_id=["'](\d+)["']/.exec(menuRes.body);
  const m4 = /uid=["'](\d+)["']/.exec(menuRes.body);
  const m5 = /href=["'][^"']*\/(\d+)["'][^>]*>Profil/.exec(menuRes.body);

  console.log("Extracted from pilihmenu:", { edit: m1?.[1], view: m2?.[1], user_id: m3?.[1], uid: m4?.[1], profile: m5?.[1] });

  console.log("Fetching /dashboard...");
  const dashRes = await req({
    hostname: '107.102.8.148', port: 80, path: '/MERS/dashboard', method: 'GET',
    headers: { 'Cookie': cookie }
  });

  const d1 = /\/user\/edit\/(\d+)/.exec(dashRes.body);
  const d2 = /finalorder\/view\/(\d+)/.exec(dashRes.body);
  const d3 = /href=["'][^"']*\/(\d+)["'][^>]*>Profil/.exec(dashRes.body);

  console.log("Extracted from dashboard:", { edit: d1?.[1], view: d2?.[1], profile: d3?.[1] });
}
run().catch(console.error);
