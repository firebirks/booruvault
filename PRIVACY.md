# Privacy

BooruVault is a client: it talks to the booru sites you add and to nothing else, with two exceptions listed below. It has no account system, no telemetry and no crash reporting, and it never contacts its developer.

## What leaves the phone

- **The sites you add:** your searches, the posts you open and download, and, if you set them, your account name and API key, sent the way each site's API expects. Nothing else about you or your phone travels with them: the app presents itself as Chrome on Android without model or system version.
- **check.torproject.org:** when Tor is on, one request to confirm that connections really leave through Tor, before any other traffic and again whenever Orbot stops and comes back.
- **Google, only if you turn it on:** the risky option "Google Safe Browsing in the built-in browser" has the phone's Google Play services check the addresses the built-in browser opens. Off by default, and unavailable without Play services.

## What stays on the phone

Everything the app keeps is encrypted in the vault: downloaded files, settings, sources and their keys, the cookies you bring back from the built-in browser and the download queue. The vault opens with your password, optionally with your fingerprint. Browsed images are cached in memory only. Locking forgets the session. The full design is in the README, under "Security design".

## What is never collected

No usage statistics, no identifiers, no logs of what you browse. There is no backup of the vault to any cloud: `allowBackup` is off.
