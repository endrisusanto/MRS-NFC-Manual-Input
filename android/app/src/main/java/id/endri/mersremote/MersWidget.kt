package id.endri.mersremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray

class MersWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("pinned_name", "") ?: ""
        val ordersJson = prefs.getString("pinned_orders", "[]") ?: "[]"

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.mers_widget)

            if (name.isEmpty()) {
                views.setTextViewText(R.id.widget_title, "Belum ada ID dipin")
                views.setViewVisibility(R.id.widget_carousel, android.view.View.GONE)
            } else {
                views.setTextViewText(R.id.widget_title, name)
                views.setViewVisibility(R.id.widget_carousel, android.view.View.VISIBLE)

                views.removeAllViews(R.id.widget_carousel)

                try {
                    val ordersArray = JSONArray(ordersJson)
                    if (ordersArray.length() == 0) {
                        val itemViews = RemoteViews(context.packageName, R.layout.widget_item)
                        itemViews.setTextViewText(R.id.item_menu, "Tidak ada pesanan")
                        itemViews.setTextViewText(R.id.item_detail, "")
                        views.addView(R.id.widget_carousel, itemViews)
                    } else {
                        for (i in 0 until ordersArray.length()) {
                            val order = ordersArray.getJSONObject(i)
                            val itemViews = RemoteViews(context.packageName, R.layout.widget_item)

                            val meal = order.optString("meal", "")
                            val menu = order.optString("menu", "")
                            val tanggal = order.optString("tanggal", "")
                            val loket = order.optString("loket", "")

                            itemViews.setTextViewText(R.id.item_menu, menu)
                            itemViews.setTextViewText(R.id.item_detail, "$meal | Loket $loket | $tanggal")

                            views.addView(R.id.widget_carousel, itemViews)
                        }

                        if (ordersArray.length() > 1) {
                            views.setBoolean(R.id.widget_carousel, "setAutoStart", true)
                            views.setInt(R.id.widget_carousel, "setFlipInterval", 3000)
                        } else {
                            views.setBoolean(R.id.widget_carousel, "setAutoStart", false)
                        }
                    }
                } catch (e: Exception) {
                    val itemViews = RemoteViews(context.packageName, R.layout.widget_item)
                    itemViews.setTextViewText(R.id.item_menu, "Error data")
                    itemViews.setTextViewText(R.id.item_detail, e.message ?: "")
                    views.addView(R.id.widget_carousel, itemViews)
                }
            }

            val intent = Intent(context, MainActivity::class.java)
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
