package id.endri.mersremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MersWidgetSchedule : AppWidgetProvider() {

    companion object {
        private const val ACTION_PREV_SLIDE = "id.endri.mersremote.action.SCHEDULE_PREV_SLIDE"
        private const val ACTION_NEXT_SLIDE = "id.endri.mersremote.action.SCHEDULE_NEXT_SLIDE"
        private const val ACTION_REFRESH = "id.endri.mersremote.action.REFRESH"
        private val ZONE = ZoneId.of("Asia/Jakarta")

        private fun normalizeDateToIso(rawDate: String): String {
            val trimmed = rawDate.trim()
            if (trimmed.isEmpty()) return ""
            val isoMatch = Regex("\\d{4}-\\d{2}-\\d{2}").find(trimmed)?.value
            if (isoMatch != null) return isoMatch
            val dmyMatch = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").find(trimmed)
            if (dmyMatch != null) {
                val (d, m, y) = dmyMatch.destructured
                return String.format("%04d-%02d-%02d", y.toInt(), m.toInt(), d.toInt())
            }
            return trimmed.take(10)
        }

        private fun formatShortDate(dateStr: String): String {
            val iso = normalizeDateToIso(dateStr)
            return try {
                val localDate = LocalDate.parse(iso)
                val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
                val mStr = monthNames.getOrElse(localDate.monthValue - 1) { "" }
                String.format("%02d %s", localDate.dayOfMonth, mStr)
            } catch (e: Exception) {
                dateStr
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val mgr = AppWidgetManager.getInstance(context)
        val ids4x2 = mgr.getAppWidgetIds(android.content.ComponentName(context, MersWidget4x2::class.java))
        val ids2x2 = mgr.getAppWidgetIds(android.content.ComponentName(context, MersWidget2x2::class.java))
        val idsSchedule = mgr.getAppWidgetIds(android.content.ComponentName(context, MersWidgetSchedule::class.java))
        if (ids4x2.isEmpty() && ids2x2.isEmpty() && idsSchedule.isEmpty()) {
            WidgetSyncWorker.cancel(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            WidgetSyncWorker.syncNow(context)
            return
        }
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE &&
            !intent.getBooleanExtra(MersWidget.EXTRA_RENDER_ONLY, false)
        ) {
            WidgetSyncWorker.schedule(context)
            WidgetSyncWorker.syncNow(context)
        }

        if (intent.action == ACTION_PREV_SLIDE || intent.action == ACTION_NEXT_SLIDE) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val prefs = context.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
                val ordersJson = prefs.getString("pinned_orders", "[]") ?: "[]"
                val filteredList = getScheduleOrders(ordersJson)
                if (filteredList.isNotEmpty()) {
                    val currentIndex = prefs.getInt("widget_schedule_index_$appWidgetId", 0)
                    val newIndex = if (intent.action == ACTION_PREV_SLIDE) {
                        (currentIndex - 1 + filteredList.size) % filteredList.size
                    } else {
                        (currentIndex + 1) % filteredList.size
                    }
                    prefs.edit().putInt("widget_schedule_index_$appWidgetId", newIndex).apply()

                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
                }
            }
        }
    }

    private data class ScheduledOrder(
        val isToday: Boolean,
        val order: JSONObject
    )

    private fun getScheduleOrders(ordersJson: String): List<ScheduledOrder> {
        val list = mutableListOf<ScheduledOrder>()
        val todayDate = LocalDate.now(ZONE)
        val tomorrowDate = todayDate.plusDays(1)
        val todayIso = todayDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowIso = tomorrowDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        try {
            val arr = JSONArray(ordersJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawDate = obj.optString("tanggal", obj.optString("schedule_date", ""))
                val iso = normalizeDateToIso(rawDate)
                if (iso == todayIso) {
                    list.add(ScheduledOrder(isToday = true, order = obj))
                } else if (iso == tomorrowIso) {
                    list.add(ScheduledOrder(isToday = false, order = obj))
                }
            }
        } catch (e: Exception) {}
        return list
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("pinned_name", "") ?: ""
        val ordersJson = prefs.getString("pinned_orders", "[]") ?: "[]"

        val scheduleList = getScheduleOrders(ordersJson)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout_schedule)

            val appFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val mutableFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            // App shortcut
            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(context, appWidgetId + 30000, appIntent, appFlags)
            views.setOnClickPendingIntent(R.id.widget_app_btn, appPendingIntent)

            // Refresh action on refresh icon
            val refreshIntent = Intent(context, javaClass).apply { action = ACTION_REFRESH }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId + 40000, refreshIntent, appFlags
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPendingIntent)

            if (name.isEmpty()) {
                views.setTextViewText(R.id.widget_title, "📌 Setup MeRS")
                views.setTextViewText(R.id.widget_day_indicator, "Ketuk untuk input ID")
                views.setViewVisibility(R.id.widget_card_content, View.GONE)
                views.setViewVisibility(R.id.widget_prev_btn, View.GONE)
                views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                views.setViewVisibility(R.id.card_empty, View.VISIBLE)
                views.setTextViewText(R.id.text_empty, "Belum ada ID GEN dipin\nKetuk untuk memasukkan ID")

                val configIntent = Intent(context, WidgetConfigActivity::class.java)
                val configPending = PendingIntent.getActivity(context, appWidgetId + 50000, configIntent, appFlags)
                views.setOnClickPendingIntent(R.id.widget_container, configPending)
            } else {
                views.setTextViewText(R.id.widget_title, name)

                if (scheduleList.isEmpty()) {
                    views.setTextViewText(R.id.widget_day_indicator, "Jadwal H & H+1")
                    views.setViewVisibility(R.id.widget_card_content, View.GONE)
                    views.setViewVisibility(R.id.widget_prev_btn, View.GONE)
                    views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                    views.setViewVisibility(R.id.card_empty, View.VISIBLE)
                    views.setTextViewText(R.id.text_empty, "🍽️ Tidak ada pesanan untuk Hari Ini & Besok")

                    views.setOnClickPendingIntent(R.id.widget_container, refreshPendingIntent)
                } else {
                    views.setViewVisibility(R.id.card_empty, View.GONE)
                    views.setViewVisibility(R.id.widget_card_content, View.VISIBLE)

                    val currentIndex = prefs.getInt("widget_schedule_index_$appWidgetId", 0) % scheduleList.size
                    val item = scheduleList[currentIndex]
                    val order = item.order

                    val meal = order.optString("meal", order.optString("schedule_meal_name", "Siang"))
                    val menu = order.optString("menu", order.optString("menu_name", "Menu"))
                    val tanggal = order.optString("tanggal", order.optString("schedule_date", ""))
                    val loket = order.optString("loket", order.optString("loket_name", ""))
                    val status = order.optString("status", if (order.optBoolean("order_ambil", false)) "Sudah Diambil" else "Belum Diambil")

                    // Subheader / Indicator: "📅 HARI INI • 1/3" or "📅 BESOK • 2/3"
                    val dayLabel = if (item.isToday) "📅 HARI INI" else "📅 BESOK"
                    val countLabel = if (scheduleList.size > 1) " • ${currentIndex + 1}/${scheduleList.size}" else ""
                    views.setTextViewText(R.id.widget_day_indicator, "$dayLabel$countLabel")
                    views.setTextColor(R.id.widget_day_indicator, Color.parseColor(if (item.isToday) "#38BDF8" else "#A78BFA"))

                    // Title menu badge fullwidth
                    views.setTextViewText(R.id.item_menu, menu)

                    // 3 Group Badges (Background Putih, Text Hitam)
                    val isSiang = meal.contains("Siang", ignoreCase = true)
                    views.setTextViewText(R.id.badge_meal, if (isSiang) "Siang" else "Malam")
                    views.setInt(R.id.badge_meal, "setBackgroundResource", R.drawable.badge_white)
                    views.setTextColor(R.id.badge_meal, Color.parseColor("#0F172A"))

                    val loketText = if (loket.isNotBlank()) {
                        if (loket.startsWith("Loket", ignoreCase = true)) loket else "Loket $loket"
                    } else "Loket -"
                    views.setTextViewText(R.id.badge_loket, loketText)
                    views.setInt(R.id.badge_loket, "setBackgroundResource", R.drawable.badge_white)
                    views.setTextColor(R.id.badge_loket, Color.parseColor("#0F172A"))

                    val dateText = formatShortDate(tanggal)
                    views.setTextViewText(R.id.badge_date, dateText)
                    views.setInt(R.id.badge_date, "setBackgroundResource", R.drawable.badge_white)
                    views.setTextColor(R.id.badge_date, Color.parseColor("#0F172A"))

                    // Status badge
                    views.setTextViewText(R.id.badge_status, status)
                    val isSudah = status.contains("Sudah", ignoreCase = true)
                    views.setInt(R.id.badge_status, "setBackgroundResource", if (isSudah) R.drawable.badge_status_sudah else R.drawable.badge_status)

                    // Horizontal Carousel Navigation Buttons
                    if (scheduleList.size > 1) {
                        views.setViewVisibility(R.id.widget_prev_btn, View.VISIBLE)
                        views.setViewVisibility(R.id.widget_next_btn, View.VISIBLE)

                        val prevIntent = Intent(context, javaClass).apply {
                            action = ACTION_PREV_SLIDE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        val prevPending = PendingIntent.getBroadcast(
                            context, appWidgetId + 60000, prevIntent, mutableFlags
                        )
                        views.setOnClickPendingIntent(R.id.widget_prev_btn, prevPending)

                        val nextIntent = Intent(context, javaClass).apply {
                            action = ACTION_NEXT_SLIDE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        }
                        val nextPending = PendingIntent.getBroadcast(
                            context, appWidgetId + 70000, nextIntent, mutableFlags
                        )
                        views.setOnClickPendingIntent(R.id.widget_next_btn, nextPending)

                        // Tapping menu also advances next slide
                        views.setOnClickPendingIntent(R.id.item_menu, nextPending)
                    } else {
                        views.setViewVisibility(R.id.widget_prev_btn, View.GONE)
                        views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                        views.setOnClickPendingIntent(R.id.item_menu, refreshPendingIntent)
                    }
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
