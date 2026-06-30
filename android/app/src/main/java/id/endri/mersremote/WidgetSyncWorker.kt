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
import java.util.concurrent.TimeUnit

class WidgetSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val WORK_NAME = "mers_widget_sync"
        private const val SYNC_NOW_WORK_NAME = "mers_widget_sync_now"
        private const val SERVER_URL = "https://makan.endrisusanto.my.id"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)

            val syncNow = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(SYNC_NOW_WORK_NAME, ExistingWorkPolicy.REPLACE, syncNow)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_NOW_WORK_NAME)
        }
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val genId = prefs.getString("pinned_gen_id", "") ?: ""

        if (genId.isEmpty()) return Result.success()

        return try {
            val url = URL("$SERVER_URL/mers-proxy/widget-sync?genId=$genId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            if (responseCode != 200) return Result.retry()

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val body = reader.readText()
            reader.close()
            conn.disconnect()

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return Result.retry()

            val name = json.optString("name", genId)
            val ordersArray = json.optJSONArray("orders") ?: JSONArray()

            // Update shared prefs
            prefs.edit().apply {
                if (name.isNotEmpty()) putString("pinned_name", name)
                putString("pinned_orders", ordersArray.toString())
                putLong("last_sync", System.currentTimeMillis())
                apply()
            }

            // Trigger widget refresh
            refreshWidgets()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
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
