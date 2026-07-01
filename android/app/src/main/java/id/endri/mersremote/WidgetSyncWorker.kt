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

            val url = URL("$SERVER_URL/mers-proxy/widget-sync?genId=$genId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 35000

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                prefs.edit().putString("last_sync_error", "Sync gagal HTTP $responseCode").apply()
                refreshWidgets()
                return Result.success()
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val body = reader.readText()
            reader.close()
            conn.disconnect()

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                prefs.edit().putString("last_sync_error", json.optString("message", "Sync gagal")).apply()
                refreshWidgets()
                return Result.success()
            }

            val name = json.optString("name", genId)
            val ordersArray = json.optJSONArray("orders") ?: JSONArray()

            // Update shared prefs
            prefs.edit().apply {
                if (name.isNotEmpty()) putString("pinned_name", name)
                putString("pinned_orders", ordersArray.toString())
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
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                mgr.getAppWidgetIds(ComponentName(context, MersWidget4x2::class.java)))
        }
        context.sendBroadcast(intent4x2)

        val intent2x2 = Intent(context, MersWidget2x2::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                mgr.getAppWidgetIds(ComponentName(context, MersWidget2x2::class.java)))
        }
        context.sendBroadcast(intent2x2)
    }
}
