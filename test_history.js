const axios = require('axios');
async function test() {
  try {
    const res = await axios.get('http://localhost:7465/mers-proxy/history?genId=16756586&from=2026-06-29&to=2026-06-30');
    console.log(JSON.stringify(res.data, null, 2));
  } catch (e) {
    console.log(e.message);
  }
}
test();
