# Privacy

BooruVault is a client: it talks to the booru sites you add and to nothing else, with two exceptions listed below. It has no account system, no telemetry and no crash reporting, and it never contacts its developer.

## What leaves the phone

Nothing at all until, right after creating the vault, you choose between Tor (through Orbot) and a direct connection. The choice can be changed later in Settings. What a site handed out along one of the two (cookies, anti-bot passes, TLS sessions) is never shown along the other. Through Tor, the app's own connections never resume a TLS session either, as a later connection may leave by another exit; the built-in browser's TLS sessions are kept by Chromium, which offers no way to clear them.

- **The sites you add:** your searches, the posts you open and download, and, if you set them, your account name and API key, sent the way each site's API expects. Nothing else about you or your phone travels with them: the app presents itself as Chrome on Android without model or system version. Danbooru boards are told only the app's name, or the built-in browser's while a check you passed there still holds; e926 boards are also told the app's name and version. The built-in browser presents itself as Chrome's WebView on every site.
- **check.torproject.org:** when Tor is on, one request to confirm that connections really leave through Tor, before any other traffic and again whenever Orbot stops and comes back.
- **Google, only if you turn it on:** the risky option "Google Safe Browsing in the built-in browser" has the phone's Google Play services check the addresses the built-in browser opens. Off by default, and unavailable without Play services.

## What stays on the phone

Everything the app keeps is encrypted in the vault: downloaded files, settings, sources and their keys, the cookies you bring back from the built-in browser and the download queue. Only the network choice (Tor or direct, and Orbot's port) and, before Android 13, the app's language are kept outside it, as they are needed before the vault opens. The vault opens with your password, optionally with your fingerprint. Browsed images are cached in memory only. Locking forgets the session. The full design is in the README, under "Security design".

## What is never collected

No usage statistics, no identifiers, no logs of what you browse. There is no backup of the vault to any cloud: `allowBackup` is off.

## Who is responsible for your data

The developer receives no data at all: there is no server, no account, no telemetry and no crash reporting. There is therefore no data controller for this app in the sense of the GDPR, nothing you could ask the developer to hand over and nothing to delete. What you keep on the phone is yours alone.

The data you send elsewhere is governed by the privacy policy of whoever receives it: the booru sites you add, the Tor Project for the exit check, and Google if you turn on Safe Browsing. Read theirs for what they keep.

A bug report or a question opened on GitHub is subject to GitHub's privacy policy, not this page. Do not paste API keys or vault passwords into an issue.
