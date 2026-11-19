# WebRTC probe

Checks, on a phone, that the script the built-in browsers run (`DISABLE_WEBRTC_SCRIPT` in
`app/src/main/kotlin/io/github/firebirks/booruvault/net/PrivateWebView.kt`) still keeps WebRTC
out of every frame a page can reach. WebRTC's connections do not go through the proxy, and a STUN
server would learn the phone's own address; the WebView has no setting that turns WebRTC off, so
the app removes it by script, and what a script can do depends on the WebView. Run the probe when
the phone's WebView updates, and after any change to the script.

```sh
tools/webrtc-probe/run.sh
```

It needs one phone over adb, with USB debugging allowed, and the Android SDK named by
`local.properties` or `ANDROID_HOME`. It builds a small app of its own, with its own package, so
the app on the phone and its vault are not touched; the probe is uninstalled when it is done.

The probe's page looks for `RTCPeerConnection` wherever a page can reach a new frame: a frame made
by the parser, `document.write`, `appendChild`, `innerHTML`, `insertAdjacentHTML`, a shadow root,
a nested frame, `execCommand`, its `contentWindow`, code that runs during an insertion (an empty
frame's synchronous load, a custom element's `connectedCallback`), a frame sent to `about:blank`,
and `window.open`. Where it finds one it gathers an ICE candidate with no server: nothing leaves the
phone, and a host candidate proves a peer connection works there. It also checks that every
function the script replaces still looks native, as anti-bot checks look for replaced ones.

It runs twice, and passes only if both hold:

- **without the script**, the page must find WebRTC in its frames: otherwise the probe no longer
  finds what it looks for, and a pass with the script would prove nothing;
- **with the app's script**, it must find it nowhere, and every replaced function must look native.

The script is read from the app's source at build time, never copied. The plugin and WebView
library versions are the app's, from `gradle/libs.versions.toml`.
