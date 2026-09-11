#!/usr/bin/env node

/**
 * CLI Workflow untuk Pembatalan Pesanan MERS (Samsung Intranet)
 * Mendukung pembatalan pesanan aktif, besok, maupun data yang sudah lewat.
 * 
 * Penggunaan:
 *   node scripts/cancel_order.js                     (Mode interaktif, cari & pilih pesanan)
 *   node scripts/cancel_order.js --xid <XID>         (Batalkan langsung dengan XID)
 *   node scripts/cancel_order.js --list              (Hanya tampilkan riwayat pesanan)
 *   node scripts/cancel_order.js --date <YYYY-MM-DD> (Cari pesanan pada tanggal tertentu)
 */

import readline from 'node:readline/promises';
import { stdin as input, stdout as output } from 'node:process';

// Nonaktifkan verifikasi TLS certificate untuk host intranet Samsung
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const CONFIG = {
  DEFAULT_SERVER: 'https://seinp.sec.samsung.net/MERS',
  DEFAULT_GEN_ID: '16756586',
  DEFAULT_PASSWORD: '27051994',
  MASTER_GEN_ID: '14829575',
  MASTER_PASSWORD: '23051995',
  TIMEOUT_MS: 12000,
};

// ANSI Color Helpers
const C = {
  reset: '\x1b[0m',
  bold: '\x1b[1m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  white: '\x1b[37m',
  bgRed: '\x1b[41m',
  bgGreen: '\x1b[42m',
  bgYellow: '\x1b[43m',
};

export function cleanHtmlText(text) {
  if (!text) return '';
  return text
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

export function parseIndonesianDate(dateStr) {
  const clean = cleanHtmlText(dateStr);
  const match = clean.match(/(\d{1,2})\s+([a-z]+)\s+(\d{4})/i);
  if (!match) return '';

  const months = {
    januari: '01', january: '01',
    februari: '02', february: '02',
    maret: '03', march: '03',
    april: '04',
    mei: '05', may: '05',
    juni: '06', june: '06',
    juli: '07', july: '07',
    agustus: '08', august: '08',
    september: '09',
    oktober: '10', october: '10',
    november: '11',
    desember: '12', december: '12',
  };

  const month = months[match[2].toLowerCase()];
  if (!month) return '';
  return `${match[3]}-${month}-${String(match[1]).padStart(2, '0')}`;
}

export function getDayName(isoDate) {
  if (!isoDate) return '-';
  const parts = isoDate.split('-').map(Number);
  if (parts.length !== 3) return '-';
  const d = new Date(parts[0], parts[1] - 1, parts[2]);
  const days = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
  return days[d.getDay()] || '-';
}

export function parseOrderRows(html, targetGenId = null) {
  const rows = [];
  const trMatches = html.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/gi);
  const xidRegex = /(?:xid=|data-xid=["']?|hapusPesanan[/?]|hapusPesanan\?xid=)(\d+)/i;

  for (const trMatch of trMatches) {
    const trInner = trMatch[1];
    const tdMatches = [...trInner.matchAll(/<td[^>]*>([\s\S]*?)<\/td>/gi)].map(m => cleanHtmlText(m[1]));

    if (tdMatches.length >= 7) {
      let offset = 0;
      if (targetGenId) {
        if (tdMatches[4] === String(targetGenId)) {
          offset = 0;
        } else if (tdMatches[5] === String(targetGenId)) {
          offset = 1;
        } else {
          continue; // Pesanan milik karyawan lain
        }
      } else {
        const isDate0 = !!parseIndonesianDate(tdMatches[0]);
        const isDate1 = tdMatches.length > 1 && !!parseIndonesianDate(tdMatches[1]);
        if (!isDate0 && isDate1) offset = 1;
      }

      const tanggal = tdMatches[0 + offset] || '';
      const tanggalIso = parseIndonesianDate(tanggal);
      const jadwal = tdMatches[1 + offset] || '';
      const loket = tdMatches[2 + offset] || '';
      const nama = tdMatches[3 + offset] || '';
      const gen = tdMatches[4 + offset] || '';
      const part = tdMatches[5 + offset] || '';
      const menu = tdMatches[6 + offset] || '';
      const status = tdMatches[7 + offset] || '';

      const xidMatch = trInner.match(xidRegex);
      const xid = xidMatch ? xidMatch[1] : null;

      rows.push({
        tanggal,
        tanggalIso,
        hari: getDayName(tanggalIso),
        jadwal,
        loket,
        nama,
        gen,
        part,
        menu,
        status: status.includes('Sudah') ? 'Sudah Diambil' : 'Belum Diambil',
        xid,
      });
    }
  }

  return rows;
}

export async function loginMers(serverUrl, genId, password) {
  const base = serverUrl.replace(/\/+$/, '');
  const body = new URLSearchParams({ identity: genId, password });

  const res = await fetch(`${base}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
    redirect: 'manual',
    signal: AbortSignal.timeout(CONFIG.TIMEOUT_MS),
  });

  const cookies = [];
  const rawSetCookie = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  if (rawSetCookie && rawSetCookie.length > 0) {
    for (const c of rawSetCookie) {
      const match = c.match(/ci_session=[^;]+/);
      if (match) cookies.push(match[0]);
    }
  } else {
    const single = res.headers.get('set-cookie');
    if (single) {
      const match = single.match(/ci_session=[^;]+/);
      if (match) cookies.push(match[0]);
    }
  }

  const cookie = cookies.length > 0 ? cookies.join('; ') : '';
  if (!cookie) {
    throw new Error('Gagal login ke MERS: Cookie sesi ci_session tidak diterima. Periksa GEN ID dan Password.');
  }

  return cookie;
}

export async function fetchOrderHistory(serverUrl, cookie, genId, fromDate, toDate) {
  const base = serverUrl.replace(/\/+$/, '');
  let html = '';

  // 1. Coba unduh dari /reports/generate/{from}/{to}/all/final-order
  try {
    const res = await fetch(`${base}/reports/generate/${fromDate}/${toDate}/all/final-order`, {
      headers: { Cookie: cookie },
      redirect: 'manual',
      signal: AbortSignal.timeout(CONFIG.TIMEOUT_MS),
    });
    if (res.ok) {
      const text = await res.text();
      if (text.includes('<table')) {
        html = text;
      }
    }
  } catch (_) {}

  // 2. Fallback: coba unduh dari /reports/generate/{from}/{to}/{genId}/final-order
  if (!html) {
    try {
      const res = await fetch(`${base}/reports/generate/${fromDate}/${toDate}/${genId}/final-order`, {
        headers: { Cookie: cookie },
        redirect: 'manual',
        signal: AbortSignal.timeout(CONFIG.TIMEOUT_MS),
      });
      if (res.ok) {
        const text = await res.text();
        if (text.includes('<table')) {
          html = text;
        }
      }
    } catch (_) {}
  }

  // 3. Fallback: jika akses report ditolak untuk akun biasa, coba login master account
  if (!html) {
    try {
      const masterCookie = await loginMers(serverUrl, CONFIG.MASTER_GEN_ID, CONFIG.MASTER_PASSWORD);
      const res = await fetch(`${base}/reports/generate/${fromDate}/${toDate}/all/final-order`, {
        headers: { Cookie: masterCookie },
        redirect: 'manual',
        signal: AbortSignal.timeout(CONFIG.TIMEOUT_MS),
      });
      if (res.ok) {
        const text = await res.text();
        if (text.includes('<table')) {
          html = text;
        }
      }
    } catch (_) {}
  }

  return html;
}

export async function cancelOrderByXid(serverUrl, cookie, xid) {
  const base = serverUrl.replace(/\/+$/, '');

  // 1. Coba POST ke /order/hapusPesanan
  try {
    const form = new URLSearchParams({ xid: String(xid) });
    const resPost = await fetch(`${base}/order/hapusPesanan`, {
      method: 'POST',
      headers: {
        Cookie: cookie,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: form.toString(),
      redirect: 'manual',
      signal: AbortSignal.timeout(CONFIG.TIMEOUT_MS),
    });

    if (resPost.status === 302 || resPost.status === 200) {
      return { success: true, message: `Pesanan XID ${xid} berhasil dibatalkan via POST.` };
    }
  } catch (err) {
    // Lanjut ke metode GET
  }

  // 2. Fallback: Coba GET ke /order/hapusPesanan?xid=...
  try {
    const resGet = await fetch(`${base}/order/hapusPesanan?xid=${encodeURIComponent(xid)}`, {
      method: 'GET',
      headers: { Cookie: cookie },
      redirect: 'manual',
      signal: AbortSignal.timeout(CONFIG.TIMEOUT_MS),
    });

    if (resGet.status === 302 || resGet.status === 200) {
      return { success: true, message: `Pesanan XID ${xid} berhasil dibatalkan via GET.` };
    }
  } catch (err) {
    return { success: false, message: `Koneksi gagal saat membatalkan: ${err.message}` };
  }

  return {
    success: false,
    message: `Server MERS menolak pembatalan XID ${xid}. Pesanan mungkin sudah lewat batas waktu pembatalan sistem atau sudah diproses.`,
  };
}

function formatDateISO(d) {
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function printUsage() {
  console.log(`
${C.bold}${C.cyan}🍱 MERS Order Cancellation CLI Workflow${C.reset}
Alat CLI untuk melihat riwayat dan membatalkan pesanan MERS (termasuk data besok & data yang sudah lewat).

${C.bold}PENGGUNAAN:${C.reset}
  node scripts/cancel_order.js [OPTIONS]

${C.bold}PILIHAN:${C.reset}
  ${C.green}--xid <id>${C.reset}          Batalkan langsung pesanan dengan XID tertentu tanpa prompt
  ${C.green}--list${C.reset}              Hanya tampilkan daftar pesanan tanpa membatalkan
  ${C.green}--date <yyyy-mm-dd>${C.reset} Filter pesanan pada tanggal tertentu
  ${C.green}--from <yyyy-mm-dd>${C.reset} Tanggal awal pencarian report (default: 7 hari lalu)
  ${C.green}--to <yyyy-mm-dd>${C.reset}   Tanggal akhir pencarian report (default: 5 hari ke depan)
  ${C.green}--id <gen_id>${C.reset}        GEN ID karyawan (default: 16756586 atau MERS_GEN_ID)
  ${C.green}--pass <password>${C.reset}    Password akun MERS (default: 27051994 atau MERS_PASSWORD)
  ${C.green}--server <url>${C.reset}       URL MERS (default: https://seinp.sec.samsung.net/MERS)
  ${C.green}--force, -f${C.reset}          Langsung batalkan tanpa konfirmasi tambahan
  ${C.green}--help, -h${C.reset}           Tampilkan bantuan ini

${C.bold}CONTOH:${C.reset}
  # 1. Mode Interaktif (Cari dan pilih pesanan yang ingin dibatalkan):
  node scripts/cancel_order.js

  # 2. Batalkan langsung jika sudah tahu nomor XID:
  node scripts/cancel_order.js --xid 54321

  # 3. Lihat daftar pesanan 14 hari terakhir s/d minggu depan:
  node scripts/cancel_order.js --list --from 2026-08-25 --to 2026-09-15
`);
}

async function runCli() {
  const args = process.argv.slice(2);
  const flags = {};

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--help' || arg === '-h') {
      printUsage();
      process.exit(0);
    } else if (arg === '--list' || arg === '-l') {
      flags.list = true;
    } else if (arg === '--force' || arg === '-f') {
      flags.force = true;
    } else if (arg === '--xid' || arg === '-x') {
      flags.xid = args[++i];
    } else if (arg === '--date' || arg === '-d') {
      flags.date = args[++i];
    } else if (arg === '--from') {
      flags.from = args[++i];
    } else if (arg === '--to') {
      flags.to = args[++i];
    } else if (arg === '--id' || arg === '-u') {
      flags.genId = args[++i];
    } else if (arg === '--pass' || arg === '-p') {
      flags.password = args[++i];
    } else if (arg === '--server' || arg === '-s') {
      flags.server = args[++i];
    }
  }

  const serverUrl = flags.server || process.env.MERS_SERVER_URL || CONFIG.DEFAULT_SERVER;
  const genId = flags.genId || process.env.MERS_GEN_ID || CONFIG.DEFAULT_GEN_ID;
  const password = flags.password || process.env.MERS_PASSWORD || CONFIG.DEFAULT_PASSWORD;

  const now = new Date();
  const defaultFromDate = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000); // 7 hari lalu
  const defaultToDate = new Date(now.getTime() + 5 * 24 * 60 * 60 * 1000);   // 5 hari ke depan
  const fromDate = flags.from || formatDateISO(defaultFromDate);
  const toDate = flags.to || formatDateISO(defaultToDate);

  console.log(`\n${C.bold}${C.cyan}================================================================${C.reset}`);
  console.log(`${C.bold}${C.cyan}       🍱 MERS ORDER WORKFLOW - BATALKAN PESANAN MAKAN          ${C.reset}`);
  console.log(`${C.bold}${C.cyan}================================================================${C.reset}`);
  console.log(`  🌐 Server MERS : ${C.white}${serverUrl}${C.reset}`);
  console.log(`  👤 Akun GEN ID : ${C.white}${genId}${C.reset}`);
  console.log(`  📅 Rentang Cek : ${C.white}${fromDate} s/d ${toDate}${C.reset}`);
  console.log(`${C.dim}----------------------------------------------------------------${C.reset}`);

  // 1. Melakukan Login
  process.stdout.write(`⏳ Melakukan autentikasi ke sistem MERS... `);
  let cookie = '';
  try {
    cookie = await loginMers(serverUrl, genId, password);
    console.log(`${C.green}${C.bold}BERHASIL!${C.reset}`);
  } catch (err) {
    console.log(`${C.red}${C.bold}GAGAL!${C.reset}`);
    console.error(`\n❌ ${err.message}`);
    process.exit(1);
  }

  // 2. Jika user langsung memberikan argumen --xid, eksekusi langsung
  if (flags.xid) {
    console.log(`\n🎯 Memproses pembatalan langsung untuk XID: ${C.bold}${flags.xid}${C.reset}`);
    if (!flags.force) {
      const rl = readline.createInterface({ input, output });
      const answer = await rl.question(`⚠️  Apakah Anda yakin ingin membatalkan pesanan XID ${flags.xid}? (y/N): `);
      rl.close();
      if (answer.trim().toLowerCase() !== 'y') {
        console.log(`🛑 Pembatalan dibatalkan oleh pengguna.`);
        process.exit(0);
      }
    }

    console.log(`⏳ Mengirim instruksi pembatalan ke MERS...`);
    const cancelRes = await cancelOrderByXid(serverUrl, cookie, flags.xid);
    if (cancelRes.success) {
      console.log(`\n${C.bold}${C.green}✅ ${cancelRes.message}${C.reset}\n`);
    } else {
      console.log(`\n${C.bold}${C.red}❌ ${cancelRes.message}${C.reset}\n`);
    }
    process.exit(cancelRes.success ? 0 : 1);
  }

  // 3. Mengunduh dan mem-parsing data pesanan
  process.stdout.write(`⏳ Mengunduh data pesanan (${fromDate} s/d ${toDate})... `);
  let htmlReport = '';
  try {
    htmlReport = await fetchOrderHistory(serverUrl, cookie, genId, fromDate, toDate);
  } catch (err) {
    console.log(`${C.red}Gagal mengunduh: ${err.message}${C.reset}`);
  }

  const allOrders = parseOrderRows(htmlReport, genId);
  console.log(`${C.green}${allOrders.length} pesanan ditemukan.${C.reset}`);

  // Filter jika ada parameter --date
  let orders = allOrders;
  if (flags.date) {
    orders = allOrders.filter(o => o.tanggalIso === flags.date);
    console.log(`🔍 Difilter hanya untuk tanggal: ${flags.date} (${orders.length} pesanan cocok)`);
  }

  if (orders.length === 0) {
    console.log(`\nℹ️  Tidak ada data pesanan ditemukan pada rentang tanggal tersebut.`);
    console.log(`   Tip: Gunakan parameter --from <YYYY-MM-DD> dan --to <YYYY-MM-DD> untuk rentang yang lebih luas.\n`);
    process.exit(0);
  }

  // 4. Menampilkan tabel daftar pesanan
  console.log(`\n${C.bold}DAFTAR PESANAN:${C.reset}`);
  console.log(`-------------------------------------------------------------------------------------------------------`);
  console.log(
    ` ${C.bold}No${C.reset} | ${C.bold}Tanggal / Hari${C.reset}        | ${C.bold}Jadwal${C.reset}       | ${C.bold}Loket${C.reset} | ${C.bold}Menu Makanan${C.reset}           | ${C.bold}Status${C.reset}        | ${C.bold}XID${C.reset}`
  );
  console.log(`----+-----------------------+--------------+-------+------------------------+---------------+----------`);

  orders.forEach((o, idx) => {
    const no = String(idx + 1).padStart(2, ' ');
    const tgl = (o.tanggalIso ? `${o.tanggalIso} (${o.hari.padEnd(6, ' ')})` : o.tanggal).padEnd(21, ' ');
    const jadwal = o.jadwal.padEnd(12, ' ');
    const loket = String(o.loket || '-').padEnd(5, ' ');
    const menu = (o.menu.length > 22 ? o.menu.substring(0, 20) + '..' : o.menu).padEnd(22, ' ');
    
    let statusColored = '';
    if (o.status === 'Sudah Diambil') {
      statusColored = `${C.green}Sudah Diambil${C.reset} `;
    } else {
      statusColored = `${C.yellow}Belum Diambil${C.reset} `;
    }

    const xidStr = o.xid ? `${C.bold}${C.cyan}${o.xid}${C.reset}` : `${C.dim}(tidak ada)${C.reset}`;

    console.log(` ${no} | ${tgl} | ${jadwal} | ${loket} | ${menu} | ${statusColored} | ${xidStr}`);
  });
  console.log(`-------------------------------------------------------------------------------------------------------\n`);

  if (flags.list) {
    console.log(`✅ Selesai menampilkan daftar pesanan (mode --list).\n`);
    process.exit(0);
  }

  // 5. Mode interaktif: Meminta user memilih nomor urut pesanan atau memasukkan XID
  const rl = readline.createInterface({ input, output });

  try {
    const choice = await rl.question(
      `👉 ${C.bold}Pilih Nomor pesanan (1-${orders.length}) atau ketik XID manual untuk dibatalkan (q = keluar):${C.reset} `
    );

    const trimmed = choice.trim();
    if (!trimmed || trimmed.toLowerCase() === 'q') {
      console.log(`🛑 Keluar tanpa melakukan pembatalan.`);
      rl.close();
      process.exit(0);
    }

    let targetXid = null;
    let targetOrder = null;

    const chosenIdx = parseInt(trimmed, 10);
    if (!isNaN(chosenIdx) && chosenIdx >= 1 && chosenIdx <= orders.length) {
      targetOrder = orders[chosenIdx - 1];
      targetXid = targetOrder.xid;
      if (!targetXid) {
        console.log(`\n⚠️  Pesanan #${chosenIdx} tidak memiliki XID di laporan MERS.`);
        const manualXid = await rl.question(`   Silakan masukkan XID manual jika Anda mengetahuinya (atau Enter untuk batal): `);
        if (manualXid.trim()) {
          targetXid = manualXid.trim();
        } else {
          console.log(`🛑 Dibatalkan.`);
          rl.close();
          process.exit(0);
        }
      }
    } else {
      // User mengetik XID langsung
      targetXid = trimmed;
      targetOrder = orders.find(o => o.xid === targetXid);
    }

    if (!targetXid) {
      console.log(`❌ XID tidak valid.`);
      rl.close();
      process.exit(1);
    }

    // Peringatan jika pesanan sudah diambil
    if (targetOrder && targetOrder.status === 'Sudah Diambil') {
      console.log(`\n${C.yellow}⚠️  PERINGATAN: Pesanan ini tercatat "${targetOrder.status}". Biasanya pesanan yang sudah diambil tidak dapat dibatalkan oleh sistem MERS.${C.reset}`);
    }

    // Konfirmasi akhir
    const confirmPrompt = targetOrder
      ? `⚠️  Batalkan pesanan ${targetOrder.tanggalIso || targetOrder.tanggal} [${targetOrder.jadwal}] "${targetOrder.menu}" (XID: ${targetXid})? (y/N): `
      : `⚠️  Batalkan pesanan dengan XID: ${targetXid}? (y/N): `;

    const confirm = await rl.question(confirmPrompt);
    rl.close();

    if (confirm.trim().toLowerCase() !== 'y') {
      console.log(`🛑 Pembatalan dibatalkan.`);
      process.exit(0);
    }

    console.log(`\n⏳ Mengirim instruksi pembatalan ke MERS...`);
    const cancelRes = await cancelOrderByXid(serverUrl, cookie, targetXid);

    if (cancelRes.success) {
      console.log(`\n${C.bold}${C.green}================================================================${C.reset}`);
      console.log(`${C.bold}${C.green}  ✅ ${cancelRes.message}${C.reset}`);
      console.log(`${C.bold}${C.green}================================================================${C.reset}\n`);
    } else {
      console.log(`\n${C.bold}${C.red}================================================================${C.reset}`);
      console.log(`${C.bold}${C.red}  ❌ ${cancelRes.message}${C.reset}`);
      console.log(`${C.bold}${C.red}================================================================${C.reset}\n`);
    }

  } catch (err) {
    rl.close();
    console.error(`\n❌ Terjadi kesalahan: ${err.message}\n`);
    process.exit(1);
  }
}

// Hanya jalankan runCli jika dipanggil langsung sebagai executable
const isMain = process.argv[1] && (
  process.argv[1].endsWith('cancel_order.js') || 
  process.argv[1].endsWith('cancel_order')
);

if (isMain) {
  runCli().catch(err => {
    console.error('Fatal CLI Error:', err);
    process.exit(1);
  });
}
