# Handoff: branch `route-separation-and-challenge-fixes`

Notes for continuing this work on another machine. Delete this file, and revert the
`TEMPORARY` commit, before merging into `main`.

## Commits on the branch

1. `Ask for the route at first start, keep routes apart, handle file-host checks`: the
   actual work, with no debug logging.
2. `TEMPORARY: trace anti-bot checks and routes in debug builds`: logcat traces (tag
   `BooruVaultCheck`, debug builds only) used for the phone tests. Revert with
   `git revert <hash>` before merging.
3. This file.
4. Fixes from a security and leak review, each with its test written first and seen to
   fail:
   - `Let the vault replace its files when the tests run on Windows`: `File.moveOver`
     (`util/Files.kt`, `Files.move` with `ATOMIC_MOVE`) replaces every `renameTo`, which
     fails on Windows when the target exists.
   - `Keep the old key pair while a file still needs it`: the rekeyer never gives up a
     file it could read but not write (full disk), which used to drop the retired pair
     and lose that file.
   - `Open only web pages from a site's links`: `tel:`, `intent:`, `file:` and the like
     are refused (`file:` crashed the app).
   - `Wipe each part of the WebView's storage on its own`: one failing step of
     `Challenge.wipe` no longer skips the rest.
   - `Name no account to any site`: no User-Agent carries the e621 login any more.
   - `Take copied links and tags off the clipboard on lock` (`util/SensitiveClipboard.kt`).
   - `Close the screen when the vault locks behind it`: a lock while the activity is
     stopped calls `finishAndRemoveTask()`, releasing the WebView and the player.
   - `Let one writer at a time seal a vault file`: two concurrent saves of `cookies.bin`
     shared one temp file and could put a torn copy in place.
   - `Resume no TLS session from one connection through Tor to the next`.
   - `Ask every private key the vault holds to wipe itself`: a try only; the platform's
     EC keys refuse `destroy()`.
   - `Keep the retired pairs when the password changed again meanwhile`: a rekeyer
     finishing during a second password change dropped the pair everything had just been
     re-sealed to, losing the whole vault.
   - `Have the system wake the app for a timed lock`: Android's cached-app freezer froze
     the process about 70 s after it left the screen, so the delayed lock never ran.
     Leaving the app now also sets an inexact `AlarmManager` alarm (`vault/LockAlarm.kt`,
     receiver not exported); the delayed job and the clock check on return stay.
   - `Sync a sealed blob's file on the first close only`: Android's `FileOutputStream`
     finalizer closes the stream again, and syncing the released descriptor logged a
     `SyncFailedException` after every blob write.
   - `Drop the browse screen's derived state once nothing shows it`: `tagShelf` and
     `saveStates` used `WhileSubscribed(5000)`, which keeps the last value for good; a lock
     from another tab left the searched tags and the vault's post ids in the view model.
     `whileShown()` adds `replayExpirationMillis = 0`.
   - `Keep library buffer pools from holding what went through them`
     (`util/LibraryBuffers.kt`): `kotlinx.serialization.json.pool.size` is set to 0 in
     `BooruVaultApp`'s constructor, before any JSON (the library reads it once, on first
     use); on lock, `forgetSession` sends a zero-filled buffer through each of okio's
     per-thread `SegmentPool` buckets. Okio has no switch, and R8 rules out reflection on
     its internals in release builds, so the wipe uses only its public `Buffer`.
5. This file again, updated each time.

Unit tests: `./gradlew testDebugUnitTest`, 130 tests, all passing.
`VaultTest.duplicatesFromTheSamePostAreDropped` fails now and then: unlock publishes the
cached index before reconcile drops the duplicate. That predates the branch.

## What the work does

- **First start asks the route.** Right after the vault is created: "Use Tor (through
  Orbot)" or "Direct connection", same weight, nothing preselected. `RouteChoiceGate`
  stops every request until the answer; `WebViewRoute` refuses to load as well.
  Installs that already have a vault are not asked (`TorManager.routeChosenAtStart`).
  Verified on the phone with a clean install: 0 bytes and 0 sockets from the app's UID
  before the choice.
- **Routes kept apart** (`net/Route.kt`, `net/RouteTls.kt`, `SecureCookieJar`,
  `booru/Http.kt`). The cookie jar holds one share per route (cookies, kept cookies,
  recorded agents, worked/refused clearance bookkeeping). The OkHttp client has no
  `CookieJar`: `SiteHeaders` is a network interceptor and reads the route from
  `chain.connection()` (SOCKS proxy = Tor), so a switch flipped mid-request cannot mix
  routes. `DefaultUserAgent` (application interceptor) names the browser before OkHttp
  would add `okhttp/x`. Direct connections share one `SSLContext`, and every
  connection through Tor gets a fresh one, so no TLS session is resumed across exits:
  direct sockets come from `RouteTls.socketFactory` (class `DirectSocket`), and OkHttp
  creates SOCKS sockets itself, so the wrapped socket's class picks the factory. The challenge WebView
  records a clearance only into the route it loaded along, and only if that is still the
  current route. The hidden browser aborts if the route changes during a visit. Stored
  cookies in the old single-jar format go to the route in use when first read, then
  are rewritten in the split format.
- **Downloads and checks.** A check met on the file host (for example `cdn.donmai.us`
  originals) shows "Verify in browser" on the download row, opening the exact address
  (`ChallengeMode.ADDRESS`). The browser sends a `Range` that no file satisfies, and
  once a clearance exists, `shouldInterceptRequest` answers the file request with an
  empty 204 and closes, so no file bytes reach the WebView. Then the source's challenged
  downloads are requeued (`DownloadManager.retryAfterCheck`).
- **Anti-repeat rule** (`downloadProblem`, `CHECK_HOLDS_MS` = 30 min). If a download is
  challenged again within 30 minutes of a pass, the result depends on the pass's history:
  - refused before it ever worked: "won't help" message, no button;
  - worked at least once, then refused (expired, or the IP changed): plain challenge, with
    the button offered again.

  `routeChanged()` clears the timers.
- **Refused clearances are dropped** with their agent once none is left, so Danbooru
  boards go back to the app's own name.
- **User-Agent.** Danbooru boards get `BooruVault` (no version) unless a pass is held,
  in which case they get the agent that earned it. e926 gets the browser name plus
  `BooruVault/<version>`, never the account name. The built-in browser sends the
  WebView's own name on every site.

## Findings from the phone tests (Galaxy A56, 2026-10-29)

- Cloudflare currently challenges donmai for every client tried: the phone from the home
  IP, the phone through NordVPN, the phone through Tor, and curl from the PC through
  NordVPN. This holds for both `BooruVault` and a Chrome UA, on the API and on
  originals. The app's own name no longer avoids the check by itself.
- The challenge passes by itself in the WebView (no checkbox) on all three routes:
  about 3 s direct, 40 to 80 s over Tor.
- A clearance is bound to the address. One earned over Tor was refused from the home IP,
  and one earned through one NordVPN server was refused through another.
- Over Tor, the clearance earned in the WebView is sometimes refused for the app's own
  requests. The app offers Orbot username/password SOCKS auth and the WebView offers none,
  so Tor's `IsolateSOCKSAuth` puts them on different circuits and exits
  (`SocksIsolationTest`). It worked once and failed once in today's tests. This is open.
- Route separation was verified live. Over Tor the app sent only the Tor share's cookies
  (never the direct `_danbooru2_session` or clearance) and `BooruVault` as UA. Back on
  direct, it found its own clearance intact and got 200.
- Before the split, the anonymous `_danbooru2_session` cookie that the WebView picked up
  over Tor was being kept in the vault and sent from the home IP. That leak is what
  started the route separation work.

## Open items

- The lock alarm is inexact: with 5 minutes it went off after 8 min 45 s, the latest
  Android allows (75% of the delay late). An exact alarm needs `SCHEDULE_EXACT_ALARM`,
  which the user must grant by hand on Android 14+; not worth it unless asked. An alarm
  still pending when the process dies starts the process for nothing (harmless).
- The built-in browser's WebView writes `shared_prefs/AwOriginVisitLoggerPrefs.xml` in
  clear and it survives locks and `Challenge.wipe`: the date the browser was last used
  plus one opaque number per visited origin and site (not the Java hash of the host
  names tried). WebView metrics are opted out in the manifest, Safe Browsing is off.
- Not checked: whether Samsung Keyboard's own clipboard history kept the copied tags
  (the keyboard comes out black in screenshots over the protected screen). Check by hand:
  copy a tag, lock, then open the keyboard's clipboard panel in another app.
- From the review, not fixed: the built-in browser looks the same on both routes
  (same Accept-Language, time zone and WebView fingerprint); `network.xml` and the
  language preference are clear-text SharedPreferences, so the comment in
  `AppContainer.kt` ("The one clear file is the vault header") is not accurate; http
  sources are allowed in clear on API 26/27; on a full disk the rekeyer retries every
  second for as long as the vault is open.
- `SecureCookieJar.save()` takes its snapshot before `SealedBlob.write` takes the file's
  lock, so in a rare interleaving an older snapshot can be written last.

- Not yet seen on the device: the download row showing "Verify in browser" again after
  a clearance that had worked is refused. The grid path logged `had worked: true` as
  expected.
- Tor: decide whether the app's SOCKS connections and the WebView's should share an
  isolation group, so a WebView clearance works for the app over Tor.
- `_danbooru2_session` is sometimes sent twice on direct requests. One copy is the kept
  host-only cookie from the WebView, the other is the site's own `Set-Cookie` with
  different attributes. This behaviour predates the split. Check whether `put` should
  treat them as the same cookie.
- Unverified: the WebView's own TLS session cache (Chromium) may or may not be keyed by
  proxy. There is no public API to clear it; the HTTP cache is already cleared after
  each use.
- The Tor settings hint recommends Orbot's VPN mode. That does not work for users who run
  another VPN (Android allows one); consider rewording.
- Before merging: revert the `TEMPORARY` commit, delete this file, and do the usual
  release steps (version bump, fastlane/fdroid metadata) if this goes out as a release.

## Working rules the user has set

- Git as `firebirks`, remote `git@github-firebirks:firebirks/booruvault.git`. For the
  `gh` CLI, set `GH_CONFIG_DIR=~/.config/gh-firebirks`. (The second machine uses
  `https://github.com/firebirks/booruvault.git` and has no such `gh` config.)
- Commit as `firebirks <336470935+firebirks@users.noreply.github.com>` with `TZ=UTC`. Keep
  anything personal about the developer out of code, docs and commit messages.
- Code, comments and docs in English. No co-author trailers. Commit and push only when
  asked.
- Releases are tags `v<version>`. The user pushes tags. F-Droid metadata lives in
  `fastlane/` and `fdroid/`.
- Phone: ask before opening the app or booting an emulator unless the user has asked
  for phone tests. Do not turn NordVPN off, and do not touch Android's "Always-on VPN".
  Do not set Orbot as a VPN; Orbot runs in "Power User Mode" (proxy only, SOCKS 9050).
  Do not install apps from stores; the user does that. Leave human-verification
  checkboxes to the user.
- Test on the real phone (Galaxy A56), never on an emulator that happens to be running;
  with both attached, pass `-s <serial>` from `adb devices -l` (or set `ANDROID_SERIAL`).
- On-device tests use only the Safebooru board (the "Safebooru" preset,
  safebooru.donmai.us) and only Pokémon names as searches (pikachu, eevee, bulbasaur...).
- The test vault password is not written here; ask the user.
- A debug APK cannot update a release-signed install (uninstall first). adb on the
  original machine was in `~/Android/Sdk/platform-tools`. On the second machine (Windows)
  the unit tests need `JAVA_HOME` set to Android Studio's `jbr` and `ANDROID_HOME` to the
  SDK.

## Phone tests, 2026-10-30 and 31 (Tor route, Safebooru, "pikachu")

What held up:
- At rest, locked, with three posts in the vault: `files/vault` holds only sealed files;
  no search term, tag, post id, host or source URL appears in clear in any file of the
  app; no JPEG/PNG/GIF/WebP/MP4/WebM header outside sealed files; no Coil disk cache;
  nothing on shared storage and no external app dir. Clear text left: `network.xml`
  (`route_chosen`, `tor`), the WebView file above, WebView start-up and metrics files,
  `databases/http_auth.db` (WebView), GPU shader caches in `user_de`.
- logcat: the app logs nothing of note outside the temporary `BooruVaultCheck` traces
  (hosts, paths, agent, cookie names, never queries or values).
- Screenshots of the app come out black, the keyboard over it included.
- With "Lock immediately", Home destroyed the activity and took it out of recents within
  a second, and the clipboard was empty afterwards: pasting into the lock screen's
  password field gave nothing, while a tag copied in the app did paste into the change
  password dialog's field.
- Over Tor with a fresh TLS context per connection: Tor exit check, search, thumbnails,
  samples and original downloads all worked. Whether a session is resumed cannot be seen
  without a capture.
- A Cloudflare check over Tor that was left on screen passed by itself overnight; its
  clearance worked for the app's requests the next morning.
- Timed auto-lock, 5 minutes, after `Have the system wake the app for a timed lock`:
  Home set the alarm (due 5:00 to 8:45 later); the process was frozen 70 s after Home;
  8 min 45 s after Home the system unfroze it for the alarm, and within a second the
  vault was locked and the activity destroyed and out of recents. Coming back after 20 s
  cancelled the alarm (`alarm_cancelled` in `dumpsys alarm`) and left the vault open.
  Check pending alarms with `dumpsys alarm | grep AutoLockReceiver`.
- After `Sync a sealed blob's file on the first close only`, a lock (which rewrites
  `cookies.bin` and `downloads.bin`, then forces a collection) no longer logs
  `SyncFailedException` from the finalizer.
- Memory after a lock (2026-10-30, before the last two fixes): after "Lock now" with the
  screen open, `am dumpheap -g <pid>` (GC first, so live objects only) still held, on
  paths from GC roots: in kotlinx.serialization's static `CharArrayPool`, the cookie jar
  JSON with the values of `cf_clearance` and `_danbooru2_session`, the settings with
  `tagUses` (`"pikachu":4`) and vault item metadata; in okio's static `SegmentPool`, raw
  search responses and hundreds of thumbnail URLs; in `BrowseViewModel`, `tagShelf` and
  `saveStates`. The password was not found.
- The same after `Keep library buffer pools from holding what went through them`
  (2026-10-31): search "pikachu" over Tor, three pages scrolled, the Vault tab, more than
  5 s, "Lock now", dump. None of the above is left: no searched tag, cookie value,
  `preview_file_url`, `cdn.donmai.us` URL, saved post id or source. The remaining hits
  are code constants (the cookie name, serializer field names), "ryukyu" in OkHttp's
  public suffix list and Android's URL pattern, and an interned `com.instagram.android`.

How the tests were driven: screenshots are useless (`FLAG_SECURE`), so the UI was read
with `uiautomator dump` and driven with `input tap/text/keyevent`; files were read with
`adb shell run-as io.github.firebirks.booruvault sh < script`; heap dumps were pulled and
walked with a small hprof parser (instance fields, arrays, statics, GC roots).
Cloudflare asked for a checkbox on the Pokémon search over Tor on 2026-10-31; the user
ticked it. An adb server left running from the day before held the phone on port 5038
(`adb -P 5038`, or `ANDROID_ADB_SERVER_PORT=5038`); `adb devices` on 5037 showed nothing.

## Phone state left behind

- NordVPN split tunneling currently excludes BooruVault (for the home-IP test) and also
  Orbot. Orbot was added either by the user or by NordVPN's "exclude apps not compatible
  with VPN". The user may want Orbot back inside the tunnel.
- BooruVault was uninstalled on 2026-10-29 and reinstalled from this branch (debug, with
  the traces), so the old vault is gone. It was last updated in place to `Keep library
  buffer pools from holding what went through them`. The new test vault: Tor route, no
  biometric unlock, source Safebooru, three Pikachu posts (19 MB), a donmai clearance in
  the Tor share, auto-lock at 5 minutes, left locked. Orbot must be started by hand before
  tests over Tor.
