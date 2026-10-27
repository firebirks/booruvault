# BooruVault

An Android app for browsing booru image boards and keeping what you save in an encrypted vault.

Everything the app stores about you is encrypted: the files you download, your settings, your sources and their API keys, site cookies and the download queue. The app opens only through the vault, and it can send all its traffic through Tor via Orbot.

The interface follows the phone's language, with English as the fallback. See [Languages](#languages).

## Features

### Browsing

- **Sites:** Danbooru, Moebooru (yande.re, Konachan), Gelbooru 0.2 and its forks (Safebooru.org, Rule34.xxx, Xbooru, TBIB…) and e621/e926, through their APIs.
- **Sites without a usable API**, read from their web pages:
  - Gelbooru 0.2 sources fall back to the site's own pages when the API refuses, e.g. gelbooru.com or rule34.xxx without an API key.
  - Gelbooru 0.1 sites (booru.org).
  - Shimmie2 sites.
  - **Any web page:** its images and videos are extracted. The address may contain `{tags}` and `{page}`.
- **Search:** tag autocomplete, infinite scrolling grid, pull to refresh.
- **Viewer:** pinch zoom, swipe between posts, video playback, and a tag sheet. From the sheet you can search a tag, add it to the search, exclude it or blacklist it.
- **Content filter:** by rating (general, sensitive, questionable, explicit, unrated) and blacklist rules, applied the same way to every source.
- **Built-in browser:** used to pass anti-bot checks (Cloudflare), log in, or set site preferences such as Gelbooru's "Display all site content". The site's cookies are then kept in the vault.

### Downloads

- **What you can download:** single posts, a multi-selection, or every result of a search (up to 1000).
- **Encrypted while streaming:** files are sealed as they arrive, so plaintext never touches storage.
- **Background:** three downloads run in parallel under a foreground service. Its notification shows counts only.
- **The queue** is sealed in the vault and resumes after a restart.
- **On lock:** the queue is paused and dropped from memory. A risky option lets it keep running, at the cost of keeping its URLs, tags, sources and cookies in RAM until it drains.
- **Duplicates** are skipped. Ones that cannot be detected while the vault is locked are dropped at the next unlock.

### Vault

- **Unlocking:** password, plus optional biometric unlock.
- **Auto-lock:** after a configurable time in the background.
- **Contents:** grid with filtering (tags, `-tag`, `type:video|gif|image`, `source:name`), viewer, info sheet, delete.
- **Collections:** bookmarked tag sets shown as chips at the top of the vault. A collection holds every item whose tags match its query (e.g. “Landscapes” = `landscape -rain`), including items saved later. The query uses booru syntax: exact tags, `-tag`, `~tag` alternatives, `*` wildcards, plus `type:`, `source:` and `rating:`. Collections can be created from the vault filter, from a search in Browse, or from a tag in a post's details. Their names and queries are sealed in the vault.
- **Import** from the device, optionally deleting the originals. **Export** decrypted copies to a folder or a single file.
- **Screen protection** (no screenshots, hidden in recents), always on while locked.

### Privacy and network

- **No disk image cache:** browsed images stay in memory.
- **Locking forgets the session:** settings, cookies, the queue, search results and vault contents are dropped from memory. A garbage collection is then requested so released key material is freed.
- **Keyboard:** every text field asks the keyboard not to learn from what is typed, the built-in browser included.
- **Built-in browser hardening:**
  - autofill excluded and third-party cookies blocked;
  - reduced User-Agent, without phone model or Android version;
  - no WebView metrics;
  - Safe Browsing off unless the user turns it on;
  - its storage wiped after each session and at start-up.
- **Cookies:** only those the user brings back from the built-in browser are kept, scoped to the site's registrable domain (public suffix list), so a login on one `github.io` site is never sent to another. Cookies sites set on their own last for the session.
- **Risky options:** a separate settings page holds everything that trades privacy for convenience. All are off by default, and each one explains what it costs:
  - keep downloading while the vault is locked;
  - read pages that keep demanding an anti-bot check through a hidden browser. It runs the site's scripts and can be fingerprinted, but loads no images, blocks third-party cookies, has WebRTC off, goes through Tor when Tor is on, and is wiped after each page;
  - Google Safe Browsing in the built-in browser.
- **Tor via Orbot:** see [below](#tor).
- **No Google Play services needed:** the app has no dependency on them. Without them, the Safe Browsing option is shown as unavailable.

## Languages

- **Available:** English (default), plus Arabic (right-to-left), Chinese (Simplified and Traditional), Dutch, French, German, Hindi, Indonesian, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Thai, Turkish, Ukrainian and Vietnamese.
- **Which one is used:** the phone's language by default. On Android 13 and later you can pick a different one for this app alone, in the system's app language settings.
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

- **The app's network traffic** goes through a single HTTP client: API calls, images, video and downloads.
- **Orbot only:** that client reaches the network through Orbot's SOCKS port and nothing else. If Orbot is not running, every request fails; there is no direct fallback.
- **The port is a setting** (default 9050), never taken from Orbot's status messages. Those broadcasts carry no proof of their sender, so any app on the phone could send one naming a port it listens on. They are shown for information; whether traffic really leaves through Tor is told by the exit check below.
- **No local DNS:** host names are passed to Orbot unresolved, and any local DNS lookup is refused. A unit test drives a fake SOCKS5 server to check that names travel as domains.
- **Built-in browser:** goes through Orbot via a WebView proxy override with no direct rule, and WebRTC is removed before page scripts run. If the WebView cannot do either, the built-in browser does not open while Tor is on.
- **Orbot integration:** Orbot is started and queried with its standard intents. The settings screen shows the status Orbot reports, and a check against `check.torproject.org` reports the exit address.
- **Installing Orbot:** if it is missing, the app lets you choose among the installed stores (F-Droid, Droid-ify, Aurora, Play…), or opens Orbot's site.

- **Links outside the app** ("open in browser", a post's source) open in Tor Browser while Tor is on. Without Tor Browser, a warning explains that a normal browser would expose your IP and keep the visit. Its "open anyway" button unlocks only after a few seconds; it also offers to copy the link instead.

**Orbot's VPN mode** with BooruVault selected is an extra, system-level safety net, mainly for the WebView.

Tor hides your IP address from the sites. It does not hide you from a site you are logged in to, or whose cookies you use.

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

The full text is in [LICENSE](LICENSE). The libraries the app uses keep their own licenses (Apache-2.0, MIT and similar), all compatible with GPL-3.0.
