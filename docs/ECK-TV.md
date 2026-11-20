# ECK TV for Samsung Tizen

## 1.7 interaction update

The large featured area is now a remote-selectable button: it opens the selected trailer, or the TMDB key form when recommendations have not been configured. Demo movies remain browser-only and never substitute for real TV recommendations. Apps use one compact row of eight tiles, with paging at the row edge; vertical navigation follows header → featured movie → apps → recommendations → movie actions. White logos have contrasting brand backgrounds.

Each genre toggle immediately switches discovery to the genre list and starts a fresh request; generation checks discard earlier responses. Each ambient opening chooses a random first scene excluding the preceding opening's first scene, persisted across app restarts. A one-scene collection necessarily repeats. Trailer playback still uses the existing YouTube adapter and requires confirmation on the real TV; mock launch success is not evidence of actual playback.

ECK TV 1.6 is a Turkish home screen for Samsung Tizen 4.0 and newer, based on Free My TV. The Android and LG home screens retain their existing behavior. Only the Samsung builder enables `CONFIG.eckHome`.

## Behavior

- Three top shortcuts: Live TV, a colored ambient preview, and app settings. Live TV requests the TV source without forcing a terrestrial channel; it preserves the TV viewer's last tuned channel. Satellite, cable and terrestrial behavior still needs device validation.
- A large selected film, an expanding film card, and two rows of seven app tiles. Additional apps are paged at the left/right edge of a row.
- Two independent recommendation choices in settings: TMDB's popular movies with `region=TR`, or discovery using selected genres (OR). Neither filters by installed streaming service. The first is a region-filtered TMDB popularity list, not measured Turkish audience viewing statistics.
- OK on a film requests a YouTube trailer. TMDB video metadata is looked up by movie ID, with Turkish trailers preferred, then English. Clips and teasers are excluded. Missing trailers and rejected launch requests fall back to a quoted film-title/year search with Turkish official-trailer keywords. YouTube can accept a request yet ignore the video URI on some firmware: the separate “YouTube’da ara” action is always available. Playback and Return behavior must be checked on the device. No autoplay video is loaded on focus.
- The selected movie, focused section and app page survive hiding, relaunching and reload. Favorites (80 maximum) and hidden movies (200 maximum) stay in local storage. Two movie-list cache slots expire after six hours; stale data remains available when a request fails.
- Ambient mode starts after two idle minutes on the home screen, with a 45-second photo interval and a two-second crossfade. Its small clock, date and weather remain visible. The first received key dismisses it without also activating a home-screen item.
- Online scene metadata is refreshed daily from the repository scene manifest and NASA Images. Only approved HTTPS image hosts are accepted; at most 12 scene records and four explicit preloaded Image objects are retained. The next scene is preloaded before switching. HTTP image caching is browser-managed; photos are not guaranteed to survive offline restarts. A gradient remains usable when no image can load. All timers pause visible work when the app is hidden.
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

On the TV, verify the two-minute timeout, 45-second crossfade, first-key-only wake, both app rows, genre selection, favorites, hiding/reset, day/night readability, trailer search, preserved position after returning, last satellite channel and a subsequent update signed by the same ECK author. Do not change picture, sound, network or account settings to make a test pass.

## Public repository boundaries

Do not commit personal/device identifiers, local addresses, credentials, API keys, certificate private keys, signing profiles, device logs, generated `config.js`, signed packages containing private configuration, or environment files. Generic builds include no user settings. Public certificate chains in a generic signed package contain no personal author fields when a neutral development subject is used; private keys are never included.

Image sources: [Unsplash](https://unsplash.com/) and [NASA Images](https://images.nasa.gov/). Movie metadata: [TMDB](https://www.themoviedb.org/). Base project: [Free My TV](https://github.com/simbasjungles/free-my-tv).
