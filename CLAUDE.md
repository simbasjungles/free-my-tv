# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
LG TVs (webOS) and Samsung TVs (Tizen) get the same home screen as a web app: `web/`, packed by `webos/` and `tizen/`
(see below).
The website (freemytv.io) is in `docs/`: static HTML, CSS and JS, no build step. A push to `main` that changes it
publishes it to GitHub Pages (`.github/workflows/pages.yml`). `docs/index.html` (English) and `docs/pt/index.html`
(Brazilian Portuguese) are the same page in two languages, so change both; visitors from Brazil land on the
Portuguese one by themselves, and the PT/EN switch in the menu is remembered.
Before you push, run `tools/check.sh`: the Google TV build, `translate.py --check`, the LG and Samsung packages, ES5 in
`web/` (syntax and the newer calls old TVs lack) and the CSS they can't use. Every pull request runs it too
(`.github/workflows/check.yml`), with JDK 17, build-tools 34.0.0 and android-34: a newer SDK here can let through a
call that fails there.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Always

- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it, everywhere
  (`targets` order, `applyOriginal`, `revertHomeNow`, `verifyNow`): with the home screen off and it on, its
  RecoveryActivity takes Home, and a restart there factory-reset a Xiaomi. If it can't go off, the home screen stays on.
  The Kalley 43G200 (`R3_GTV`) turns it back on at every start and boot-looped without its home screen, and a Huawei
  TV (HarmonyOS) stopped at its logo at the first restart after the switch (back only with a factory reset): the
  switch refuses on both (`HomeMode.BRICKS`, Huawei by brand or by `com.huawei.system.BuildEx.getOsBrand()`), and
  the boot check or an update takes one already in Free My TV mode back to *Original*. On a phone (`HomeMode.phone`:
  telephony, no leanback, not in TV mode) the settings say the APK goes on the TV, and the switch refuses. On Amazon Fire TV (Fire OS: `HomeMode.AMAZON`), Amazon's launcher keeps the Home button (`priority=950`
  on `CATEGORY_HOME`), so the switch refuses Free My TV mode while *Original* and the watchdog still run, and no
  `com.amazon.*` package is ever turned off (`mayTurnOff`).
- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`). When the Wi-Fi service doesn't answer, the switch stops on Wi-Fi, and the boot
  check leaves the setup app and the stock home screen for later.
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`. With the release's APK (no
   `config.properties`), they add it on the TV themselves: Home Settings > TMDB key (a QR code for their phone, or typed).
   Without a key everything works except the picks and their photos on the screensaver (photos they send themselves
   still show).
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`.
   And for `plex_url` and `plex_token` (Google TV only; their Plex server's newest titles join the picks): the token opens
   their Plex account, so suggest a Plex Home managed user's (the README says how), and it goes only in `config.properties`.
   The optional Immich key (with `immich_server`, their own Immich server: My Photos come from it) is even more theirs:
   it opens their photos, so it never goes in `config.properties` or the APK. They send it in their own terminal with
   `python3 tools/send_key.py <IP> immich_api_key` (Android 9+), which asks for it without showing it (yours can't
   answer that).
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`. If it hangs, Google Play Protect is asking on the TV (Android 14:
   *Risky app blocked*, "built for an older version of Android", since targetSdk is 33): they choose *More details* →
   *Install anyway* (updates don't ask again). Always through adb: an APK opened on the TV (a file manager, a browser)
   can get *App blocked to protect your device*, with no way past it, where Play Protect blocks sideloaded apps with an
   accessibility service or a notification listener (#378). On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. An uninstall takes the permission back (moving from their own build to the
   release means uninstalling, #375), so run it after every new install. When a switch's job hasn't started 10 s after
   the request, the settings screen says so instead of showing *Original* again, and on TCL it shows this command
   (`ModeJob.started`, #364). Some TCL firmware has neither (a 2024 MediaTek Google TV, Android 14, has no
   `TclAppBoot`): both names answer `Unknown operation string`, and nothing needs allowing.
   Some TCL firmware (a "Smart TV Pro", Realtek RTD2880, Android 11, build `RP1A200622.001`; the same TV as #264) also
   has a second, separate background killer, `TGuard` (`com.tcl.guard`), which kills the app's processes about a second
   after any other app comes to the front. The switch's benchmark opens the original home screen a few times (that's
   the launcher flashing by during "Measuring…"), so TGuard can kill `ModeJob` there (log: `TGuardMemoryManagerKillHandler
   ... tguard-kill`, then `Scheduling restart of crashed service io.freemytv/.ModeJob`). The JobScheduler runs the job
   again by itself within a minute or two, and a benchmark that fails only logs now (it used to read as "Couldn't turn on
   Free My TV: Read timed out", #277). Tell them: if the settings screen closes on the stock launcher, wait a minute or two, then press
   Home; Free My TV opening confirms the switch. No `adb`/`appops` setting turns TGuard off (its content providers aren't
   exported to the shell user).
   Chromecast with Google TV (Android 14) refuses port 5555 even with USB debugging on: have them turn on *Wireless
   debugging*, pair with `adb pair <address:port> <code>` (the code is on the TV), then run `adb tcpip 5555` over that
   connection. It closes again at every restart (Wireless debugging turns itself off; `persist.adb.tcp.port` is
   refused), and until `adb tcpip 5555` runs again *Original*, the watchdog and the boot check can't reach ADB. Tell
   them before the switch (#195). Google TV's voice results for films and shows come from its home app (launcherx),
   which Free My TV mode turns off; ADB can't turn off only its home activities, so tell them before they switch.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Free My TV* → *Remote Buttons* picks what each opens); *Original* turns it off. On TCL's Android 11 there's no
   service (the TV moved the volume twice with it), so there the buttons work only on Free My TV's home screen. On
   Xiaomi TVs whose remote has no Home button, the same service makes its Mi button open Free My TV instead of PatchWall.
8. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   Before they switch, run `adb disconnect <IP>:5555` (connect again for step 9): on Android 8 to 10 the TV can hand
   its answer to a computer that's still connected (#221).
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Installing it on an LG TV (webOS)

On LG it's an app, installed through LG's Developer Mode, and it changes nothing else on the TV: LG's home screen
stays, and Free My TV opens with a long press of a number key.

1. **Ask** for the TV's IP address and their city, if they didn't say. Tools: Python 3 and OpenSSH (`ssh`,
   `ssh-keygen`), already on macOS, Linux and Windows 10+.
2. **On the TV** (they do it): LG Content Store → *Developer Mode* → sign in with a free LG developer account
   (webostv.developer.lge.com) → *Dev Mode Status* on (the TV restarts) → *Key Server* on. It shows the IP and a
   passphrase.
3. **Settings:** the same `config.properties` as on Android (the first `python3 webos/build.py` creates it). The
   TMDB key rules above apply.
4. **Build:** `python3 webos/build.py` must end with `Built FreeMyTV-webos.ipk`.
5. **Install:** the first time needs the TV's passphrase, typed by them: ask them to run
   `python3 webos/install.py <IP>` in their own terminal (yours can't answer its prompt, and the passphrase doesn't go
   in the chat). It keeps the unlocked key in `~/.ssh/freemytv_webos_<IP>` (dots as underscores), so later updates
   run from your terminal with the same command.
6. **Tell them:** while Free My TV is open, holding a number key pins it to that key (on a remote without number keys,
   it's in LG's app list on the home screen). Developer Mode lasts about 40 days; *EXTEND* in that app renews it (if
   it runs out, the TV removes developer apps, and installing again brings it back). `--uninstall` removes Free My TV.
7. **Empty shelf on webOS 3** (user agent Chrome 38): the app can't list the TV's apps there (`listLaunchPoints`
   denied, no `getAppLoadStatus`, and the remote control API answers `invalid origin` to file:// pages). Run
   `python3 webos/apps.py <IP>`: the TV asks them once to allow it, then build and install again. It writes
   `webos-apps.local.json` and `webos-icons.local/` (git-ignored), which only the LG build packs, never `--generic`.
   Again after they install or remove apps. Where the TV resets plain connections on port 3000 (a 2024 set, #355), it
   uses the same API over TLS on 3001, and fetches the icons over https (#357).

## Installing it on a Samsung TV (Tizen)

On Samsung it's an app too (Tizen 2.3+: 2015 and newer, one 2015 TV reported, #317; TizenBrew needs 2017 and newer), installed through Samsung's
Developer Mode; Samsung's home screen stays. Two ways:

- **The release, no tools:** TizenBrew Installer (github.com/reisxd/TizenBrewInstaller) installs the latest GitHub
  release when they type `simbasjungles/free-my-tv`, and on 2023+ TVs signs it with their Samsung account. That build
  has no TMDB key built in (they type theirs in *Home Settings* → *TMDB key*) and takes the city from the TV's time
  zone. Walk them through the README's steps.
- **Their own build** (their city, their TMDB key), below.

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **On the TV** (they do it): *Apps* → type `1 2 3 4 5` on the remote → *Developer mode* on, *Host PC IP* = this
   computer's IP → restart the TV.
3. **Tools:** Samsung's `tizen` command to sign (Tizen Studio's CLI, or Samsung's Claude Code plugin
   `tizen-sdk-skills` from github.com/Samsung/tizen-agent-skills, which installs it and makes Samsung certificates),
   and `python3 tizen/install.py` to install, which speaks SDB itself. `tizen` runs on Java; Samsung's `sdb`, its new
   `tz` and Tizen Studio's installer (with its own JDK) are Intel binaries on macOS. On Apple Silicon without Rosetta,
   take the CLI from Samsung's package repository (download.tizen.org/sdk/tizenstudio/official, `pkg_list_macos-64`;
   check each zip's SHA256 there): unzip the `data/` of `new-common-cli`, `certificate-generator`, `sdk-utils` and
   `sdk-utils-core` into `~/tizen-studio`, then write `~/tizen-studio/sdk.info` with three lines,
   `TIZEN_SDK_INSTALLED_PATH=<that folder>`, `TIZEN_SDK_DATA_PATH=<home>/tizen-studio-data` and `JDK_PATH=<a native
   JDK 17's .jdk folder>` (Homebrew: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk`). That `tizen` signs natively.
4. **Certificate, once:** 2023+ TVs (Tizen 7+) need a Samsung certificate, made with their Samsung account for this TV
   (its DUID): the plugin's Samsung profile opens the browser for them to sign in, or Tizen Studio's Certificate
   Manager does it. 2015-2022 TVs take a Tizen certificate (`tizen certificate`, then `tizen security-profiles add`
   and `tizen cli-config "profiles.path=<home>/tizen-studio-data/profile/profiles.xml"`; on macOS the passwords go to
   the login Keychain). Certificate passwords are theirs: they type them in their own terminal, never in the chat.
5. **Settings:** the same `config.properties`; the TMDB key rules above apply.
6. **Build and sign:** `python3 tizen/build.py --sign <profile>` must end with `Built FreeMyTV-tizen.wgt (...,
   signed with "<profile>")`.
7. **Install:** `python3 tizen/install.py <IP>`: it pushes the app over SDB (port 26101), installs it with the TV's
   `0 vd_appinstall` and opens it with `0 was_execute` (tested on 2016 Tizen 2.4, 2017 Tizen 3.0, 2019 Tizen 5.0 and
   2024 Tizen 9.0 TVs, and reported on a 2015 Tizen 2.3 one; Tizen 2.x has no `sdk_toolpath`, so it pushes to
   `/opt/usr/apps/tmp`; on 2015 firmware `developerMode` on port 8001 can read 0 while SDB works, and SDB resets the
   connection when the TV's Host PC IP isn't this computer's current one); it only reports success
   when the TV says `install completed` (`cmd_ret:0` comes after failures too). `--uninstall` removes
   it; some TVs' sdbd doesn't answer that, and then it's removed on the TV (Apps, Settings, Free My TV, Delete).
   A bare `install failed[118]` (Tizen 2.4 gives no reason) can be a copy left by an earlier failed install, signed
   with another certificate and not shown on the TV: `--uninstall`, then install again (a 2016 KU6640, #349). Still
   118 after that, for a package id the TV has never seen and from Samsung's own `tizen install` with an install
   permit: the model refuses developer installs (a 2016 Full HD K5670, `16_HAWKM_FHD`, #349), so suggest a Google TV
   streamer on HDMI.
   With Samsung's tools instead: `sdb connect <IP>`, `tizen install -n FreeMyTV-tizen.wgt -t <serial from sdb
   devices>` and `tizen run -p FreeMyTVio.FreeMyTV -t <serial>`. Updates need the same certificate: the TV refuses
   one signed by another (uninstall first). A retail TV's sdbd only takes files (`filesync_support:push`): nothing
   can be pulled from it. To look inside the running app, `0 debug FreeMyTVio.FreeMyTV 300` over SDB opens it with
   a Web Inspector port (Runtime.evaluate works on Tizen 3; screenshots don't; Tizen 2.4 doesn't answer it, nor
   `0 vd_applist`). Tizen 2.3 (a 2015 UE48JU6075) answers it on port 7011, one WebSocket connection per launch
   (`ws://<IP>:7011/devtools/page/1`; `0 kill FreeMyTVio` first to launch it again), and `0 applist` lists its web
   apps (not the native ones, below).
8. **Tell them:** *Autorun Last App* in the TV's settings (under Start Screen Options; where it is changes by year)
   makes the TV turn on into Free My TV on a 2017 set; 2019 and 2020 sets don't reopen Developer Mode apps with it
   (#356), so there they move it to the first place in the Home bar. A Tizen Web Service declared `on-boot` that
   reopens it when the TV comes on worked on a 2019 RU7099 and a 2020 Q80T (a fork's opt-in build, #356), but only
   signed with a Partner-level distributor certificate bound to that TV's DUID, which the person requests from Samsung
   with their own account (Samsung decides who gets that level): the release and TizenBrew's builds can't carry it,
   a build with another author certificate means uninstalling first (Home Settings and the TMDB key go with it), and
   certificates, their passwords and the DUID never go in the chat.

## Releases

Every release carries `FreeMyTV.apk` for Google TV and Android TV, built with `./build.sh --generic` (no
config.properties: no key, and `Place` takes the city, the currency and the region from the TV's time zone through
`app/assets/zones.json`) and signed with the project's release key, which lives only on the owner's Mac
(`~/.freemytv/release.keystore`, its password in the login Keychain as `freemytv-release-apk`):
`FREEMYTV_KEYSTORE=~/.freemytv/release.keystore FREEMYTV_KEYSTORE_PASS="$(security find-generic-password -s
freemytv-release-apk -w)" ./build.sh --generic`. Sign every release with that key: Android refuses an update signed
by another, and people who built their own must uninstall it before installing the release.

TizenBrew Installer takes the first `.wgt` of the latest GitHub release, so every release carries
`FreeMyTV-tizen.wgt`: built with `--generic` (no config.properties: no key, the city from the time zone) and signed
with a Tizen certificate (`--sign`), which 2017-2022 TVs install as it is; on 2023+ TVs the installer signs it again
with the person's Samsung certificate. Sign every release with the same author certificate: older TVs refuse an
update signed by another. `python3 webos/build.py --generic` makes the LG `.ipk` that goes along.
The release's `FreeMyTV.apk` updates itself (`Updates`: a weekly job, and Home Settings > Updates): it reads
`releases/latest`, so keep the asset named `FreeMyTV.apk`, sign it with the project's key, and raise `versionName`
(compared number by number with the tag, `v1.3.0` > `1.2`) and `versionCode` (Android refuses a lower one). Before
uploading, `apksigner verify --print-certs FreeMyTV.apk` must show the release certificate
(`b85d4ff69cb42bfb1837ee0279506363954470b16402a4165f2f095886d19204`, `Updates.RELEASE_CERT`): TVs refuse any other. Then
`python3 tools/homebrew_repo.py <the uploaded FreeMyTV-webos.ipk>` writes `docs/webos/apps.json`, the webOS Homebrew
Channel repository (https://freemytv.io/webos/apps.json, with that file's SHA-256), and a copy of the `.ipk` beside it:
commit both with the release. The Channel installs from freemytv.io, not from the GitHub release, because GitHub's
download redirect carries more headers than the TV's Node accepts ("Parse Error: Header overflow").

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it. `mayTurnOff` also refuses
  Android's own pieces (`CORE`: with the permission controller or package installer off, a TV may not start again) and
  factory menus (`FACTORY`), and `neededByTv` keeps on, on the TV itself, anything that asks for `TV_INPUT_HARDWARE`
  (the layer under HDMI and the tuner: off, inputs show "No signal") or receives the remote's Input key
  (`fusion.android.tv.demo` does on several MediaTek boards).
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

LG and Samsung: the web app above. Other TVs that don't run Google TV or Android TV: the way is a Google TV streamer on
HDMI (Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.
The onn 4K Streaming Box (Amlogic S905Y4, Google TV 14 tested) ships no promo apps of its own, so `DEBLOAT` has no onn
entries; its system pieces are in `PROTECTED`. USB debugging opens ADB on 5555 there, as the app needs.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`. On an HY300 with JmLauncher, also
`adb shell setprop persist.sys.home.selectpkg ''`. On TCL, an older version may also have turned off
`com.tcl.channelplus` and `com.tcl.waterfall.overseas` (Live TV's channel source and TCL Channel): `pm enable` them too.

## The code

- Java 8 language level, minSdk 26 (Android 8), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 and older have no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 8 to 11,
  and on Android 12 and 12L under 3 GB of RAM (`Ui.LEGACY`), the home screen skips the GPU layers, draws blurred
  shadows from cached bitmaps (`Shadows`) and rounds images with a shader instead of clipPath: their GPUs (a Mi Box
  3's Mali-450) choked on those, and a 2 GB TCL on Android 12 went into swap with the layers (#361).
  Android 11 to 12L (API 30–32) run the screensaver as `ScreensaverAlone`, in the main process: AOSP starts their
  DreamActivity in the app's default process (13 moved it to the dream's own), and it only works with the dream in that
  process, which can't also hold a home activity (a Sony BRAVIA VH22 on Android 12 crashed with it in `:home`, #94).
  `res/values-v30` and `values-v31` pick it; Android 8 to 10 and 13 and newer (`values`, `values-v33`) keep
  `Screensaver` in `:home`. Keep both in step. `ScreensaverAlone` reads `Prefs` from another process than the settings screen's, so it calls `Prefs.reload()`
  when it starts (which also re-applies a new language; `HomeData` fetches the forecast again when the city changed).
  To check for newer APIs, compile against `platforms/android-26/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded), `Recording.Node`, `Ui.Weighted` (the Android 9 font weight)
  and `Activity.setTranslucent` (guarded: TCL's Android 11 marks the home activity translucent, and the app just left
  stayed on screen over it) should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 8 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- TMDB's API is reached at `api.tmdb.org`, TMDB's own mirror of `api.themoviedb.org` on the images' domain
  (`image.tmdb.org`): some ISPs block the other name (Jio in India, #327).
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. The sleep timer's overlay (`SleepOverlay`) runs alone in `:sleep`, so its foreground service doesn't keep
  the home screen in memory under the app in front; the remote's key service lives in `:keys` (below).
  `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
  `Updates` (the release APK updating itself) runs there too: the week's check and Settings' OK are `ModeJob`s, one at
  a time, and they write `update.json`, which Settings only reads. The download must be this repository's release
  asset (its size and SHA-256 as GitHub lists them), newer than the app (versionName and versionCode) and signed with
  the release key (`Updates.RELEASE_CERT`); Android's installer always asks. A build made on a computer (not
  `--generic`, or another key) checks only when asked and never downloads.
- The TMDB key given on the TV (`Prefs.tmdbKey`) wins over `Config.TMDB_KEY`; read it through `Recs.key()`. Its screen
  serves a one-time page (`KeyPage`: the TV's private IPv4 on Wi-Fi or Ethernet, never a VPN's or a public one, a random
  port and token, one POST, 5 minutes, and only while that screen shows) behind a QR code (`Qr`, byte mode, level M, after
  Project Nayuki's MIT encoder, whose notice stays in the file), and checks a key with TMDB (`/configuration`) before
  saving it. Never log it. Nothing may escape KeyPage's threads: `:home`'s watchdog counts it as a home screen crash.
  On LG and Samsung it's typed in Home Settings (no QR: a web app can't serve a page): `Prefs.tmdbKey()` (localStorage)
  wins over `CONFIG.tmdbKey`, and `Data.keyChanged()` fetches the picks again.
- Continue Watching gets `READ_TV_LISTINGS` with `pm grant` through the TV's own ADB only when picked in Home Settings;
  *Original* revokes it with `pm revoke`. Watch Next pictures load only from the TV (`content://`, `android.resource://`),
  never from the network. The app talks only to TMDB, Open-Meteo, Binance, Watchmode (with a `watchmode_key`), your Plex
  server (with a `plex_url`), your Immich server (with `immich_server` and its key), Trakt (with a `trakt_client_id`,
  once the person signs in on the TV), YouTube's public RSS feeds and thumbnails (`www.youtube.com`, `i.ytimg.com`: only
  with the YouTube tab turned on, only from `:home`, no redirects) and GitHub's releases (Updates): new sources (such as
  Stremio) must not add network calls without the owner's OK.
- Trakt (`Trakt`, Google TV only) needs the person's own API app: `trakt_client_id` (the Client ID only: device code
  with PKCE, so no secret is built in). Until they sign in on the Picks screen, nothing talks to `api.trakt.tv`. The
  tokens stay in `:home`'s private `trakt` preferences, are never logged and never follow a redirect, and only `:home`
  uses them (`Trakt.available()`): on Android 11 to 12L the screensaver's main process has no Trakt, and the saved list
  keeps the account it was made with (`traktStamp`, 0 there), so `:home` fetches again when it finds one made without.
- On-screen text: `Ui.tr("English")`, translated from `app/assets/i18n/<lang>.json` (keyed by the English; one
  HashMap lookup, English when a language lacks it). Text built at runtime is a pattern: `Ui.trf("{n} min ago", "n", 12)`,
  never concatenated words. A key `"context|Text"` is the same English where a language may need another word
  (`"unit|Automatic"`, `"format|h:mm"` holds a SimpleDateFormat pattern). A count with a word is
  `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: each catalogue gives one form per CLDR plural category of
  its language (`one`/`few`/`many`/`other` in Russian, Ukrainian, Polish; only `other` in Japanese, Chinese…), and
  ICU's rules pick one. Portuguese, Spanish, German, Russian, Thai and Turkish are hand translations: the reference, edited by hand,
  never replaced with machine ones (`--fill` leaves them alone unless `--lang` names one). Dates and numbers use
  `Ui.locale()`. Text
  drawn every frame is cached and made again when `Ui.generation` changes. After changing text, run
  `python3 tools/translate.py`: it rebuilds `en.json` from the code and lists what each language lacks; translate those
  in every catalogue (or `--fill` with `ANTHROPIC_API_KEY`, which sends only the strings), and keep placeholders as they
  are. Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
  No right-to-left languages yet (the layout isn't mirrored); CJK and Devanagari use the system font.
  Comments in English, short, only where the why isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  Built with `--generic`, the place values are empty and `Place` fills them from the TV's time zone
  (`app/assets/zones.json`, written by `tools/make_zones.py` with `web/zones.js`).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons, Sleep Timer) wins over those first values.
- `ImmichPhotos` (with `immich_server` and the key sent to that TV; without the key My Photos is unchanged): every 6
  hours, on the home screen's resume, a random choice from the server (`POST /api/search/random`, or one album,
  `Prefs.photoSource`) is downloaded one photo at a time into `Photos.privateInbox` and imported right away, so at most
  one original is on disk (Immich's preview instead on low-RAM and 720p TVs). Nothing goes before something new came:
  at `Photos.MAX` the oldest of Immich's make room (`-immich.jpg` names), a new source's photos replace the previous
  one's as they arrive, and `tools/photos.py`'s are never touched. Redirects are never followed (`x-api-key` would go
  along to another host).
- Plain HTTP only to the user's own network: `build.sh` reads every server setting with `lan_server <key>` (now
  `immich_server`), which refuses an `http://` address that isn't private (10/8, 172.16/12, 192.168/16, `.local`,
  `.lan`, `.home.arpa`, a one-word name) and lists its host in the generated `network_security_config.xml`, the one
  place cleartext is allowed. A new feature that talks to a server on the user's network goes through it too.
- Keys that open the user's own servers (the Immich key) stay out of the APK, which any app on the TV can read:
  `tools/send_key.py` writes one over adb to `Secrets` (`content://io.freemytv.secrets/<name>`, only adb's shell may,
  only the names in `Secrets.NAMES`) into the app's private files, and `Secrets.get` reads it. `content query`
  answers present and when, never the value, so the tool can tell it arrived; adb's `content write` needs
  Android 9. A new feature with such a
  key adds its name there and to the tool.
- Settings rows that need something this TV lacks stay, dimmed as *Unavailable*: `SettingsActivity.need` names what a
  row needs, `Capabilities.missing` says why it's missing (once per screen), and OK on the row shows it in a toast.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar), Sony, Xiaomi and Hisense TVs, the JVC G24, Formovie projectors and the Google TV Streamer, the service is on only in Free My TV mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  The service is also off while every button is *Nothing*, and always on TCL's Android 11 (`RemoteKeys.FILTER_SAFE`):
  there any key filter makes the TV act on a volume key twice (one press moves 2 steps and a held key gets stuck, #325;
  turning off only our service fixed it), so the buttons work only where they reach our home screen's window.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons, `KEYCODE_BUTTON_4` for Netflix and `KEYCODE_BUTTON_8` for the Google Play button of
  older remotes (BRAVIA 4K UR3, Android 10 tested), global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. Sony ships its own Apple TV build, `com.apple.atve.sony.appletv`.
  Xiaomi projectors made by Formovie (Fengmi; L1, MediaTek, Google TV 14 tested) send the same `KEYCODE_STEM_1` for
  Netflix (`RemoteKeys.STEM_NETFLIX`); their YouTube button goes to MediaTek's `GlobalKeyReceiver` and stays with the system.
  Some Xiaomi TVs (MiTV-MSSP3, MediaTek, Android TV 9 tested) have no Home button on the remote (`RemoteKeys.XIAOMI`
  matches only the TVs, `MiTV-M…` models, not the Mi Box or Mi TV Stick): the Mi button sends
  `KEYCODE_BUTTON_4`, a global key that `com.xiaomi.android.tvsetup.partnercustomizer`'s `GlobalKeyReceiver` turns into
  PatchWall (`com.mitv.tvhome.atv`), never the home activity, so without the service there is no way back to Free My
  TV, and PatchWall's All Apps opens the disabled `tvlauncher`. `Prefs.KEY_MI` opens our home screen by default
  ("Xiaomi default", empty, leaves it to PatchWall). Leave PatchWall on: it also shows the TV's input picker.
  Xiaomi TVs with a Home button (MiTV-MOOR0, MediaTek, Android TV 11 tested) send `KEYCODE_BUTTON_3` for YouTube, a
  global key for the stock home screen (`launcherx`) that Free My TV turns off, and `KEYCODE_BUTTON_5` for PatchWall,
  a global key for the same `partnercustomizer` receiver: both are ours (`Prefs.KEY_XIAOMI_PATCHWALL` is left to the
  TV until an app is picked). Netflix (`BUTTON_6`) stays with the system. Their remote sends one press as a burst of 5
  to 8: `RemoteKeys` opens the app once (Xiaomi only).
  Xiaomi TVs sold in Russia (MiTV-MOOQ1, MediaTek, Android TV 11 tested) send `KEYCODE_BUTTON_13` for Okko, `_1` for
  VK, `_16` for Rutube, `_11` for KION, `_10` for Wink and `_12` for IVI, global keys for Xiaomi's hot-key receiver
  (`com.xiaomi.android.tvsetup.partnercustomizer`, its log: "dealWithHotkey keycode:199"). "Xiaomi default" (empty)
  leaves the key to the TV.
  The MiTV-AXSO2 (Amlogic `txlx`, Android TV 11, India; its home screen is `tvlauncher`, not `launcherx`) has a Home
  button, and `dumpsys window policy` hands `BUTTON_2` to `_7` and `_15` to that same receiver, which stays on, so its
  app buttons keep working without the service. Only its PatchWall button is listed (`RemoteKeys.XIAOMI_PATCHWALL_ONLY`,
  by model name): Linux key 400 (`KEY_YELLOW`, `Vendor_2717_Product_32b9.kl`) → `BUTTON_5`, left to the TV until an
  app is picked. Its other buttons' codes weren't matched to the remote, so none of them is ours.
  Hisense (Q6N, MediaTek, Android TV 12 tested) sends `295` for Prime Video, `KEYCODE_BUTTON_3` for YouTube,
  `KEYCODE_ALL_APPS` for its All Apps (3×3) button, `KEYCODE_NOTIFICATION` for the gear and `KEYCODE_PROFILE_SWITCH`
  for the profile button, all global keys for Google TV's home app (`launcherx`), which Free My TV mode turns off, and
  `607` for Disney+: without the service they do nothing. Netflix (`KEYCODE_STEM_1`) stays with the system. The gear
  opens TV Settings and All Apps opens our own sheet (`Prefs.ALL_APPS`, `HomeActivity.EXTRA_ALL_APPS`); the profile
  button has nothing to open without `launcherx`. Hisense keeps only error logs: `adb shell setprop log.tag.FreeMyTV I`
  before reading `adb logcat -s FreeMyTV`.
  The Google TV Streamer (`RemoteKeys.GOOGLE_STREAMER`: model `Google TV Streamer`, device `kirkwood`, Android 14
  tested) maps YouTube to `BUTTON_3`, Netflix to `BUTTON_4` and its custom star button to `MACRO_1` (313, API 34), from
  `Vendor_18d1_Product_9451.kl`. YouTube and the star go to launcherx; Netflix goes to chromecastservice. The model
  check excludes Chromecasts and other Google devices. YouTube opens YouTube initially; Netflix and the star stay
  with Google until an app is picked. An empty choice (*Google default*) passes the key through, so YouTube and the
  star may do nothing with launcherx off. All three can also open Free My TV's All Apps; there is no separate
  long-press mapping. Home, Back, voice, power and volume stay with the system.
  The JVC G24 (Skyworth, Android 11 tested; `RemoteKeys.JVC_SKYWORTH`, by model name and Android version) sends its
  Inputs button as `KEYCODE_BUTTON_16` with scan code 383 (Bluetooth) or 79 (IR), or as `BUTTON_14` with 240, and its
  gear as `KEYCODE_NOTIFICATION` (`SkyworthRemote`; `BUTTON_16` with other scan codes is its app buttons, which stay
  with the system). In Free My TV mode the Inputs button's vendor receiver crashes on Google TV's disabled provider and
  the gear asks for Google TV's disabled panel, so Inputs opens `com.smartdevice.dailyshortcut` directly
  (`Prefs.TV_SHORTCUTS`) and the gear our own small Quick Settings panel (`QuickSettingsActivity`). Leaving Google TV's
  home app on is no way around it there: its Home priority wins over ours. `tools/test-remote.sh` (run by
  `tools/check.sh`) checks which TVs and keys match.
  `adb shell input keyevent` doesn't test any of these: injected keys skip the accessibility key filter.
- Recent Apps (`RecentApps`, over a 96×54 `ScreenBlur` copy of the window) is off by default: *Home Settings* →
  *Recent Apps* (`Prefs.recents()`: `off`, `drawer`, `home`) adds the drawer shortcut and the app-switch key, and `home`
  also two Home presses within 700 ms (`HomeActivity.onNewIntent`; not offered on Xiaomi TVs, whose remote sends one
  press as a burst); Top Buttons can add it on its own. Only while it's open, it reads the list through the TV's ADB
  (`dumpsys activity recents`, `ps -A -o NAME`), and ▲ runs `am force-stop`.
  A card may be closed only where `HomeMode.mayStop` allows it (`mayTurnOff`, plus Amazon's streaming apps) and the app holds neither an enabled accessibility
  service (Android turns a force-stopped app's service off for good) nor the current keyboard, and only if
  `HomeMode.neededByTv` doesn't keep it (the TV's inputs and the remote's Input key, as before a switch). Its loads carry a
  generation (only the newest answers) and catch every exception: `:home`'s watchdog counts a crash there. It draws
  only while something moves, hides the layers under it once it covers the screen, and TalkBack reads its cards
  (`RECENT_CARDS`) instead of the home screen. Once closed (`isOpen()`), while it fades out, the keys and TalkBack are
  the home screen's again.
- What's playing (`NowPlaying`) comes from the apps' media sessions, which Android hands only to an allowed notification
  listener. A listener could read every notification, so ours follows Home Settings' *Now Playing widget* (off by
  default): turning it on allows it through ADB (`HomeMode.nowPlayingAccess`, `cmd notification allow_listener`; the
  `enabled_notification_listeners` setting grants nothing since Android 8) and proves it took (`Capabilities.LISTENER`,
  else the row shows Unavailable); turning it off, *Original* and the watchdog's revert take it back. The switch to
  Free My TV mode allows nothing. `HomeMode` sets it, under the `RUN` lock, to what the widget says at that moment (so
  quick toggles and a revert can't cross), `verifyNow` does the same after a boot or an update, and a screen that finds
  it on but not allowed asks once.
  It unbinds itself as soon as it connects (the permission is all it's for), and the home screen and the screensaver
  watch the sessions only while they show. The line is cut to fit when the title changes, never while drawing; the
  home screen's bars bounce again only for a new title (`Watch.title`), and TalkBack announces it then.
- *Status icons* (Home Settings, off by default): Wi-Fi or Ethernet, Bluetooth audio (A2DP and headset only: a
  Bluetooth remote is always connected) and a mounted removable drive. Nothing listens while the row is off; Android
  12+ asks for `BLUETOOTH_CONNECT` (*Nearby devices*) when it's turned on, and without it only that icon is missing.
- A mouse or an air mouse works on the home screen and in Settings (`onTouchEvent`, `onHoverEvent`,
  `onGenericMotionEvent`; the remote's keys never go through them): a click acts on what's drawn under the pointer, the
  wheel moves like the arrows, and hover moves the focus only when the pointer comes onto another item (a hand's nudge
  doesn't pull it back from the arrows) and never to one that would scroll its row (the next one would slide under the
  pointer).
- HY300 projectors (Hotack, Allwinner H713, Android 11 tested; the build claims to be an `askey`/`adt3`): the stock
  home screen and screensaver are `com.htc.htclauncherhighenglishd08`, which also carries an ad SDK (`com.zeasn.ad`).
  Keystone, zoom and projection mode live in `com.htc.htcsettings` (`action.htcsettings.prjsettings`), and the Miracast
  receiver is `com.softwinner.miracastReceiver/.Miracast`: `HomeApps.drawerShortcuts` adds both (kind `DEVICE`) where
  they resolve. Opening Miracast moves the Wi-Fi to Wi-Fi Direct: adb drops and asks to be allowed again. Nothing opens
  a passthrough input there; Allwinner's `com.softwinner.awlivetv` does, but always on HDMI 1 (the one real port of
  the three the board lists), so `inputs` keeps only HDMI 1.
  HY320 projectors (Allwinner too, Android 11 tested) use the same player, but it stays on whatever it was showing
  unless the intent names the port (`input_source` = `HDMI1`, as its own source picker sends; the HY300 opens its one
  port either way), and their port reports `INPUT_STATE_CONNECTED_STANDBY` even with a source on: on Allwinner boards
  standby counts as connected.
  Other HY300s (Amlogic txhd2, Android 13 tested; the build claims to be a Google `redfin`, model `t950s_be311`) have
  the same HTC home screen, but the Home button goes to JmLauncher (`com.jm.jmlauncher`), which ignores the default
  home: it opens the package in `persist.sys.home.selectpkg`, else the HTC one, and with that off and the property
  empty, a black screen. Their adb shell runs as root, so the switch sets the property (`HomeMode.JM_HOME`) and only
  then turns the HTC home off; Original and the watchdog put both back. Wireless debugging's port changes and turns
  itself off, but adb also listens on 5555 there. They carry `com.htc.htcsettings` too, but any change in its keystone
  or zoom crashes it (`SecurityException`: SurfaceFlinger doesn't know the Allwinner request code 1050): their own is
  `com.softwinner.tcorrection/.projection.ProjectionSettingActivity`, which the Keystone shortcut opens where it's
  installed.
  HY300 Pro+ (Rockchip rk3566, Android 14 tested; product `rk3566_rgo`) has no `launcherapps` system service
  (`service check launcherapps`: not found), so `LauncherApps.registerCallback` throws a NullPointerException and
  `HomeView` now catches it: the shelf doesn't refresh by itself after an install there, only when the home screen
  opens again. Its stock home is `com.htc.magcubicos`, which also holds the Wi-Fi, Bluetooth and projection settings:
  in Free My TV mode nothing answers `android.settings.SETTINGS`, so TV Settings falls back to Android TV's own
  `com.android.tv.settings/.MainSettings` by name (`HomeApps.open`), with Wi-Fi and Bluetooth; keystone and projection
  mode stay in magcubicos, out of reach while it's off (#379). Its own "ADB debug" switch turns on Wireless debugging,
  TLS-only on 5555, with no pairing screen in its settings: `com.android.tv.settings` (Activity Launcher, from
  F-Droid) has one. After pairing, `adb tcpip 5555` and Wireless debugging off leave classic adb on 5555, until the
  next restart.
- Xiaomi's Chinese firmware (MIUI TV; Redmi `MiTV-MFTP0`, Android 9 tested) has no passthrough handler either: its own
  `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity` (action `com.xiaomi.mitv.tvplayer.EXTSRC_PLAY`) opens a port from
  an `input` extra (HDMI 1 to 3 are 23 to 25; there is no number for a fourth), so `inputs` keeps HDMI 1 to 3 there.
- MediaTek TVs that list one hardware tuner input per mode (Haier H32S80EFX, Google TV 14: `AnalogInputService/HW1`,
  `CableInputService/HW0`, `TunerInputService/HW0`, in no fixed order), or two of one name (a Cecotec on a Vestel base,
  Android TV 14: `TunerInputService/HW0` antenna, `/HW1` analog, HW1 listed first, #312): Live TV tunes the digital
  antenna's (`HomeApps.tunerRank`: by name, then the lowest HW number), then cable's, then analog's.
- Sony BRAVIA (`BRAVIA_ATV2_EU`, Android TV 9 tested) lists `DvbOpv1`, the CI+ operator input (hidden and empty there),
  first among its tuners and at the satellite inputs' rank, so Live TV opened an empty channel list for satellite and
  antenna viewers alike (#340). On Sony, Live TV opens Sony's TV app (`com.sony.dtv.tvx`) the way its tile does, and
  it plays the last input and channel; a Sony without that app keeps the tuner request above.
- TVs with Fire OS built in (Xiaomi Fire TV, MediaTek, Fire OS 8 tested): the passthrough request lands on Vewd's
  HbbTV player (`com.vewd.core.tvapp`) or on Amazon's Live TV, and both play the antenna's last channel (RaiPlay's)
  instead of the port. Amazon's input player wants `com.amazon.tv.permission.TUNE_INPUT` and its home screen's switch
  `LAUNCH_INPUTS` (signature only), but `com.amazon.tv.inputpreference.service/.KaminoInputSwitchService` takes an
  `inputType` extra, the port's hardware number (`.../HdmiInputService/HW2` is 2, HDMI 1), from any app
  (`HomeApps.fireInput`). Live TV opens `com.amazon.tv.livetv/.DttIconWrapperActivity`, the antenna's entry.
  A Fire OS TV restarts quietly into standby when turned off (QHB) once it booted from power; after a reboot from
  software (`adb reboot`) every power-off restarts it with the screen on, until it's unplugged for 30 seconds.
- Realtek TVs on Hikeen boards (Romsat `D50A114D-U-T2-S`, KIVI; Android 9 tested; their `com.hikeen.*` apps tell
  them) force-stop the app in front as they go to standby (a hook in ActivityManager), so the TV woke on Realtek's
  black `FallbackHome` until Home was pressed, and nothing in the app survives the force-stop (#367).
  `HomeMode.wakeWatch` starts a shell loop through the TV's ADB (uid 2000, which the hook and `com.hikeen.cleanmaster`
  leave alone): it reads `DisplayPowerController`'s "Unblocked screen on" in the system log and opens the default home
  only while `FallbackHome` is in front. The switch and the boot check start it (a restart ends it), Original and the
  watchdog's revert stop it; `adb shell pgrep -f 'freemytv-wak[e]'` shows it. Leave `cleanmaster` on: the loop doesn't
  need it off.
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.
- LG and Samsung (`web/`): plain ES5 JavaScript and CSS without variables, grid or backdrop-filter, since older TVs
  (webOS 3 and 4, Tizen 3) run a Chromium from 2015 to 2017 (`tools/check.sh` greps for all of these); only transform
  and opacity animate. Tizen 2.4 (2016) runs WebKit 538: no Intl (`fmt` in data.js rounds by hand), SVGs need a width
  and height, and `tv.js` adds a `lite` class there that turns off shadows and transitions, which its GPU can't keep up
  with (LGs whose `navigator.deviceMemory` reads 1, up to about 1.5 GB, get it too: webOS TV Lite sets went from 25 to
  60 fps at a ratio of 2, #318, and from 45-50 to 59 at a ratio of 1, #383; Samsungs only when they also draw the page
  at twice its size, `devicePixelRatio`, since none at a ratio of 1 has been measured); it also
  can't open the TV's settings (`TV.canOpenSettings`). Without Intl, `toLocaleDateString` ignores its options and
  writes the whole date, so `strings.js` takes weekday and month names from the catalogues there (`I18N.dateNames` in
  data.js: `weekday|`, `month|` and `monthday|` keys, the last in the form next to a day). Tizen 2.3 and 2.4 don't
  trust Let's Encrypt's ISRG root, so Open-Meteo falls back to plain http there (`getMeteo`; only coordinates and the
  forecast go in the clear). On Tizen 2.3 (2015) Netflix, YouTube and Amazon are native apps whose ids start with a
  number (`11101200001.Netflix`, `111299001912.YouTube`, `111299001578.Amazon_Video`, listed as "Amazon Video"):
  `tizen.application.launch` doesn't open them, the TV's DIAL server does (`POST http://127.0.0.1:8001/ws/apps/<name>`,
  `dialLaunch`). `web/tv.js` is the
  TV's side: on LG, Luna (`listLaunchPoints`, `launch`, the inputs; where webOS 6 and newer deny `listLaunchPoints`
  to Developer Mode apps, the list comes from the TV's remote control API on `ws://127.0.0.1:3000` once the TV has
  allowed it (it asks once; a "no" isn't asked again; firmware that gives the page its own origin,
  `file://io.freemytv-webos`, gets 1008 "invalid origin" there, and the call goes through `webos/service`, a JS
  service in the package whose handshake says `file://`; webOS 22 with 2026 firmware tested), and until then from
  `getAppLoadStatus` over a list of known app ids, which shows the logo bundled in `web/icons/` where there is one, otherwise the name on the brand's color);
  on Samsung, `tizen.application` (`getAppsInfo`, `launch`), the `VIDEOSOURCE` property and
  `tizen.tvwindow.setSource` for the inputs, then Samsung's viewer (`org.tizen.tv-viewer`) to show them. Samsung's own
  data is unreliable: on Tizen 3 no app's `iconPath` loads (on Tizen 4 and 5, YouTube's and Prime Video's are a
  placeholder), so logos are bundled in `web/icons/` and keyed by app id in `SAMSUNG_ICONS` (SVGs need a width and
  height for old WebKit); some names are empty or long (`SAMSUNG_TITLES`); and Tizen 3 answers no "settings" app
  control, and `org.tizen.menu` froze 2017 and 2018 TVs (Tizen 3 and 4, #257), so TV Settings only shows from
  Tizen 5 (`TV.canOpenSettings`).
  `web/home.js` follows `HomeView`'s layout and behavior, so a design change on
  Android belongs there too. Text: the same catalogues as Android, packed into `i18n.js` by `tools/webapp.py` (only
  the keys `web/` uses): `tr('Live TV')`, `trf('{n} min ago', {n: 12})`, `plural('plural|…', n)` and
  `formatDate('format|h:mm a', at)` (the catalogue's SimpleDateFormat pattern, written by `web/strings.js`), and
  `tools/translate.py` reads those calls too. Built without a city (`--generic`), `web/zones.js`
  (`tools/make_zones.py`, from the tz database) turns the TV's time zone into the city, the currency and the region.
  `tools/webapp.py` is what both builds share. Preview: `python3 webos/build.py --preview` (or `tizen/build.py`), then
  open `out/webos/index.html` in a browser (a stand-in lists sample apps).
