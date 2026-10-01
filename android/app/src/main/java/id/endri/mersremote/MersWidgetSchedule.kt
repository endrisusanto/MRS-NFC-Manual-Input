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

        private fun formatScheduleDate(dateStr: String, isToday: Boolean): String {
            val iso = normalizeDateToIso(dateStr)
            val prefix = if (isToday) "Hari Ini" else "Besok"
            return try {
                val localDate = LocalDate.parse(iso)
                val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
                val mStr = monthNames.getOrElse(localDate.monthValue - 1) { "" }
                String.format("%s, %02d %s", prefix, localDate.dayOfMonth, mStr)
            } catch (e: Exception) {
                "$prefix, $dateStr"
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

            // App shortcut button
            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(context, appWidgetId + 30000, appIntent, appFlags)
            views.setOnClickPendingIntent(R.id.widget_app_btn, appPendingIntent)

            // Refresh action when clicking widget container
            val refreshIntent = Intent(context, javaClass).apply { action = ACTION_REFRESH }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId + 40000, refreshIntent, appFlags
            )
            views.setOnClickPendingIntent(R.id.widget_container, refreshPendingIntent)

            if (name.isEmpty()) {
                views.setTextViewText(R.id.widget_title, "📌 Ketuk untuk setup")
                views.setViewVisibility(R.id.item_menu, View.GONE)
                views.setViewVisibility(R.id.item_menu_empty, View.VISIBLE)
                views.setTextViewText(R.id.item_menu_empty, "🤷‍♂️\nBelum ada ID dipin\nKetuk di sini untuk input GEN ID")
                views.setTextColor(R.id.item_menu_empty, Color.parseColor("#94A3B8"))
                views.setViewVisibility(R.id.widget_badge_container, View.GONE)
                views.setViewVisibility(R.id.badge_status, View.GONE)
                views.setViewVisibility(R.id.widget_prev_btn, View.GONE)
                views.setViewVisibility(R.id.widget_next_btn, View.GONE)

                val configIntent = Intent(context, WidgetConfigActivity::class.java)
                val configPending = PendingIntent.getActivity(context, appWidgetId + 50000, configIntent, appFlags)
                views.setOnClickPendingIntent(R.id.widget_container, configPending)
            } else {
                views.setTextViewText(R.id.widget_title, name)

                if (scheduleList.isEmpty()) {
                    views.setViewVisibility(R.id.item_menu, View.GONE)
                    views.setViewVisibility(R.id.item_menu_empty, View.VISIBLE)
                    views.setTextViewText(R.id.item_menu_empty, "🍽️ Tidak ada pesanan untuk Hari Ini & Besok")
                    views.setTextColor(R.id.item_menu_empty, Color.parseColor("#FBBF24"))
                    views.setViewVisibility(R.id.widget_badge_container, View.GONE)
                    views.setViewVisibility(R.id.badge_status, View.GONE)
                    views.setViewVisibility(R.id.widget_prev_btn, View.GONE)
                    views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                } else {
                    views.setViewVisibility(R.id.item_menu, View.VISIBLE)
                    views.setViewVisibility(R.id.item_menu_empty, View.GONE)
                    views.setViewVisibility(R.id.widget_badge_container, View.VISIBLE)
                    views.setViewVisibility(R.id.badge_status, View.VISIBLE)

                    val currentIndex = prefs.getInt("widget_schedule_index_$appWidgetId", 0) % scheduleList.size
                    val item = scheduleList[currentIndex]
                    val order = item.order

                    val meal = order.optString("meal", order.optString("schedule_meal_name", "Siang"))
                    val menu = order.optString("menu", order.optString("menu_name", "Menu"))
                    val tanggal = order.optString("tanggal", order.optString("schedule_date", ""))
                    val loket = order.optString("loket", order.optString("loket_name", ""))
                    val status = order.optString("status", if (order.optBoolean("order_ambil", false)) "Sudah Diambil" else "Belum Diambil")

                    // Middle row menu text
                    views.setTextViewText(R.id.item_menu, menu)

                    // Badges: Waktu Makan, Loket, Tanggal
                    val isSiang = meal.contains("Siang", ignoreCase = true)
                    views.setTextViewText(R.id.badge_meal, if (isSiang) "Makan Siang" else "Makan Malam")
                    val loketText = if (loket.isNotBlank()) {
                        if (loket.startsWith("Loket", ignoreCase = true)) loket else "Loket $loket"
                    } else "Loket -"
                    views.setTextViewText(R.id.badge_loket, loketText)

                    val dateText = formatScheduleDate(tanggal, item.isToday)
                    views.setTextViewText(R.id.badge_date, dateText)

                    // Dynamic badge background and text colors matching MersWidget
                    val mealBg = if (isSiang) R.drawable.badge_meal_siang else R.drawable.badge_meal_malam
                    views.setInt(R.id.badge_meal, "setBackgroundResource", mealBg)
                    views.setInt(R.id.badge_loket, "setBackgroundResource", mealBg)
                    views.setInt(R.id.badge_date, "setBackgroundResource", mealBg)
                    val badgeTextColor = Color.parseColor(if (isSiang) "#111827" else "#F8FAFC")
                    views.setTextColor(R.id.badge_meal, badgeTextColor)
                    views.setTextColor(R.id.badge_loket, badgeTextColor)
                    views.setTextColor(R.id.badge_date, badgeTextColor)

                    // Status badge
                    views.setTextViewText(R.id.badge_status, status)
                    val isSudah = status.contains("Sudah", ignoreCase = true)
                    views.setInt(R.id.badge_status, "setBackgroundResource", if (isSudah) R.drawable.badge_status_sudah else R.drawable.badge_status)

                    // Carousel Navigation Buttons
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
                    } else {
                        views.setViewVisibility(R.id.widget_prev_btn, View.GONE)
                        views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                    }
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
