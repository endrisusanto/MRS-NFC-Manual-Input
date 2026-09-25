package id.endri.mersremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class AutoOrderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    data class MenuItem(
        val id: String,
        val name: String,
        val qtyBalance: Int,
        val isAvailable: Boolean
    )

    companion object {
        const val PREFS_NAME = "mers_auto_order_prefs"
        private const val WORK_NAME = "mers_auto_lunch_order"
        private const val TEST_WORK_NAME = "mers_auto_lunch_order_test"
        private const val SERVER_URL = "https://makan.endrisusanto.my.id"
        private const val CHANNEL_ID = "mers_auto_order_channel"
        private val ZONE = ZoneId.of("Asia/Jakarta")
        private val TARGET_TIME = LocalTime.of(7, 30) // 07:30 WIB

        fun schedule(context: Context) {
            enqueueNext(context, ExistingWorkPolicy.REPLACE)
        }

        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AutoOrderWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(TEST_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(TEST_WORK_NAME)
        }

        private fun enqueueNext(context: Context, policy: ExistingWorkPolicy) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("enabled", false)
            if (!enabled) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val delayMillis = calculateNextRunDelayMillis()
            val request = OneTimeWorkRequestBuilder<AutoOrderWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }

        fun calculateNextRunDelayMillis(now: LocalDateTime = LocalDateTime.now(ZONE)): Long {
            var next = now.with(TARGET_TIME)
            if (!now.isBefore(next)) {
                next = next.plusDays(1)
            }
            // Skip weekend if weekdays_only
            while (next.dayOfWeek == DayOfWeek.SATURDAY || next.dayOfWeek == DayOfWeek.SUNDAY) {
                next = next.plusDays(1)
            }
            return Duration.between(now, next).toMillis().coerceAtLeast(1000)
        }
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", false)
        val weekdaysOnly = prefs.getBoolean("weekdays_only", true)
        val genId = prefs.getString("gen_id", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        if (!enabled || genId.isEmpty() || password.isEmpty()) {
            return Result.success()
        }

        val now = LocalDateTime.now(ZONE)
        if (weekdaysOnly && (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY)) {
            prefs.edit()
                .putString("last_status", "Skip: Akhir pekan (Sabtu/Minggu)")
                .putLong("last_run_timestamp", System.currentTimeMillis())
                .apply()
            enqueueNext(applicationContext, ExistingWorkPolicy.REPLACE)
            return Result.success()
        }

        val todayIso = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

        return try {
            // 1. Cek apakah sudah ada pesanan makan siang hari ini
            val syncUrl = URL("$SERVER_URL/mers-proxy/widget-sync?genId=$genId")
            val syncJson = httpGetJson(syncUrl)
            if (syncJson != null && syncJson.optBoolean("success", false)) {
                val orders = syncJson.optJSONArray("orders") ?: JSONArray()
                for (i in 0 until orders.length()) {
                    val ord = orders.optJSONObject(i) ?: continue
                    val ordDate = ord.optString("schedule_date", ord.optString("date", ""))
                    val mealName = ord.optString("meal_name", "")
                    val mealId = ord.optString("meal_id", "")
                    val menuName = ord.optString("menu_name", ord.optString("item_name", "Menu"))

                    if (ordDate.contains(todayIso) && (mealId == "2" || mealName.contains("Siang", ignoreCase = true))) {
                        // Sudah memesan makan siang!
                        prefs.edit()
                            .putString("last_status", "Sudah memesan: $menuName ($todayIso)")
                            .putString("last_order_date", todayIso)
                            .putLong("last_run_timestamp", System.currentTimeMillis())
                            .apply()
                        return Result.success()
                    }
                }
            }

            // 2. Belum pesan makan siang -> Ambil stok & menu hari ini
            val stockUrl = URL("$SERVER_URL/mers-proxy/stock?date=$todayIso&meal_id=2&genId=$genId&password=$password")
            val stockJson = httpGetJson(stockUrl)

            val namesUrl = URL("$SERVER_URL/mers-proxy/menu-names?date=$todayIso&meal_id=2&genId=$genId&password=$password")
            val namesJson = httpGetJson(namesUrl)

            val menuItems = mutableListOf<MenuItem>()
            val namesObj = namesJson?.optJSONObject("names") ?: JSONObject()

            if (stockJson != null && stockJson.optBoolean("success", false)) {
                val stockArray = stockJson.optJSONArray("data") ?: JSONArray()
                for (i in 0 until stockArray.length()) {
                    val item = stockArray.optJSONObject(i) ?: continue
                    val menuId = item.optString("schedule_menu_id", "")
                    if (menuId.isEmpty()) continue
                    val name = namesObj.optString(menuId, "Menu #$menuId")
                    val balance = item.optInt("qty_balance", item.optInt("qty", 0))
                    val avail = item.optBoolean("is_available", balance > 0)
                    menuItems.add(MenuItem(menuId, name, balance, avail))
                }
            }

            val availableMenus = menuItems.filter { it.isAvailable && it.qtyBalance > 0 }
            if (availableMenus.isEmpty()) {
                val msg = "Semua menu Makan Siang untuk hari ini ($todayIso) sudah habis/tidak tersedia."
                sendNotification("⚠️ MeRS: Stok Menu Habis", msg)
                prefs.edit()
                    .putString("last_status", "Gagal: Stok menu habis")
                    .putLong("last_run_timestamp", System.currentTimeMillis())
                    .apply()
                return Result.success()
            }

            // 3. Cocokkan dengan Urutan Preferensi Pengguna
            val preferencesRaw = prefs.getString("preferences", "") ?: ""
            val allowFallback = prefs.getBoolean("allow_fallback", true)
            var selectedMenu: MenuItem? = null
            var matchedCategory = ""

            val prefsList = parsePreferences(preferencesRaw)
            for (pref in prefsList) {
                if (!pref.enabled) continue
                val match = availableMenus.firstOrNull { menu ->
                    val lowerName = menu.name.lowercase()
                    pref.keywords.any { kw -> lowerName.contains(kw.lowercase().trim()) }
                }
                if (match != null) {
                    selectedMenu = match
                    matchedCategory = pref.label
                    break
                }
            }

            // Jika tidak ada preferensi yang cocok, gunakan fallback jika diizinkan
            if (selectedMenu == null) {
                if (allowFallback) {
                    selectedMenu = availableMenus.first()
                    matchedCategory = "Fallback (Menu Pertama Tersedia)"
                } else {
                    val msg = "Tidak ada menu yang sesuai dengan preferensi Anda untuk hari ini ($todayIso)."
                    sendNotification("⚠️ MeRS: Preferensi Tidak Ditemukan", msg)
                    prefs.edit()
                        .putString("last_status", "Dilewati: Tidak ada yang cocok preferensi")
                        .putLong("last_run_timestamp", System.currentTimeMillis())
                        .apply()
                    return Result.success()
                }
            }

            // 4. Lakukan Pemesanan Otomatis
            val orderUrl = URL("$SERVER_URL/mers-proxy/order")
            val orderPayload = JSONObject().apply {
                put("genId", genId)
                put("password", password)
                put("xtanggal", todayIso)
                put("xjadwal", "2")
                put("menusaya", selectedMenu.id)
            }

            val orderRes = httpPostJson(orderUrl, orderPayload.toString())
            val orderSuccess = orderRes?.optBoolean("success", false) == true

            if (orderSuccess) {
                val successMsg = "Berhasil memesan ${selectedMenu.name} ($matchedCategory) untuk Makan Siang hari ini."
                sendNotification("🍱 Makan Siang Berhasil Dipesan!", successMsg)
                prefs.edit()
                    .putString("last_status", "Berhasil: ${selectedMenu.name}")
                    .putString("last_order_date", todayIso)
                    .putLong("last_run_timestamp", System.currentTimeMillis())
                    .apply()
            } else {
                val errMsg = orderRes?.optString("message", "Gagal submit order") ?: "Gagal submit order"
                sendNotification("❌ Gagal Auto-Pesan Makan Siang", "$errMsg (${selectedMenu.name})")
                prefs.edit()
                    .putString("last_status", "Gagal: $errMsg")
                    .putLong("last_run_timestamp", System.currentTimeMillis())
                    .apply()
            }

            Result.success()
        } catch (e: Exception) {
            prefs.edit()
                .putString("last_status", "Error: ${e.message}")
                .putLong("last_run_timestamp", System.currentTimeMillis())
                .apply()
            Result.success()
        } finally {
            enqueueNext(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    data class PreferenceCategory(
        val id: String,
        val label: String,
        val keywords: List<String>,
        val enabled: Boolean
    )

    private fun parsePreferences(raw: String): List<PreferenceCategory> {
        val defaultList = listOf(
            PreferenceCategory("daging", "🥩 Daging / Sapi", listOf("daging", "sapi", "rendang", "empal", "rawon", "gulai sapi", "beef"), true),
            PreferenceCategory("ayam", "🍗 Ayam", listOf("ayam", "chicken", "bebek", "unggas"), true),
            PreferenceCategory("ikan", "🐟 Ikan / Seafood", listOf("ikan", "tongkol", "lele", "nila", "gurame", "udang", "cumi", "seafood"), true),
            PreferenceCategory("telur", "🥚 Telur", listOf("telur", "egg", "dadar", "ceplok", "balado telur"), true)
        )

        if (raw.isBlank()) return defaultList

        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PreferenceCategory>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "cat_$i")
                val label = obj.optString("label", id)
                val enabled = obj.optBoolean("enabled", true)
                val kwArr = obj.optJSONArray("keywords") ?: JSONArray()
                val kws = mutableListOf<String>()
                for (j in 0 until kwArr.length()) {
                    kws.add(kwArr.optString(j))
                }
                list.add(PreferenceCategory(id, label, kws, enabled))
            }
            if (list.isEmpty()) defaultList else list
        } catch (e: Exception) {
            defaultList
        }
    }

    private fun sendNotification(title: String, message: String) {
        val context = applicationContext
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Pesan MeRS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi status auto-pesan makan siang MeRS"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_food)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(2001, notification)
    }

    private fun httpGetJson(url: URL): JSONObject? {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode in 200..399) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun httpPostJson(url: URL, jsonBody: String): JSONObject? {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..399) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
