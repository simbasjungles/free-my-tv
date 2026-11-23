# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen (`app/`, built by `build.sh`). LG (webOS) and Samsung (Tizen) TVs get the
same home screen as a web app: `web/`, packed by `webos/` and `tizen/`. `docs/` is the website (freemytv.io), published
to GitHub Pages by any push to `main` that changes it: whatever goes in `docs/` is public there. `docs/index.html` (English) and
`docs/pt/index.html` (Brazilian Portuguese) are the same page: change both.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Guides

Read the one that fits before you start:

- Installing it for someone on a Google TV or Android TV: [guide/install-android.md](guide/install-android.md) (also
  how to bring a TV back after an uninstall in Free My TV mode).
- On an LG TV: [guide/install-lg.md](guide/install-lg.md). On a Samsung TV: [guide/install-samsung.md](guide/install-samsung.md).
- Making it work on another brand or model: [guide/new-tv.md](guide/new-tv.md).
- Making a release: [guide/releases.md](guide/releases.md).
- What real TVs taught us, by feature and device (remote buttons per brand, projectors, tuners, Fire OS, Android
  versions): [guide/android-notes.md](guide/android-notes.md), and for LG and Samsung
  [guide/web-notes.md](guide/web-notes.md). Read the paragraph for what you change, and add what a new TV teaches there.

## Always

- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it, everywhere
  (`targets` order, `applyOriginal`, `revertHomeNow`, `verifyNow`): with the home screen off and it on, its
  RecoveryActivity takes Home, and a restart there factory-reset a Xiaomi. If it can't go off, the home screen stays on.
- The switch refuses where it bricked or can't work: the Kalley 43G200 (`R3_GTV`) and Huawei TVs (`HomeMode.BRICKS`,
  Huawei by brand or by `com.huawei.system.BuildEx.getOsBrand()`; the boot check or an update takes one already in Free
  My TV mode back to *Original*), phones (`HomeMode.phone`: the settings say the APK goes on the TV), and Fire OS
  (`HomeMode.AMAZON`: Amazon's launcher keeps Home; *Original* and the watchdog still run, and no `com.amazon.*` package
  is ever turned off, `mayTurnOff`).
- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and *Original* can't bring them back
  (`wifiOwners` in `HomeMode`). When the Wi-Fi service doesn't answer, the switch stops on Wi-Fi, and the boot check
  leaves the setup app and the stock home screen for later.
- System apps are only turned off with `pm disable-user --user 0`, through `HomeMode`, so *Original* can bring them
  back. Never uninstall them or clear their data.
- `HomeMode.DEBLOAT` lists only promo, demo, store-mode and telemetry apps. The brand's system pieces (picture, sound,
  inputs and HDMI-CEC, tuner and live TV, remote and Bluetooth pairing, setup, updates, system UI) go in
  `HomeMode.PROTECTED`: when in doubt, protect it.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog (back to the stock home screen after 3 crashes in 5
  minutes). Whatever a feature turns on through the TV's ADB, *Original* takes back.
- The person switches modes on the TV, not you from the computer. Run `adb disconnect <IP>:5555` before they switch:
  on Android 8 to 10 the TV can hand its answer to a computer that's still connected (#221).
- Never commit `config.properties`, `*.keystore` or any key.

## Keys and secrets

- The person's keys are theirs and secret: the TMDB key, the Watchmode key, the Plex token (suggest a Plex Home managed
  user's), the Trakt tokens. Don't ask for them in the chat, print, log or commit them. They paste a key into
  `config.properties` themselves, or type it on the TV (Home Settings). If they paste one in the chat anyway, put it
  only in `config.properties`.
- Keys that open the person's own servers (the Immich key, the Home Assistant token) never go in `config.properties` or
  the APK, which any app on the TV can read, and you don't ask for them in the chat. They send them in their own terminal with
  `python3 tools/send_key.py <IP> <name>` (it asks without showing it; yours can't answer that). A new feature with such
  a key adds its name to `Secrets.NAMES` and to that tool.
- Requests to the person's own servers and accounts (Plex, Immich, Home Assistant, Trakt) don't follow redirects.
- Passphrases, certificate passwords and a Samsung TV's DUID are typed by the person in their own terminal, never in
  the chat.

## Network and privacy

- The app talks only to TMDB (at `api.tmdb.org`: some ISPs block `api.themoviedb.org`), Open-Meteo, Binance, Watchmode,
  the person's Plex, Immich and Home Assistant servers, Trakt (once they sign in) and GitHub's releases. A new source
  doesn't add network calls without the owner's OK.
- An address another app wrote (Watch Next pictures) is fetched only through `NetPicture`.
- On Android, plain HTTP only to the person's own network: a server setting goes through `lan_server` in `build.sh`.
  (The web app's plain-HTTP Open-Meteo on Tizen 2.x is deliberate: see web-notes.md.)
- Features that read more than the home screen needs are off until the person turns them on: Now Playing (the
  notification listener, which turning it off, *Original* and the watchdog's revert take back), Continue Watching
  (`READ_TV_LISTINGS`, which *Original* revokes), Recent Apps, Status icons, Home Assistant. Home Assistant never
  offers locks or alarms, and covers only of `HomeAssistant.SAFE_COVERS`.

## Code

- Java 8 language level, minSdk 26, targetSdk 33, no Gradle and no libraries: only the Android SDK. After any change,
  `./build.sh` must end with `Built FreeMyTV.apk`.
- Before you push, run `tools/check.sh` (what every pull request runs). CI uses JDK 17, build-tools 34.0.0 and
  android-34: a newer SDK here can let through a call that fails there. To check for newer APIs, compile against
  `platforms/android-26/android.jar`: only AGSL (`RuntimeShader`, guarded), `BitmapShader.setFilterMode` (guarded),
  `Recording.Node`, `Ui.Weighted` and `Activity.setTranslucent` (guarded) should come up.
- The home screen (`HomeView`) keeps 60 fps: no allocations in `onDraw`, no work on the UI thread. Old and low-RAM TVs take
  the lighter drawing paths (`Ui.LEGACY`), and on TVs whose UI is 1280x720 (TCL's 1 GB models) every pixel and MB counts.
- `:home`'s watchdog counts any exception that escapes there as a home screen crash: background work in `:home`
  (`KeyPage`, `RecentApps`) catches everything.
- The remote's key service runs alone in `:keys`, and the system waits for it on every key: `onKeyEvent` does no heavy
  work and reads `remote-keys.txt`, never SharedPreferences. `adb shell input keyevent` doesn't test it (injected keys
  skip the key filter): test with the remote.
- The screensaver runs as `ScreensaverAlone` on Android 11 to 12L and as `Screensaver` elsewhere (`res/values*`): keep
  both in step.
- A settings row this TV can't use stays, dimmed as *Unavailable* (`SettingsActivity.need`, `Capabilities.missing`).
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that);
  what the person changes on the TV (`Prefs`) wins.
- On-screen text goes through `Ui.tr`, `Ui.trf` (patterns, never concatenated words) and `Ui.plural`, with dates and
  numbers in `Ui.locale()`; on the web, `tr`, `trf`, `plural` and `formatDate`. After changing text, run
  `python3 tools/translate.py` and translate what each catalogue lacks, keeping placeholders as they are (`--check`
  validates). Portuguese, Spanish, German, Russian, Thai and Turkish are hand translations: edit them by hand, never
  replace them with machine ones. The Android app isn't mirrored for right-to-left yet, so Arabic is only offered on
  LG and Samsung.
- Comments in English, short, only where the why isn't obvious.
- `web/` runs on Chromium from 2015 to 2017 and Tizen 2.4's WebKit: plain ES5, CSS without variables, grid or
  backdrop-filter, and only transform and opacity animate. `tools/check.sh` checks the ES5 and CSS, not the rest: Tizen
  2.4 has no Intl and needs a width and height on SVGs (more in web-notes.md). `web/home.js` follows `HomeView`'s
  layout and behavior, so a design change on Android belongs there too.
- On Samsung, `background-support` and the `<tizen:app-control>` with `reload` go in the manifest only in a build with
  `samsung_home_button=true` (`manifest_for` in `tizen/build.py`), never in the release, until such a build is tested on
  older TVs. New pieces of the app to work around a TV (a service in the package, a Partner certificate) are the
  owner's call.
