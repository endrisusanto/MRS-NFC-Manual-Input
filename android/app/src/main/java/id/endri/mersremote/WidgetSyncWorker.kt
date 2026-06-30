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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class WidgetSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val WORK_NAME = "mers_widget_sync"
        private const val SYNC_NOW_WORK_NAME = "mers_widget_sync_now"
        private const val MERS_BASE_URL = "http://107.102.8.148/MERS"
        private const val MASTER_GEN_ID = "14829575"
        private const val MASTER_PASSWORD = "23051995"

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
            val loginBody = "identity=${enc(MASTER_GEN_ID)}&password=${enc(MASTER_PASSWORD)}"
            val login = mersRequest("POST", "/auth/login", loginBody)
            val cookie = login.cookie
            if (cookie.isEmpty()) {
                prefs.edit().putString("last_sync_error", "Login MERS gagal").apply()
                refreshWidgets()
                return Result.retry()
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val today = sdf.format(Date())
            val future = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }.time
            val report = mersRequest("GET", "/reports/generate/$today/${sdf.format(future)}/all/final-order", cookie = cookie)
            if (report.status !in 200..299) {
                prefs.edit().putString("last_sync_error", "Report gagal HTTP ${report.status}").apply()
                refreshWidgets()
                return Result.retry()
            }

            val parsed = parseOrders(report.body, genId)

            // Update shared prefs
            prefs.edit().apply {
                if (parsed.first.isNotEmpty()) putString("pinned_name", parsed.first)
                putString("pinned_orders", parsed.second.toString())
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
            Result.retry()
        }
    }

    private data class MersResponse(val status: Int, val body: String, val cookie: String)

    private fun mersRequest(method: String, path: String, body: String? = null, cookie: String = ""): MersResponse {
        val conn = (URL("$MERS_BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "text/html,application/json,*/*")
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val text = try {
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } catch (_: Exception) {
            conn.errorStream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } } ?: ""
        }
        val setCookie = conn.headerFields.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
            ?.value.orEmpty()
            .joinToString("; ") { it.substringBefore(";") }
        val response = MersResponse(conn.responseCode, text, setCookie)
        conn.disconnect()
        return response
    }

    private fun parseOrders(html: String, genId: String): Pair<String, JSONArray> {
        var name = ""
        val orders = JSONArray()
        val trRe = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
        val tdRe = Regex("<td[^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
        for (tr in trRe.findAll(html)) {
            val cells = tdRe.findAll(tr.groupValues[1]).map { cleanCell(it.groupValues[1]) }.toList()
            if (cells.size >= 7 && cells[4] == genId) {
                if (name.isEmpty()) name = cells[3]
                val statusRaw = cells.getOrNull(7).orEmpty()
                orders.put(JSONObject().apply {
                    put("meal", cells[1])
                    put("menu", cells[6])
                    put("tanggal", cells[0])
                    put("loket", cells[2])
                    put("status", if (statusRaw.contains("Sudah", ignoreCase = true)) "Sudah Diambil" else "Belum Diambil")
                })
            }
        }
        return Pair(name, orders)
    }

    private fun cleanCell(html: String): String = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

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
