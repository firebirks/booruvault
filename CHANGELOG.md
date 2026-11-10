# Changelog

All notable changes to BooruVault. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

Each release also has a short text, at most 500 characters, in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: it is what F-Droid shows and what the GitHub release says.

## [Unreleased]

### Added
- A download cut short or stalled is taken up where it stopped, when the file's host allows it, instead of starting over: over a slow Tor circuit a large file no longer begins again at every stall.

### Changed
- In the network settings, where Tor stands and the buttons that act on it come right under the switch, before the explanations.
- Back from a settings page opened from Browse, by the onion, the content filter or the missing sources, returns to Browse instead of the settings' hub.

### Fixed
- A full image that fails to load in a viewer says why and offers to try again, instead of leaving its preview under a spinner for good. A check in front of the file can be passed in the built-in browser from there.
- A blacklist edited just before leaving the content filter is saved, instead of being dropped with the page.
- A long source name, a page's host or a large font no longer pushes the browser, the onion and the menu off the top bar of Browse: the name is cut short instead.

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

[0.4.0]: https://github.com/firebirks/booruvault/releases/tag/v0.4.0
[0.3.2]: https://github.com/firebirks/booruvault/releases/tag/v0.3.2
[0.3.1]: https://github.com/firebirks/booruvault/releases/tag/v0.3.1
[0.3.0]: https://github.com/firebirks/booruvault/releases/tag/v0.3.0
[0.2.1]: https://github.com/firebirks/booruvault/releases/tag/v0.2.1
[0.2.0]: https://github.com/firebirks/booruvault/releases/tag/v0.2.0
[0.1.0]: https://github.com/firebirks/booruvault/releases/tag/v0.1.0
