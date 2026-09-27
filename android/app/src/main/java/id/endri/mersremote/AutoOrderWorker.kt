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
import java.net.URLEncoder
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
        private val TARGET_TIME = LocalTime.of(6, 0) // 06:00 WIB

        fun schedule(context: Context) {
            enqueueNext(context, ExistingWorkPolicy.REPLACE)
        }

        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AutoOrderWorker>()
                .setConstraints(constraints)
                .addTag(TEST_WORK_NAME)
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
        val isManualTest = tags.contains(TEST_WORK_NAME)

        if (genId.isEmpty() || password.isEmpty()) {
            val errMsg = "Gagal: Akun MeRS belum login (GEN/Password kosong)"
            prefs.edit()
                .putString("last_status", errMsg)
                .putLong("last_run_timestamp", System.currentTimeMillis())
                .apply()
            if (isManualTest) {
                sendNotification("Gagal Auto-Pesan MeRS", "Harap login terlebih dahulu di aplikasi MeRS Remote.")
            }
            return Result.success()
        }

        if (!enabled && !isManualTest) {
            return Result.success()
        }

        val now = LocalDateTime.now(ZONE)
        val startDate = now.toLocalDate()

        // Scan rentang hari kerja aktif (H s/d H+7)
        val targetDates = (0..7).map { startDate.plusDays(it.toLong()) }.filter { date ->
            if (weekdaysOnly) {
                date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
            } else true
        }

        if (targetDates.isEmpty()) {
            val statusText = "Skip: Tidak ada hari kerja aktif dalam jadwal"
            prefs.edit()
                .putString("last_status", statusText)
                .putLong("last_run_timestamp", System.currentTimeMillis())
                .apply()
            if (isManualTest) {
                sendNotification("Auto-Pesan MeRS", statusText)
            }
            enqueueNext(applicationContext, ExistingWorkPolicy.REPLACE)
            return Result.success()
        }

        return try {
            val encGen = URLEncoder.encode(genId, "UTF-8")
            val encPass = URLEncoder.encode(password, "UTF-8")

            // 1. Ambil daftar pesanan aktif via MERS history & widget-sync
            val existingLunchOrders = mutableMapOf<String, String>()
            val fromDate = targetDates.first().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val toDate = targetDates.last().format(DateTimeFormatter.ISO_LOCAL_DATE)

            val historyUrl = URL("$SERVER_URL/mers-proxy/history?genId=$encGen&password=$encPass&from=$fromDate&to=$toDate")
            val historyJson = httpGetJson(historyUrl)
            if (historyJson != null && historyJson.optBoolean("success", false)) {
                val rows = historyJson.optJSONArray("rows") ?: JSONArray()
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val tgl = row.optString("tanggal", "")
                    val jdwl = row.optString("jadwal", "")
                    val menu = row.optString("menu", "Menu")
                    if (jdwl.contains("Siang", ignoreCase = true) && tgl.isNotBlank()) {
                        val dateMatch = Regex("\\d{4}-\\d{2}-\\d{2}").find(tgl)?.value ?: tgl.take(10)
                        existingLunchOrders[dateMatch] = menu
                    }
                }
            }

            if (existingLunchOrders.isEmpty()) {
                val syncUrl = URL("$SERVER_URL/mers-proxy/widget-sync?genId=$encGen")
                val syncJson = httpGetJson(syncUrl)
                if (syncJson != null && syncJson.optBoolean("success", false)) {
                    val orders = syncJson.optJSONArray("orders") ?: JSONArray()
                    for (i in 0 until orders.length()) {
                        val ord = orders.optJSONObject(i) ?: continue
                        val ordDate = ord.optString("schedule_date", ord.optString("date", ""))
                        val mealName = ord.optString("meal_name", "")
                        val mealId = ord.optString("meal_id", "")
                        val menuName = ord.optString("menu_name", ord.optString("item_name", "Menu"))

                        val isLunch = mealId == "2" || mealName.contains("Siang", ignoreCase = true)
                        if (isLunch && ordDate.isNotBlank()) {
                            val dateMatch = Regex("\\d{4}-\\d{2}-\\d{2}").find(ordDate)?.value ?: ordDate.take(10)
                            existingLunchOrders[dateMatch] = menuName
                        }
                    }
                }
            }

            // 2. Baca preferensi menu pengguna
            val preferencesRaw = prefs.getString("preferences", "") ?: ""
            val allowFallback = prefs.getBoolean("allow_fallback", true)
            val prefsList = parsePreferences(preferencesRaw)

            val bookedResults = mutableListOf<String>()
            val failedResults = mutableListOf<String>()
            val skippedAlreadyOrdered = mutableListOf<String>()
            val noAvailableMenuDays = mutableListOf<String>()

            // 3. Cek dan pesan setiap hari kerja yang belum ada pesanan
            for (targetDate in targetDates) {
                val dateIso = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

                if (existingLunchOrders.containsKey(dateIso)) {
                    skippedAlreadyOrdered.add("$dateIso (${existingLunchOrders[dateIso]})")
                    continue
                }

                val encDate = URLEncoder.encode(dateIso, "UTF-8")
                val stockUrl = URL("$SERVER_URL/mers-proxy/stock?date=$encDate&meal_id=2&genId=$encGen&password=$encPass")
                val stockJson = httpGetJson(stockUrl)

                if (stockJson == null) {
                    failedResults.add("$dateIso: Gagal koneksi ke server MeRS")
                    continue
                }

                if (stockJson.has("success") && !stockJson.optBoolean("success", true)) {
                    val errMsg = stockJson.optString("message", "Gagal cek stok")
                    failedResults.add("$dateIso: $errMsg")
                    continue
                }

                val stockArray = when {
                    stockJson.has("data") -> stockJson.optJSONArray("data")
                    stockJson.has("menus") -> stockJson.optJSONArray("menus")
                    stockJson.has("items") -> stockJson.optJSONArray("items")
                    else -> null
                }

                if (stockArray == null || stockArray.length() == 0) {
                    noAvailableMenuDays.add(dateIso)
                    continue
                }

                val namesUrl = URL("$SERVER_URL/mers-proxy/menu-names?date=$encDate&meal_id=2&genId=$encGen&password=$encPass")
                val namesJson = httpGetJson(namesUrl)
                val namesObj = namesJson?.optJSONObject("names") ?: namesJson?.optJSONObject("data") ?: JSONObject()

                val menuItems = mutableListOf<MenuItem>()
                for (i in 0 until stockArray.length()) {
                    val item = stockArray.optJSONObject(i) ?: continue
                    val menuId = item.optString("schedule_menu_id", item.optString("id", ""))
                    if (menuId.isEmpty()) continue
                    val name = namesObj.optString(menuId, item.optString("name", item.optString("menu_name", "Menu #$menuId")))
                    val balance = item.optInt("qty_balance", item.optString("qty_balance", "0").toIntOrNull() ?: item.optInt("qty", 0))
                    val isAvailRaw = item.optBoolean("is_available", balance > 0) || item.optString("is_available") == "1" || item.optString("is_available").equals("true", true)
                    val isAvailable = (isAvailRaw || balance > 0) && balance > 0
                    menuItems.add(MenuItem(menuId, name, balance, isAvailable))
                }

                val availableMenus = menuItems.filter { it.isAvailable && it.qtyBalance > 0 }
                if (availableMenus.isEmpty()) {
                    noAvailableMenuDays.add("$dateIso (semua menu habis)")
                    continue
                }

                // 4. Cocokkan dengan Urutan Preferensi Pengguna
                var selectedMenu: MenuItem? = null
                var matchedCategory = ""

                for (pref in prefsList) {
                    if (!pref.enabled) continue
                    val match = availableMenus.firstOrNull { menu ->
                        val lowerName = menu.name.lowercase()
                        pref.keywords.any { kw -> kw.isNotBlank() && lowerName.contains(kw.lowercase().trim()) }
                    }
                    if (match != null) {
                        selectedMenu = match
                        matchedCategory = pref.label
                        break
                    }
                }

                // Gunakan fallback jika tidak ada preferensi cocok
                if (selectedMenu == null) {
                    if (allowFallback) {
                        selectedMenu = availableMenus.first()
                        matchedCategory = "Fallback"
                    } else {
                        noAvailableMenuDays.add("$dateIso (preferensi tidak cocok)")
                        continue
                    }
                }

                // 5. Eksekusi Pemesanan Otomatis
                val orderUrl = URL("$SERVER_URL/mers-proxy/order")
                val orderPayload = JSONObject().apply {
                    put("genId", genId)
                    put("password", password)
                    put("xtanggal", dateIso)
                    put("xjadwal", "2")
                    put("menusaya", selectedMenu.id)
                }

                val orderRes = httpPostJson(orderUrl, orderPayload.toString())
                val orderSuccess = orderRes?.optBoolean("success", false) == true

                if (orderSuccess) {
                    existingLunchOrders[dateIso] = selectedMenu.name
                    bookedResults.add("$dateIso: ${selectedMenu.name} ($matchedCategory)")
                } else {
                    val errMsg = orderRes?.optString("message", "Gagal submit order") ?: "Gagal submit order"
                    failedResults.add("$dateIso: $errMsg")
                }
            }

            // 6. Ringkasan Status & Notifikasi
            when {
                bookedResults.isNotEmpty() -> {
                    val notifyBody = bookedResults.joinToString("\n")
                    sendNotification("Makan Siang Berhasil Dipesan", notifyBody)
                    prefs.edit()
                        .putString("last_status", "Berhasil: " + bookedResults.joinToString(", "))
                        .putString("last_order_date", bookedResults.last().substringBefore(":"))
                        .putLong("last_run_timestamp", System.currentTimeMillis())
                        .apply()
                }
                failedResults.isNotEmpty() -> {
                    val errBody = failedResults.joinToString("\n")
                    sendNotification("Gagal Auto-Pesan Makan Siang", errBody)
                    prefs.edit()
                        .putString("last_status", "Gagal: " + failedResults.joinToString(", "))
                        .putLong("last_run_timestamp", System.currentTimeMillis())
                        .apply()
                }
                skippedAlreadyOrdered.isNotEmpty() && (skippedAlreadyOrdered.size == targetDates.size) -> {
                    val statusText = "Semua sudah dipesan (${skippedAlreadyOrdered.size} hari kerja)"
                    if (isManualTest) {
                        sendNotification("Auto-Pesan: Semua Sudah Dipesan", skippedAlreadyOrdered.joinToString("\n"))
                    }
                    prefs.edit()
                        .putString("last_status", statusText)
                        .putLong("last_run_timestamp", System.currentTimeMillis())
                        .apply()
                }
                noAvailableMenuDays.isNotEmpty() -> {
                    val statusText = if (skippedAlreadyOrdered.isNotEmpty()) {
                        "Sudah pesan (${skippedAlreadyOrdered.size} hari), hari lain: ${noAvailableMenuDays.joinToString(", ")}"
                    } else {
                        "Menu belum buka / stok kosong: ${noAvailableMenuDays.joinToString(", ")}"
                    }
                    if (isManualTest) {
                        sendNotification("Auto-Pesan: Menu Belum Tersedia", statusText)
                    }
                    prefs.edit()
                        .putString("last_status", statusText)
                        .putLong("last_run_timestamp", System.currentTimeMillis())
                        .apply()
                }
                else -> {
                    val statusText = if (skippedAlreadyOrdered.isNotEmpty()) {
                        "Sudah pesan (${skippedAlreadyOrdered.size} hari)"
                    } else {
                        "Menu belum buka / stok kosong untuk hari mendatang"
                    }
                    if (isManualTest) {
                        sendNotification("Auto-Pesan MeRS", statusText)
                    }
                    prefs.edit()
                        .putString("last_status", statusText)
                        .putLong("last_run_timestamp", System.currentTimeMillis())
                        .apply()
                }
            }

            Result.success()
        } catch (e: Exception) {
            val errMsg = "Error: ${e.message}"
            prefs.edit()
                .putString("last_status", errMsg)
                .putLong("last_run_timestamp", System.currentTimeMillis())
                .apply()
            if (isManualTest) {
                sendNotification("Gagal Auto-Pesan Makan Siang", e.message ?: "Terjadi kesalahan internal.")
            }
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
            PreferenceCategory("daging", "Daging / Sapi", listOf("daging", "sapi", "rendang", "empal", "rawon", "gulai sapi", "beef", "kambing", "bistik", "iga", "rolade", "semur daging"), true),
            PreferenceCategory("ayam", "Ayam", listOf("ayam", "chicken", "bebek", "unggas", "fillet ayam", "katsu", "nasi goreng", "nasgor"), true),
            PreferenceCategory("ikan", "Ikan / Seafood", listOf("ikan", "tongkol", "lele", "nila", "gurame", "udang", "cumi", "seafood", "kakap", "tuna", "patin", "bandeng", "salmon", "dori"), true),
            PreferenceCategory("telur", "Telur", listOf("telur", "egg", "dadar", "ceplok", "balado telur", "omelet", "omelete", "omelette", "martabak"), true)
        )

        val defaultKeywordMap = defaultList.associate { it.id to it.keywords }

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
                val kws = mutableSetOf<String>()
                for (j in 0 until kwArr.length()) {
                    val kw = kwArr.optString(j).trim()
                    if (kw.isNotEmpty()) kws.add(kw)
                }
                defaultKeywordMap[id]?.let { kws.addAll(it) }

                list.add(PreferenceCategory(id, label, kws.toList(), enabled))
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
