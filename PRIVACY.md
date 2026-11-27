# Privacy

BooruVault is a client: it talks to the booru sites you add and to nothing else, with two exceptions listed below. It has no account system, no telemetry and no crash reporting, and it never contacts its developer.

## What leaves the phone

Nothing at all until, right after creating the vault, you choose between Tor (through Orbot) and a direct connection. The choice can be changed later in Settings. What a site handed out along one of the two (cookies, anti-bot passes, TLS sessions) is never shown along the other. Through Tor, the app's own connections never resume a TLS session either, as a later connection may leave by another exit; the browsers' engine empties its TLS session cache each time a browser closes. With a direct connection, an onion address is refused rather than looked up: the network's resolver would learn which hidden service you were after.

- **The sites you add:** your searches, the posts you open and download, and, if you set them, your account name and API key, sent the way each site's API expects. Nothing else about you or your phone travels with them: the app presents itself as Chrome on Android without model or system version, except to a site you visited in the built-in browser, which it asks under that browser's name while a cookie the visit brought back is kept. Danbooru boards are told only the app's name, or the built-in browser's while a check you passed there still holds; e926 boards are also told the app's name and version and, on the API calls signed with your account, the account's name, as e621 asks of API clients; images and files, fetched without the account, are not. The built-in browsers present themselves as Firefox on Android, which names the Android version but no phone model, and through Tor ask pages in English and tell their scripts English and the UTC time zone, as Tor Browser does; on a direct connection they use the phone's languages and time zone.
- **check.torproject.org:** when Tor is on, one request to confirm that connections really leave through Tor, before any other traffic, again whenever Orbot stops and comes back, and again when the app comes back to the front after five minutes away.

## What stays on the phone

Everything the app keeps is encrypted in the vault: downloaded files, settings, sources and their keys, the cookies you bring back from the built-in browser and the download queue. Only the network choice (Tor or direct, and Orbot's port) and, before Android 13, the app's language are kept outside it, as they are needed before the vault opens, with the browsers' engine's own files: its configuration, and a profile where it keeps the route it was last set to and the languages pages were asked in, but nothing of a visit. The vault opens with your password, optionally with your fingerprint. Browsed images are cached in memory only. Locking forgets the session. The full design is in the README, under "Security design".

## What is never collected

No usage statistics, no identifiers, no logs of what you browse. The system makes no backup of the vault to any cloud: `allowBackup` is off.

## The backup you make yourself

Settings can save the whole vault as one file, wherever you tell the system to put it. It is written without being decrypted, and holds what the vault holds, except the site cookies, the download queue and the fingerprint's copy of the key. The app sends it nowhere: where it goes after that is up to you. Outside the phone only the vault's password protects it, and whoever holds the file can try passwords on it at leisure, so a backup deserves a strong one.

## Who is responsible for your data

The developer receives no data at all: there is no server, no account, no telemetry and no crash reporting. There is therefore no data controller for this app in the sense of the GDPR, nothing you could ask the developer to hand over and nothing to delete. What you keep on the phone is yours alone.

The data you send elsewhere is governed by the privacy policy of whoever receives it: the booru sites you add, and the Tor Project for the exit check. Read theirs for what they keep.

A bug report or a question opened on GitHub is subject to GitHub's privacy policy, not this page. Do not paste API keys or vault passwords into an issue.
