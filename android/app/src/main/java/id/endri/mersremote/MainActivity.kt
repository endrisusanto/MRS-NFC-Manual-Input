package id.endri.mersremote

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED), IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WebView.setDataDirectorySuffix("mersremote")
            } catch (e: Exception) {}
        }
        webView = WebView(this)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                android.util.Log.d("MeRSWeb", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.userAgentString = "${webView.settings.userAgentString} MeRSRemoteAndroid/1.0"
        webView.addJavascriptInterface(AndroidNfcBridge(), "AndroidNfc")

        setContentView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        webView.loadUrl("file:///android_asset/index.html")

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        requestNotificationPermission()
        AutoOrderWorker.schedule(this)
        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        handleNfc(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, null)
    }

    override fun onPause() {
        nfcAdapter?.disableForegroundDispatch(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfc(intent)
    }

    private inner class AndroidNfcBridge {
        @JavascriptInterface
        fun requestScan() {
            runOnUiThread {
                when {
                    nfcAdapter == null -> {
                        Toast.makeText(this@MainActivity, "Perangkat ini tidak mendukung NFC.", Toast.LENGTH_LONG).show()
                        setWebStatus("Perangkat ini tidak mendukung NFC.", "bad")
                    }
                    nfcAdapter?.isEnabled != true -> {
                        Toast.makeText(this@MainActivity, "Aktifkan NFC terlebih dahulu.", Toast.LENGTH_LONG).show()
                        setWebStatus("Aktifkan NFC terlebih dahulu.", "bad")
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    }
                    else -> {
                        Toast.makeText(this@MainActivity, "Tempelkan kartu NFC.", Toast.LENGTH_SHORT).show()
                        setWebStatus("Tempelkan kartu NFC.", "warn")
                    }
                }
            }
        }

        @JavascriptInterface
        fun pingMers(url: String): Boolean {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3500
                conn.readTimeout = 3500
                conn.useCaches = false
                conn.responseCode in 200..399
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun getJson(url: String): String {
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.useCaches = false
                conn.setRequestProperty("Accept", "application/json")
                val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            } catch (e: Exception) {
                """{"success":false,"message":${(e.message ?: "HTTP request gagal").js()}}"""
            }
        }

        @JavascriptInterface
        fun postJson(url: String, json: String): String {
            return try {
                val body = json.toByteArray(Charsets.UTF_8)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.useCaches = false
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use { it.write(body) }
                val stream = if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            } catch (e: Exception) {
                """{"success":false,"message":${(e.message ?: "HTTP request gagal").js()}}"""
            }
        }

        @JavascriptInterface
        fun getAutoOrderConfig(): String {
            val prefs = getSharedPreferences(AutoOrderWorker.PREFS_NAME, Context.MODE_PRIVATE)
            val json = org.json.JSONObject().apply {
                put("enabled", prefs.getBoolean("enabled", false))
                put("weekdays_only", prefs.getBoolean("weekdays_only", true))
                put("gen_id", prefs.getString("gen_id", ""))
                put("has_password", (prefs.getString("password", "") ?: "").isNotEmpty())
                put("preferences", prefs.getString("preferences", "[]"))
                put("allow_fallback", prefs.getBoolean("allow_fallback", true))
                put("last_status", prefs.getString("last_status", "Belum pernah berjalan"))
                put("last_order_date", prefs.getString("last_order_date", "-"))
                put("last_run_timestamp", prefs.getLong("last_run_timestamp", 0))
            }
            return json.toString()
        }

        @JavascriptInterface
        fun saveAutoOrderConfig(jsonStr: String): Boolean {
            return try {
                val obj = org.json.JSONObject(jsonStr)
                val prefs = getSharedPreferences(AutoOrderWorker.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()

                if (obj.has("enabled")) editor.putBoolean("enabled", obj.optBoolean("enabled"))
                if (obj.has("weekdays_only")) editor.putBoolean("weekdays_only", obj.optBoolean("weekdays_only", true))
                if (obj.has("gen_id")) editor.putString("gen_id", obj.optString("gen_id"))
                if (obj.has("password") && obj.optString("password").isNotEmpty()) {
                    editor.putString("password", obj.optString("password"))
                }
                if (obj.has("preferences")) editor.putString("preferences", obj.optString("preferences"))
                if (obj.has("allow_fallback")) editor.putBoolean("allow_fallback", obj.optBoolean("allow_fallback", true))

                editor.apply()

                if (prefs.getBoolean("enabled", false)) {
                    AutoOrderWorker.schedule(this@MainActivity)
                } else {
                    AutoOrderWorker.cancel(this@MainActivity)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun syncAutoOrderCredentials(genId: String, pass: String): Boolean {
            return try {
                val prefs = getSharedPreferences(AutoOrderWorker.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("gen_id", genId)
                    .putString("password", pass)
                    .apply()
                if (prefs.getBoolean("enabled", false)) {
                    AutoOrderWorker.schedule(this@MainActivity)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun triggerAutoOrderNow(): String {
            return try {
                AutoOrderWorker.runNow(this@MainActivity)
                """{"success":true,"message":"Proses auto-order dijalankan di background"}"""
            } catch (e: Exception) {
                """{"success":false,"message":${(e.message ?: "Gagal menjalankan worker").js()}}"""
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun setWebStatus(message: String, kind: String) {
        webView.evaluateJavascript(
            """
            (() => {
              const status = document.getElementById('status');
              if (status) {
                status.textContent = ${message.js()};
                status.className = 'status $kind';
              }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun handleNfc(intent: Intent?) {
        val tag = intent?.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val hex = tag.id.joinToString("") { "%02X".format(it) }
        val uidHex = tag.id.reversedArray().joinToString("") { "%02X".format(it) }
        val decimal = BigInteger(uidHex, 16).toString()
        Toast.makeText(this, "NFC: $decimal", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript(
            """
            (() => {
              const input = document.getElementById('uid');
              if (input) {
                input.value = '$decimal';
                input.dispatchEvent(new Event('input', { bubbles: true }));
              }
              const status = document.getElementById('status');
              if (status) {
                status.textContent = 'NFC terbaca: $hex -> $uidHex';
                status.className = 'status ok';
              }
              if (document.body.classList.contains('nfc-mode') && typeof send === 'function') {
                send();
              }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun String.js(): String = org.json.JSONObject.quote(this)
}
