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
5. This file again, updated.

Unit tests: `./gradlew testDebugUnitTest`, 121 tests, all passing three runs in a row.
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

- Phone leak tests, started 2026-10-29 and cut short (see "Phone state left behind").
  Still to do, with Safebooru and Pokémon searches only: download a few posts, then as
  the app (`run-as`, debug build) list every private file and look for the search term
  and JPEG/PNG/MP4 signatures outside sealed files (Coil, WebView, notifications);
  repeat after a lock; read logcat for anything but the `BooruVaultCheck` traces; check
  on the device that the clipboard is cleared on lock with the app in the background and
  that a lock in the background closes the screen. Done so far: screenshots of the app
  come out black (`FLAG_SECURE`); before the vault exists the data dir holds only the
  WebView's start-up files and `shared_prefs/network.xml` (`route_chosen=false`).
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
- On-device tests use only the Safebooru board (the "Safebooru" preset,
  safebooru.donmai.us) and only Pokémon names as searches (pikachu, eevee, bulbasaur...).
- The test vault password is not written here; ask the user.
- A debug APK cannot update a release-signed install (uninstall first). adb on the
  original machine was in `~/Android/Sdk/platform-tools`. On the second machine (Windows)
  the unit tests need `JAVA_HOME` set to Android Studio's `jbr` and `ANDROID_HOME` to the
  SDK.

## Phone state left behind

- NordVPN split tunneling currently excludes BooruVault (for the home-IP test) and also
  Orbot. Orbot was added either by the user or by NordVPN's "exclude apps not compatible
  with VPN". The user may want Orbot back inside the tunnel.
- BooruVault was uninstalled on 2026-10-29 and reinstalled from this branch (debug, with
  the traces, at `Keep the retired pairs when the password changed again meanwhile`), so
  the old vault and its clearances are gone. A new test vault exists, Tor chosen at first
  start, no biometric unlock, source Safebooru. The Tor exit check passed; Safebooru's
  API answered 403 with `cf-mitigated: challenge`, and the built-in browser was still on
  Cloudflare's "verifying you are not a robot" page after about 40 s when the tests
  stopped and it was closed. No search has been run yet.
