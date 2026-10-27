package app.booruvault.net

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * A WebView that keeps what is typed in it to itself: the keyboard is asked not to learn from
 * it, and the system autofill service is kept out, so neither saves logins or searches.
 */
@SuppressLint("ViewConstructor")
class PrivateWebView(context: Context) : WebView(context) {
    init {
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
}

/** Removes WebRTC before any page script runs: its peer connections would bypass the proxy. */
const val DISABLE_WEBRTC_SCRIPT = """
(function () {
  ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCSessionDescription', 'RTCIceCandidate']
    .forEach(function (name) {
      try { Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false }); }
      catch (e) { try { window[name] = undefined; } catch (ignored) {} }
    });
  try { Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: false }); } catch (e) {}
})();
"""
