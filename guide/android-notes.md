# Android: device and feature notes

What was learned on real TVs and why the code is the way it is, one paragraph per feature or device. Read the paragraph for what you touch; add what a new TV teaches. The rules that apply everywhere are in [CLAUDE.md](../CLAUDE.md).

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
  The Watchmode key has the same screen and page (`Prefs.watchmodeKey`, read through `Recs.watchmodeKey()`, checked with
  `/status/`), on Google TV and Android TV only.
  On LG and Samsung it's typed in Home Settings (no QR: a web app can't serve a page): `Prefs.tmdbKey()` (localStorage)
  wins over `CONFIG.tmdbKey`, and `Data.keyChanged()` fetches the picks again.
- Continue Watching gets `READ_TV_LISTINGS` with `pm grant` through the TV's own ADB only when picked in Home Settings;
  *Original* revokes it with `pm revoke`. Watch Next pictures load from the TV (`content://`, `android.resource://`) and
  from the https addresses the apps themselves left there (Netflix and JioHotstar do, #174), only through `NetPicture`:
  https, no host on the TV or the home network (redirects checked hop by hop), `image/*`, 4 MB, 10 s, and errors that
  never carry the address. Nothing else may be fetched from an address another app wrote. Besides those, the app talks only to TMDB, Open-Meteo, Binance, Watchmode (with a `watchmode_key`), your Plex
  server (with a `plex_url`), your Immich server (with `immich_server` and its key), Trakt (with a `trakt_client_id`,
  once the person signs in on the TV) and GitHub's releases (Updates): new sources (such as Stremio) must not add
  network calls without the owner's OK.
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
  Right to left: the web app (LG, Samsung) mirrors for Arabic (`I18N.rtl`; home.js places things with `startAt`/`endAt`
  from the start edge and swaps ← → where the layout runs; home.css's `.rtl` rules mirror the rest), and `I18N.digits`
  writes ٠-٩ in Arabic. The Android app isn't mirrored yet, so `Ui.languages()` leaves the right-to-left catalogues
  out (`Ui.RTL`): Arabic isn't in its list, and "auto" on an Arabic TV stays in English; `ar.json` still ships for the
  web app and is checked like the others. CJK, Devanagari and Arabic use the system font.
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
- Android 8 and 9 have no `cmd role`, and with Free My TV installed beside the stock launcher and no default home, HOME
  answers with the chooser, so the switch takes `stockHome` instead (Original's pick: never where launcherx is installed, nor
  while an earlier switch's work is still off, after a reinstall), without timing its Home button. The boot check does
  the same for a TV switched earlier, only for Google's `tvlauncher`, since it runs by itself (#453: `tvrecommendations`
  started tvlauncher at wakes on a Philips TPM171E). `Capabilities.screensaver` (`stay_on_while_plugged_in`, read once) says whether
  Android can start ours at all: that Philips stays awake and runs its own from its system UI, burn-in protection that must
  not be turned off or raced (#451); the switch's texts leave the screensaver out there, and its rows are Unavailable.
- Settings rows that need something this TV lacks stay, dimmed as *Unavailable*: `SettingsActivity.need` names what a
  row needs, `Capabilities.missing` says why it's missing (once per screen), and OK on the row shows it in a toast.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar), Sony, Xiaomi, Hisense and Philips' Google TV TVs, the JVC G24, Formovie projectors,
  Chromecast with Google TV (Sabrina, YouTube only), the Google TV Streamer and onn streamers; the service is on only
  in Free My TV mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  The Apps key reaches us two ways, and does the same on every brand: the system starts `HomeActivity` with
  `ACTION_ALL_APPS` (the manifest filter) where a stock home screen answered it, and `KEYCODE_ALL_APPS` is taken in
  `HomeActivity.dispatchKeyEvent` unless the key has a row in Remote Buttons on this TV (Hisense).
  `HomeView.showAllApps` opens the sheet, or closes it on a second press, ignoring the same press delivered twice within
  400 ms. Neither reaches us where the system aims `ACTION_ALL_APPS` at `com.android.tv.settings` and keeps the key (a
  Mi Stick, #414; a TCL on Google TV 14: `START {act=android.intent.action.ALL_APPS pkg=com.android.tv.settings}`
  fails with -91 and the window never sees 284).
  The service is also off while every button is *Nothing* (not on Hisense, for the Input key), and always on TCL's
  Android 11 (`RemoteKeys.FILTER_SAFE`): there any key filter makes the TV act on a volume key twice (one press moves
  2 steps and a held key gets stuck, #325; turning off only our service fixed it), so the buttons work only where they
  reach our home screen's window.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons, `KEYCODE_BUTTON_4` for Netflix and `KEYCODE_BUTTON_8` for the Google Play button of
  older remotes (BRAVIA 4K UR3, Android 10 tested), global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. The BRAVIA VU31's remote (Realtek, Google TV 14, #395) sends `BUTTON_4` with scan code 758 for
  Netflix, `BUTTON_2` (652) for Prime Video, `BUTTON_15` (653) for YouTube and key code 3004 for SonyLIV; `Prefs.sonyKey`
  puts the first three on the same rows. There the TV hands the buttons to launcherx, so with it off "Sony default" does
  nothing: on a Sony with launcherx installed (`Prefs.sonyGoogleTv`) the buttons default to their apps. Another remote's
  codes go in `sonyKey` the same way, as long as they don't clash with a known one. The BRAVIA 8 II (K-65XR8M2,
  `BRAVIA_AE2_EU`, MediaTek, Google TV 14; remote `SONY TV VRC 001`) sends `KEYCODE_VIDEO_APP_1` to `_8` instead, with
  the same scan codes (583 Netflix, 485 YouTube), so `RemoteKeys` maps those through `sonyKey` too. Its gear sends
  `KEYCODE_NOTIFICATION`, which SystemUI turns into launcherx's dashboard panel ("Could not resolve activityInfo for
  intent android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL" with launcherx off): `Prefs.KEY_SONY_SETTINGS` opens TV
  Settings by default on a Sony with launcherx installed. Sony ships its own Apple TV build,
  `com.apple.atve.sony.appletv`.
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
  The MiTV-AFKR0 (Amlogic, Android TV 11 tested; device `jaws`) has a Home button and a remote marked "Mi TV+",
  Netflix, Prime Video and YouTube: `RemoteKeys.XIAOMI_AFKR0` lists those four, as `BUTTON_5` (the PatchWall one,
  left to PatchWall until an app is picked), `BUTTON_6`, `BUTTON_7` and `BUTTON_16` (not `BUTTON_3` as above; this
  remote has no Rutube button to clash with it). Netflix, Prime Video and YouTube open their apps unless picked, and
  its presses come one at a time, so no burst. `getevent` shows its presses on the remote's consumer-control interface,
  with codes that keylayout leaves unmapped (`KEY_VIDEOPHONE` for Netflix); what the framework dispatches comes from
  its keyboard interface (`key 416 BUTTON_6`), so read key codes from `adb logcat -s FreeMyTV` ("remote key 193 -> …").
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
  button has nothing to open without `launcherx`. Its Input key (`KEYCODE_TV_INPUT`) becomes VIEW_INPUTS in the firmware
  (`HisensePhoneWindowHelper`), answered only by the stock home screen's inputs panel (#494): while nothing resolves it,
  the service opens our drawer on the first input (`HomeActivity.EXTRA_INPUTS`). Only on Hisense, since another
  firmware could open its own panel another way. Hisense keeps only error logs: `adb shell setprop log.tag.FreeMyTV I`
  before reading `adb logcat -s FreeMyTV`.
  Philips' Google TV models (`RemoteKeys.PHILIPS_GTV`: Android 13 and newer, by brand, maker or model name; a 65OLED910,
  `Philips Google TV TA5`, Android 14, gave the codes, #437, and no one has run it yet) send TP Vision's own numbers: 811
  Settings, 816 apps, `GUIDE`, 475 Netflix, 781 YouTube, 788 Prime Video. `org.droidtv.GlobalKey` hands Settings to
  launcherx's dashboard, off in our mode, so it opens TV Settings by default, and the apps button our All Apps; the
  app buttons keep working through that receiver until an app is picked (*Philips default*).
  TCL's Settings button sends `KEYCODE_NOTIFICATION`, which only Google TV's home app (launcherx, off in our mode)
  answers with its quick settings panel. It is a *Remote Buttons* row (`Prefs.KEY_TCL_SETTINGS`) whose default is TV
  Settings (`com.android.tv.settings`, as #289 does from the home screen); its choice `Prefs.QUICK_PANEL` makes
  `RemoteKeys` open `QuickPanelActivity`, and *Nothing* leaves the key alone (`HomeView` too, as for Hisense). On TCL's
  Android 11 the key service is off (#325), so there the row works on the home screen only. The panel is our own, over
  what's playing (a window on the left, drawn as glass since TVs turn window blur off), whose tiles open the TV's own screens
  (TCL's Picture and Sound panels through `DISPLAY_SETTINGS` and `VIEW_SOUND_SETTINGS`; TCL Google TV 14 tested).
  TCL's BeyondTV (Android 9, Realtek RTD2851, build `PPR1.180610.011`) sends each remote key through
  `com.tcl.keycustomfunctionservice` (`KeyMultipleService`). This service injects the key again (the log shows
  `deviceId=-1`). The key service is on and runs in `:keys`, but it does not receive these keys. Thus Remote Buttons
  work only when our home screen window has the focus. The system also handles Netflix (4062) and TCL Channel (4020)
  as global keys (`GlobalKeyManager handleGlobalKey`; 4020 goes to the receiver of `com.realtek.tv.settings`). Thus
  the TV also does its own action: on the home screen, a Netflix button set to a different choice did that choice, and
  Netflix also opened.
  Chromecast with Google TV (Sabrina, Android 14; device `sabrina`, remote 18d1:9450) sends scan usage `0x000c0077`
  for YouTube and `0x000c0078` for Netflix. The public `Vendor_18d1_Product_9450.kl` maps these to BUTTON_3 and
  BUTTON_4; a read-only `getevent` capture on this TV confirmed both scan usages. User testing confirmed Netflix
  continues through Google's handler while YouTube does not; launcherx owns YouTube and is off in Free My TV mode.
  Only YouTube is ours: `SabrinaRemote` matches the exact device/model and `RemoteKeys.onKey` also checks the physical
  remote's vendor/product before opening the selected app (YouTube by default). Netflix and all other buttons stay
  with Android.
  The Google TV Streamer (`RemoteKeys.GOOGLE_STREAMER`: model `Google TV Streamer`, device `kirkwood`, Android 14
  tested) maps YouTube to `BUTTON_3`, Netflix to `BUTTON_4` and its custom star button to `MACRO_1` (313, API 34), from
  `Vendor_18d1_Product_9451.kl`. YouTube and the star go to launcherx; Netflix goes to chromecastservice. The model
  check excludes Chromecasts and other Google devices. YouTube opens YouTube initially; Netflix and the star stay
  with Google until an app is picked. An empty choice (*Google default*) passes the key through, so YouTube and the
  star may do nothing with launcherx off. All three can also open Free My TV's All Apps; there is no separate
  long-press mapping. Home, Back, voice, power and volume stay with the system.
  onn streamers (`RemoteKeys.ONN`, by brand; the onn 4K Pro, `onn. Streaming Device 4K pro`, device `SNA`, Amlogic,
  Google TV 14 tested) read their remote through `Vendor_0957_Product_0031.kl`: YouTube is HID usage 0x77 (`BUTTON_3`,
  for launcherx), Netflix 0x78 (`BUTTON_4`), Disney+ 0x79 (`BUTTON_9`) and Paramount+ 0x7A (`BUTTON_10`), the last three
  for the box's own receivers (`com.droidlogic`, `com.gretzky.GlobalKey`), which stay on. Free TV is 0x187,
  `FEATURED_APP_1` (297; public only since API 33, so a number in the code), also launcherx's. With launcherx off,
  YouTube and Free TV do nothing, so YouTube opens YouTube by default and Free TV waits for a pick; *onn default*
  (empty) leaves the others to the box. Each can also open Free My TV's home screen or All Apps. Only that remote
  (vendor 0957, product 0031) is ours: `RemoteKeys.onKey` checks it as it does Sabrina's, so another onn model's remote
  stays with the box until its codes are read. `getevent -l` on the remote's device shows each press's usage
  (`MSC_SCAN`).
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
- Home Assistant buttons (`HomeAssistant`): `home_assistant_url` and `_entities` in `config.properties` go into the build
  (`Config.HOME_ASSISTANT`: only then does Home Settings have its *Home Assistant* row, off by default). The token never
  does (any app can read an installed APK, and a token reaches every service): it's theirs and secret, like `tmdb_key`,
  and they send it themselves with `python3 tools/send_key.py <IP> home_assistant_token` (`Secrets`, as the Immich key;
  it checks the token with the server and lists what each entity becomes first; don't ask for it in the chat). Until
  it's there the row shows Unavailable (`Capabilities.NEED_HOME_ASSISTANT`). On, the entities are a
  third group of tiles in the bottom drawer (`HomeApps.HOME_ASSISTANT`), read when it opens and every 30 s while it's
  open, and OK calls their service (`/api/services`). Only `HomeAssistant.DOMAINS`, at most 8: locks and alarms are
  never offered, and covers only of `HomeAssistant.SAFE_COVERS` (awning, blind, curtain, shade, shutter; not garage
  doors, gates, doors or a cover with no class), once its class is read. The UI thread never reads the token file
  (`itemsNow`, `onNow`). Redirects aren't followed. The address goes through `lan_server` in `build.sh`: plain HTTP
  only to the local network.
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
- The drawer's inputs are the person's to choose (#488, #497): *Inputs* (Settings on Google TV and Android TV, Home
  Settings on LG and Samsung, where it took the place of the *Live TV* switch and carries it over) is the Shelf's
  editor over Live TV and the HDMI ports: OK shows or hides one, ← → move it (`Prefs.inputsOrder`/`inputsHidden` on both
  sides, `VisibleOrder` on Android). Never by signal; until it's used, everything shows in the TV's order; a port
  that turns up later joins at the end, and one that's gone keeps its place. The quick panel follows it, and with no
  input at all the row is Unavailable. *Home Settings* → *Live TV button* (`Prefs.liveButton`, #422, #503) is separate:
  what the first top button opens, Live TV or an HDMI input, even one hidden from the drawer.
- *At power-on* (Home Settings, `Prefs.powerOn()`: `tv`, the default, `home` or `live`; #396, #346) acts only after a
  full start, never on a wake from standby. `HomeActivity` keeps the uptime of the first time the home screen came up
  in this start (`HomeMode.noteHomeShown`, the file `home-shown` with `Settings.Global.BOOT_COUNT`). `live`: 10 s later,
  if no key reached our window and it's still in front, it opens Live TV as the button does. If the screen went off
  first (a TV that starts in standby after a power cut, as Android's quiescent boot does: a TCL Smart TV Pro
  reports `ro.boot.bootreason` `cold,quiescent`, #489), the 10 s count again
  when it comes on with our home screen in front; a key or Home before that cancels it, and each outcome is in the
  log (`at power-on: ...`). `home`: a `POWER_ON` job
  10 s after boot, for up to a minute, sends the HOME intent through the TV's ADB when an app of `LIVE_TV_APPS` is in
  front and `dumpsys power`'s last user activity (`mLastUserActivityTime`; `lastUserActivityTime` per display group on
  Android 14) is still older than that uptime (a key moves it; a Changhong on
  MediaTek stamped it once at 23 s, Home came up at 26 s and its TV center took the screen at 27 s). An unreadable
  stamp counts as a key: nothing moves.
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
  (`HomeApps.fireInput`). On a TCL AFTDEC012E (Fire OS 8), HDMI 4 is HW5 but the switch requires type 10; type 5
  times out waiting for input data and queues later requests. Live TV opens `com.amazon.tv.livetv/.DttIconWrapperActivity`, the antenna's entry.
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
