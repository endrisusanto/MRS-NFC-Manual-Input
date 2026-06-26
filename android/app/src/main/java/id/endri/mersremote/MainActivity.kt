package id.endri.mersremote

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.math.BigInteger

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED), IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
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
            })();
            """.trimIndent(),
            null
        )
    }

    private fun String.js(): String = org.json.JSONObject.quote(this)
}
