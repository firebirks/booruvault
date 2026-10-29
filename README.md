# BooruVault

Your collection, encrypted and reached through Tor. BooruVault is an Android app that browses booru image boards and keeps what you save in a vault only your password opens.

Everything the app stores about you is encrypted: the files you download, your settings, your sources and their API keys, site cookies and the download queue. Downloads are sealed to the vault's public key as they stream, so they can land even while the vault is locked and plaintext never touches storage. With Orbot installed, every connection goes through Tor, with no direct fallback.

The interface follows the phone's language, with English as the fallback. See [Languages](#languages).

## Installing

- **Obtainium:** add `https://github.com/firebirks/booruvault` as a source; it installs the APK attached to each release and follows later ones.
- **F-Droid client:** add the repository `https://firebirks.github.io/booruvault/fdroid/repo` with fingerprint `0DDEB006B5340592AAFC96349364A69DE0485FD1723572D7B3884414A510531A`. It carries the same APK as the release.
- **By hand:** download `booruvault-<version>.apk` from the [releases](https://github.com/firebirks/booruvault/releases); its SHA-256 is next to it.

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
- **Search:** tag autocomplete, infinite scrolling grid, pull to refresh. Under an empty search field each source offers its favourite tags, starred from a post's tag sheet or from the shelf itself, followed by the tags searched most often on it; both are kept with the settings in the vault.
- **Viewer:** pinch zoom, swipe between posts, video playback, and a tag sheet. From the sheet you can search a tag, add it to the search, exclude it or blacklist it.
- **Content filter:** by rating (general, sensitive, questionable, explicit, unrated) and blacklist rules, applied the same way to every source. Only general content is shown until you choose otherwise; turning on questionable or explicit first asks you to confirm that you are an adult.
- **Built-in browser:** used to pass anti-bot checks (Cloudflare), log in, or set site preferences. The site's cookies are then kept in the vault. It opens from the icon next to the source name in Browse, or from the source's settings. A session stays on the site it was opened for: a link or script leading to another site is not followed, and the bar names the host actually on screen.

### Downloads

- **What you can download:** single posts, a multi-selection, or every result of a search (up to 1000).
- **Encrypted while streaming:** files are sealed as they arrive, so plaintext never touches storage.
- **Background:** three downloads run in parallel under a foreground service. Its notification shows counts only.
- **The queue** is sealed in the vault and resumes after a restart.
- **On lock:** the queue is paused and dropped from memory. A risky option lets it keep running, at the cost of keeping its URLs, tags, sources and cookies in RAM until it drains.
- **Duplicates** are skipped. Ones that cannot be detected while the vault is locked are dropped at the next unlock.

### Vault

- **Unlocking:** password of at least eight characters, plus optional biometric unlock. The key-derivation parameters in the header are checked against what the app would write, so an altered header cannot make the unlock eat the memory.
- **Auto-lock:** after a configurable time in the background, five minutes by default. Locking also forgets the search and the open screens.
- **Contents:** grid with filtering (tags, `-tag`, `type:video|gif|image`, `source:name`), viewer, info sheet, delete.
- **Collections:** bookmarked tag sets shown as chips at the top of the vault. A collection holds every item whose tags match its query (e.g. “Landscapes” = `landscape -rain`), including items saved later. The query uses booru syntax: exact tags, `-tag`, `~tag` alternatives, `*` wildcards, plus `type:`, `source:` and `rating:`. Collections can be created from the vault filter, from a search in Browse, or from a tag in a post's details. Their names and queries are sealed in the vault.
- **Import** from the device, optionally deleting the originals. **Export** decrypted copies to a folder or a single file. A single file, downloaded or imported, may hold up to 2 GB.
- **Screen protection** (no screenshots, hidden in recents), always on while locked.

### Privacy and network

- **No disk image cache:** browsed images stay in memory.
- **Locking forgets the session:** settings, cookies, the queue, search results and vault contents are dropped from memory. A garbage collection is then requested so released key material is freed.
- **Keyboard:** every text field asks the keyboard not to learn from what is typed, the built-in browser included.
- **User-Agent:** to every site the app is Chrome on Android, in the reduced form Chrome itself sends, without phone model or Android version; the Chrome version is the phone's own. Boards on the e926 API, which asks clients to name themselves, also get the app's name and, when the request is signed with an account, its login.
- **Built-in browser hardening:**
  - autofill excluded and third-party cookies blocked;
  - reduced User-Agent, without phone model or Android version;
  - no WebView metrics;
  - Safe Browsing off unless the user turns it on;
  - its storage wiped after each session and at start-up.
- **Cookies:** only those the user brings back from the built-in browser are kept, for thirty days at most. Cloudflare's clearance is kept for the site's registrable domain (public suffix list), since its image hosts check it too; every other cookie, a login above all, is bound to the one host it was read from, so it never travels to a sibling host or to another `github.io` site. Cookies sites set on their own last for the session.
- **Risky options:** a separate settings page holds everything that trades privacy for convenience. All are off by default, and each one explains what it costs; turning one on first shows that explanation in full, behind a button that unlocks after a few seconds:
  - keep downloading while the vault is locked;
  - read pages that keep demanding an anti-bot check through a hidden browser. It runs the site's scripts and can be fingerprinted, but loads no images, blocks third-party cookies, has WebRTC off, goes through Tor when Tor is on, and is wiped after each page;
  - Google Safe Browsing in the built-in browser;
  - open links in a normal browser when Tor is on but Tor Browser is missing.
- **Tor via Orbot:** see [below](#tor).
- **No Google Play services needed:** the app has no dependency on them. Without them, the Safe Browsing option is shown as unavailable.

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
- **Changing the password replaces the key pair.** The old private key is kept in the header, boxed to the new public key, while every file is re-sealed to the new pair in the background; then it is dropped. Once that is done, an old copy of the header opened with the old password reads nothing. Locking pauses the re-sealing and the next unlock resumes it. The biometric copy, which held the old key, is removed and must be enrolled again.

### Sealed files

Every file (media, thumbnails, metadata, the index, settings, cookies, the queue) is sealed to the vault's **public** key:

- **ECIES:** an ephemeral P-256 key pair, ECDH with the vault key, and HKDF-SHA256 over a random salt, the header and both public keys. This yields an AES-256 key and a nonce prefix for this file only.
- **Chunks:** the payload is split into 64 KiB chunks, each sealed with AES-256-GCM. The nonce is `prefix ‖ chunk index ‖ last-chunk flag`, and the whole header is the associated data. Reordering, truncation and tampering are all detected.
- **Random access:** every chunk but the last is full, so any offset can be decrypted on its own. Videos stream to ExoPlayer and thumbnails are decoded straight from the decrypting reader.

Sealing needs only the public key, which is how downloads can land in a locked vault. Reading needs the private key.

### On disk

All files live in the app's private storage, under `files/vault/`:

| File | Content |
|---|---|
| `vault.json` | Header: KDF parameters, public key, wrapped private key, and during a re-keying the retired key boxed to the new one. **Clear**, holds no user data. |
| `meta/<id>` | Item metadata (source of truth). Sealed. |
| `media/<id>`, `thumbs/<id>` | File and thumbnail. Sealed. |
| `index.bin` | Cache of all metadata, rebuilt from `meta/` if lost. Sealed. |
| `config.bin` | Settings, sources, API keys. Sealed. |
| `cookies.bin` | Kept site cookies and the User-Agent each site's check was passed with. Sealed. |
| `downloads.bin` | Pending and failed downloads, and search collections in progress. Sealed. |

File names are random UUIDs. The number of files and their approximate sizes remain visible.

The other clear-text settings are the Tor on/off switch and Orbot's port (`shared_prefs/network.xml`). They must hold from process start, before any unlock, so that no connection can go out directly. They reveal only that the app is set to use Tor, and on which local port.

### Memory

Arrays the app owns are overwritten once used: passwords, derived keys, decrypted keys, chunk and transfer buffers, thumbnails.

On the JVM this is best effort and not a guarantee:
- strings are immutable;
- the garbage collector may leave copies of objects it has moved;
- decoded bitmaps and video buffers belong to the platform.

## Tor

BooruVault uses [Orbot](https://orbot.app). When Tor is enabled:

- **The app's network traffic** goes through one HTTP client, built once: API calls and page reads on one queue, images, video and downloads on another, sharing the same route and connections.
- **Orbot only:** that client reaches the network through Orbot's SOCKS port and nothing else. If Orbot is not running, every request fails; there is no direct fallback. When Tor is switched on, pooled connections opened directly are dropped, and until they are, a guard refuses any request that would reuse one.
- **The port is a setting** (default 9050). It is never taken from a broadcast: Orbot's status messages carried no proof of their sender, so any app could have sent one naming a port it listens on, and current Orbot sends none at all.
- **No local DNS:** host names are passed to Orbot unresolved, and any local DNS lookup is refused. A unit test drives a fake SOCKS5 server to check that names travel as domains.
- **Built-in browser:** goes through Orbot via a WebView proxy override with no direct rule, and WebRTC is removed before page scripts run. If the WebView cannot do either, the built-in browser does not open while Tor is on.
- **Orbot integration:** since Orbot 17.9.6 other apps can neither start it nor hear its status, so the app finds out by itself whether a SOCKS5 server answers on the port, and offers to open Orbot when none does.
- **The listener is not Tor until proven:** any local process that took the port first would answer the SOCKS5 greeting too, see every host name and go out in clear. So as soon as the port answers, and again whenever it stops and comes back, the app asks `check.torproject.org` whether its connections really exit through Tor; until that check passes, nothing but the check itself leaves, and the built-in browser does not open. A proxy that does forward to Tor while logging destinations cannot be told apart this way.
- **Installing Orbot:** if it is missing, the app lets you choose among the installed stores (F-Droid, Droid-ify, Aurora, Play…), or opens Orbot's site.

- **Links outside the app** ("open in browser", a post's source) open in Tor Browser while Tor is on. Without Tor Browser, the link can only be copied, to paste in a browser that goes through Orbot. Opening it in a normal browser is possible only with the risky option "Open links outside Tor" on, and then behind a warning that explains what a normal browser exposes and keeps; its "open anyway" button unlocks after a few seconds.

**Orbot's VPN mode** with BooruVault selected is an extra, system-level safety net, mainly for the WebView.

Tor hides where you connect from, not who you are. To every site you are not logged in to, you are just another Tor user. A site you are logged in to, or that recognises its own cookies, still knows which account is browsing, exactly as it would without Tor; what it no longer sees is your address. If some browsing should stay apart from an account, do it logged out, or clear that site's cookies in the Data section first.

## Building

Requirements:
- JDK 17;
- Android SDK with platform 36.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM tests: crypto, vault, sealed state, parsers, Tor routing
./gradlew lintDebug
./gradlew assembleRelease      # minified with R8; unsigned, no signing config is included
```

- **SDK and version pins:** `minSdk` is 26 (Android 8), `compileSdk`/`targetSdk` is 36. Several dependencies are held at the last version that compiles against SDK 36 (OkHttp 5.4, Coil 3.4, the Compose BOM). See the notes in `gradle/libs.versions.toml`.
- **Stack:** Kotlin, Jetpack Compose with Material 3, OkHttp, kotlinx.serialization, Coil 3 with Telephoto, Media3, jsoup, BouncyCastle (Argon2 only), androidx.biometric and androidx.webkit.

## Releasing

A release is a commit and a tag:

1. Raise `versionCode` (always by one) and `versionName` in `app/build.gradle.kts`.
2. Write `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Commit, tag `v<versionName>` and push the tag.

The tag starts the `Release` workflow, which checks that the tag matches `versionName`, runs the tests, builds the APK signed with the project's key (kept in the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` and `KEY_PASSWORD`) and publishes it, with its SHA-256, as a GitHub release whose notes are the changelog. Obtainium and direct installs use that APK.

A second job then adds the same APK to the project's F-Droid repository, served by GitHub Pages from the `gh-pages` branch under `fdroid/repo`. It runs `fdroid update` with the metadata in `fdroid/` and the texts and icon in `fastlane/`, signs the index with the repository key (secrets `FDROID_KEYSTORE_BASE64`, `FDROID_KEYSTORE_PASS` and `FDROID_KEY_PASS`, alias `booruvault-repo`) and commits only `fdroid/repo`, so the site at the root of the branch is untouched. Both keys live offline with their owner; losing the app key would leave users unable to update without reinstalling.

F-Droid builds every version itself from the same tag and signs it with its own key; `fdroid/io.github.firebirks.booruvault.yml` is the reference copy of its metadata. The first version is submitted to [fdroiddata](https://gitlab.com/fdroid/fdroiddata) as a merge request, later ones are picked up through `UpdateCheckMode: Tags`. `fastlane/metadata/android/` holds the store texts; the icon and screenshots are taken from the APK when not provided there.

To sign a release build locally, put a `keystore.properties` next to this file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; it is ignored by git, and `assembleRelease` then produces a signed APK. Without it the release build is unsigned, exactly as F-Droid makes it.

See [PRIVACY.md](PRIVACY.md) for what the app sends and keeps, in one page.

## Project layout

```
app/src/main/kotlin/io/github/firebirks/booruvault/
├── booru/      site engines: JSON APIs, HTML parsers, models, presets
├── download/   download queue and foreground service
├── net/        sealed cookie jar, Tor/Orbot, built-in browser hardening
├── settings/   sealed settings, content filter
├── vault/      key management, sealed store, import/export, Coil and Media3 adapters
│   └── crypto/ Argon2id, HKDF, AES-GCM, the sealed stream format
├── ui/         Compose screens: browse, vault, downloads, settings, lock screens
└── util/
```

## Limitations

- **Fragile parsers:** HTML engines depend on each site's current markup and break when it changes.
- **Terms of service:** reading sites through their web pages may be against some of them.
- **Accounts and blocks:** some sites need an API key, and some block Tor or ask for anti-bot checks more often.
- **WebView storage:** cleared as far as the platform allows; a few profile files may remain.
- **Memory:** the clearing is best effort, see [Memory](#memory).

## License

Copyright (C) 2026 firebirks

BooruVault is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

BooruVault is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

The full text is in [LICENSE](LICENSE). The libraries the app uses keep their own licenses (Apache-2.0, MIT and similar), all compatible with GPL-3.0; Settings → About lists each of them with its author and license.
