# Moving the built-in browsers to GeckoView

Decided on 2026-11-25, after a security audit of 0.6.1: Android's
WebView has no setting that turns WebRTC off or sends it through the proxy, and a script in the
page cannot keep it out of every frame. Gecko leaves it out of the engine by a preference.
`tools/gecko-probe` showed on a phone that this holds, that a SOCKS proxy set by preferences takes
loopback requests and names alike, that a private session leaves nothing behind, that Gecko's own
connections can be turned off, and that Cloudflare takes from OkHttp a clearance earned in Gecko.

This file says what is done, what each stage is to do, and what is still to be found out.

## What it costs, and is accepted

- About 100 MB for each ABI: one APK for each (`arm64-v8a`, `armeabi-v7a`, `x86_64`), native code
  compressed in the APK.
- The engine ships inside the app: every Firefox security release is a release of the app.
- GeckoView comes from Mozilla's Maven repository and asks for SDK 37.2. F-Droid builds from source;
  a prebuilt engine at least complicates the app's inclusion there.
- GeckoView's dependency on Google Play services' FIDO library is excluded.

## Stage 1: foundations (done)

- The build: the dependency, Mozilla's repository for that group alone, SDK 37.2, an APK for each
  ABI.
- `net/GeckoPrefs.kt`: the engine's preferences, pure, with unit tests. WebRTC off; a proxy that
  takes everything and has no direct way to fall back on; no resolver of the engine's own and no
  QUIC; none of Gecko's own connections. The engine starts with a proxy that leads nowhere, so
  nothing leaves before a route is applied.
- `net/GeckoEngine.kt`: one runtime for the process, made on first use; `applyRoute`, which sets the
  proxy for the route in force through `GeckoPreferenceController` and waits for it; cookies read
  and handed over through the app's own extension (`assets/browser-ext`), partitioned ones
  included; private sessions; a wipe.
- Nothing uses it yet: the browsers still run on the WebView.
- Checked on a phone on 2026-11-25 by `tools/gecko-probe`, which compiles `GeckoPrefs.kt` and loads
  the extension from the app's own source: nothing leaves at start, the route follows what is set
  while the engine runs, the extension reads and sets a private session's cookies, and three idle
  minutes make no connection. `GeckoEngine` itself has not run yet. Gecko starts the extension
  again when it is let into private sessions, so the engine keeps the last line the extension
  opens, not the first.

## Stage 2: the built-in browser (`ChallengeScreen`)

Written on 2026-11-25: the screen opens a private session of the engine, as the table says.

Tried on a phone the same day, through Tor, on a board behind a Cloudflare check. The browser opened,
the check was passed, the session closed by itself with the clearance and the cookies read through
the extension. Twice the app's own request, sent right after with Firefox's name and those cookies,
was still answered with the check; the third time it was answered, as a plain client's beside it
was. Between the second and the third, one thing changed: the application no longer makes its
container in the engine's processes. GeckoView runs pages in processes of its own, Android starts
the application class in each, and each was checking Tor's exit and sweeping the vault's folder
for itself. Whether that was what made the first two fail is not known: the prototype, asked to do
all the app does, never failed, and one success is not a proof. To be tried again.

Tried again later that day, the request was sometimes refused and sometimes answered, with
nothing changed in between; a pass kept from an hour before was taken after a restart. What does
change from one try to the next is the exit the app's own connection leaves Tor by: see the next
section, written for it. The prototype's check of it passed on a phone. In the app, on 2026-11-25 and
2026-11-26: a check passed once with it, the app's request answered at the first try, and the
pass taken again after a restart eighteen hours later. Too few tries to call the refusals gone.

### The check taken as passed while still on (found on 2026-11-26)

What made the request after a check fail now and then was found on a phone, with nobody at it: the
browser closed by itself on a check that was still running. The app took a check as passed once
the session held a `cf_clearance` cookie and the page's title no longer read "Just a moment...".
Cloudflare sets a clearance cookie while a check is still on, and asks again if it does not hold;
and the title comes in the phone's language ("Ci siamo quasi..." on an Italian phone), so the
second condition was always met there. The browser closed on the first cookie, the app sent it,
and it was refused, from the app's client and from a plain one alike.

The browser now waits for the site to answer with a page of its own: the extension tells the app,
with each page's status, whether Cloudflare marked it as a check's (`cf-mitigated: challenge`),
and a clearance counts only once the site's last page was not one. Tried at once on the same
phone, in the shrunk build: the browser stayed open through the check, closed when the site's own
page came, and the app's request was answered.

A file host's check went by the cookie as well: the browser opens on the file itself, asked for
with a range no file has, and closed on the page's own request for the file once a clearance was
there. It now refuses that request and makes it again with the range: the check's page comes back
while the check is on, and no byte of the file (416) once it is passed, on which the browser
closes and keeps what it earned. Written the same day, and not seen on a phone: it needs a file
host that asks for a check when tried.

### One credential for Orbot

Tor keeps streams that come with different SOCKS credentials on different circuits, and a circuit
has one exit. Java's SOCKS client, under OkHttp, sent the system user's name; the engine sends
nothing. So a check passed in the browser left by one exit and the app's next request by another,
as with the WebView before (`SocksIsolationTest` said so). A site that grants a pass to one exit
and looks again at a request from a worse one refuses it there whatever the pass.

- `net/SocksCredential.kt`: one credential, which Java's client sends through the process's
  authenticator and the engine sends because the extension says so. Preferences can name a SOCKS
  proxy but no credential: the extension's `proxy.onRequest` answers each request with Orbot's
  port and the credential, once `GeckoEngine.applyRoute` has told it the port. Told nothing, and
  after a restart of its script, it leaves requests to the preferences, which stay what decides
  the route; it never names a direct way. For the direct route it is told to stop before the
  preferences change, and a route is not applied unless the extension answers.
- After a pass is recorded the app closes its idle connections, so that its next request opens a
  stream on the circuit the credential is on now.
- The prototype's `engine` check looks at what reaches the proxy from both. On a phone, on
  2026-11-25: OkHttp sent the credential, so Android's SOCKS client does ask the process's
  authenticator; the engine sent it on the Tor route, went directly on the direct one, and sent
  it again back on Tor.
- Sites are no more apart than before: one credential for all. A credential for
  each site, on both sides, is the step after this.

For trying it:

- the risky option "Google Safe Browsing in the built-in browser" is gone (2026-11-26): the
  engine's Safe Browsing works otherwise than the WebView's, with lists fetched from Google rather
  than a question to Play services, and stays off;
- a debug build shows no image at all, in the engine or in the app's own grid, viewer, previews
  and vault, when the file `no-images` is in its files
  (`adb shell run-as io.github.firebirks.booruvault.debug touch files/no-images`).

| Today, on the WebView | On GeckoView |
| --- | --- |
| `WebViewRoute.apply` | `GeckoEngine.applyRoute` |
| Chrome's WebView name as User-Agent | Firefox's own: a clearance is bound to the name, and a name the engine does not match is what anti-bot checks look for. The name kept with a pass, and sent by OkHttp, becomes Firefox's |
| `shouldOverrideUrlLoading`, top-level GET only | `NavigationDelegate.onLoadRequest`, POST included |
| `CookieManager` for the clearance and the cookies kept | the extension: `cookiesFor`, asked for again while the check is on |
| a guarded file: a `Range` no file has, `shouldInterceptRequest` answering empty once cleared, 416 through `onReceivedHttpError` | `Loader.additionalHeaders` with the unrestricted filter; `onLoadRequest` refusing the file once cleared; the status through the extension's `webRequest` |
| progress, title, host on screen, Back, a renderer gone | `ProgressDelegate`, `ContentDelegate.onTitleChange`, `onLocationChange`, `onCanGoBack`, `onCrash` and `onKill` |
| Safe Browsing, the risky option | gone with the option: the engine's own, which fetches lists from Google, stays off |
| no autofill, a keyboard that does not learn | a private session; autofill off on the view and in the runtime |
| `Challenge.wipe` and the wait for it | the session closed, then `GeckoEngine.wipe` |
| the document-start script | none |

## Stage 3: the hidden browser (`HiddenBrowser`)

Written on 2026-11-25. The prototype's check passed on a phone the same day: from a private
session on no view, the page's HTML came through the extension with what the page's script added
after the load and the cookie handed over; no image was asked for with images off, and one was
with them back on, so the preference holds when set while the engine runs.

On 2026-11-26 the app's own hidden browser read a site's pages on a phone, through Tor: a debug
build made to read every HTML page that way listed a Gelbooru-style board, its next pages and a
tag search, each page in two to three seconds once the circuit was up, one in fifteen. The page's
script answered before the page had finished loading, as expected, and the read waited for the
finished one. No check stood in the way, so a check passed on no view is still to be seen.

- **A private session on no view**, under the engine's own name, set active so that the engine
  does not slow it down as a background page. The route, the wait for the built-in browser and the
  stop when the route changes are as they were.
- **The page's HTML through the extension.** GeckoView has no call that runs a script and returns
  its value. The extension has a script in every page (`page.js`), which opens a line to the app
  where the app listens on the session, as `GeckoEngine.pagesOf` does for this browser alone, and
  hands over the address, the HTML and whether the page has finished loading. The app asks when a
  load ends and every second and a half besides, and takes the first finished page of the site
  that does not look like a check. In the built-in browser nothing listens, and nothing is read.
- **No images:** `permissions.default.image`, set for the visit and set back after. It is one
  switch for the engine; the two browsers are never up together, and the built-in one sets it back
  as it opens.
- **Cookies:** the ones kept are handed over with `setCookies` once the session is open, a
  clearance with the site's domain and the others on their host. After the visit, what the
  session holds is read back under a stricter rule: a cookie is renewed only if that very
  one, by name, domain and path, was already kept, and within the deadline it had; a clearance the
  visit earned is kept as a new pass, with Firefox's name beside it, since OkHttp is to send it
  under the name it was earned with.
- The line between a page's script and the app is named as a native application is, in letters,
  digits and underscores: the engine refuses any other name, and the script then reads nothing.
- The extension's version goes up with every change to it: the engine installs a built-in
  extension again only when its version differs from the one its profile holds.
- `tools/gecko-probe` has a check for it (`hidden`): a local page read from a session on no view,
  with what its script adds after the load and the cookie handed over, no image asked for with
  images off and one asked for with them back on.

Still a WebView's: `Challenge.cookiesFor`, `Challenge.wipe` and `PrivateWebView`, which nothing
uses any longer and stage 4 removes.

## Stage 4: what goes

Done on 2026-11-26:

- `PrivateWebView`, `DISABLE_WEBRTC_SCRIPT`, `WebViewRoute`, the WebView's cookie reading and wipe,
  its form of the app's name, `androidx.webkit` and `tools/webrtc-probe` are gone; the probe's page
  lives in `tools/gecko-probe`, which is now the check to run on every GeckoView update.
- One touch of the system's WebView stays: at start the app asks it for Chrome's version, for the
  name its HTTP client goes by. No page is loaded in it, and the folders it leaves are deleted at
  start as before.
- A build type `staging`: the release build as R8 leaves it, under a package name of its own
  (`.staging`), signed with the debug key, to try on a phone what only a shrunk build can get wrong
  without touching the release install. `./gradlew assembleStaging`. It was debuggable at first,
  and R8 then renamed nothing; since the audit of 2026-11-26 it is not, so that it is what a
  release is. Its first run so built ended the app as the browser opened: R8 had moved SnakeYAML,
  which reads the engine's preferences, out of its package. SnakeYAML is now kept whole.
- The release build shrinks again: R8 is told that Google's FIDO classes are missing on purpose.
  Unsigned release APKs are 93 MiB (arm64-v8a), 90 MiB (armeabi-v7a) and 98 MiB (x86_64). The
  shrunk build ran on a phone on 2026-11-26 as `staging`, then debuggable and so not renamed: a
  vault made, the exit verified, the browser opened on the engine, a check passed in it and the
  grid loaded.
- Third-party cookies are refused in both browsers, as the settings and the README say, where the
  engine's own default keeps them apart for each site. A Cloudflare check was passed with them
  refused, on 2026-11-26.
- The README and the changelog say what the browsers now are.

- **Releases, decided on 2026-11-26.** arm64-v8a and armeabi-v7a are published, as
  `booruvault-<version>-<abi>.apk`; x86_64 is built, for emulators, and not published. Each ABI's
  APK has a version code of its own, the release's with a digit after it (1, 2, 4), since F-Droid
  tells the APKs of a version apart by their codes. The project's F-Droid repository, on GitHub
  Pages, carries the latest release alone. Since 2026-11-27 it is deployed from Actions as an
  artifact, not pushed as a branch: a branch put every release's APKs, some 190 MB, in the code's
  own repository, and a file in it may not pass 100 MiB. A Pages deployment took a file of
  120 MiB; a site may not pass 1 GB. The workflows install the SDK platform 37.2 themselves. They
  ran whole on 2026-11-26 in a private copy of the repository with throwaway keys, and the Pages
  deployment on 2026-11-27 in a public one. 0.7.0 was the first release they made, on
  2026-11-27: the two APKs signed with the project's key, codes 111 and 112, and the repository
  on Pages with them alone. Its first try met a runner image whose Maven Central gave 404s, its
  second a unit test that a fast runner could get past the moment it looked at (fixed since); run
  again, the job published nothing twice. Its index gave the two APKs no changelog: fdroidserver
  gives a version one only when the metadata has a build entry for its code, and the recipe's
  stop at 0.6.1. The job now adds one for each APK to its own copy of the metadata, with no build
  steps; tried locally with fdroidserver 2.4.5 on the published APKs.
- Lint is clean again: the engine class opts in to `GeckoPreferenceController`, which GeckoView
  marks experimental.

Still to do:

- F-Droid's own repository: a build recipe for an engine that comes prebuilt from Mozilla's Maven
  repository, with an entry and a version code for each ABI.

## English through Tor, decided on 2026-11-26

Left to itself the engine asks pages in the phone's languages (`Accept-Language`, and
`navigator.languages` to scripts), as the WebView did. Through Tor that tells a site something the
exit hides, and sets one user apart from the others who leave by it. Tor Browser asks in English
for everyone, and so do the built-in browsers now while the route is Tor: `intl.accept_languages`
is set with the route, first of its preferences, and given back the phone's languages on the
direct route. A site's pages, and Cloudflare's check, are then in English through Tor.

The prototype's `engine` check reads the preference back on the Tor route and looks at the header
a page is asked with on the direct one. Passed on a phone the same day.

On the phone, after the audit, it did not hold. Cloudflare's check came in Italian through Tor,
and the prototype's `locale` page, with the language set as above, was asked in `it-IT` and read
`it-IT` through `navigator.language`, `Intl` and dates, and the phone's time zone: as the runtime
starts GeckoView sets the language preference from the phone's, over the file's, and its message
may come after the route's; and scripts read the engine's own locale, which the preference does
not reach. Three ways were tried on the page:

- the language preference alone, as above: the phone's language everywhere;
- the runtime's locale (`GeckoRuntimeSettings.setLocales`) set to `en-US`: `Intl`, dates and
  `navigator.language` in English, but the header and `navigator.languages` with the phone's
  language after English, which GeckoView adds, and the phone's time zone;
- the fingerprinting protection's `JSLocale` and `JSDateTimeUTC` targets: `Intl` in English and
  the time zone UTC, the header and `navigator` in the phone's language.

So through Tor the route now sets the runtime's locale to `en-US` first, then the language
preference, after it, and the fingerprinting protection with only `JSDateTimeUTC` added to its
own targets; directly, the phone's locale and languages again and the protection off. The route
reads the language back through Tor and fails if it is not English. The page then read `en-US`
and `en` everywhere and the UTC time zone (`Atlantic/Reykjavik`, as Firefox's protection names
it), and in the app Cloudflare's check came in English through Tor, and was passed by a person's
tap. Whether a check that needs no person still passes by itself with English and UTC is not
known yet.

## Two names, decided on 2026-11-26

The app's HTTP client goes on calling itself Chrome, in the reduced form that names no phone model
or Android version, and the built-in browsers call themselves Firefox, as the engine does. A site
whose check was passed in the browser is then asked by the client under Firefox's name, since a
pass is bound to the name that earned it. So a site may see the app as Chrome before a check and
as Firefox after it.

Going by Firefox's name everywhere was weighed and left for later:

- it would give a site one name before and after a check, and the app would no longer ask the
  system's WebView for Chrome's version, its last touch of the WebView;
- but Firefox's name carries the phone's Android version, which Chrome's reduced form hides, and
  the change would reach every site the app asks, each to be tried again: Danbooru boards, which
  are told the app's own name, e621, which wants the app's name beside the browser's, and the
  file hosts that put a check in front of a client calling itself a browser it is not.

It is not a matter of what leaves the phone around Tor, and nothing in this migration needs it.
To be taken up again with `privacy.resistFingerprinting`, which gives Firefox's name without the
Android version, if anti-bot checks take it (see below).

## What Tor Browser does, and what is taken from it

Tor Browser for Android runs on GeckoView too, built from its own sources, with its preferences
compiled in and many of them locked. Read on 2026-11-25 in its repository
(`gitlab.torproject.org/tpo/applications/tor-browser`, branch `tor-browser-158.0a1-17.0-2`):
`browser/app/profile/000-tor-browser.js`, `browser/app/profile/001-base-profile.js`,
`mobile/android/app/geckoview-prefs.js` and `toolkit/components/tor-launcher/TorDomainIsolator.sys.mjs`.
A stock GeckoView has none of this; the app hands it what it needs in a configuration file.

Taken into `GeckoPrefs`, as they only keep traffic in:

- **The proxy:** manual SOCKS on 127.0.0.1 with names resolved by the proxy, nothing exempted
  (`no_proxies_on` empty, `allow_hijacking_localhost`), and the engine's own ways around it closed
  (`failover_direct`, `allow_bypass`, `fast_path_system_direct`). `network.dns.disabled` so that the
  engine resolves no name at all through Tor; the app sets it back for the direct route.
- **No DNS over HTTPS, no HTTP/3**, no prefetch of names or pages, no speculative connections.
- **WebRTC off** (`media.peerconnection.enabled`), and in depth, should it ever be on: host addresses
  hidden, relay only, nothing outside the proxy (`ice.proxy_only_if_behind_proxy` and the rest).
- **The phone's network out of pages' reach:** Local Network Access blocking, 0.0.0.0 refused,
  no geolocation.
- **None of the engine's own connections:** captive portal and connectivity checks, Safe Browsing,
  telemetry, push, Normandy, region lookups, media plugin downloads, CRLite and intermediate
  certificates from remote settings, add-on and system add-on updates.

Not taken yet, as they change what a site sees and have to be tried against an anti-bot check:

- `privacy.resistFingerprinting`, which also gives every user the same User-Agent, without the
  phone's Android version: it would answer the open point below about Firefox's name.
- `privacy.firstparty.isolate`. Third-party cookies are refused, through the runtime's content
  blocking settings rather than a preference.
- Referrers trimmed across origins.

Not available to a stock GeckoView:

- **A circuit for each site.** `TorDomainIsolator` gives each first-party domain a SOCKS username
  of its own, so that Tor keeps sites on separate circuits. It is privileged code of Tor Browser's
  build. The app's extension names a credential for each request with `proxy.onRequest`, today the
  same for all (see "One credential for Orbot"); one for each site would keep sites apart in the
  browsers, once the app's client can send it too.
- **Locked preferences.** A page cannot change preferences, so this matters less.

## Still to be found out, on a phone

- A file host's check passed with the browser waiting for the host's answer: see stage 2.

- Whether the app's request after a check is still refused now and then, with one credential for
  the app and the engine: see stage 2.
- Whether a check that needs no person is passed in a session on no view: with nothing to draw
  on, the engine may not run what the check waits for. If not, the session needs a view that is
  not shown.
- The engine keeps a profile in the app's files (`files/mozilla`), in clear. Private sessions store
  nothing of a visit there, but what it does hold is to be looked at, and it may have to be deleted
  at start as the WebView's folders are.

- A screen recording with a page shown, on Android 9 and on a recent version. The browser draws
  on GeckoView's TextureView backend, inside the window and its screen protection: a SurfaceView's
  layer carries a flag of its own, which GeckoView never sets, and on Android 8 and 9 a recording
  could show the page.
- Which of the three permissions GeckoView's manifest adds (`ACCESS_NETWORK_STATE`, `WAKE_LOCK`,
  `MODIFY_AUDIO_SETTINGS`) can be removed without harm.
- Whether a clearance earned in Gecko is taken for API calls and on the file hosts, and on other
  sites than the one tried.
- Whether Firefox's name should lose the Android version it carries, as the app's names do today:
  `privacy.resistFingerprinting` does it, if anti-bot checks take it.
- What `GeckoPreferenceController` does when a preference cannot be set, and how long a route
  change takes to reach connections already open.
