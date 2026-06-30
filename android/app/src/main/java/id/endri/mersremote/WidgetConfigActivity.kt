package id.endri.mersremote

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*

class WidgetConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 128, 64, 128)
            setBackgroundColor(Color.parseColor("#0f172a"))
        }

        // Emoji header
        val emoji = TextView(this).apply {
            text = "📌"
            textSize = 48f
            gravity = Gravity.CENTER
        }
        root.addView(emoji, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        // Title
        val title = TextView(this).apply {
            text = "Pin ID ke Widget"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "Masukkan GEN ID karyawan untuk\nmemantau status pesanan di widget"
            textSize = 14f
            setTextColor(Color.parseColor("#94a3b8"))
            gravity = Gravity.CENTER
        }
        root.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 40 })

        // Input field
        val input = EditText(this).apply {
            hint = "Contoh: 16756586"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(32, 28, 32, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1e293b"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#334155"))
            }
        }

        // Pre-fill with existing pinned ID if any
        val prefs = getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("pinned_gen_id", "") ?: ""
        if (existing.isNotEmpty()) input.setText(existing)

        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 32 })

        // Save button
        val btn = Button(this).apply {
            text = "📌 Simpan & Pin ke Widget"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563eb"))
                cornerRadius = 24f
            }
            setOnClickListener {
                val genId = input.text.toString().trim()
                if (genId.isEmpty()) {
                    Toast.makeText(this@WidgetConfigActivity, "ID tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Save to shared prefs (name will be updated when data syncs)
                prefs.edit().apply {
                    putString("pinned_gen_id", genId)
                    putString("pinned_name", genId)  // placeholder until real sync
                    putString("pinned_orders", "[]")
                    apply()
                }

                // Trigger widget update for both sizes
                updateWidgets()

                Toast.makeText(this@WidgetConfigActivity, "ID $genId berhasil dipin! 📌", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Handle Enter key on input
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btn.performClick()
                true
            } else false
        }

        setContentView(root)
    }

    private fun updateWidgets() {
        val mgr = AppWidgetManager.getInstance(this)

        val intent4x2 = Intent(this, MersWidget4x2::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                mgr.getAppWidgetIds(ComponentName(this@WidgetConfigActivity, MersWidget4x2::class.java)))
        }
        sendBroadcast(intent4x2)

        val intent2x2 = Intent(this, MersWidget2x2::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                mgr.getAppWidgetIds(ComponentName(this@WidgetConfigActivity, MersWidget2x2::class.java)))
        }
        sendBroadcast(intent2x2)
    }
}
