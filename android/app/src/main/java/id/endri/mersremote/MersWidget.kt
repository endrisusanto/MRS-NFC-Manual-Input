package id.endri.mersremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray

open class MersWidget : AppWidgetProvider() {
    open val layoutId: Int = R.layout.widget_layout_4x2

    companion object {
        private const val ACTION_TOGGLE_SLIDE = "id.endri.mersremote.action.TOGGLE_SLIDE"
        private const val ACTION_REFRESH = "id.endri.mersremote.action.REFRESH"
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Only cancel if no widgets of either size remain
        val mgr = AppWidgetManager.getInstance(context)
        val ids4x2 = mgr.getAppWidgetIds(android.content.ComponentName(context, MersWidget4x2::class.java))
        val ids2x2 = mgr.getAppWidgetIds(android.content.ComponentName(context, MersWidget2x2::class.java))
        if (ids4x2.isEmpty() && ids2x2.isEmpty()) {
            WidgetSyncWorker.cancel(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_SLIDE) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val prefs = context.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
                val ordersJson = prefs.getString("pinned_orders", "[]") ?: "[]"
                try {
                    val ordersArray = JSONArray(ordersJson)
                    if (ordersArray.length() > 1) {
                        val currentIndex = prefs.getInt("widget_slide_index_$appWidgetId", 0)
                        val nextIndex = (currentIndex + 1) % ordersArray.length()
                        prefs.edit().putInt("widget_slide_index_$appWidgetId", nextIndex).apply()

                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
                    }
                } catch (e: Exception) {}
            }
        } else if (intent.action == ACTION_REFRESH) {
            WidgetSyncWorker.schedule(context)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("pinned_name", "") ?: ""
        val ordersJson = prefs.getString("pinned_orders", "[]") ?: "[]"

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, layoutId)
            val refreshFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val refreshIntent = Intent(context, javaClass).apply { action = ACTION_REFRESH }
            views.setOnClickPendingIntent(
                R.id.widget_refresh_btn,
                PendingIntent.getBroadcast(context, appWidgetId + 10000, refreshIntent, refreshFlags)
            )

            if (name.isEmpty()) {
                // No ID pinned — show empty state with config prompt
                views.setTextViewText(R.id.widget_title, "📌 Ketuk untuk setup")
                views.setViewVisibility(R.id.item_menu, View.GONE)
                views.setViewVisibility(R.id.item_menu_empty, View.VISIBLE)
                views.setTextViewText(R.id.item_menu_empty, "🤷‍♂️\nBelum ada ID dipin\nKetuk di sini untuk input GEN ID")
                views.setViewVisibility(R.id.widget_badge_container, View.GONE)
                views.setViewVisibility(R.id.widget_next_btn, View.GONE)

                // Click opens config activity
                val configIntent = Intent(context, WidgetConfigActivity::class.java)
                val configFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val configPending = PendingIntent.getActivity(context, appWidgetId + 5000, configIntent, configFlags)
                views.setOnClickPendingIntent(R.id.widget_container, configPending)
            } else {
                views.setTextViewText(R.id.widget_title, "👤 $name")

                try {
                    // Show ALL orders (including Sudah Diambil)
                    val ordersArray = JSONArray(ordersJson)

                    if (ordersArray.length() == 0) {
                        views.setViewVisibility(R.id.item_menu, View.GONE)
                        views.setViewVisibility(R.id.item_menu_empty, View.VISIBLE)
                        views.setTextViewText(R.id.item_menu_empty, "🤷‍♂️\nBelum ada pesanan nih~")
                        views.setViewVisibility(R.id.widget_badge_container, View.GONE)
                        views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                    } else {
                        views.setViewVisibility(R.id.item_menu, View.VISIBLE)
                        views.setViewVisibility(R.id.item_menu_empty, View.GONE)
                        val currentIndex = prefs.getInt("widget_slide_index_$appWidgetId", 0) % ordersArray.length()
                        val order = ordersArray.getJSONObject(currentIndex)

                        val meal = order.optString("meal", "")
                        val menu = order.optString("menu", "")
                        val tanggal = order.optString("tanggal", "")
                        val loket = order.optString("loket", "")
                        val status = order.optString("status", "Belum Diambil")

                        views.setTextViewText(R.id.item_menu, menu)
                        views.setViewVisibility(R.id.widget_badge_container, View.VISIBLE)

                        // Set badge texts
                        views.setTextViewText(R.id.badge_meal, meal)
                        views.setTextViewText(R.id.badge_loket, "Loket $loket")
                        views.setTextViewText(R.id.badge_date, tanggal)
                        views.setTextViewText(R.id.badge_status, status)

                        // Set dynamic meal badge color background
                        val isSiang = meal.contains("Siang", ignoreCase = true)
                        val mealBg = if (isSiang) R.drawable.badge_meal_siang else R.drawable.badge_meal_malam
                        views.setInt(R.id.badge_meal, "setBackgroundResource", mealBg)

                        // Set dynamic status badge color
                        val isSudah = status.contains("Sudah", ignoreCase = true)
                        val statusBg = if (isSudah) R.drawable.badge_meal_siang else R.drawable.badge_status
                        views.setInt(R.id.badge_status, "setBackgroundResource", statusBg)

                        if (ordersArray.length() > 1) {
                            views.setViewVisibility(R.id.widget_next_btn, View.VISIBLE)

                            val toggleIntent = Intent(context, javaClass).apply {
                                action = ACTION_TOGGLE_SLIDE
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            }
                            val toggleFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                            } else {
                                PendingIntent.FLAG_UPDATE_CURRENT
                            }
                            val togglePendingIntent = PendingIntent.getBroadcast(
                                context, appWidgetId, toggleIntent, toggleFlags
                            )
                            views.setOnClickPendingIntent(R.id.widget_next_btn, togglePendingIntent)
                        } else {
                            views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                        }
                    }
                } catch (e: Exception) {
                    views.setViewVisibility(R.id.item_menu, View.GONE)
                    views.setViewVisibility(R.id.item_menu_empty, View.VISIBLE)
                    views.setTextViewText(R.id.item_menu_empty, "😵\nError data")
                    views.setViewVisibility(R.id.widget_badge_container, View.GONE)
                    views.setViewVisibility(R.id.widget_next_btn, View.GONE)
                }

                // Click container opens main app when ID is set
                val intent = Intent(context, MainActivity::class.java)
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)
                views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class MersWidget4x2 : MersWidget() {
    override val layoutId: Int = R.layout.widget_layout_4x2
}

class MersWidget2x2 : MersWidget() {
    override val layoutId: Int = R.layout.widget_layout_2x2
}
