package id.endri.mersremote

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class WidgetSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val WORK_NAME = "mers_widget_sync"
        private const val SYNC_NOW_WORK_NAME = "mers_widget_sync_now"
        private const val SERVER_URL = "https://makan.endrisusanto.my.id"
        private val ZONE = ZoneId.of("Asia/Jakarta")
        private val LUNCH_START = LocalTime.of(11, 30)
        private val LUNCH_END = LocalTime.of(12, 15)
        private val DINNER_START = LocalTime.of(17, 30)
        private val DINNER_END = LocalTime.of(18, 30)

        fun schedule(context: Context) {
            enqueueNext(context, ExistingWorkPolicy.REPLACE)
        }

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(SYNC_NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private fun enqueueNext(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(nextDelayMillis(), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_NOW_WORK_NAME)
        }

        private fun nextDelayMillis(now: LocalDateTime = LocalDateTime.now(ZONE)): Long {
            val time = now.toLocalTime()
            val nextStart = if (isMealTime(time)) now.plusMinutes(1) else now.plusHours(1)
            return Duration.between(now, nextStart).toMillis().coerceAtLeast(0)
        }

        private fun isMealTime(time: LocalTime): Boolean =
            (!time.isBefore(LUNCH_START) && time.isBefore(LUNCH_END)) ||
                (!time.isBefore(DINNER_START) && time.isBefore(DINNER_END))
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val genId = prefs.getString("pinned_gen_id", "") ?: ""

        return try {
            if (genId.isEmpty()) return Result.success()

            val autoPrefs = applicationContext.getSharedPreferences(AutoOrderWorker.PREFS_NAME, Context.MODE_PRIVATE)
            val pass = prefs.getString("pinned_password", "")?.takeIf { it.isNotEmpty() }
                ?: autoPrefs.getString("password", "") ?: ""
            val encGen = java.net.URLEncoder.encode(genId, "UTF-8")
            val encPass = if (pass.isNotEmpty()) java.net.URLEncoder.encode(pass, "UTF-8") else ""

            val today = java.time.LocalDate.now(ZONE)
            val fromDate = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val toDate = today.plusDays(14).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

            var name = prefs.getString("pinned_name", genId) ?: genId
            val ordersMap = mutableMapOf<String, JSONObject>()

            // 1. Fetch from /mers-proxy/history (always fetch, server/agent fallback to master session if password is blank)
            try {
                val histUrl = URL("$SERVER_URL/mers-proxy/history?genId=$encGen&password=$encPass&from=$fromDate&to=$toDate")
                val histConn = histUrl.openConnection() as HttpURLConnection
                histConn.requestMethod = "GET"
                histConn.connectTimeout = 15000
                histConn.readTimeout = 35000
                if (histConn.responseCode == 200) {
                    val body = histConn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val rows = json.optJSONArray("rows") ?: JSONArray()
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val rowName = row.optString("nama", "")
                        if (rowName.isNotBlank() && (name.isEmpty() || name == genId)) name = rowName

                        val tglIso = row.optString("tanggal_iso", row.optString("tanggal", ""))
                        val jdwl = row.optString("jadwal", "Makan Siang")
                        val key = "$tglIso|$jdwl"
                        val ordObj = JSONObject().apply {
                            put("meal", jdwl)
                            put("menu", row.optString("menu", "Menu"))
                            put("tanggal", tglIso)
                            put("loket", row.optString("loket", ""))
                            put("status", row.optString("status", "Belum Diambil"))
                        }
                        ordersMap[key] = ordObj
                    }
                }
                histConn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("WidgetSync", "History fetch error: ${e.message}")
            }

            // 2. Fetch from /mers-proxy/widget-sync (NFC Live Status)
            try {
                val syncUrl = URL("$SERVER_URL/mers-proxy/widget-sync?genId=$encGen&password=$encPass")
                val syncConn = syncUrl.openConnection() as HttpURLConnection
                syncConn.requestMethod = "GET"
                syncConn.connectTimeout = 15000
                syncConn.readTimeout = 35000
                if (syncConn.responseCode == 200) {
                    val body = syncConn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val syncName = json.optString("name", "")
                    if (syncName.isNotBlank() && syncName != genId) name = syncName

                    val ordersArr = json.optJSONArray("orders") ?: JSONArray()
                    for (i in 0 until ordersArr.length()) {
                        val ord = ordersArr.optJSONObject(i) ?: continue
                        val tgl = ord.optString("tanggal", ord.optString("schedule_date", fromDate))
                        val meal = ord.optString("meal", ord.optString("schedule_meal_name", "Makan Siang"))
                        val key = "$tgl|$meal"
                        val existing = ordersMap[key] ?: JSONObject()
                        val updated = JSONObject().apply {
                            put("meal", meal)
                            put("menu", ord.optString("menu", existing.optString("menu", "Menu")))
                            put("tanggal", tgl)
                            put("loket", ord.optString("loket", existing.optString("loket", "")))
                            put("status", ord.optString("status", existing.optString("status", "Belum Diambil")))
                        }
                        ordersMap[key] = updated
                    }
                }
                syncConn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("WidgetSync", "WidgetSync fetch error: ${e.message}")
            }

            // Convert map to sorted JSONArray by date and meal
            val sortedOrders = ordersMap.values.sortedWith(
                compareBy(
                    { it.optString("tanggal", "") },
                    { if (it.optString("meal", "").contains("Siang", ignoreCase = true)) 0 else 1 }
                )
            )
            val finalOrdersArray = JSONArray()
            for (ord in sortedOrders) {
                finalOrdersArray.put(ord)
            }

            // Update shared prefs
            prefs.edit().apply {
                if (name.isNotEmpty()) putString("pinned_name", name)
                putString("pinned_orders", finalOrdersArray.toString())
                putLong("last_sync", System.currentTimeMillis())
                putString("last_sync_error", "")
                apply()
            }

            // Trigger widget refresh
            refreshWidgets()

            Result.success()
        } catch (e: Exception) {
            prefs.edit().putString("last_sync_error", "Sync gagal: ${e.message}").apply()
            refreshWidgets()
            Result.success()
        } finally {
            enqueueNext(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    private fun refreshWidgets() {
        val context = applicationContext
        val mgr = AppWidgetManager.getInstance(context)

        val intent4x2 = Intent(context, MersWidget4x2::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(MersWidget.EXTRA_RENDER_ONLY, true)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                mgr.getAppWidgetIds(ComponentName(context, MersWidget4x2::class.java)))
        }
        context.sendBroadcast(intent4x2)

        val intent2x2 = Intent(context, MersWidget2x2::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(MersWidget.EXTRA_RENDER_ONLY, true)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                mgr.getAppWidgetIds(ComponentName(context, MersWidget2x2::class.java)))
        }
        context.sendBroadcast(intent2x2)

        val intentSchedule = Intent(context, MersWidgetSchedule::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(MersWidget.EXTRA_RENDER_ONLY, true)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                mgr.getAppWidgetIds(ComponentName(context, MersWidgetSchedule::class.java)))
        }
        context.sendBroadcast(intentSchedule)
    }
}
