# GeckoView prototype

A prototype, not part of the app: it checks on a phone whether GeckoView, Mozilla's embeddable
engine, would close what a script in Android's WebView cannot, should the built-in and hidden
browsers move to it. It was written for a security audit of 2026-11-24.

```sh
tools/gecko-probe/run.sh
```

It needs one phone over adb (arm64), the Android SDK named by `local.properties` or `ANDROID_HOME`
with the platform `android-37.2`, and the network once, to fetch GeckoView from Mozilla's Maven
repository; only this build uses that repository. It builds a small app of its own, with its own
package, runs each check and uninstalls it. Nothing leaves the phone: every connection Gecko makes
goes to a fake SOCKS5 listener that records it and refuses it, or to the probe's own listeners on
127.0.0.1. The device log is read from the start of each run on, not cleared. The one exception is
the Cloudflare check below, which is not part of `run.sh` and does reach a site.

## What it checks

- **WebRTC** (`rtc-on`, `rtc-off`): the page written for the WebView's script (`probe.html`,
  from the WebRTC probe this one replaces), every way a page reaches a new frame, with `media.peerconnection.enabled` on, where the page must find WebRTC,
  and off, where it must find it nowhere. With the preference off the engine leaves
  `RTCPeerConnection` out of every window, so no frame has it to take, the frames a script reaches
  inside an insertion included.
- **Proxy** (`proxy`): a fetch, an image and a WebSocket to 127.0.0.1, and a fetch to a name, with
  Gecko's proxy set by preferences to a SOCKS5 port, names resolved by the proxy and loopback not
  exempted. All must reach the listener, the name as a name.
- **Gecko's own traffic** (`idle-default`, `idle-hardened`): a blank page for a minute, with Gecko's
  own preferences, then with the preferences in `HARDENING`. With the latter there must be none.
- **Orbot down** (`idle-closed`): a blank page for a minute with the app's own first preferences,
  `GeckoPrefs.initial`, whose proxy port nothing listens on, as when Orbot is not running: a
  refused proxy is what sets off an engine's ways around it.
- **What a page finds of the phone** (`surface`, and `surface-stock` by hand): a page served from
  127.0.0.1 looks for WebAuthn, the screen wake lock, speech synthesis, DRM and WebRTC, with the
  app's preferences on the direct route; none may be there, DRM's call being refused. On the phone
  on 2026-11-26 none was, and with Gecko's own preferences (`surface-stock`) the page found
  WebAuthn, the wake lock and speech, and its question for a passkey ended the prototype's main
  process: `NoClassDefFoundError` for Google Play services' FIDO classes, which the app leaves out
  too.
- **Around the listeners**: the listeners see only what reaches them. Around each mode `run.sh`
  also reads the phone's own count of the bytes the probe's uid sent on its networks
  (`dumpsys netstats`, which leaves loopback out). The modes with the app's own preferences
  (`engine`, `hidden`, `idle-closed`) must have sent none; the others are reported. Added after
  the audit of 2026-11-26 found the listeners blind to a direct connection. On the phone the same
  day: 0 bytes in each of the app's modes and in `proxy`; with Gecko's own preferences, which the
  WebRTC and storage modes and `idle-default` run with, 6 KB to 574 KB in a minute or so; with
  `HARDENING`, the prototype's first fifteen preferences, 208 bytes, which the app's list, with no
  direct fallback and no lookup of the engine's own, does not send.
- **The app's own engine settings** (`engine`, and `idle-app` by hand): the probe compiles the
  app's `GeckoPrefs.kt` and loads the app's extension, as they are in the app's source. Nothing may
  leave at start; the route is then set while the engine runs, to the proxy, to none and back, and
  each request must go where the route says; the extension must read a private session's cookies
  and set one. Through Tor the engine must ask pages in English, and
  directly in the languages it is given back. What reaches the proxy must carry the app's SOCKS credential, from the engine on
  the Tor route and from OkHttp on the phone, as Tor puts streams with different credentials on
  different circuits. `idle-app` keeps a blank page for three minutes with those preferences.
- **A page read on no view** (`hidden`): as the app's hidden browser reads one. A private session
  that is on no view is handed a cookie by the app's extension and loads a page served from
  127.0.0.1, with images turned off by a preference set while the engine runs. The HTML the
  extension's script hands over must hold what the page's own script added after the load and the
  cookie; the image must not be asked for, and must be once images are back on.
- **Storage** (`storage`): a page served from 127.0.0.1 stores a value in localStorage, Cache
  Storage and IndexedDB in a private session, which is closed; a new private session must find none.

Preferences are written to a YAML file handed to `GeckoRuntimeSettings.configFilePath`. GeckoView
reads an explicit config file in release builds too: only its default path, in `/data/local/tmp`,
is limited to debuggable apps (checked in `GeckoRuntime`'s bytecode, GeckoView 161).

## Results, 2026-11-24

GeckoView 161.0.20261118180411 on Android 17, arm64:

- **WebRTC:** with the preference on, the page found it in all 17 frames it tried, host candidates
  hidden behind mDNS names; with it off, in none, the two that get past the app's script on the
  WebView included.
- **Proxy:** every request reached the SOCKS listener, 127.0.0.1 and the name as names (ATYP 3);
  none reached 127.0.0.1 around it.
- **Gecko's own traffic:** with its own preferences, Gecko tried hundreds of connections in a minute
  to `firefox.settings.services.mozilla.com`, `firefox-settings-attachments.cdn.mozilla.net`,
  `content-signature-2.cdn.mozilla.net`, `firefox-portal-detection.com` and
  `safebrowsing.googleapis.com`. With the hardening preferences it made none. A later GeckoView may
  add a service of its own, so this check would have to run on every update.
- **Storage:** nothing a private session stored was found by the next one. A page loaded from
  `resource://android/assets/`, an opaque origin, did keep IndexedDB across private sessions; a
  real origin did not, so the check serves its page over http.

On 2026-11-25, with the app's 62 preferences: nothing left at start; names and the phone's own
address went to the proxy on the Tor route, the local page was reached directly on the direct one,
and the proxy took over again after; the extension read the cookies a private session held and set
one the page then saw; and three idle minutes made no connection.

The same day, the page read on no view: the HTML held what the page's script had added and the
cookie handed over; the image was not asked for with images off, and was once they were back on.

On 2026-11-26 the whole of `run.sh` passed in one run, the checks added since included.

## What it would cost

- **Size:** the debug APK, for arm64 alone, is about 105 MB; the app's release APK is about 5 MB.
- **Updates:** GeckoView ships inside the app, so every Firefox security release becomes a release
  of the app; Mozilla published 11 GeckoView builds between 2026-09-21 and 2026-11-18. Android's
  WebView updates by itself.
- **Build:** GeckoView 161 asks to be compiled against SDK 37.2, and comes from Mozilla's Maven
  repository; F-Droid builds from source, and a prebuilt GeckoView would at least complicate the
  app's inclusion there.
- **Google:** GeckoView depends on `play-services-fido`, for WebAuthn; it is excluded here, with no
  harm seen in these checks.
- **Cookies:** GeckoView has no cookie manager; the clearance a check earns would have to be read
  by a built-in WebExtension.

## The Cloudflare check

Whether Cloudflare takes a clearance earned in Gecko when OkHttp sends it on: the pass is bound to
the browser's name, so the app would present itself as Firefox, while OkHttp's TLS handshake is not
Firefox's. It needs a real site behind a check and someone at the phone to pass it, so it is run by
hand:

```sh
adb shell am start -n io.github.firebirks.booruvault.probe.gecko/.GeckoProbeActivity \
    --es mode cf --es url https://site.example/page --es socks 9050
```

The site opens on screen, through Orbot's port when `socks` is given; once the session holds a
`cf_clearance` cookie, OkHttp asks for the same address three times and the log says what came
back. The cookies are read by a built-in WebExtension, as GeckoView has no cookie manager; it asks
for partitioned cookies too, since Cloudflare partitions its clearance and they are not returned
otherwise. The session is not private, so uninstall the probe afterwards: its profile keeps the
cookies. No image is loaded unless `--es images 1` is added, and the page leaves the screen as soon
as the check is passed; an API address, as a board's `posts.json`, shows nothing but text.

On 2026-11-25, through Tor, on `safebooru.donmai.us`:

| OkHttp's request | Answer |
| --- | --- |
| Gecko's cookies, Firefox's name | 200, no check |
| Gecko's cookies, Chrome's name | 403, `cf-mitigated: challenge` |
| Firefox's name, no cookies | 403, `cf-mitigated: challenge` |

So a clearance earned in Gecko is taken from OkHttp under the browser's own name, as one earned in
the WebView is today.

More was tried the same day, each time through Tor on that site, with these extras: `then` (other
addresses to ask for after the check), `before` (an address OkHttp asks for before the check, as
the app does, leaving its connection in the pool), `api` (an API address, asked plainly and with the
Referer the app adds) and `engine app` (the check passed as in the app: its preferences, a private
session, its extension). A clearance earned on the site's home page was taken for the API and for
another page; it was taken on the connection opened before the check as on a new one; and it was
taken when earned with the app's own engine settings, with the app's Referer and query. Java's
SOCKS client and Gecko's are kept on different circuits by Tor, so the clearance is not bound to
the exit's address. The file hosts and other sites are still to be tried.
