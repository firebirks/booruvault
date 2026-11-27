# BooruVault

Your collection, encrypted and reached through Tor. BooruVault is an Android app that browses booru image boards and keeps what you save in a vault only your password opens.

Everything the app stores about you is encrypted: the files you download, your settings, your sources and their API keys, site cookies and the download queue. Downloads are sealed to the vault's public key as they stream, so they can land even while the vault is locked and plaintext never touches storage. Choose Tor at first start and every connection goes through it, by way of Orbot, with no direct fallback; what sites hand out along one route is never shown along the other.

The interface follows the phone's language, with English as the fallback. See [Languages](#languages).

## Installing

- **Obtainium:** add `https://github.com/firebirks/booruvault` as a source; it installs the APK for the phone's processor among those attached to each release, and follows later ones.
- **F-Droid client:** add the repository `https://firebirks.github.io/booruvault/fdroid/repo` with fingerprint `0DDEB006B5340592AAFC96349364A69DE0485FD1723572D7B3884414A510531A`. It carries the same APKs as the latest release, and the client takes the one for the phone's processor. In the client, under Settings > Repositories, the add button can scan this code, which holds the address and the fingerprint together:

  <img src="docs/fdroid-repo-qr.svg" alt="QR code of the BooruVault F-Droid repository, with its fingerprint" width="200">

  The same, as a link to paste: `https://firebirks.github.io/booruvault/fdroid/repo?fingerprint=0DDEB006B5340592AAFC96349364A69DE0485FD1723572D7B3884414A510531A`
- **By hand:** download `booruvault-<version>-arm64-v8a.apk` from the [releases](https://github.com/firebirks/booruvault/releases), or `booruvault-<version>-armeabi-v7a.apk` for a phone with a 32-bit processor; its SHA-256 is next to it. Each is about 95 MB: the built-in browsers' engine is inside.

All three are signed with the same key, so updates install over each other. A build from F-Droid's own repository, when there is one, is signed by F-Droid and will not update an APK from here, nor the other way round.

## Features

### Browsing

- **Sites:** any board that speaks the Danbooru, Moebooru, Gelbooru 0.2 or e926 API, through that API. Safebooru, Safebooru.org, Konachan.net and e926, one board meant to be safe for each of those APIs, are ready to use on a fresh install; any other board is added from its address. Accounts are optional; a Moebooru password is hashed on the phone the way the site expects, and only the hash is kept.
- **API or web pages?** Through its API a site hands over complete posts (file, size, md5, tags by type), in small quick pages, and the vault recognises what it already holds. Read from its web pages, as a browser would, a site needs no API or key, but each post is opened on its own to find its file, loading is slower and heavier, less is known about a post, anti-bot checks come more often, and a redesign can break the reading. The source editor says which applies to the chosen engine.
- **Sites without a usable API**, read from their web pages:
  - Gelbooru 0.2 sources fall back to the site's own pages when the API refuses, e.g. gelbooru.com without an API key.
  - Gelbooru 0.1 sites (booru.org).
  - Shimmie2 sites.
  - **Any web page:** its images and videos are extracted. The address may contain `{tags}` and `{page}`. A page opened from the Browse menu is browsed on the spot and not added to the sources.
- **Search:** tag autocomplete, infinite scrolling grid, pull to refresh. When the content filter hides several pages in a row, the grid ends there, says how many posts it hid, and offers to read on. Under an empty search field each source offers its favourite tags, starred from a post's tag sheet, from the suggestions while typing or from the shelf itself, followed by the tags searched most often on it; both are kept with the settings in the vault.
- **The route at a glance:** an onion in the top bar of Browse says where Tor stands: purple once the exit is verified, yellow while it is being checked, red when Tor is on but not working, grey and struck through when Tor is off. A tap opens the network settings. While it is yellow or red, and the app is in front, it looks again every few seconds. A download already under way when it turns red goes on through the Tor connection it was opened on; nothing new goes out until it is purple again.
- **Viewer:** pinch zoom, swipe between posts, video playback, and a tag sheet. From the sheet you can search a tag, add it to the search, exclude it or blacklist it.
- **Content filter:** by rating (general, sensitive, questionable, explicit, unrated) and blacklist rules, one a line, each written as a search in the language of the vault's collections (`-tag`, `~tag`, `*`, `rating:`, `type:`, see [Vault](#vault)): a post that matches a whole rule is hidden. Both are applied the same way to every source and to what is downloaded. A post from a site read through its web pages is known by its thumbnail alone until its own page is read; if that page says a rating or a tag the filter hides, the post is hidden then, and a download of it is skipped. Only general content is shown until you choose otherwise; turning on questionable, explicit or unrated first asks you to confirm that you are an adult, unrated included since a site that gives no ratings lets its explicit posts through with the rest.
- **Built-in browser:** used to pass anti-bot checks (Cloudflare), log in, or set site preferences. The site's cookies are then kept in the vault. It opens from the browser-window icon next to the source name in Browse, after a confirmation, or from the source's settings. A session stays on the site it was opened for: a link or script leading to another site is not followed, and the bar names the host actually on screen.

### Downloads

- **What you can download:** single posts, a multi-selection, or every result of a search (up to 1000).
- **Encrypted while streaming:** files are sealed as they arrive, so plaintext never touches storage.
- **Background:** three downloads run in parallel under a foreground service, started in the order they were queued, after a restart too. Its notification shows counts only.
- **The queue** is sealed in the vault and resumes after a restart. A download or a collection that stopped can be retried or removed on its own, or all of them at once.
- **Previews in the list** are kept, sealed, for as long as their rows are listed, so the list shown again does not ask the sites for them anew. Each is asked for once: the first time its row is shown, or when its download is done, which saves the same bytes as the item's thumbnail. A row taken off the list takes its preview with it; a lock drops those of the downloads done, which it does not keep listed.
- **Cut short:** a file whose transfer stalls, is cut, or ends short of the length its host announced is taken up where it stopped, when its host hands out parts of a file and names the file so that the rest is surely the same one (a strong `ETag`, or the date it was last changed): the rest is sealed right after what the vault already holds of it. It goes on for as long as each try brings something, and gives up after two in a row that bring nothing. From a host that does not, a file whose transfer stalled is read again from its first byte, as said under the timeouts below; one whose connection was cut in another way, reset or ended early, fails and says so, and can be retried from the list.
- **Timeouts:** a search page that times out, or a file that cannot be taken up where it stopped, is tried again twice by itself, a few seconds apart. Over Tor that is most often a slow circuit, and the next try may take another.
- **Tor being checked:** a download asked for while the exit check is on its way, as right after Orbot is started again, waits in the queue for it and goes out once it passes. With Orbot silent, or a check that failed, it fails and says so.
- **On lock:** the queue is paused and dropped from memory. A risky option lets it keep running, at the cost of keeping its URLs, tags, sources and cookies in RAM until it drains.
- **Duplicates** are skipped. Ones that cannot be detected while the vault is locked are dropped at the next unlock. The downloads a lock left queued wait, at the next unlock, until the settings and the vault's list are read, so they follow the user's filters and find what is already saved.

### Vault

- **Unlocking:** password of at least eight characters, plus optional biometric unlock. The key-derivation parameters in the header are checked against what the app would write, and against the memory the phone gives the app, so an altered header cannot make the unlock eat the memory: the vault is reported as one that cannot be opened instead. A header that cannot be read at all, or that a later version of the app wrote, is reported as damaged at start; the header can be read again, for a storage that failed for a moment, otherwise the vault can only be deleted.
- **Auto-lock:** after a configurable time in the background, five minutes by default. Android may run it a few minutes late, but coming back after the time has passed always finds the vault locked. Locking also forgets the search and the open screens.
- **Contents:** grid with filtering (tags, `-tag`, `type:video|gif|image`, `source:name`), viewer, info sheet, delete.
- **Collections:** bookmarked tag sets shown as chips at the top of the vault. A collection holds every item whose tags match its query (e.g. “Landscapes” = `landscape -rain`), including items saved later. The query uses booru syntax: exact tags, `-tag`, `~tag` alternatives, `*` wildcards, plus `type:`, `source:` and `rating:`. A rating is named by its initial, as the app labels them (`g`, `s`, `q`, `e`, `u`: general, sensitive, questionable, explicit, unrated), or in full, and several are given with commas (`rating:q,e`); `safe` is general, as most boards call it. The vault's filter box speaks the same language, except that a plain word is found inside any tag, file name or source as you type; a filter saved as a collection is rewritten with wildcards (`land` becomes `*land*`, `re:zero` becomes `*re:zero*`) so the collection holds what the filter showed, and saved within a collection it keeps that collection's terms too. Only when both carry `~` alternatives, which the language cannot keep apart, is the filter saved alone. Collections can also be created from a search in Browse, or from a tag in a post's details. A search is first put in the vault's language, shown before it is saved: a rating is named in full as the board means it (`s` is sensitive on Danbooru and safe elsewhere), a file type becomes `type:`, and metatags about what the vault does not keep, as `order:`, `score:`, `user:` or the board's own `source:`, are dropped. Their names and queries are sealed in the vault.
- **Import** from the device, optionally deleting the originals. **Export** decrypted copies to a folder or a single file. A single file, downloaded or imported, may hold up to 2 GB.
- **Backup:** Settings > Vault saves the whole vault as one file, still encrypted, to keep elsewhere or to move to another phone; the first screen of an install without a vault restores it, and it then opens with the password it had. See [Backup](#backup).
- **Screen protection** (no screenshots, hidden in recents), always on while locked.

### Privacy and network

- **No disk image cache:** browsed images stay in memory. Only the previews of the downloads you asked for are kept, sealed in the vault, while they are listed.
- **Locking forgets the session:** settings, cookies, the queue, search results and vault contents are dropped from memory. The JSON library keeps no buffers for reuse, and the network buffers kept for reuse are overwritten, so neither holds on to what went through it. A garbage collection is then requested so released key material is freed.
- **Keyboard:** every text field asks the keyboard not to learn from what is typed, the built-in browser included.
- **User-Agent:** to every site the app's own client is Chrome on Android, in the reduced form Chrome itself sends, without phone model or Android version, but for a site visited in the built-in browser, below; the Chrome version is the phone's own, asked of the system's WebView, in which the app loads nothing. Boards on the e926 API, which asks clients to name themselves, also get the app's name and version and, on the API calls signed with an account, the account's name, in the form e621 asks for (`BooruVault/<version> (by <name> on e926.net)`); images and files, fetched without the account, do not carry it. Boards on the Danbooru API get the app's name alone, `BooruVault`, without a version, for API calls, downloads and the viewer: Cloudflare on cdn.donmai.us puts an anti-bot check in front of original files for a client that calls itself Chrome without being Chrome, and lets one that names itself through. Once a check on such a board has been passed in the built-in browser, its requests carry that browser's name instead, since the pass is bound to it; once the site stops taking that pass, a request that carried it and still meets the check drops it, with the browser's name, and the board is told the app's name again. The same holds for any site visited in the built-in browser, for a check, a login or its preferences: while a cookie the visit brought back is kept, the app asks the site under the browser's name, its thumbnails included, and once the last of them runs out, under its own again. The built-in browsers are another engine, Firefox's, and go by its own name, which names the Android version but no phone model: a pass is bound to the name of the browser that earned it, and a name the engine does not match is what anti-bot checks look for. The previews in the list of downloads are asked for as the downloads themselves are, under the name the board's downloads go by: they are the thumbnails the downloads save.
- **Built-in browser hardening:** the built-in browser and the hidden one run on GeckoView, Firefox's engine, shipped inside the app; the app has no WebView.
  - WebRTC is left out of the engine, in every frame, so nothing of a page's can go around the proxy;
  - sessions are private ones: what a site stores ends with the session, and whatever is left is cleared after it;
  - autofill excluded and third-party cookies refused;
  - Firefox's own User-Agent, which names no phone model;
  - none of the engine's own connections: no telemetry, remote settings, captive portal or connectivity checks, Safe Browsing lists, region lookups, push, or updates of its own;
  - no geolocation, and pages do not reach the phone's own network;
  - a session stays on the site it was opened for, whatever the method of a navigation, and opens no window.
- **Cookies:** only those the user brings back from the built-in browser are kept, with an anti-bot pass the hidden browser earns past a check, for thirty days at most. A clearance counts only if it is HttpOnly, as Cloudflare sets it and no page script can; a cookie the client could not send, with a character past ASCII, is kept nowhere. Cloudflare's clearance is kept for the site's registrable domain (public suffix list), since its image hosts check it too; every other cookie, a login above all, is bound to the one host it was read from, and is sent over https only if it was read from an https page, so it never travels to a sibling host or to another `github.io` site. A site that renews a kept cookie renews its value only: it stays on the host or site it was bound to, and its thirty days are not pushed back. Cookies sites set on their own last for the session.
- **Risky options:** a separate settings page holds everything that trades privacy for convenience. All are off by default, and each one explains what it costs; turning one on first shows that explanation in full, behind a button that unlocks after a few seconds:
  - keep downloading while the vault is locked;
  - read pages that keep demanding an anti-bot check through a hidden browser. It runs the site's scripts and can be fingerprinted, but loads no images, refuses third-party cookies, has no WebRTC (see [Tor](#tor)), goes through Tor when Tor is on, and keeps nothing after each page;
  - open links in a normal browser when Tor is on but Tor Browser is missing.
- **Tor via Orbot:** see [below](#tor).
- **No Google Play services needed:** the app has no dependency on them and asks them nothing.

## Languages

- **Available:** English (default), plus Arabic (right-to-left), Chinese (Simplified and Traditional), Dutch, French, German, Hindi, Indonesian, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Thai, Turkish, Ukrainian and Vietnamese.
- **Which one is used:** the phone's language by default; Settings → Language picks another for this app alone. On Android 13 and later that is the system's own per-app language setting, so the two agree.
- **Errors:** they travel through the app as codes and are put into words only on screen. A site's own message is shown as the site wrote it.
- **Formats:** dates, numbers and file sizes follow the phone's conventions.

## Security design

### Key hierarchy

```
password ──Argon2id (64 MiB, t=3, p=1)──▶ KEK ──AES-256-GCM──▶ vault private key (P-256, PKCS#8)
biometric Keystore key (optional) ─────────────AES-256-GCM──▶ same private key
```

- **The private key** is the vault's root of trust. It is in memory only while the vault is unlocked.
- **The header is checked on every unlock:** the public key must be the unwrapped private key's, so a header whose public key was swapped (which would seal new files to someone else's key) is refused instead of used.
- **The Argon2id parameters** are stored with the vault, so they can be raised later.
- **The biometric key** is an Android Keystore AES key that requires strong biometric authentication for every use. Enrolling a new fingerprint invalidates it.
- **Changing the password replaces the key pair.** The old private key is kept in the header, boxed to the new public key, while every file is re-sealed to the new pair in the background; then it is dropped. The retired pair is dropped only once no write that read the old key, of a file, the settings, the cookies or the queue, may still land. Once that is done, an old copy of the header opened with the old password reads nothing. Locking pauses the re-sealing and the next unlock resumes it. The biometric copy, which held the old key, is removed and must be enrolled again.

### Sealed files

Every file (media, thumbnails, metadata, the index, settings, cookies, the queue and its previews) is sealed to the vault's **public** key:

- **ECIES:** an ephemeral P-256 key pair, ECDH with the vault key, and HKDF-SHA256 over a random salt, the header and both public keys. This yields an AES-256 key and a nonce prefix for this file only.
- **Chunks:** the payload is split into 64 KiB chunks, each sealed with AES-256-GCM. The nonce is `prefix ‖ chunk index ‖ last-chunk flag`, and the whole header is the associated data. Reordering, truncation and tampering are all detected.
- **Random access:** every chunk but the last is full, so any offset can be decrypted on its own. Videos stream to ExoPlayer and thumbnails are decoded straight from the decrypting reader.
- **Padding:** the payload ends with padding, sealed with the rest, chosen so that a media file's length on disk is a multiple of 64 KiB. The exact size of a file is a fingerprint, since a site's API states it for every post; the length on disk now says only which 64 KiB range it falls in, at a cost that is usually under 64 KiB per media file and never more than a few bytes over 128 KiB. Thumbnails, metadata and the other sealed files, whose sizes no one outside the app knows, are padded to 4 KiB, the storage's own block, which costs one more block, two in rare cases. Files sealed by earlier versions, without padding, are still read and are re-sealed in the background at the next unlock, each copy synced before it replaces the file; a marker then says the vault is in the current format, so the files are not looked over at every unlock.

Sealing needs only the public key, which is how downloads can land in a locked vault. Reading needs the private key.

### On disk

All files live in the app's private storage, under `files/vault/`:

| File | Content |
|---|---|
| `vault.json` | Header: KDF parameters, public key, wrapped private key, and during a re-keying the retired key boxed to the new one. **Clear**, holds no user data. |
| `meta/<id>` | Item metadata (source of truth). Sealed. |
| `media/<id>`, `thumbs/<id>` | File and thumbnail. Ones no meta file names, left by a process that ended before the item was committed, are deleted at the next start. Sealed. |
| `index.bin` | Cache of all metadata, rebuilt from `meta/` if lost. Written once changes have settled, not for each one, so it may lag behind `meta/`; an unlock reads what it missed. Sealed. |
| `config.bin` | Settings, sources, API keys. Sealed. |
| `cookies.bin` | Kept site cookies and the User-Agent each site's check was passed with, one set for Tor and one for the direct connection. Sealed. |
| `downloads.bin` | Pending, failed and cancelled downloads, and search collections in progress. Sealed. |
| `previews/<id>` | The preview of a download listed, under an id of its row's own. A cache: gone with the row, and fetched again if it will not open. Sealed. |
| `format-2` | Empty marker: every sealed file was found in the current, padded format, so they are not looked over at each unlock. **Clear**. |
| `*.corrupt` | A sealed settings, cookies or queue file that could not be read, set aside rather than written over. Re-sealed with the rest after a password change; one that no key opens is deleted then. Sealed. |

File names are random UUIDs. The number of files remains visible, that of the previews being at most that of the downloads listed, and, once the background re-sealing is done, each file's size to within 64 KiB for media and 4 KiB for the rest.

The other clear-text settings are the Tor on/off switch, Orbot's port and whether the route was chosen at first start (`shared_prefs/network.xml`). They must hold from process start, before any unlock, so that no connection can go out directly. They reveal only whether the app is set to use Tor, and on which local port. Before Android 13 the app's language is kept in clear too (`shared_prefs/ui.xml`), as it must show on the lock screen; from Android 13 the system keeps it.

### Backup

A backup is a ZIP of the vault's own files as they lie on storage: the header, the settings (`config.bin`) and each item's media, thumbnail and metadata. Nothing is decrypted to make it and the private key is not needed, so one under way goes on if the vault locks meanwhile.

- **What it tells:** what the vault's folder tells, and no more. The header is in clear, as on the phone; every other file is sealed, under its random name and at its padded size. The entries are stored uncompressed and all bear one fixed date, not the files' own, which would say when each item was saved.
- **What protects it:** the password alone. On the phone the header sits in the app's private storage; in a backup it travels with the files, and whoever holds the file can try passwords against Argon2id for as long as they like. A backup deserves a strong password.
- **A password changed later** does not reach a backup already made: it still opens with the password the vault had then. Changing the password while a backup is being written makes that backup fail, to be made again, since files re-sealed meanwhile would be to a key its header does not hold. One made while the files are still being re-sealed after a change holds the retired key boxed in its header, as the vault does, and the re-sealing goes on after a restore.
- **Left out:** the index, which is rebuilt from the metadata; the site cookies, which belong to the route and the browser they were earned with; the download queue and its previews; and the biometric copy of the key, which only the Keystore of the phone it was made on can open.
- **Restoring** is offered where there is no vault, on the first screen. The backup is laid out whole beside the vault's place (`files/vault.restore/`, cleared at the next start if the app was closed midway) and moved there only once its last entry, which counts the others, has been read: a file that is not a backup, one cut short, or one written by a later version leaves no vault and no files. Nothing in it is taken on its word: only the names a backup uses are accepted, each file must begin as a sealed file does and stay within the size its kind can have, and the header is read as the vault's own is at every start. The route question is asked again after the first unlock, since the choice between Tor and a direct connection is kept outside the vault and does not travel with it.
- **While it runs,** a foreground service keeps the app alive; its notification says how far it is. A backup that fails or is stopped deletes the file it had begun.

### Memory

Arrays the app owns are overwritten once used: passwords, derived keys, decrypted keys, chunk and transfer buffers, thumbnails.

On the JVM this is best effort and not a guarantee:
- strings are immutable;
- the garbage collector may leave copies of objects it has moved;
- decoded bitmaps and video buffers belong to the platform.

## Tor

**Asked at the first start:** right after the vault is created, and before anything goes out, the app asks whether to use Tor through Orbot or a direct connection. The two answers look alike and neither is picked in advance. Until one is chosen, no screen that makes requests is shown, and the HTTP client and the built-in browser refuse anything that would leave; a unit test checks that no name is looked up and no connection made. With Tor chosen, the results screen says what is still missing, Orbot or its exit check, offers to open or install it, or to connect directly instead, and loads by itself once the check passes. The choice can be changed in Settings › Network and built-in browser, where turning Tor off asks first, since what follows goes out from the home address. Installs that already had a vault are not asked.

BooruVault uses [Orbot](https://orbot.app). When Tor is enabled:

- **The app's network traffic** goes through one HTTP client, built once: API calls and page reads on one queue, images, video and downloads on another, sharing the same route and connections.
- **Orbot only:** that client reaches the network through Orbot's SOCKS port and nothing else. Its proxy is fixed to that port for as long as Tor is on, not chosen request by request: OkHttp asks a proxy selector only about an address in which Java finds a host, and sends any other out directly. If Orbot is not running, every request fails; there is no direct fallback. A request keeps to the route it was made on, and one made directly is refused if Tor is switched on before it leaves. When Tor is switched on, pooled connections opened directly are dropped, and until they are, a guard refuses any request that would reuse one.
- **Slow circuits:** through Tor a connection waits up to a minute for its circuit, rather than the fifteen seconds a direct one gets. A request that fails is put down to Tor being unreachable only when Orbot's port no longer answers; otherwise it says what happened: a timeout, or what Tor or the site reported.
- **The port is a setting** (default 9050). It is never taken from a broadcast: Orbot's status messages carried no proof of their sender, so any app could have sent one naming a port it listens on, and current Orbot sends none at all.
- **No local DNS:** host names are passed to Orbot unresolved, and any local DNS lookup is refused. That holds for a host made of digits and dots that is not an address, as `1.2.772`, which a site could name in a file URL or a redirect: Java finds no host in it, and a client that chose its proxy by asking would have the phone look it up itself. A unit test drives a fake SOCKS5 server to check that names, those included, travel as domains. With Tor off, an onion address is refused, by the app and the built-in browser alike, rather than asked of the network's resolver, which would learn which hidden service the phone was after.
- **Built-in browsers:** the engine's proxy is set by its preferences to Orbot's SOCKS port before any page loads, and takes every request, those for localhost and the phone's own addresses included: through them a page could reach a service listening on the phone, or tell which apps it has, outside Tor. Names are resolved by Tor and the engine resolves none itself; there is no direct way to fall back on, no DNS over HTTPS and no HTTP/3, which a SOCKS proxy does not carry. Pages are asked for in English, and their scripts are told English and the UTC time zone, as Tor Browser tells everyone, where the phone's language and time zone would set a user apart; directly, the phone's own. The engine starts with a proxy that leads nowhere, so nothing leaves before a route is set, and makes no connection of its own. WebRTC, whose connections would not go through the proxy and would tell a STUN server the phone's own address, is not in the engine at all: it is left out by a preference, in every frame, where a WebView has no such setting and a script in the page cannot cover every way a frame comes to be. The app's client and the engine give Orbot the same SOCKS credential, so Tor carries both on the same circuits and a check passed in the browser is followed by the app from the same exit. Most of the preferences are the ones Tor Browser sets for the same ends. `tools/gecko-probe` checks all of this on a phone.
- **Orbot integration:** since Orbot 17.9.6 other apps can neither start it nor hear its status, so the app finds out by itself whether a SOCKS5 server answers on the port. When none does, the results screen says so, offers to open or install Orbot, or to connect directly instead, and loads by itself once the exit check passes.
- **The listener is not Tor until proven:** any local process that took the port first would answer the SOCKS5 greeting too, see every host name and go out in clear. So as soon as the port answers, the app asks `check.torproject.org` whether its connections really exit through Tor; until that check passes, nothing but the check itself leaves, and the built-in browser does not open. The verdict is forgotten, and the check asked again of whatever answers next, as soon as the port is found silent or a connection to it fails because of the listener itself (refused, reset, or answered with something that is not SOCKS5); a redirect or a retry of a request already under way is refused in the meantime, a check still on its way speaks for nothing, and the built-in browser, visible or hidden, closes. The port is also looked at each time the app comes back to the front, and after five minutes away, as long as the auto-lock's default, the exit is checked again before anything leaves, as at start. A failure Tor reports about a site it cannot reach does not count against the listener. A process that took the port while the app was not looking, between two of its connections, answers as Orbot did and is not told apart until the port is next found silent, or the app comes back after five minutes away. A proxy that does forward to Tor while logging destinations cannot be told apart this way.
- **Nothing crosses between routes:** cookies (logins included), anti-bot passes with the browser name that earned them, and TLS sessions are kept apart for Tor and for the direct connection, so a site cannot tie the Tor user to the one at your home address through something it handed out along the other route. Switching Tor on or off leaves the other route's set as it was, for when you switch back. A request takes the set of the connection it actually leaves on, so a switch flipped while it is on its way cannot mix them; a unit test plays both routes against one address, and a switch flipped both ways while a request is on its way. Through Tor, each of the app's own connections also gets a TLS session cache of its own, which dies with it: a session resumed on a later connection, which may leave by another exit, would tie the two exits together. The browsers' engine keeps its TLS sessions for as long as a browser is open: its cache is emptied each time one closes, and a route switch takes the engine off the old route at once.
- **Installing Orbot:** if it is missing, the app lets you choose among the installed stores (F-Droid, Droid-ify, Aurora, Play…), or opens Orbot's site.

- **Links outside the app** ("open in browser", a post's source) open in Tor Browser while Tor is on. Without Tor Browser, the link can only be copied, to paste in a browser that goes through Orbot. Opening it in a normal browser is possible only with the risky option "Open links outside Tor" on, and then behind a warning that explains what a normal browser exposes and keeps; its "open anyway" button unlocks after a few seconds.

**Orbot's VPN mode** with BooruVault selected is an extra, system-level safety net. Android runs one VPN at a time, so it is an option only for those who use no other VPN; without it, the app still sends all its traffic through Orbot's proxy.

Tor hides where you connect from, not who you are. To every site you are not logged in to, you are just another Tor user. A site you are logged in to, or that recognises its own cookies, still knows which account is browsing, exactly as it would without Tor; what it no longer sees is your address. If some browsing should stay apart from an account, do it logged out, or delete the site cookies first, under Settings › Network and built-in browser, which logs you out of every site at once. A login made without Tor is not carried over when Tor is turned on, nor the other way round: log in along the route you want to use.

## Building

Requirements:
- JDK 17;
- Android SDK with platform 37 (`platforms;android-37.0`).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-<abi>-debug.apk, one for each ABI
./gradlew testDebugUnitTest    # JVM tests: crypto, vault, backup, sealed state, downloads, parsers, filters and queries, Tor routing
./gradlew lintDebug
./gradlew assembleRelease      # minified with R8; unsigned, no signing config is included
./gradlew assembleStaging      # the same, debug-signed and under its own package name, to try on a phone beside the release
```

`assembleStaging` builds what R8 makes of the release, renamed classes and all, and is not debuggable, as a release is not: its files are out of adb's reach, and the debug build is the one to read them in.

The Gradle distribution the wrapper fetches is checked against the SHA-256 Gradle publishes for it (`distributionSha256Sum`, to be changed with the version). Every dependency, Gradle's plugins included, is checked against the SHA-256 in `gradle/verification-metadata.xml` before the build uses it; GeckoView's is the one Mozilla publishes beside it. A changed version needs its checksums written there, by the tasks the workflows run, and the new entries looked over before they are committed:

```sh
./gradlew --write-verification-metadata sha256 assembleDebug lintDebug testDebugUnitTest assembleRelease
```

`tools/gecko-probe/run.sh` checks on a phone over adb what the built-in browsers rest on: that the engine has no WebRTC in any frame a page can reach, that every request goes to the proxy, that the engine makes no connection of its own, that a private session leaves nothing, and that the app's preferences and extension do what the app expects. Run it on every GeckoView update; it is built with the app's own version of the engine. See [its README](tools/gecko-probe/README.md).

- **SDK and versions:** `minSdk` is 26 (Android 8), `compileSdk` is 37.2 and `targetSdk` 36: the app is built against Android 17's SDK, which the current libraries ask for, and still targets Android 16, so Android 17's changes of behaviour do not apply to it yet. The versions are in `gradle/libs.versions.toml`.
- **Stack:** Kotlin, Jetpack Compose with Material 3, OkHttp, kotlinx.serialization, Coil 3 with Telephoto, Media3, jsoup, BouncyCastle (Argon2 only), androidx.biometric, and GeckoView for the built-in browsers. GeckoView comes prebuilt from Mozilla's Maven repository, which the build uses for that group alone and asks no other repository for it; Google Play services' FIDO library, which it depends on, is left out.

## Releasing

A release is a commit and a tag:

1. Move the workflows' pins along, in their own commit: each action to the commit of its current release, named beside it (`gh api repos/<owner>/<action>/commits/<tag> --jq .sha`), and `fdroidserver` to its current version. Nothing does this by itself: the repository has Dependabot alerts on for the workflows' actions, but no Dependabot pull requests, whose commits would be the bot's. Dependabot reads no Gradle file, and knows of no GeckoView vulnerability: Firefox's security releases are watched by the `GeckoView release check` workflow, which each day compares Mozilla's latest GeckoView release with `gradle/libs.versions.toml` and opens an issue when Mozilla's is newer. Each is a reason for a release of its own, as soon as the probe has passed on it: Firefox's advisories make its fixes public the day they come out.
2. Raise `versionCode` (always by one) and `versionName` in `app/build.gradle.kts`. That code is the release's; the build gives each ABI's APK its own, the release's with a digit after it (1 for armeabi-v7a, 2 for arm64-v8a, 4 for x86_64), since F-Droid tells the APKs of one version apart by their codes. Release 11 is then 111 and 112 on phones.
3. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, under the release's code, at most 500 characters, and give the version its dated entry in [CHANGELOG.md](CHANGELOG.md).
4. Set `CurrentVersion` to `versionName` and `CurrentVersionCode` to the arm64-v8a APK's code (the release's with a 2 after it) in `fdroid/io.github.firebirks.booruvault.yml`.
5. Commit, tag `v<versionName>` and push the tag.

The tag starts the `Release` workflow, which checks that the tag matches `versionName`, that the F-Droid metadata names this version and its arm64-v8a code, and that no line of temporary debugging is left, runs the tests, builds an APK for each ABI signed with the project's key (kept in the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` and `KEY_PASSWORD`) and publishes those for arm64-v8a and armeabi-v7a, each with its SHA-256, as a GitHub release whose notes are the changelog. It builds with no Gradle cache from earlier runs, and stops before publishing anything if the APKs come to over 900 MiB, too near the 1 GB GitHub Pages takes for a site. Obtainium and direct installs use those APKs. The x86_64 one, for emulators, is built and not published. Both workflows run on `ubuntu-24.04` with every action pinned to the commit of the release named beside it, and `fdroidserver` pinned to a version, so that a tag moved to other code cannot run next to the signing keys; raising one means looking up the new release's commit.

A second job then puts the same APKs in the project's F-Droid repository, served by GitHub Pages under `fdroid/repo`, in place of the release before: with an engine in each APK the repository carries the latest release alone. It runs `fdroid update` with the metadata in `fdroid/` and the texts and icon in `fastlane/`, the changelog copied under each APK's code, and signs the index with the repository key (secrets `FDROID_KEYSTORE_BASE64`, `FDROID_KEYSTORE_PASS` and `FDROID_KEY_PASS`, alias `booruvault-repo`). The repository and the page in `site/` beside it go to Pages as an artifact the job deploys, not as a branch, so no APK enters the history of the code or a clone of it. Pages takes this only with the repository's Settings > Pages source set to GitHub Actions, and the `github-pages` environment allowing deployments from `v*` tags. Both keys live offline with their owner; losing the app key would leave users unable to update without reinstalling.

`fdroid/io.github.firebirks.booruvault.yml` is the metadata the project's repository is made with, and the reference copy of what F-Droid's own repository would need to build every version itself from the same tag. Its `CurrentVersion` and `CurrentVersionCode` go up with a release, the code to the arm64-v8a APK's, the higher of the two published, so that a client suggests each phone the newest APK it can take. F-Droid's own build is open since the browsers moved to GeckoView: the engine comes prebuilt from Mozilla's Maven repository, and a build recipe there would also need an entry and a version code for each ABI. `fastlane/metadata/android/` holds the store texts; the icon and screenshots are taken from the APK when not provided there.

To sign a release build locally, put a `keystore.properties` next to this file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; it is ignored by git, and `assembleRelease` then produces signed APKs. Without it the release build is unsigned.

See [PRIVACY.md](PRIVACY.md) for what the app sends and keeps, in one page.

## Project layout

```
app/src/main/kotlin/io/github/firebirks/booruvault/
├── booru/      site engines: JSON APIs, HTML parsers, models, presets
├── download/   download queue and foreground service
├── net/        sealed cookie jar, Tor/Orbot, built-in browser hardening
├── settings/   sealed settings, content filter
├── vault/      key management, sealed store, import/export, backup, Coil and Media3 adapters
│   └── crypto/ Argon2id, HKDF, AES-GCM, the sealed stream format
├── ui/         Compose screens: browse, vault, downloads, settings, lock screens
└── util/
```

`site/` holds the page at the root of the project's Pages site, plain HTML with nothing fetched from elsewhere: the release workflow deploys it there, beside the F-Droid repository under `fdroid/repo`.

## Limitations

- **Fragile parsers:** HTML engines depend on each site's current markup and break when it changes.
- **Terms of service:** reading sites through their web pages may be against some of them.
- **Accounts and blocks:** some sites need an API key, and some block Tor or ask for anti-bot checks more often.
- **The browsers' engine:** GeckoView ships inside the app, about 95 MB for each processor architecture, and does not update by itself: a Firefox security release reaches the built-in browsers only with a release of the app. It keeps a profile of its own in the app's files, outside the vault; sessions are private ones and store nothing of a visit there. Its name tells sites the Android version. WebAuthn (passkeys) is turned off in the built-in browser, as it would need Google Play services; so are speech synthesis, the screen wake lock and DRM, which would reach another app, keep the phone from locking, or fetch keys around the proxy.
- **Memory:** the clearing is best effort, see [Memory](#memory).
- **Backups are whole:** each one writes the entire vault again, and needs that much room where it is saved; there is no backup that adds only what is new. One is restored only where there is no vault, never merged into one that exists.
- **Resuming a download** needs a host that hands out parts of a file and names the file; elsewhere a transfer cut short starts over, as before.

## License

Copyright (C) 2026 firebirks

BooruVault is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

BooruVault is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

The full text is in [LICENSE](LICENSE). The libraries the app uses keep their own licenses (Apache-2.0, MIT and similar), all compatible with GPL-3.0; Settings → About lists each of them with its author and license.
