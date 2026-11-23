# Installing it on a Google TV or Android TV for someone

For an assistant helping someone install their own build. The rules in [CLAUDE.md](../CLAUDE.md) (Always, keys) apply throughout.

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
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`, or sent to the TV in Home Settings > Watchmode key.
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
   If the TV's Wi-Fi was saved during its first setup (or by its stock home screen: the message says which), the
   switch stops before changing anything and asks them to forget the network and connect again in Settings (Network &
   Internet); then the switch goes through. `adb logcat -s FreeMyTV` names every app that saved one ("Wi-Fi networks
   saved by"). Saved networks out of range don't show in Settings: find the ones with
   `cname=com.google.android.tungsten.setupwraith` (or the home screen's package) in `adb shell dumpsys wifi` (each
   block starts with its `ID:`), and with their OK remove them with `adb shell cmd wifi forget-network <id>`. The
   check reads every `cname=` in that dump: from Android 11 each network shows up more than once (ConfigurationMap's
   `mPerId=` holds every user's, which `forget-network` can't reach), and a Passpoint profile's block has one too
   (#411).
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.


## Uninstalled while in Free My TV mode

The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`. On an HY300 with JmLauncher, also
`adb shell setprop persist.sys.home.selectpkg ''`. On TCL, an older version may also have turned off
`com.tcl.channelplus` and `com.tcl.waterfall.overseas` (Live TV's channel source and TCL Channel): `pm enable` them too.
