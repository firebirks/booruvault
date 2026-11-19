package io.github.firebirks.booruvault.probe.webrtc

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * A WebView that runs, as the app's built-in browsers do, the app's script that removes WebRTC,
 * or none with the extra mode=none, and a page that looks for every way around it. What the page
 * finds goes to the log under [TAG], one line each, read by run.sh.
 */
class ProbeActivity : Activity() {
    private fun asset(name: String): String = assets.open(name).use { it.readBytes().decodeToString() }

    private fun report(line: String) {
        Log.i(TAG, "PROBE $line")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        setContentView(view)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(TAG, message.message())
                return true
            }
        }
        report("webview ${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")
        when {
            intent.getStringExtra("mode") == "none" -> report("script none")
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) -> {
                WebViewCompat.addDocumentStartJavaScript(view, asset("disable_webrtc.js"), setOf("*"))
                report("script app")
            }
            // The app would not open its browser at all with Tor on.
            else -> report("script UNSUPPORTED")
        }
        view.loadDataWithBaseURL("https://probe.invalid/", asset("probe.html"), "text/html", "utf-8", null)
    }

    private companion object {
        const val TAG = "WebRtcProbe"
    }
}
