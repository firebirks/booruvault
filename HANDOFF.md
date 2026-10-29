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

Unit tests: `./gradlew testDebugUnitTest`, 103 tests, all passing on both commits.

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
  would add `okhttp/x`. TLS has one `SSLContext` per route: direct sockets come from
  `RouteTls.socketFactory` (class `DirectSocket`), and OkHttp creates SOCKS sockets
  itself, so the wrapped socket's class picks the session cache. The challenge WebView
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
  in which case they get the agent that earned it. e926 gets `BooruVault/<version>`
  plus the account name if set.

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
  `gh` CLI, set `GH_CONFIG_DIR=~/.config/gh-firebirks`.
- Code, comments and docs in English. No co-author trailers. Commit and push only when
  asked.
- Releases are tags `v<version>`. The user pushes tags. F-Droid metadata lives in
  `fastlane/` and `fdroid/`.
- Phone: ask before opening the app or booting an emulator unless the user has asked
  for phone tests. Do not turn NordVPN off, and do not touch Android's "Always-on VPN".
  Do not set Orbot as a VPN; Orbot runs in "Power User Mode" (proxy only, SOCKS 9050).
  Do not install apps from stores; the user does that. Leave human-verification
  checkboxes to the user.
- The test vault password is not written here; ask the user.
- A debug APK cannot update a release-signed install (uninstall first). adb on the
  original machine was in `~/Android/Sdk/platform-tools`.

## Phone state left behind

- NordVPN split tunneling currently excludes BooruVault (for the home-IP test) and also
  Orbot. Orbot was added either by the user or by NordVPN's "exclude apps not compatible
  with VPN". The user may want Orbot back inside the tunnel.
- BooruVault: Tor off (direct), debug build with the traces, direct share holding a
  donmai clearance.
