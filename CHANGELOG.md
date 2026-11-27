# Changelog

All notable changes to BooruVault. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

Each release also has a short text, at most 500 characters, in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, under the release's own version code: it is what F-Droid shows and what the GitHub release says.

## [Unreleased]

## [0.7.0] - 2026-11-27

### Security
- Those who had the hidden browser on in 0.6.x may still hold cookies it kept by mistake, under the name of a kept one, until their thirty days are over: Site cookies in Settings deletes them at once, with every other kept cookie.
- A route switch takes the browsers' engine off the route it had at once, back to a proxy that leads nowhere until a browser applies the new one; it kept the old route until the next browser opened.
- Nothing a session holds goes on into the next: the private sessions' cookies are now emptied too, which the engine's own wipe left while another private session was open, and the hidden browser waits for the built-in one to be closed and wiped, however it was left, before its next visit.
- While the app is not on screen the built-in browser's page is a background one: it plays nothing, holds no wake lock and is slowed down, rather than run on behind another app until the vault locks. It is drawn into the app's window, under its screen protection, which a screen recording on Android 8 and 9 could otherwise get past. The bar names what is shown when it is not the site, as a large `data:` document.
- The hidden browser reads no page larger than the app's client reads of any page, and looks it over off the main thread: a page that grew without end could exhaust the app's memory.
- A check in front of a file no longer lets the file through when the browser could not tell whether it holds a clearance, nor in a frame.
- The browsers' pages run in processes Android isolates, with no network, none of the app's files and none of its services. A page that took its process over through a flaw of the engine would otherwise have been the app itself: it could have connected around Orbot, read the app's settings, or rewritten the engine's configuration so that it started without its protections.
- The built-in browser's name no longer stays on a site for good. Once a site had been visited in the browser, the app asked it under the browser's name, which tells the Android version, even after everything the visit brought back had run out, and even when the visit brought nothing back at all. The name now goes with the cookies the visit brought back, and with the last of them.
- The hidden browser no longer keeps a clearance cookie as an anti-bot pass unless it got past the check, and neither browser takes one a page's script could have written. A visit that ran out of time on a check kept the cookie Cloudflare sets while its check is still on, and a script of the site's page could write its own: either was sent for thirty days as a pass, under the browser's name. A clearance now counts only if it is HttpOnly, as Cloudflare sets it, and from a visit that settled on the site's own page. The cookies the vault keeps are handed to the hidden browser out of the reach of the pages' scripts, third-party ones included, which could read a kept login.
- A page can no longer crash the app for a month with a cookie. A script of a site's own page could set, in the built-in or the hidden browser, a cookie with a character past ASCII, which the engine takes and the app's client cannot send: kept, it threw at every request to the site, which ended the app seconds after every unlock until it expired. Such a cookie is now neither kept nor sent.
- The browsers open no page unless their engine holds the preferences the app starts it with. GeckoView reads them from a file and, should it fail to, goes on with its own, saying nothing: WebRTC on, a proxy with ways around it, connections to Mozilla. A browser would then have opened, gone through Orbot and passed a check as before. The engine is now asked for each of them before its first page, and the last route it kept from an earlier start no longer stands in for the proxy that leads nowhere.
- The browsers' engine no longer writes the address of every page it loads, nor the errors of the pages' scripts, to the system log, where adb, a bug report or an app allowed to read the log would find them.
- A page read by the hidden browser no longer leaves a cookie in the vault for carrying the name of one kept elsewhere, nor pushes the deadline of the cookies kept thirty days on. What the browser held after a visit was kept whenever its name matched any kept cookie of the route: a site setting its own `session` or `PHPSESSID` had it kept and sent back on every later visit, where a login from another site was kept under that name. Now a cookie is renewed only if that very one, by name, domain and path, was kept, and within the deadline it had; an anti-bot pass the visit earns is kept for the site visited alone.
- With Tor on, a site can no longer have a name looked up outside Tor. A host made of digits and dots that is not an address, as `1.2.772`, put in a file URL, a thumbnail or a redirect, was taken out directly by the HTTP client, which then had the phone ask its network's resolver for it, past the app's refusal of local lookups. No connection followed, since Android reads no such name as an address, but the question left from the home network with a name the site chose. The client's proxy is now fixed to Orbot's port while Tor is on, so every name reaches Orbot unresolved.
- The built-in browser and the hidden one now run on GeckoView, Firefox's engine, shipped inside the app, where they ran on the phone's WebView. With Tor on, a page could reach WebRTC, whose connections go around the proxy and would tell a server the phone's own address: the WebView has no setting that turns it off, and the script that removed it from pages could be got past in more ways than a script can cover. The engine leaves WebRTC out by a preference, in every frame, with no script at all.
- With Tor on, the browsers no longer reach the phone's own addresses directly. Chromium sends localhost and the loopback and link-local addresses around a proxy unless told otherwise, so a page could reach a service listening on the phone, or tell which apps it has, outside Tor. The engine's proxy takes every request, resolves every name through Tor, and has no direct way to fall back on; nothing leaves the engine before the route is set, and it makes no connection of its own.
- Nothing a site stores in the browsers outlives the session: sessions are private ones. The WebView's wipe left Cache Storage and service workers, where a site could keep a mark and find it in a later session, along the other route as well.
- A session in the built-in browser stays on its site whatever the method of a navigation: a form posted to another site was followed.

### Changed
- The APK is one for each processor architecture, arm64-v8a and armeabi-v7a, and about 95 MB, where it was one of 5 MB for all: the browsers' engine is inside it. Each has a version code of its own, the release's with a digit after it. The project's F-Droid repository carries the latest release alone. A Firefox security release reaches the built-in browsers with a release of the app.
- The built-in browsers present themselves as Firefox, and a site whose check was passed in one is then asked by the app under that name.
- Over Tor, the app and the built-in browsers leave by the same circuits: they give Orbot the same credential, where the browser gave none and Tor kept it apart from the app. A check passed in the browser is then followed by the app's request from the same exit, which sites that look again at a request from another exit would refuse.
- Through Tor the built-in browsers ask pages in English, and tell their scripts English and the UTC time zone, as Tor Browser does for everyone: the phone's language and time zone told a site something the exit hides. A site's pages, and an anti-bot check, are then in English there; on a direct connection they are in the phone's languages as before.
- Passkeys (WebAuthn) are not available in the built-in browser: the engine reaches them through Google Play services, which the app does not include, and they are turned off. Left on, a page asking whether a passkey was there would have taken the app down.
- The browsers offer pages no speech synthesis, no screen wake lock and no DRM. Speech is spoken by the phone's speech app, which may send a page's text out itself, outside Tor; a page holding the screen awake kept the phone, and with it the app's auto-lock, from locking; DRM fetches its keys around the proxy.

### Fixed
- Two texts gave the WebView's reasons. The one shown when a site asks again for a check just passed through Tor said that the browser and the downloads leave by different exits and that passing it again would not help while Tor was on: they leave by the same circuits now, and a later try may well pass. The one shown when the built-in browser cannot open told the user to update Android System WebView; it now says that the engine did not take its settings, and that nothing was loaded.
- A check in the built-in browser is no longer taken as passed while it is still on. The browser closed by itself as soon as it held a clearance cookie and the page's title was no longer "Just a moment...": Cloudflare sets such a cookie before a check is over, and gives the title in the phone's language, so on a phone not set to English the browser could close on a check still running, and the app was asked to pass it again, over and over. It now closes once the site has answered with a page of its own; for a check in front of a file, once the host has answered the browser's request for no byte of it.
- The sweep of temporary files at start also waits for the vault's header being written, which 0.6.1 left out: a password changed, a fingerprint turned on or off, or a re-seal ending just as the vault opened could fail, the copy it was writing deleted under it.

### Removed
- The risky option "Google Safe Browsing in the built-in browser". It had the phone's Google Play services check the addresses the WebView opened; the browsers' engine has no such way, only lists of its own to fetch from Google, which the app does not let it do. The app no longer asks the phone whether Play services are there.

## [0.6.1] - 2026-11-20

### Changed
- Built against Android 17's SDK (compileSdk 37), which the current libraries ask for, with them brought up to date: OkHttp 5.5, Coil 3.6, the Compose BOM 2026.09, AndroidX Core 1.19, Activity 1.13, Lifecycle 2.11, Fragment 1.9, Media3 1.11, kotlinx.serialization 1.13, BouncyCastle 1.87 and jsoup 1.24; the build itself on Gradle 9.9, the Android Gradle plugin 9.5 and Kotlin 2.4.21. The app still targets Android 16.

### Fixed
- The hint under a collection's tags names what `rating:` takes since 0.6.0: the initials g, s, q, e and u beside the ratings' names, and several with commas, as `rating:q,e`. It still listed the names in full alone.
- A few texts agree with their number in every language: a password's least length, the files re-sealed after a password change, the files of an export that stopped short, and the posts a search being collected has found. They were written for one number, as in "8 characters", and read wrongly for others in Russian, Polish, Ukrainian and Arabic above all.
- The sweep of temporary files that a write cut short left in the vault's folder, at start, waits for a save under way to put its file in place instead of deleting the copy it is writing.
- A filter of the vault saved as a collection holds what the filter showed. A word with a colon in it, as `re:zero`, which the filter finds inside tags as any word, was saved as an exact tag; and a filter saved while a collection was looked at made a collection of the whole vault, without that collection's terms.

### Security
- Back in front after five minutes away, the app has Tor's exit checked again before anything leaves, as at start. A process that took Orbot's port while the app was not using it, with no moment the app saw the port silent, answered as Orbot did and was trusted until the port was next found silent.

## [0.6.0] - 2026-11-19

### Changed
- Each line of the blacklist is a rule written as a search, in the language of the vault's collections: `-tag` excludes, `~tag` marks alternatives, `*` stands for any letters, and `rating:` takes the initials `g`, `s`, `q`, `e` and `u` or several with commas. Only whole tags and `rating:` in full were understood, so a rule like `rating:e` or `cat -solo` was taken word for word and never hid anything. Rules written before keep hiding what they hid.
- In the vault's filter and collections, `rating:s` is sensitive, as the app's ratings are labelled, and no longer general; `safe` in full is still general.
- Turning on Unrated in the content filter first asks you to confirm that you are an adult, as questionable and explicit do: a site that gives no ratings, Shimmie or a plain web page, lets its explicit posts through with the rest.
- The API calls to an e621 or e926 board signed with an account name it in their User-Agent, as e621 asks of API clients and the README said: `(by <name> on e926.net)` after the app's name and version. Images and files, fetched without the account, still do not carry it.
- The previews in the list of downloads are kept, sealed like the rest of the vault, for as long as their rows are listed: the list opened again after a lock, or after the app has been in the background, no longer asks the sites for every one of them anew. A download done saves as its thumbnail the preview its row already has, instead of asking for it a second time. A row taken off the list takes its preview with it.

### Fixed
- A search whose last pages the content filter hid entirely no longer stops at the bottom of the grid without a word: the grid says how many posts the filters hid and offers to read on.
- Tag suggestions still on their way when a tag was picked, a search started or the source changed no longer come back over what replaced them.
- A board served over plain http is sent the cookies brought back from the built-in browser: they were all kept as https only, so a login or a passed check never reached it.
- An image or GIF opened from the vault is decrypted as it is decoded, instead of whole into memory first: a very large one no longer ends the app for want of memory.
- A download whose transfer ends without a word short of the length its host announced, as an HTTP/2 stream may, is taken for cut, and taken up where it stopped or reported, instead of being saved cut short for good.
- The service that keeps downloads alive is no longer stopped by a list of downloads that had just finished while new ones were being queued, which could end the app.
- After a restart the queue comes back in the order it was queued, instead of grouped by source.
- A collection saved from a search in Browse holds what the search found. The search was saved as typed: a metatag such as `order:score` was taken for a tag no item has, so the collection held nothing, and `rating:s` saved from Danbooru, where it means sensitive, showed general items. The search is now put in the vault's language before it is offered for saving: ratings in full as the board means them, file types as `type:`, and metatags about what the vault does not keep dropped.
- A post from a site read through its web pages is checked against the content filter and the blacklist again once its own page is read, when it is downloaded: "download all" or a selection could save a post whose page said a rating or a tag the filter hides, since its list page said nothing of it. Such a download is now skipped, and says why.
- The downloads a lock left queued wait, at the next unlock, until the settings and the vault's list are read: they could start under the default filters, and download again a file already in the vault.
- A download that the phone's storage refuses to write, a full storage for one, fails and says so. It was taken for a network cut: the rest of the file was asked of the host again and sealed after a stream that might hold part of what was refused, so the file could be saved damaged, and the reason given was the network.
- A download or an import whose app was closed after the file was written but before the item was saved, as while a download fetches its preview, no longer leaves the file behind for good, unseen and impossible to delete from the app. Such files are deleted at the next start, and so are those of a deletion cut short.
- The README says what happens to a download cut short by a host that cannot take it up where it stopped: a stalled transfer is read again from the first byte, by the timeout retries, and any other cut fails the download, to be retried from the list. It said every such file was read again from its first byte.
- The onion in Browse no longer stays red once Tor works again: while it is yellow or red, and the app is in front, it greets Orbot's port every few seconds and has the exit checked again, as the network settings already did. It used to wait for a request to be refused, so with nothing new asked for it could stay red after Orbot was back, while the downloads under way went on through Tor.

### Security
- WebRTC is removed from every frame a page in the built-in browser makes or reaches, not only from the page itself. A frame's first, empty document gets no document-start script, so a page that made a frame, through the parser, `document.write`, `innerHTML`, a shadow root or any other way, could take WebRTC from it and open a peer connection outside Tor, whose STUN request would tell a server the phone's own address. Checked on a phone: eleven ways a page reaches a new frame, all of them open before, none after.
- Tor's verified exit is forgotten every time Orbot's port is found silent, as the README says: a request that found the port silent reported Tor unreachable but kept the verdict, so whatever took the port next was trusted without a check. An exit check on its way when the port was found silent no longer brings the verdict back. The port is looked at again each time the app comes back to the front, and the built-in browser, visible or hidden, closes once the verdict is withdrawn.
- With Tor off, an onion address is refused, by the app and the built-in browser alike, and says that it needs Tor, instead of being asked of the network's resolver.
- The vault locks on time when it was opened after the app was left, as when Unlock is tapped and the app left while the password is checked: it stayed open in the background with no timer at all, and "lock immediately" did not lock it. Until the settings are read the timeout counted is the default's, and it is now replaced by the user's once they are.
- A password change waits for every write that read the old key before it drops the old pair, the settings', cookies', queue's and index's included: one that stalled in the storage could land sealed to the dropped key, and at the next unlock the settings, the sources and their API keys started afresh. A save or a deletion that comes while a file is being re-sealed is no longer undone by the re-sealed copy. The files set aside as unreadable are re-sealed too, and deleted if no key opens them, so an old copy of the header reads nothing of them.
- A cookie brought back from the built-in browser, a login above all, keeps its scope and its thirty days when a site renews it. A renewal took the site's own terms: one for the whole domain sent a login bound to one host to all its sibling hosts, and one without an expiry kept it sealed in the vault for good. A cookie the site sets after the kept one ran out now lasts for the session, as the site's own do.
- A download queued just as the vault locks, a page of a search being collected for one, no longer goes out behind the locked vault when the queue is not allowed to run there. Downloads allowed to run behind the lock whose last one finished just as it came are no longer kept in memory until the next unlock.
- The notices that named a collection or a site, on saving a collection from a post's tags and on a link the built-in browser does not follow, are shown inside the app's screen protection rather than as system toasts, which a screenshot or a screen recording would catch.
- A vault header altered to ask Argon2 for more memory than the phone gives the app, though no more than the ceiling allows, makes the unlock say the vault cannot be opened, instead of ending the app.
- The release is built by workflows whose actions are pinned to the commits of their releases, and `fdroidserver` to a version, on `ubuntu-24.04`: a tag moved to other code no longer runs next to the signing keys.

## [0.5.0] - 2026-11-13

### Added
- The whole vault can be saved as one file, still encrypted, from Settings > Vault, and restored on the first screen of an install that has no vault: files, tags, settings, sources and collections come back, opened by the password the vault had. Nothing is decrypted to make it, and the site cookies, the download queue and the fingerprint's copy of the key stay behind.
- A download cut short or stalled is taken up where it stopped, when the file's host allows it, instead of starting over: over a slow Tor circuit a large file no longer begins again at every stall.

### Changed
- The vault's index is written once a run of downloads, imports or deletions has settled, instead of every time a few items arrived: with thousands of items in the vault, downloading many more no longer rewrites megabytes for each of them. An index left behind by a lock or by the end of the app is caught up at the next unlock.
- In the network settings, where Tor stands and the buttons that act on it come right under the switch, before the explanations.
- Back from a settings page opened from Browse, by the onion, the content filter or the missing sources, returns to Browse instead of the settings' hub.

### Fixed
- Downloads start in the order they were queued: they used to race for the three places, and a post queued later could start before an earlier one.
- A full image that fails to load in a viewer says why and offers to try again, instead of leaving its preview under a spinner for good. A check in front of the file can be passed in the built-in browser from there.
- A blacklist edited just before leaving the content filter is saved, instead of being dropped with the page.
- A long source name, a page's host or a large font no longer pushes the browser, the onion and the menu off the top bar of Browse: the name is cut short instead.
- A download retried while Tor's exit is still being checked, as right after Orbot is started again, waits in the queue for the check and then goes out, instead of failing at once as not yet verified.
- A download or a collection that stopped says why across the whole row, with its retry and remove buttons beside the title: the two buttons used to narrow the row, breaking the title in two and the reason into four lines.
- A page in the built-in browser ends above the system's navigation bar and the keyboard, instead of running under them.

## [0.4.0] - 2026-11-06

### Added
- An onion in the top bar of Browse shows the route at a glance: purple once the Tor exit is verified, yellow while it is being checked, red when Tor is on but not working, grey and struck through when Tor is off. A tap opens the network settings.
- Sealed files are padded so their length on disk no longer gives away what they hold: media to a multiple of 64 KiB, everything else to 4 KiB. Files from earlier versions are re-sealed in the background at the next unlock.
- With Tor chosen, the results screen says what is still missing, Orbot or its exit check, offers it there, and reloads by itself once the check passes.
- Videos start without sound, with a button that turns it on for the rest of the session.
- Screen reader support for the grids, the setting rows and the actions that change something.
- A download or a collection that stopped can be removed on its own, not only all at once.
- The vault's header says how many items a filter or a collection shows, and what they weigh.
- Turning Tor off in the settings asks first, since what follows goes out from the home address.
- A file or a search page that times out is tried again twice by itself.
- About links the source code, and a screen with no source offers to add one.

### Changed
- Through Tor a connection waits up to a minute for its circuit, instead of fifteen seconds. A failure is put down to Tor being unreachable only when Orbot's port no longer answers; otherwise it is reported as a timeout, or as what Tor or the site said.
- The vault's filter and its collections share one query language, and a filter saved as a collection keeps showing what it showed.
- A changed content filter applies to what is already loaded, and a tag blacklisted from the viewer can be taken back.
- Results are kept through a refresh, and a site that asks for a pause is tried again by itself.
- The setup speaks in plain words, shows the password rule from the start and names what a lock takes away.
- Export and import say what they did with each file they could not handle, and which side of an import failed.
- The Tor explanation in the settings is no longer part of the switch, so a tap while reading it changes nothing.
- Audio focus is taken only with the sound on.
- The button in Browse that opens the site in the built-in browser shows a browser window instead of a person, and asks before opening.

### Fixed
- Gelbooru tags, sources and suggestions with `'`, `<`, `>` or `&` no longer show as HTML entities, such as `jack-o&#039;-lantern`.
- Failed and cancelled downloads survive "Remove finished", a lock and a restart, and the bulk retry takes the cancelled ones up too.
- A download cancelled while its server had gone quiet ends at once, and is not reported as failed.
- A grid left during a refresh searches again, and reading goes on when a filter hides every post loaded.
- The built-in browser has the cookie store to itself while it is open, and what a visit brought back is kept however it is left.
- Settings that cannot be read or saved are reported, even with no screen up at the time.
- Dark mode and the font scale hold under an in-app language, and the system bars follow the theme when a viewer closes.
- The message shown while the Tor exit is checked is centred, the viewers' top bar stays legible over bright images, the Downloads icons line up and the vault's filter hint keeps to one line.

### Security
- The Tor exit verdict is forgotten when the listener is lost and enforced on every attempt, it survives cancelled connections, and the exit check itself goes out only through the listener.
- A vault header that cannot be read is reported as a damaged vault instead of failing later.
- A lock asked for while the key is being derived is held, the last chunk of a file is proven before the file is re-sealed, and a file cut short is taken for corrupt rather than for a passing failure.

## [0.3.2] - 2026-10-31

### Fixed
- Downloading many results from Gelbooru without an API key, or from a booru.org site, no longer stops early or skips and repeats posts: the size of their web pages is read right from any page, which also fixes scrolling far down their grids.
- A post that turns up twice while a search is being collected, as new uploads push the pages along, counts once toward the maximum.

## [0.3.1] - 2026-10-31

### Changed
- Back returns to what was shown before: the previous search, scrolled as it was, the post a tag was searched from, the tab visited before, or all items when leaving a collection.

### Fixed
- Downloading a search's results goes on to the last page; it used to stop at the first page that came back short. A page that fails leaves the batch on the Downloads screen, with what it found and why it stopped, to be taken up again.

## [0.3.0] - 2026-10-31

### Added
- At first start the app asks whether to go through Tor or to connect directly, and nothing goes out before the answer.
- An anti-bot check on a file host can be passed from the download row.

### Changed
- The Tor and direct routes are kept apart: what sites hand out along one, cookies, logins, anti-bot passes and TLS sessions, is never used along the other.
- The timed lock also happens in the background, and locking clears more of what was in memory: searches, cookies and site responses.

### Fixed
- Changing the password keeps every file readable even when a download or a second change lands meanwhile.

## [0.2.1] - 2026-10-29

### Added
- Releases are also served from the project's own F-Droid repository.

### Changed
- The sources offered by name are one board meant to be safe for each engine: Safebooru, Konachan.net, Safebooru.org and e926. Any other board is added from its address, and sources already added stay.
- The tags under the search field are a list with each star at the far end of its row; they close on a tap beside them or with the keyboard, and a tag can be starred straight from the suggestions.
- The login hints no longer name a site's options.

## [0.2.0] - 2026-10-28

### Added
- Turning on the questionable or explicit rating asks for an adult's confirmation.
- Every release is published on GitHub with its signed APK.

### Changed
- Only boards meant to be safe, plus Danbooru and Gelbooru, are offered by name; any other board is added from its address.

### Security
- A vault header may no longer ask for more Argon2 memory or passes than a phone can give, so a tampered one cannot exhaust the memory.
- Settings, cookies and the download queue are synced to disk before the previous copy is replaced.

## [0.1.0] - 2026-10-28

First release.

- Browsing of Danbooru, Moebooru, Gelbooru, e621, booru.org, Shimmie2 and plain web pages.
- An encrypted vault, opened with a password or a fingerprint.
- Background downloads, sealed as they stream.
- Tor through Orbot, with a verified exit.
- A built-in browser for logins and anti-bot checks.
- 20 languages.

[0.7.0]: https://github.com/firebirks/booruvault/releases/tag/v0.7.0
[0.6.1]: https://github.com/firebirks/booruvault/releases/tag/v0.6.1
[0.6.0]: https://github.com/firebirks/booruvault/releases/tag/v0.6.0
[0.5.0]: https://github.com/firebirks/booruvault/releases/tag/v0.5.0
[0.4.0]: https://github.com/firebirks/booruvault/releases/tag/v0.4.0
[0.3.2]: https://github.com/firebirks/booruvault/releases/tag/v0.3.2
[0.3.1]: https://github.com/firebirks/booruvault/releases/tag/v0.3.1
[0.3.0]: https://github.com/firebirks/booruvault/releases/tag/v0.3.0
[0.2.1]: https://github.com/firebirks/booruvault/releases/tag/v0.2.1
[0.2.0]: https://github.com/firebirks/booruvault/releases/tag/v0.2.0
[0.1.0]: https://github.com/firebirks/booruvault/releases/tag/v0.1.0
