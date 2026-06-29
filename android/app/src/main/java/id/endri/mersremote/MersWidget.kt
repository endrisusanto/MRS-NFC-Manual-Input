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
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("pinned_name", "") ?: ""
        val ordersJson = prefs.getString("pinned_orders", "[]") ?: "[]"

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.mers_widget)

            if (name.isEmpty()) {
                views.setTextViewText(R.id.widget_title, "Belum ada ID dipin")
                views.setViewVisibility(R.id.widget_carousel, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_title, name)
                
                try {
                    val ordersArray = JSONArray(ordersJson)
                    if (ordersArray.length() == 0) {
                        views.setViewVisibility(R.id.widget_carousel, View.VISIBLE)
                        views.setViewVisibility(R.id.item1_container, View.VISIBLE)
                        views.setViewVisibility(R.id.item2_container, View.GONE)
                        
                        views.setTextViewText(R.id.item1_menu, "Tidak ada pesanan")
                        views.setTextViewText(R.id.item1_detail, "")
                        
                        views.setBoolean(R.id.widget_carousel, "setAutoStart", false)
                    } else {
                        views.setViewVisibility(R.id.widget_carousel, View.VISIBLE)
                        
                        // Populate Item 1
                        val order1 = ordersArray.getJSONObject(0)
                        views.setViewVisibility(R.id.item1_container, View.VISIBLE)
                        views.setTextViewText(R.id.item1_menu, order1.optString("menu", ""))
                        views.setTextViewText(
                            R.id.item1_detail, 
                            "${order1.optString("meal", "")} | Loket ${order1.optString("loket", "")} | ${order1.optString("tanggal", "")}"
                        )
                        
                        // Populate Item 2 if it exists
                        if (ordersArray.length() > 1) {
                            val order2 = ordersArray.getJSONObject(1)
                            views.setViewVisibility(R.id.item2_container, View.VISIBLE)
                            views.setTextViewText(R.id.item2_menu, order2.optString("menu", ""))
                            views.setTextViewText(
                                R.id.item2_detail, 
                                "${order2.optString("meal", "")} | Loket ${order2.optString("loket", "")} | ${order2.optString("tanggal", "")}"
                            )
                            
                            // Enable auto flip for 2 items
                            views.setBoolean(R.id.widget_carousel, "setAutoStart", true)
                            views.setInt(R.id.widget_carousel, "setFlipInterval", 3000)
                        } else {
                            views.setViewVisibility(R.id.item2_container, View.GONE)
                            views.setBoolean(R.id.widget_carousel, "setAutoStart", false)
                        }
                    }
                } catch (e: Exception) {
                    views.setViewVisibility(R.id.widget_carousel, View.VISIBLE)
                    views.setViewVisibility(R.id.item1_container, View.VISIBLE)
                    views.setViewVisibility(R.id.item2_container, View.GONE)
                    views.setTextViewText(R.id.item1_menu, "Error data")
                    views.setTextViewText(R.id.item1_detail, e.message ?: "")
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

class MersWidget4x2 : MersWidget()
class MersWidget2x2 : MersWidget()
