# Changelog

All notable changes to BooruVault. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

Each release also has a short text, at most 500 characters, in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: it is what F-Droid shows and what the GitHub release says.

## [Unreleased]

### Changed
- Each line of the blacklist is a rule written as a search, in the language of the vault's collections: `-tag` excludes, `~tag` marks alternatives, `*` stands for any letters, and `rating:` takes the initials `g`, `s`, `q`, `e` and `u` or several with commas. Only whole tags and `rating:` in full were understood, so a rule like `rating:e` or `cat -solo` was taken word for word and never hid anything. Rules written before keep hiding what they hid.
- In the vault's filter and collections, `rating:s` is sensitive, as the app's ratings are labelled, and no longer general; `safe` in full is still general.
- Turning on Unrated in the content filter first asks you to confirm that you are an adult, as questionable and explicit do: a site that gives no ratings, Shimmie or a plain web page, lets its explicit posts through with the rest.
- The API calls to an e621 or e926 board signed with an account name it in their User-Agent, as e621 asks of API clients and the README said: `(by <name> on e926.net)` after the app's name and version. Images and files, fetched without the account, still do not carry it.
- The previews in the list of downloads are kept, sealed like the rest of the vault, for as long as their rows are listed: the list opened again after a lock, or after the app has been in the background, no longer asks the sites for every one of them anew. A download done saves as its thumbnail the preview its row already has, instead of asking for it a second time. A row taken off the list takes its preview with it.

### Fixed
- An image or GIF opened from the vault is decrypted as it is decoded, instead of whole into memory first: a very large one no longer ends the app for want of memory.
- A vault header altered to ask Argon2 for more memory than the phone gives the app, though no more than the ceiling allows, makes the unlock say the vault cannot be opened, instead of ending the app.
- A download whose transfer ends without a word short of the length its host announced, as an HTTP/2 stream may, is taken for cut, and taken up where it stopped or reported, instead of being saved cut short for good.
- A download queued just as the vault locks, a page of a search being collected for one, no longer goes out behind the locked vault when the queue is not allowed to run there. Downloads allowed to run behind the lock whose last one finished just as it came are no longer kept in memory until the next unlock.
- The service that keeps downloads alive is no longer stopped by a list of downloads that had just finished while new ones were being queued, which could end the app.
- After a restart the queue comes back in the order it was queued, instead of grouped by source.
- A password change waits for every write that read the old key before it drops the old pair, the settings', cookies', queue's and index's included: one that stalled in the storage could land sealed to the dropped key, and at the next unlock the settings, the sources and their API keys started afresh. A save or a deletion that comes while a file is being re-sealed is no longer undone by the re-sealed copy. The files set aside as unreadable are re-sealed too, and deleted if no key opens them, so an old copy of the header reads nothing of them.
- A collection saved from a search in Browse holds what the search found. The search was saved as typed: a metatag such as `order:score` was taken for a tag no item has, so the collection held nothing, and `rating:s` saved from Danbooru, where it means sensitive, showed general items. The search is now put in the vault's language before it is offered for saving: ratings in full as the board means them, file types as `type:`, and metatags about what the vault does not keep dropped.
- A post from a site read through its web pages is checked against the content filter and the blacklist again once its own page is read, when it is downloaded: "download all" or a selection could save a post whose page said a rating or a tag the filter hides, since its list page said nothing of it. Such a download is now skipped, and says why.
- The downloads a lock left queued wait, at the next unlock, until the settings and the vault's list are read: they could start under the default filters, and download again a file already in the vault.
- A download that the phone's storage refuses to write, a full storage for one, fails and says so. It was taken for a network cut: the rest of the file was asked of the host again and sealed after a stream that might hold part of what was refused, so the file could be saved damaged, and the reason given was the network.
- A download or an import whose app was closed after the file was written but before the item was saved, as while a download fetches its preview, no longer leaves the file behind for good, unseen and impossible to delete from the app. Such files are deleted at the next start, and so are those of a deletion cut short.
- The vault locks on time when it was opened after the app was left, as when Unlock is tapped and the app left while the password is checked: it stayed open in the background with no timer at all, and "lock immediately" did not lock it. Until the settings are read the timeout counted is the default's, and it is now replaced by the user's once they are.
- A cookie brought back from the built-in browser, a login above all, keeps its scope and its thirty days when a site renews it. A renewal took the site's own terms: one for the whole domain sent a login bound to one host to all its sibling hosts, and one without an expiry kept it sealed in the vault for good. A cookie the site sets after the kept one ran out now lasts for the session, as the site's own do.
- The README says what happens to a download cut short by a host that cannot take it up where it stopped: a stalled transfer is read again from the first byte, by the timeout retries, and any other cut fails the download, to be retried from the list. It said every such file was read again from its first byte.
- Tor's verified exit is forgotten every time Orbot's port is found silent, as the README says: a request that found the port silent reported Tor unreachable but kept the verdict, so whatever took the port next was trusted without a check. An exit check on its way when the port was found silent no longer brings the verdict back. The port is looked at again each time the app comes back to the front, and the built-in browser, visible or hidden, closes once the verdict is withdrawn.
- The onion in Browse no longer stays red once Tor works again: while it is yellow or red, and the app is in front, it greets Orbot's port every few seconds and has the exit checked again, as the network settings already did. It used to wait for a request to be refused, so with nothing new asked for it could stay red after Orbot was back, while the downloads under way went on through Tor.

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

[0.5.0]: https://github.com/firebirks/booruvault/releases/tag/v0.5.0
[0.4.0]: https://github.com/firebirks/booruvault/releases/tag/v0.4.0
[0.3.2]: https://github.com/firebirks/booruvault/releases/tag/v0.3.2
[0.3.1]: https://github.com/firebirks/booruvault/releases/tag/v0.3.1
[0.3.0]: https://github.com/firebirks/booruvault/releases/tag/v0.3.0
[0.2.1]: https://github.com/firebirks/booruvault/releases/tag/v0.2.1
[0.2.0]: https://github.com/firebirks/booruvault/releases/tag/v0.2.0
[0.1.0]: https://github.com/firebirks/booruvault/releases/tag/v0.1.0
