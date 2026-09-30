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
import java.util.Locale

class MersWidgetSchedule : AppWidgetProvider() {

    companion object {
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

        private fun formatDayLabel(date: LocalDate, prefix: String): String {
            val dayName = when (date.dayOfWeek) {
                DayOfWeek.MONDAY -> "Senin"
                DayOfWeek.TUESDAY -> "Selasa"
                DayOfWeek.WEDNESDAY -> "Rabu"
                DayOfWeek.THURSDAY -> "Kamis"
                DayOfWeek.FRIDAY -> "Jumat"
                DayOfWeek.SATURDAY -> "Sabtu"
                DayOfWeek.SUNDAY -> "Minggu"
                else -> ""
            }
            val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
            val monthStr = monthNames.getOrElse(date.monthValue - 1) { "" }
            val formattedDate = String.format("%02d %s", date.dayOfMonth, monthStr)
            return "📅 $prefix • $dayName, $formattedDate"
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
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("pinned_name", "") ?: ""
        val ordersJson = prefs.getString("pinned_orders", "[]") ?: "[]"

        val todayDate = LocalDate.now(ZONE)
        val tomorrowDate = todayDate.plusDays(1)
        val todayIso = todayDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowIso = tomorrowDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout_schedule)

            // App shortcut
            val appIntent = Intent(context, MainActivity::class.java)
            val appFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val appPendingIntent = PendingIntent.getActivity(context, appWidgetId + 30000, appIntent, appFlags)
            views.setOnClickPendingIntent(R.id.widget_app_btn, appPendingIntent)

            // Refresh action on refresh icon & container
            val refreshIntent = Intent(context, javaClass).apply { action = ACTION_REFRESH }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId + 40000, refreshIntent, appFlags
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_container, refreshPendingIntent)

            if (name.isEmpty()) {
                views.setTextViewText(R.id.widget_title, "📌 Ketuk untuk setup ID MeRS")
                views.setViewVisibility(R.id.card_today, View.GONE)
                views.setViewVisibility(R.id.card_tomorrow, View.GONE)
                views.setViewVisibility(R.id.card_empty, View.VISIBLE)
                views.setTextViewText(R.id.text_empty, "Belum ada ID GEN dipin\nKetuk untuk memasukkan ID")

                val configIntent = Intent(context, WidgetConfigActivity::class.java)
                val configPending = PendingIntent.getActivity(context, appWidgetId + 50000, configIntent, appFlags)
                views.setOnClickPendingIntent(R.id.widget_container, configPending)
            } else {
                views.setTextViewText(R.id.widget_title, "$name • Jadwal Makan")

                try {
                    val ordersArray = JSONArray(ordersJson)
                    val todayOrders = mutableListOf<JSONObject>()
                    val tomorrowOrders = mutableListOf<JSONObject>()

                    for (i in 0 until ordersArray.length()) {
                        val order = ordersArray.optJSONObject(i) ?: continue
                        val rawTanggal = order.optString("tanggal", order.optString("schedule_date", ""))
                        val dateIso = normalizeDateToIso(rawTanggal)

                        if (dateIso == todayIso) {
                            todayOrders.add(order)
                        } else if (dateIso == tomorrowIso) {
                            tomorrowOrders.add(order)
                        }
                    }

                    // Render Card Hari Ini
                    if (todayOrders.isNotEmpty()) {
                        views.setViewVisibility(R.id.card_today, View.VISIBLE)
                        views.setTextViewText(R.id.today_header, formatDayLabel(todayDate, "HARI INI"))

                        val first = todayOrders[0]
                        bindMealRow(views, R.id.today_meal_1_row, R.id.today_meal_1_badge, R.id.today_meal_1_menu, R.id.today_meal_1_loket, R.id.today_meal_1_status, first)

                        if (todayOrders.size > 1) {
                            views.setViewVisibility(R.id.today_meal_2_row, View.VISIBLE)
                            bindMealRow(views, R.id.today_meal_2_row, R.id.today_meal_2_badge, R.id.today_meal_2_menu, R.id.today_meal_2_loket, R.id.today_meal_2_status, todayOrders[1])
                        } else {
                            views.setViewVisibility(R.id.today_meal_2_row, View.GONE)
                        }
                    } else {
                        views.setViewVisibility(R.id.card_today, View.GONE)
                    }

                    // Render Card Besok
                    if (tomorrowOrders.isNotEmpty()) {
                        views.setViewVisibility(R.id.card_tomorrow, View.VISIBLE)
                        views.setTextViewText(R.id.tomorrow_header, formatDayLabel(tomorrowDate, "BESOK"))

                        val first = tomorrowOrders[0]
                        bindMealRow(views, R.id.tomorrow_meal_1_row, R.id.tomorrow_meal_1_badge, R.id.tomorrow_meal_1_menu, R.id.tomorrow_meal_1_loket, R.id.tomorrow_meal_1_status, first)

                        if (tomorrowOrders.size > 1) {
                            views.setViewVisibility(R.id.tomorrow_meal_2_row, View.VISIBLE)
                            bindMealRow(views, R.id.tomorrow_meal_2_row, R.id.tomorrow_meal_2_badge, R.id.tomorrow_meal_2_menu, R.id.tomorrow_meal_2_loket, R.id.tomorrow_meal_2_status, tomorrowOrders[1])
                        } else {
                            views.setViewVisibility(R.id.tomorrow_meal_2_row, View.GONE)
                        }
                    } else {
                        views.setViewVisibility(R.id.card_tomorrow, View.GONE)
                    }

                    // Empty state when no orders for either day
                    if (todayOrders.isEmpty() && tomorrowOrders.isEmpty()) {
                        views.setViewVisibility(R.id.card_empty, View.VISIBLE)
                        views.setTextViewText(R.id.text_empty, "🍽️ Tidak ada pesanan untuk Hari Ini & Besok")
                    } else {
                        views.setViewVisibility(R.id.card_empty, View.GONE)
                    }
                } catch (e: Exception) {
                    views.setViewVisibility(R.id.card_today, View.GONE)
                    views.setViewVisibility(R.id.card_tomorrow, View.GONE)
                    views.setViewVisibility(R.id.card_empty, View.VISIBLE)
                    views.setTextViewText(R.id.text_empty, "😵 Gagal memuat jadwal pesanan")
                }
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun bindMealRow(
        views: RemoteViews,
        rowId: Int,
        badgeId: Int,
        menuId: Int,
        loketId: Int,
        statusId: Int,
        order: JSONObject
    ) {
        val meal = order.optString("meal", order.optString("schedule_meal_name", "Siang"))
        val menu = order.optString("menu", order.optString("menu_name", "Menu"))
        val loket = order.optString("loket", order.optString("loket_name", ""))
        val status = order.optString("status", if (order.optBoolean("order_ambil", false)) "Sudah Diambil" else "Belum Diambil")

        views.setViewVisibility(rowId, View.VISIBLE)
        val isSiang = meal.contains("Siang", ignoreCase = true)
        views.setTextViewText(badgeId, if (isSiang) "Siang" else "Malam")
        views.setInt(badgeId, "setBackgroundResource", if (isSiang) R.drawable.badge_meal_siang else R.drawable.badge_meal_malam)

        views.setTextViewText(menuId, menu)

        if (loket.isNotBlank()) {
            views.setViewVisibility(loketId, View.VISIBLE)
            views.setTextViewText(loketId, if (loket.startsWith("Loket", ignoreCase = true)) loket else "Loket $loket")
        } else {
            views.setViewVisibility(loketId, View.GONE)
        }

        views.setTextViewText(statusId, status)
        val isSudah = status.contains("Sudah", ignoreCase = true)
        views.setInt(statusId, "setBackgroundResource", if (isSudah) R.drawable.badge_status_sudah else R.drawable.badge_status)
    }
}
