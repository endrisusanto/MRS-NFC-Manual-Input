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

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set CANCELED as default: if user backs out, widget won't be added
        setResult(RESULT_CANCELED)

        // Get widget ID from intent (passed when adding new widget or reconfiguring)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDark) Color.parseColor("#090d16") else Color.parseColor("#f3f4f6")
        val surfaceColor = if (isDark) Color.parseColor("#111827") else Color.parseColor("#ffffff")
        val borderColor = if (isDark) Color.parseColor("#374151") else Color.parseColor("#e5e7eb")
        val titleColor = if (isDark) Color.parseColor("#f9fafb") else Color.parseColor("#111827")
        val subtitleColor = if (isDark) Color.parseColor("#9ca3af") else Color.parseColor("#6b7280")
        val inputBg = if (isDark) Color.parseColor("#1f2937") else Color.parseColor("#f9fafb")
        val inputBorder = if (isDark) Color.parseColor("#374151") else Color.parseColor("#d1d5db")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 64, 48, 64)
            setBackgroundColor(bgColor)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = 32f
                setStroke(2, borderColor)
            }
        }

        // Title
        val title = TextView(this).apply {
            text = "Pengaturan Widget MeRS"
            textSize = 20f
            setTextColor(titleColor)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "Masukkan GEN ID untuk menyinkronkan status pesanan makan ke Home Screen."
            textSize = 13f
            setTextColor(subtitleColor)
            gravity = Gravity.CENTER
            lineSpacingMultiplier = 1.25f
        }
        card.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 28 })

        // Label
        val label = TextView(this).apply {
            text = "GEN ID KARYAWAN"
            textSize = 11f
            setTextColor(subtitleColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.START
        }
        card.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        // Input field
        val input = EditText(this).apply {
            hint = "Contoh: 16756586"
            setHintTextColor(if (isDark) Color.parseColor("#4b5563") else Color.parseColor("#9ca3af"))
            setTextColor(titleColor)
            textSize = 16f
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(32, 28, 32, 28)
            background = GradientDrawable().apply {
                setColor(inputBg)
                cornerRadius = 18f
                setStroke(2, inputBorder)
            }
        }

        // Pre-fill with existing pinned ID if any
        val prefs = getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("pinned_gen_id", "") ?: ""
        if (existing.isNotEmpty()) input.setText(existing)

        card.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        // Save button
        val btn = Button(this).apply {
            text = "Simpan ke Widget"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isAllCaps = false
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563eb"))
                cornerRadius = 18f
            }
            setOnClickListener { saveAndFinish(input.text.toString().trim()) }
        }
        card.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })

        val refreshBtn = Button(this).apply {
            text = "Refresh Data Sekarang"
            textSize = 14f
            setTextColor(if (isDark) Color.parseColor("#e5e7eb") else Color.parseColor("#374151"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isAllCaps = false
            setPadding(32, 20, 32, 20)
            background = GradientDrawable().apply {
                setColor(inputBg)
                cornerRadius = 18f
                setStroke(2, borderColor)
            }
            setOnClickListener { refreshOrders(input.text.toString().trim()) }
        }
        card.addView(refreshBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Handle Enter key
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btn.performClick()
                true
            } else false
        }

        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
    }

    private fun saveAndFinish(genId: String) {
        if (genId.isEmpty()) {
            Toast.makeText(this, "ID tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }

        savePinnedGenId(genId)

        // Update all widgets
        updateWidgets()

        // Start periodic background sync
        WidgetSyncWorker.schedule(this)
        WidgetSyncWorker.syncNow(this)

        Toast.makeText(this, "ID $genId berhasil dipin! 📌", Toast.LENGTH_SHORT).show()

        // Return RESULT_OK so the widget is confirmed/added
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }

    private fun refreshOrders(genId: String) {
        if (genId.isEmpty()) {
            Toast.makeText(this, "ID tidak boleh kosong", Toast.LENGTH_SHORT).show()
            return
        }

        savePinnedGenId(genId)
        updateWidgets()
        WidgetSyncWorker.schedule(this)
        WidgetSyncWorker.syncNow(this)
        Toast.makeText(this, "Refresh pesanan dimulai", Toast.LENGTH_SHORT).show()
    }

    private fun savePinnedGenId(genId: String) {
        getSharedPreferences("mers_widget_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("pinned_gen_id", genId)
            putString("pinned_name", genId)
            putString("pinned_orders", "[]")
            putString("last_sync_error", "")
            apply()
        }
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
