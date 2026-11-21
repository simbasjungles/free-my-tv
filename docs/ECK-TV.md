# ECK TV for Samsung Tizen

## Current home: 1.10 cinematic showcase

The main screen now has one 1792×660 px showcase and one horizontal row of eight app tiles on the 1920×1080 stage. The lower catalog and genre bar described in the historical 1.9 section below are no longer mounted or requested at home. Existing saved genre/favorite data is retained locally. The showcase keeps general suggestions, Left/Right selection, a 30-second rotation paused on focus, and OK-to-trailer with saved return position. Its heading is above the image; a restrained bottom gradient supports the title, year, rating, up to three TMDB genres and two description lines. White focus and slight scaling apply to app tiles. Header shortcuts are Live TV, Ambient and Settings; the clock, temperature, saved city and weather icon share one line. No footer/help/version bar is rendered.

The new ECK TV vector source is `web/icons/eck-brand.svg`, exported to a 512 px web startup asset and the 117 px `tizen/icon.png`. It uses a dark navy field, white lettering and a blue/purple frame. The startup markup in `web/index.html` yields as soon as the ECK home is built; it never waits for TMDB, weather or an artificial timer. The shared legacy home hides this ECK startup element when ECK mode is disabled. Open-source licenses, TMDB attribution, image credits and the Free My TV acknowledgement remain present. The application/package ids and private signing profile are unchanged.

Targeted DOM tests cover the current single-row navigation, removed catalog/footer, ECK boot/title/icon, rotation/pause, trailers/resume, Live TV dispatch, settings and ambient behavior. Browser geometry verified the 660 px image and all eight apps within 1080 px before packaging.

## 1.9 independent film areas

The featured showcase and lower discovery catalog have independent lists, selected indices, saved movie identities and image generations. The showcase mixes TMDB genres without reading the catalog genre; Left/Right changes only its own movie. It advances every 30 seconds unless focused, hidden, in a modal or in ambient mode. Rotation updates only the showcase DOM, never the catalog selection/window/focus. Each area's OK dispatches its own trailer and preserves its position on return. A missing key opens the local key form. Demo movies remain browser-only and never substitute for real TV recommendations.

Only eight named apps appear, in two large rows of four. Vertical navigation follows header → showcase → first app row → second app row → catalog genres → catalog films. The compact horizontal genre bar offers All, Action, Science Fiction, Comedy, Horror, Thriller, Drama, Animation and an additional-genre menu. All is the initial default; only the lower single genre is persisted. Changing it clears only the catalog selection to its first film and queries real TMDB genre ids. Moving down to films preserves the active genre and later returns to the same genre focus. The former movie-action strip, Live TV shortcut, tagline and visible trailer instruction remain removed. Settings use grouped menus and large focusable rows.

Catalog genre selection starts only catalog discovery, reusing cached or in-flight results where possible; generation checks discard earlier responses. Each ambient opening chooses a random first scene excluding the preceding opening's first scene, persisted across app restarts. A one-scene collection necessarily repeats. Trailer playback still uses the existing YouTube adapter and requires confirmation on the real TV; mock launch success is not evidence of actual playback.

ECK TV 1.9 is a Turkish home screen for Samsung Tizen 4.0 and newer, based on Free My TV. The Android and LG home screens retain their existing behavior. Only the Samsung builder enables `CONFIG.eckHome`.

## Behavior

- Two pill shortcuts: colored ambient preview and settings. No Live TV shortcut.
- Two fixed app rows: YouTube, Netflix, Disney+, Prime Video / HBO Max, Internet, Apple TV, Spotify. Only detected installed ids launch; absent apps show an installation hint. See [logo sources](ECK-LOGO-SOURCES.md).
- Two independent feeds query TMDB discovery in Turkish with `region=TR`, without filtering by streaming service or theatrical/digital release type. Two popularity-sorted pages from the last 18 months are combined with an unrestricted-date popularity backfill, with a minimum 20 votes. Duplicate, adult, future-dated, hidden and imageless films are removed. The catalog applies one optional real genre id, orders newest first and caps at 30. The unfiltered showcase interleaves candidate primary genre groups for variety. Fewer results are possible for narrow genres or partial network failures. This is a metadata-based discovery list, not measured Turkish viewing statistics or a guarantee of local streaming availability. API reference: [Discover Movie](https://developer.themoviedb.org/reference/discover-movie).
- OK on a film requests a YouTube trailer. TMDB video metadata is looked up by movie ID, with Turkish trailers preferred, then English. Clips and teasers are excluded. Missing trailers and rejected launch requests fall back to a quoted film-title/year search with Turkish official-trailer keywords. YouTube can accept a request yet ignore the video URI on some firmware. Playback and Return behavior must be checked on the device. No autoplay video is loaded on focus.
- Each feed's selected movie and the focused section survive hiding, relaunching and reload. The catalog rail advances a moving six-card window using Left/Right. Previous favorites remain accessible in settings; the removed action strip no longer offers add/hide/search buttons. Existing hidden ids are retained. Two versioned persistent cache slots, one per feed, expire after six hours. Identical in-flight discovery requests coalesce; up to 12 recent discovery responses are reused in memory. Genre changes have an independent catalog generation guard. Each feed has independent busy/error state: one can fail while the other continues. Lists refresh while visible or on resume and can be refreshed separately in settings. Stale data remains available when all requests fail; successful partial results remain usable.
- Ambient mode starts after two idle minutes on the home screen, with a 45-second photo interval and a two-second crossfade. Its small clock, date and weather remain visible. The first received key dismisses it without also activating a home-screen item.
- Online scene metadata is refreshed daily from the repository scene manifest and NASA Images. Only approved HTTPS image hosts are accepted; at most 12 scene records, two ambient preloads and two independent showcase preloads are retained. At most six small catalog images are mounted; late image callbacks cannot overwrite another selection. The next ambient scene is preloaded before switching. HTTP image caching is browser-managed; photos are not guaranteed to survive offline restarts. A gradient remains usable when no image can load. All timers pause visible work when the app is hidden.
- Day/night theme follows forecast sunrise/sunset, with 06:00/18:00 fallback; manual light/dark themes are available. Crypto requests and panels are disabled in the Samsung mode.

## Preview and build

```text
python tizen/build.py --generic --preview
python -m http.server 8765 --bind 127.0.0.1 --directory out/tizen
```

Open `http://127.0.0.1:8765/` for the real empty-key flow or append `?demo=1` for clearly labeled, browser-only sample movies. Demo titles are fixed fixtures, not a live popularity list. The demo switch is ignored on a real TV.

For live recommendations, enter a personal TMDB key in the TV's own ECK settings; the generic package contains no key. Set the weather city there too. Location stays in the app's local preferences and goes to Open-Meteo for forecasts.

Build and sign with an existing private author profile:

```text
python tizen/build.py --generic --sign PROFILE
python tizen/install.py <TV_IP>
```

The generic build creates an **unsigned** `FreeMyTV-tizen.wgt` unless `--sign` is passed. Do not install an unsigned build. Signing with a separate local helper is also possible; author material must stay outside the repository. Retain the same author certificate for every subsequent ECK update. Never reuse a previous signature XML after changing the payload.

## Installation identity

ECK uses `ECKTVHome1.ECKTV` / package `ECKTVHome1`, distinct from upstream `FreeMyTVio.FreeMyTV`. An upstream release signed by another author cannot be assumed to accept a custom update. The separate identity preserves the existing installation and its settings. Storage is isolated, so an old app's TMDB key is not copied to ECK. Do not uninstall an existing app automatically to bypass a signature error.

## Verification

Run `tools/check.sh web` (Python, Node and Bash) for translations, both web packages, preference tests, the ECK core/TV tests, ES5 syntax and the legacy API/CSS checks. Android CI remains the upstream build. The new tests use mocked network and TV APIs: passing them is not evidence of real YouTube playback, satellite video or frame rate.

The additional `node tests/EckHomeTest.js` DOM suite requires jsdom 26.1. Install it externally and set `NODE_PATH` to that installation's node_modules directory, keeping dependencies out of the TV payload. The web CI job installs this pinned dependency in its temporary directory and runs the suite. It tests both directions of selection isolation, catalog-only genres, 30-second rotation and focus pause, both trailer/return paths, restart persistence, genre overflow, late images, hidden/modal/ambient pause and shared startup requests. The core suite also checks stale genre responses, genre diversity and independent network failures.

On the TV, verify the two-minute timeout, 45-second crossfade, first-key-only wake, both app rows, grouped settings and Back behavior, genre selection, 30-film navigation where available, day/night readability, trailer search, preserved position after returning and a subsequent update signed by the same ECK author. Do not change picture, sound, network or account settings to make a test pass.

## Public repository boundaries

Do not commit personal/device identifiers, local addresses, credentials, API keys, certificate private keys, signing profiles, device logs, generated `config.js`, signed packages containing private configuration, or environment files. Generic builds include no user settings. Public certificate chains in a generic signed package contain no personal author fields when a neutral development subject is used; private keys are never included.

Image sources: [Unsplash](https://unsplash.com/) and [NASA Images](https://images.nasa.gov/). Movie metadata: [TMDB](https://www.themoviedb.org/). Base project: [Free My TV](https://github.com/simbasjungles/free-my-tv).
