<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Free My TV

**A fast, ad-free home screen for Google TV, LG and Samsung TVs.**

Trending picks from your own apps, the weather at a glance, and a look that follows the sunset.<br>
Open source. Built with Claude Code. One button to undo.

[**freemytv.io**](https://freemytv.io) · [Install](#install) · [LG TVs](#lg-tvs-webos) · [Samsung TVs](#samsung-tvs-tizen) · [Any TV](#any-tv) · [Make it yours](#make-it-yours)

<img src=".github/preview.webp" width="880" alt="The Free My TV home screen: a glass shelf of apps, trending picks, and a drawer with the weather and crypto prices">

</div>

## What you get

- **No ads.** No sponsored rows, banners or autoplaying promos. Not one.
- **Your apps, front and center.** The streaming apps you actually use, on a glass shelf. Pick them and their order right on the TV, or (on Google TV and Android TV) let the one you opened last come first.
- **Trending, not sponsored.** Three fresh picks every hour (up to six, in Home Settings) from what's trending this week and the latest releases, only from apps you already have, and from the services you choose. The focused one opens wide and the others show their posters. Movies and Shows get their own tabs, and on Google TV your own [Plex library](#your-plex-library-optional) can join in.
- **Continue Watching** (Google TV and Android TV). *Home Settings* → *Top of the screen* puts what you left halfway in your apps on top, with its progress, and OK picks it up in its app. Or a big clock, or the picks.
- **Your TMDB key from your phone.** *Home Settings* → *TMDB key* shows a QR code: scan it and paste the key. On LG and Samsung, type it with the remote.
- **Weather and markets, one press away.** The hour-by-hour forecast (or the next 5 days), sunrise and sunset, and live crypto prices.
- **What's playing.** Music, a podcast or the radio going on in the background shows on the home screen and the screensaver: the title, the artist and the app, from any app that tells Android what it plays (*Home Settings*, off until you turn it on).
- **The status at a glance, and a mouse** (Google TV and Android TV). *Home Settings* → *Status icons* puts Wi-Fi or Ethernet, Bluetooth audio and a USB drive at the top of the home screen; off, nothing listens for them. A mouse or an air-mouse remote works too: hover moves the focus, the wheel scrolls, a click picks.
- **Light by day, dark by night.** It follows your city's real sunset, the way your phone does.
- **A screensaver to match.** Full-screen photos of the picks with a big glass clock. On LG and Samsung, *Home Settings* → *Screensaver* can make it a large clock with the weather instead, drifting slowly so it doesn't burn into OLED screens. Optionally (*Watch from the screensaver*, in Home Settings), OK opens the title on screen in its app, and any other button just closes it.
- **Dimming for a dark room** (LG and Samsung). *Home Settings* → *Dim* darkens Free My TV's own screens, not the apps you open: low, medium, high, or automatic (medium from 22:00 to 07:00). Off until you pick it.
- **Your own photos** (Google TV and Android TV). Send them from your computer (up to 100): the screensaver shows them, and without picks one is the home screen's background each hour.
- **Fast.** 60 fps, every frame in 16.6 ms, on the TV's own chip. The home screen uses 96 MB of memory instead of 242 MB (−60%).<br><sub>Measured on a 98″ TCL Google TV running Android 14.</sub>
- **Set up on the TV.** Language, your city, temperature, theme, how many picks and which streaming services they come from, the weather and prices cards, the forecast's hours or days, which coins, and the apps on the shelf, in Free My TV's own settings.
- **Safe.** No root. Nothing is uninstalled. One button brings back the factory home screen.

## Install

About ten minutes. You need a Google TV or Android TV (Android 8 or newer) and a computer on the same Wi-Fi. The APK goes on the TV, not on your phone (on a phone, the settings say so and nothing switches).

### The easy way: an AI assistant

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then Settings → System → Developer options → turn on **USB debugging**. Write down the TV's IP address (Settings → Network & Internet).
2. **On your computer**, open an AI coding assistant ([Claude Code](https://claude.com/claude-code), Codex or Antigravity) and say:
   > Install Free My TV (github.com/simbasjungles/free-my-tv) on my TV at 192.168.1.50. I live in Lisbon.

   It downloads the code, installs the tools it needs, builds the app and puts it on your TV, and asks before anything important. Swap in your TV's IP and your city.
3. **On the TV**, open *Free My TV* → *Home Screen* → *Free My TV*. That's when the TV asks *Allow USB debugging?* for Free My TV: tick *Always allow* and accept.

### The ready APK

No tools to install besides adb. The APK on the [latest release](https://github.com/simbasjungles/free-my-tv/releases/latest) has no TMDB key: add yours on the TV for the picks (*Home Settings* → *TMDB key*, [below](#the-tmdb-key)). It takes your city from the TV's time zone (change it in *Home Settings* → *City*).

If you built Free My TV yourself before, switch back to *Original* and uninstall it first: your build and the release are signed with different keys.

1. **On the TV**, do step 1 above.
2. **On your computer**, download `FreeMyTV.apk` from the release and install it:
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV.apk
   ```
   On Android 14, Google Play Protect may hold the install with *Risky app blocked*: choose *More details* → *Install anyway*.

   Install it with adb like this, not by opening the APK on the TV: from a file manager or a browser, Play Protect can refuse it outright with *App blocked to protect your device* (in some countries it blocks sideloaded apps that have an accessibility service or a notification listener, which Free My TV uses for the remote's buttons and Now Playing; #378).

   On TCL TVs, also let it run in the background (TCL blocks it by default, and the switch never starts). An uninstall takes the permission back, so run it again after every new install:
   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```
3. **On the TV**, do step 3 above.

For the picks, add your TMDB key in *Home Settings* → *TMDB key* (below).

**Updates come to the TV.** Once a week the APK from the release looks for a newer release and downloads it; the home screen then says so once, and *Home Settings* → *Updates* (the last row) installs it through Android's installer, which asks first (and, the first time, asks to allow Free My TV to install apps). OK on *Updates* also checks right away. The download must come from this repository's releases, match the size and SHA-256 GitHub lists, be newer, and be signed with the project's key. A build you made yourself checks only when you press *Updates*, and only says when a new version is out: build it again to update.

### The manual way

1. **On the TV**, do step 1 above.
2. **Tools:** a JDK (17+), the Android SDK command-line tools (build-tools 34, platform android-34) and adb. On a Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   On Linux, get OpenJDK 17, Android's command-line tools and adb from your package manager, then run the same `sdkmanager` line. On Windows, use Git Bash (which Claude Code uses there) or WSL.
3. **Build:**
   ```bash
   ./build.sh
   ```
   The first run creates `config.properties`. Set your city and your [TMDB key](#the-tmdb-key) there, then run it again.
4. **Install:**
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV.apk
   ```
   If the install seems stuck, look at the TV: on Android 14, Google Play Protect may hold it with *Risky app blocked* ("built for an older version of Android"), since Free My TV targets Android 13. Choose *More details* → *Install anyway*. Updates don't ask again.

   Chromecast with Google TV on Android 14 keeps port 5555 closed even with USB debugging on: turn on *Wireless debugging* (Developer options), pair with `adb pair <address:port> <code>`, then run `adb tcpip 5555` over that connection. Port 5555 closes again at every restart, so after one, *Original*, the watchdog and the boot check can't reach ADB until you run `adb tcpip 5555` again (the pairing stays).

   On TCL TVs, also let it run in the background (TCL blocks it by default, and the switch never starts). An uninstall takes the permission back, so run it again after every new install:
   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```
5. **On the TV**, open *Free My TV* → *Home Screen* → *Free My TV*. The TV asks *Allow USB debugging?* once more, now for Free My TV itself: tick *Always allow* and accept. (Run `adb disconnect 192.168.1.50:5555` first: on Android 8 to 10 the TV can hand that answer to a computer that's still connected.)

   On some TCL TVs (Android 11, Realtek), a second background killer, TGuard, can stop the switch while it measures the original home screen: the settings screen closes and the TV's own home screen stays. Android runs the switch again by itself within a minute or two; then press Home: Free My TV opening means it went through.

## LG TVs (webOS)

The same home screen as an LG app: the picks, the glass shelf with your apps, the drawer with inputs, weather and prices, day and night, Home Settings and the shelf editor. It installs through LG's own Developer Mode, with nothing rooted or changed on the TV. LG's home screen stays where it is, and Free My TV is one press of a number key away (or in LG's app list, on remotes without them).

You need an LG TV (webOS 3 or newer) and a computer on the same Wi-Fi, with Python 3.

1. **On the TV**, open the LG Content Store and install **Developer Mode**. Sign in with a free [LG developer account](https://webostv.developer.lge.com), turn on **Dev Mode Status** (the TV restarts), then turn on **Key Server**. Write down the IP address and the passphrase it shows.
2. **On your computer**, get the code and build. `config.properties` works as for Google TV (your city, your [TMDB key](#the-tmdb-key)):
   ```bash
   git clone https://github.com/simbasjungles/free-my-tv && cd free-my-tv
   python3 webos/build.py
   ```
3. **Install.** The first time, it asks for the TV's passphrase; updates don't. On a TV that isn't 4K, it puts the app in at 1280 × 720, the size those TVs draw apps at:
   ```bash
   python3 webos/install.py 192.168.1.50
   ```
4. **On the TV**, while Free My TV is open, hold a number key on the remote. From then on, that key opens it from anywhere. On a remote without number keys, open it from LG's app list on the home screen, like any other app.

With an AI assistant, say *"Install Free My TV (github.com/simbasjungles/free-my-tv) on my LG TV at 192.168.1.50. I live in Lisbon."* It builds the app and hands you the install command, so you type the passphrase yourself.

With the [webOS Homebrew Channel](https://github.com/webosbrew/webos-homebrew-channel), you can skip the computer: add the repository `https://freemytv.io/webos/apps.json` in the Channel's settings, and Free My TV shows up in its list. That's the release build: it takes the city from the TV's time zone (change it in *Home Settings* → *City*), and for the picks you type your [TMDB key](#the-tmdb-key) in *Home Settings* → *TMDB key*. If you installed your own build, don't update it from there, or the release replaces it. On some LG TVs that aren't 4K, that build fills only the top-left part of the screen: `install.py` puts it in at the right size there.

On webOS 6 and newer, LG doesn't let apps installed through Developer Mode see which apps the TV has. Free My TV then shows the well-known ones it finds (Netflix, YouTube, Prime Video, Disney+, Apple TV, Plex and more) and asks the TV, once, for the access LG's phone app uses: a message on the TV asks whether to allow Free My TV. Allow it, and from the next start all your apps show, with their icons.

Older LG TVs (webOS 3) don't let Free My TV see their apps at all, so the shelf stays empty. There your computer asks the TV instead (the TV asks once whether to allow it), and the list and icons are built into the app. Run it again after installing new apps on the TV:
```bash
python3 webos/apps.py 192.168.1.50 && python3 webos/build.py && python3 webos/install.py 192.168.1.50
```

Developer Mode lasts about 40 days: open the Developer Mode app now and then and press **EXTEND**. To remove Free My TV, run `python3 webos/install.py 192.168.1.50 --uninstall`.

## Samsung TVs (Tizen)

The same home screen as a Samsung app, with everything the LG one has: the picks, the shelf, the drawer with the inputs, the weather and the prices, day and night, and its own settings. It installs through Samsung's own Developer Mode, with nothing rooted or changed on the TV, and Samsung's home screen stays where it is.

You need a Samsung TV on Tizen, from 2015 or newer, and a computer on the same Wi-Fi (a 2015 UE48JU6100 on Tizen 2.3 was reported working, #317, while a 2016 Full HD UE49K5670 refuses developer installs altogether, with `failed[118]` even from Samsung's own `tizen install`, #349: on a 2016 UE49KU6640 and a UN49KU6400 the same answer was a copy left by an earlier failed install, which `python3 tizen/install.py <IP> --uninstall` removed (sign with a Tizen certificate there: a Samsung one kept getting it on the KU6400); Orsay models can't run it, but a Google TV streamer on HDMI can). 2015 and 2016 TVs can't run TizenBrew, so on those build it yourself (below); TV Settings isn't in the drawer there, since apps can't open the TV's settings, nor on 2017 and 2018 TVs (Tizen 3 and 4), which freeze trying (#257); the remote's own Settings button works on all of them.

1. **On the TV**, open **Apps** and type `1 2 3 4 5` on the remote. Turn **Developer mode** on, type your computer's IP address in **Host PC IP**, and restart the TV.
2. **On your computer**, download [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller/releases/latest), the community's installer for Samsung TVs, open it and connect to your TV.
3. **Install from GitHub:** type `simbasjungles/free-my-tv`. On 2023 and newer TVs it asks you to sign in to your Samsung account once, to sign the app for your TV.
4. **Straight to it:** in the TV's settings, under Start Screen Options, turn on **Autorun Last App**: the TV turns on into Free My TV (tested on a 2017 TV; 2019 and 2020 TVs don't reopen it, #356). There, move Free My TV to the first place in the Home bar, so it's one press away.

This build takes your city from the TV's time zone (change it in *Home Settings* → *City*), and for the trending picks you type your [TMDB key](#the-tmdb-key) in *Home Settings* → *TMDB key*. To build it with your own `config.properties` instead, open the folder in Claude Code and say *"Install Free My TV on my Samsung TV at 192.168.1.50. I live in Lisbon."* It sets up Samsung's tools, and on 2023 and newer TVs you sign in to your Samsung account in the browser to make the certificate. Your signed build installs with `python3 tizen/install.py 192.168.1.50`, which needs only Python: no Tizen Studio, and no Rosetta on Apple Silicon Macs.

## Settings

The first values come from `config.properties`, created by the first build (Google TV, LG and Samsung use the same file). Most of them can also be changed on the TV, in *Free My TV* → *Home Settings*, and the TV's choice wins:

| Key | What it does | Default |
|---|---|---|
| `tmdb_key` | Your free TMDB key, for the picks | empty |
| `tmdb_region` | Your country (two letters): which titles are on which app | `US` |
| `watchmode_key` | Optional, free [Watchmode](https://api.watchmode.com/) key: a pick opens the title's page in its app. On Google TV and Android TV you can also send it from your phone | empty |
| `plex_url` and `plex_token` | Optional, your Plex server's `plex.direct` address and token: its newest titles join the picks and open on their page | empty |
| `trakt_client_id` | Optional: the Client ID of your own free [Trakt](https://trakt.tv/oauth/applications) API app. Home Settings > Picks then gets a Trakt row to sign in with a code, and the picks start with what you're in the middle of, bring in your watchlist and leave out what you've watched. Google TV only | empty |
| `home_assistant_url`, `home_assistant_entities` | Optional: buttons for your own [Home Assistant](#home-assistant-buttons-optional) in the bottom drawer (Google TV only; the token goes from the computer, not in here) | empty |
| `latitude`, `longitude` | Your city, for the weather and the sunset | New York |
| `city` | The name on the weather card | `Weather` |
| `hide_animation` | Leave cartoons and anime out of the picks; *Cartoons and anime* in Home Settings (under *Picks* on Google TV) changes it on the TV | `false` |
| `open_in_stremio` | A pick opens on its page in [Stremio](https://www.stremio.com/) when the Stremio app is on the TV, instead of in the app it streams on. The picks stay the same: only from the TV's own apps. Google TV only | `false` |
| `language` | `auto` (follows the TV), or a language with a [translation](#translations) (`en`, `pt`, `es`, `de`, `ru`, `fr`, `ja`…) | `auto` |
| `show_crypto` | Show Binance crypto prices on home and screensaver | `true` |
| `currency` | Crypto prices in `USD`, `EUR`, `BRL`… | `USD` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `theme` | `auto` (dark from sunset to sunrise), `light` or `dark` | `auto` |
| `disable_assistant` | Also turn off Google Assistant | `false` |
| `screensaver` | `false` keeps the TV's own screensaver: Free My TV then changes only the home screen and the promo apps (Google TV) | `true` |
| `hide_services` | Streaming services to leave out of the picks even when their app is on the TV (`netflix`, `prime`, `disney`…), comma separated; for apps the TV ships that you don't subscribe to. On Google TV and Android TV, a service that TMDB lists in your country under the same name as an app on the TV also appears in *Picks from*, off until you switch it on. It is where the switches in Home Settings › Picks › Picks from start; what you switch on the TV wins. Google TV only | empty |
| `hide_apps` | Apps to leave out of the shelf, All Apps and the picks, by id or name (part of it), comma separated; for apps the TV ships that can't be uninstalled, like Samsung's built-in Netflix. LG and Samsung only | empty |
| `shelf_apps` | The shelf's apps in this order, by id or name (part of it), comma separated; changing it wins once over the shelf picked on the TV. On Samsung, a whole app id also brings an app the TV leaves out of its list, like one installed in Developer Mode. LG and Samsung only | empty |
| `drawer_first` | `true` opens the home screen with the drawer already out (apps in the middle, inputs, weather and prices below), so nothing has to be scrolled to reach the inputs; Back closes it to the full page. For a TV whose streaming apps live on a box, where the picks stay empty. LG and Samsung only | `false` |
| `immich_server` | Optional: your [Immich](https://immich.app) server: [your own photos](#from-an-immich-server) come from it, on each TV you send its key to (with `tools/send_key.py`, never here). Google TV only | empty |
| `open_picks_in` | Where a pick opens: `apps` (the streaming app that has it) or `nuvio` (its page in Nuvio, when the TV has it); Home Settings changes it on the TV. LG only (Samsung passes no link yet) | `apps` |

Changed something? Run `./build.sh` and install again. Keep `freemytv.keystore`: updates are signed with it.

### The TMDB key

Free, and it takes two minutes: create an account at [themoviedb.org](https://www.themoviedb.org/signup), then open [Settings → API](https://www.themoviedb.org/settings/api) and request a developer key. Paste the *API Read Access Token* (or the *API Key*) after `tmdb_key=`. It's your key: `config.properties` stays out of git.

On Google TV and Android TV you can also add it on the TV, which the APK from the releases needs: *Home Settings* → *TMDB key*
shows a QR code. Scan it with your phone (on the same Wi-Fi), paste the key and send it; the TV checks it with TMDB and
keeps it. The code works once, for 5 minutes, and only while it's on screen. The phone's page is plain HTTP on your own
Wi-Fi: on a network you don't trust (a hotel's), type the key instead. No phone at hand? *Type it with the remote*: the
short *API Key* (32 characters) is the easier one to type. A key added on the TV wins over the one in `config.properties`,
and *Remove the key* goes back to it.

On LG and Samsung you can also type it on the TV, in *Home Settings* → *TMDB key*: the short *API Key* (32 characters) is the easier one to type with the remote. TMDB checks it before it's saved, it stays on the TV, and it wins over the one in `config.properties` (*Remove the key* goes back to that one).

### Opening a title, not just its app (optional)

With a free [Watchmode](https://api.watchmode.com/) key (*Home Settings* → *Watchmode key* on Google TV and Android TV: a QR code for your phone, or typed, as for the TMDB key; or `watchmode_key=` in `config.properties`, and the TV's wins), a pick opens its own page in the streaming app instead of the app's home. The free plan (2,500 credits a month) is enough: each title costs two requests the first time and is remembered after that. It works where the app takes the title's web address (tested: Prime Video, JioHotstar, Netflix and Disney+); where it doesn't, the pick just opens the app, as without a key.

### Home Assistant buttons (optional)

On Google TV and Android TV, the bottom drawer can hold buttons for your own [Home Assistant](https://www.home-assistant.io/): a light, a scene for movie night, a fan. In `config.properties`:

```properties
home_assistant_url=http://homeassistant.local:8123
home_assistant_entities=light.living_room, scene.movie_night, switch.fan
```

Build and install again: *Home Settings* gets a *Home Assistant* row (only builds with an address have it). Then send the token from the computer, a long-lived access token (in Home Assistant: your profile → Security → Long-lived access tokens):

```bash
python3 tools/send_key.py 192.168.1.50 home_assistant_token
```

It asks for the token without showing it, checks it with your server, lists what each entity becomes, sends it over adb into the app's private files, and asks the TV whether it arrived (Android 9 and newer). It never goes into the APK or `config.properties`, since any app on the TV can read an installed APK, and a Home Assistant token reaches every service, locks included. A Home Assistant user of its own that isn't an administrator is still safest. `--remove` takes it off the TV again.

Then turn on *Home Settings* → *Home Assistant* on the TV: it starts off. A group of buttons joins Inputs and Shortcuts, one per entity (up to 8, in your order), with its name from Home Assistant; every tile in the drawer shares one width, so more buttons make them all narrower. OK toggles a light, switch, fan or cover, turns on a scene or script, runs an automation or presses a button; one that is off shows dimmed. Locks and alarms are never offered, since anyone in the room can press a TV remote, and covers only when they shade a window (awning, blind, curtain, shade or shutter): not garage doors, gates or doors, nor a cover without a class, as many garage doors are. A script, scene, automation or button runs whatever it's set to do, so don't list one that unlocks a door, opens the garage or turns off an alarm: the same promise as no locks. The address can be `http://` only on your own network (a private address like `192.168.1.20`, or a `.local`, `.lan` or `.home.arpa` name); anything else needs `https://`, and redirects aren't followed.

### Your own photos (optional)

On Google TV and Android TV, with the TV connected over adb (see *Install*), send photos or whole folders from your computer:

```bash
python3 tools/photos.py <TV IP> beach.jpg hills.jpg Holidays/
```

They land in the app's own folder on the TV (no permission needed); on Android 11, which keeps adb out of other apps' folders, they go through Free My TV itself. The next time the home screen, the screensaver or Free My TV's settings open, the app imports each one: turned upright, cropped to 16:9, resized for the TV and saved without its GPS and other tags; what was sent is then deleted. The screensaver shows them in the order you sent them, instead of the picks' photos; without a TMDB key, one of them is the home screen's background, a new one every hour. *Home Settings* → *My Photos*: the arrows turn them on or off, OK removes them. Up to 100; JPEG, PNG and WebP, and HEIC on Android 9 and newer.

#### From an Immich server

With an [Immich](https://immich.app) server, the TV can fetch them by itself. In `config.properties`, set `immich_server` to its address (`http://192.168.1.20:2283` on your network, or its `https://` address), then build and install again. Then make a key in Immich → *Account Settings* → *API Keys* (`asset.read`, `asset.view`, `asset.download` and `album.read` are enough) and send it to each TV that should show your photos (Android 9 and newer):

```bash
python3 tools/send_key.py <TV IP> immich_api_key
```

It asks for the key without showing it, checks it with your server (a search, a preview and an original, as the TV does), puts it in the app's private storage on the TV over adb, and asks the TV whether it arrived. It never goes into the APK, which any app on the TV can read, nor into `config.properties`; `--remove` takes it off the TV. A TV without the key keeps *My Photos* as above.

Every 6 hours, when the home screen opens, a new random choice of up to 40 photos comes in (at least 1080 pixels on their short side), one at a time, through the same import. Nothing goes before something new has come: at 100 the oldest of Immich's make room, and the photos you sent with `tools/photos.py` always stay. *Home Settings* → *My Photos* then chooses where they come from: *Default* (none of yours: the picks' photos), *Immich: all photos* or one of its albums; after a change, the previous ones go as the new ones arrive. TVs with little memory or a 720p screen take Immich's preview of each photo instead of the original.

A server given as `http://` must be on your own network (a private address like `192.168.1.20`, or a `.local`, `.lan` or `.home.arpa` name; the build stops otherwise), and only its address may be reached without HTTPS; anything else needs `https://` (over Tailscale, the server's `https://<machine>.<tailnet>.ts.net` address, which Tailscale can serve with a certificate). The app never follows a redirect from it, so the key only goes to that address.

### Your Plex library (optional)

Set `plex_url` and `plex_token` in `config.properties`, and one of the newest movies and one of the newest shows of your Plex server (another one each time the list is made, a few times a day, among the latest 8 of each kind) join the picks, with their own poster and backdrop, tagged "Plex". A tab shows at most one. Selecting one opens the title's page in the Plex app. If the server can't be reached when the list is made, its titles are left out.

This is for Google TV and Android TV, and it needs the TMDB key and at least one streaming app on the TV: without those no picks come at all, Plex included.

- **`plex_url`** is the server's own `plex.direct` address, which has a valid certificate (the TV checks it as for any site; plain `http` and any other address are ignored, so a typo never sends your token anywhere). It looks like `https://192-168-1-20.0123456789abcdef0123456789abcdef.plex.direct:32400`: your server's local IP with dashes, then its id. Open any item in Plex's web app, *Get Info → View XML*, and the address bar has it, before `/library/...`.
- **`plex_token`** is on the same address, after `X-Plex-Token=`. It gives access to your Plex account, and any app on the TV can read the APK it is built into, so use the token of a Plex Home *managed user* with access to your libraries, not your own, and never commit it.

The TV talks to your server directly on your network. If the server can't be reached, the other picks load as usual.

## Going back

On the TV: *Free My TV* → *Home Screen* → *Original*. The factory home screen, screensaver and every app come back exactly as they were. If the new home screen ever crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall *Free My TV* (Settings → Apps). Uninstalled it while it was on? Install it again and pick *Original*: the list of what it turned off is kept in the TV's settings, so everything comes back.

## What it changes

- It becomes the home screen and, unless `screensaver=false`, the screensaver.
- Picking Continue Watching in Home Settings grants `READ_TV_LISTINGS` with `pm grant` through the TV's own ADB; *Original* takes it back with `pm revoke`. Pictures come only from the TV (`content://`, `android.resource://`), never from the network.
- It turns off, without uninstalling:
  - the stock home screen and screensaver (the screensaver stays with `screensaver=false`);
  - Google TV's recovery home, Play Games and YouTube Music;
  - on TCL TVs, messages, store demo, hotel menu, manuals, mirroring and logging apps;
  - on Philco TVs, AI PONT (promo channels and app downloads) and the manual;
  - on Multilaser TVs, Anoki's screen-content recognition (ACR), the store demo and the manual;
  - on KTC-made TVs (NextStar), Anoki's screen-content recognition (ACR), the store demo and the manual;
  - Google Assistant, only with `disable_assistant=true`.
- On Google TV, the Assistant's results for films and shows come from Google TV's home app, which is off in Free My TV mode: asking it to play a show can end in "Sorry, I don't understand", and a search can show only YouTube videos. Opening apps, questions and apps' own voice search (YouTube's, for one) still work; *Original* brings it all back.
- On TCL TVs, the remote's Netflix, YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons. Pick what each one opens in *Free My TV* → *Remote Buttons*; *Original* turns the service off. The Settings button opens TV Settings; in *Remote Buttons* it can open *Quick Settings* instead, a panel over what's playing (Inputs, Display, Sound, Network, Bluetooth, Accessibility and all settings) in place of Google TV's, which is part of its home screen. On TCL TVs with Android 11 the service stays off, since with it the TV moved the volume two steps per press: there the buttons work on Free My TV's home screen. On Vestel-made TVs (F&U and other store brands) and Xiaomi (Formovie) projectors the same goes for the Netflix button, and on KTC-made TVs (NextStar) for the Tabii button. On Sony TVs, the BRAVIA CORE, Netflix, Disney+, Prime Video, YouTube and Crunchyroll buttons (and Google Play or SonyLIV, on remotes that have them) can each open the app you pick, or keep Sony's default. On Sony's Google TV models, where those buttons went through Google TV's home screen, they open their own apps unless you pick otherwise. On Xiaomi TVs whose remote has a Mi button instead of Home, that button opens Free My TV instead of PatchWall (or, if you pick it, PatchWall as before, or another app). On Xiaomi TVs, the YouTube button opens YouTube again (Google TV's home screen answered it), and the PatchWall button can open the app you pick. On Xiaomi TVs sold in Russia, the same goes for the Okko, VK, Rutube, KION, Wink and IVI buttons, which keep Xiaomi's default until you pick another app. On Hisense TVs, the Prime Video, YouTube, Disney+, All Apps, Settings and profile buttons, which Google TV's home screen answered, open what you pick (All Apps opens Free My TV's own; Netflix stays with the TV). On the Google TV Streamer, the YouTube, Netflix and ★ buttons can each open the app you pick or Free My TV's All Apps: YouTube opens YouTube (Google TV's home screen answered it, and ★ too), and Netflix and ★ keep Google's default until you pick something, so ★ does nothing in Free My TV mode until then. On the JVC G24 (Skyworth), whose Inputs and Settings buttons need Google TV's home screen, Inputs opens the TV's list of inputs and Settings a small Quick Settings panel (Picture, Sound, Inputs, Wi-Fi, Bluetooth and TV Settings). On any of these TVs, setting every button to *Nothing* (or the brand's default) turns the service off.
- With *Home Settings* → *Now Playing widget* on (it starts off), what an app is playing shows in place of the prices while it plays, on the home screen and the screensaver. Android hands that only to a notification listener, so turning the widget on allows ours through the TV's ADB; turning it off, *Original* and the switch back after crashes take it away. Where the TV refuses it, the row says so.
- On HY300 projectors, whose stock home screen was the only way to them, the bottom drawer gets *Keystone* (keystone, zoom and projection mode) and *Screen Mirroring* (the projector's Miracast receiver), and HDMI opens through the projector's own player.
- *Home Settings* → *Recent Apps* (off by default, Google TV and Android TV) puts *Recent Apps* in the bottom drawer, and on the remote's app-switch key where it has one; *Drawer and Home twice* also opens it with two quick presses of Home (not on Xiaomi TVs, whose remote sends one press as several), and *Top Buttons* can add it as a round button. It shows the open apps over the blurred home screen: OK opens one, ▲ closes it. Closing stops the app (`am force-stop`), so it starts from scratch next time; the TV's own pieces, what its inputs and remote need, apps whose accessibility service is on, and the keyboard can't be closed. It reads the list only while it's open, with `dumpsys activity recents` and `ps -A` through the TV's ADB.
- On Google TV, Android TV and Fire TV, hold OK on a top button or shelf app to move it directly on Home: ◀ ▶ changes its position, OK saves, and Back cancels. Leaving Home also cancels an unfinished move. A short press still opens the item; the Shelf and Top Buttons editors in Settings remain available.
- *Free My TV* → *Top Buttons* → *App Store* (Google TV, Android TV and Fire TV) adds a shopping-bag shortcut to the top of the home screen. It opens Google Play or, on Fire TV, Amazon Appstore. OK adds or removes it; ◀ ▶ moves it next to Live TV or wherever you prefer. It starts off, and is unavailable when neither supported store can be opened.
- *Home Settings* → *At power-on* (Google TV and Android TV, where the TV has live TV) decides what shows after the TV starts from off; waking from standby is left alone. *TV's choice* (the default) changes nothing. *Home screen* gives the screen back to Free My TV when the TV's live TV app puts itself on top during the start, as some MediaTek TVs do, but only if no key has been pressed since the home screen came up. *Live TV* opens live TV about 10 seconds after the home screen comes up, unless a key was pressed first (on Sony, Sony's TV app, which remembers the last input and channel).
- *Free My TV* → *Sleep Timer* turns the TV off after 5 minutes to 4 hours, with the minutes left over any app (top center, or the corner you pick). It presses the remote's Sleep key through the TV's own ADB, so it works in Free My TV mode; *Original* stops it.
- Picture, sound, inputs, remote pairing, Google services and the Play Store are on a protected list, checked before every change, and so are Android's own pieces and factory menus. Before anything goes off, the TV is asked whether its inputs or its remote's Input key depend on it; those stay on.
- On Google TV, the setup app goes off before the stock home screen and comes back after it, so its recovery screen never takes over the Home button in between.
- Apps that saved a Wi-Fi network are never turned off, since Android would erase the network. If the TV's first setup saved yours, the switch asks you to forget the network and connect again in Settings first (saved networks out of range don't show there; Claude Code can remove them).

## Any TV

Free My TV runs on Google TV and Android TV: TCL, Sony, Hisense, Philips, Xiaomi, Vestel-made TVs (F&U), KTC-made TVs (NextStar) and more, plus streamers like Chromecast with Google TV, the Google TV Streamer, onn 4K, the Xiaomi Mi TV Stick and NVIDIA Shield, and projectors like the Wanbo T2 Ultra. And on [LG](#lg-tvs-webos) and [Samsung](#samsung-tvs-tizen) TVs, as an app.

**Huawei TVs (HarmonyOS) and the Kalley 43G200:** there Free My TV stays an app you open. After the switch, one of each didn't start again (the Huawei came back only with a factory reset), so the switch refuses on them, and one already switched by an older version goes back to *Original* by itself. On Fire TV it's an app too: Amazon's home screen keeps the Home button.

**Any other TV:** plug a Google TV streamer into HDMI and free that one. Same steps, same home screen.

**A different brand or model:** ask Claude. Free My TV is made to be adapted by AI. Open the folder in Claude Code and say *"set it up for my Sony"*: it reads your TV, finds its home screen and its promo apps, updates the lists below and walks you through a test. [CLAUDE.md](CLAUDE.md) tells it how.

| What | Where |
|---|---|
| Apps to turn off | `DEBLOAT` in [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) |
| Apps never to touch | `PROTECTED` in the same file |
| Apps on the shelf | `SHELF` in [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) |
| Your brand's live TV app | `LIVE_TV_APPS` in the same file |

## Make it yours

It's your code, and Claude Code finds its way around it. Try:

- *"Add Crunchyroll to the shelf."*
- *"Show the prices in euros."*
- *"Add Catalan."*
- *"Switch to the night look an hour after sunset."*

## How it works

- **A plain Android app, no Gradle, no libraries.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign → apksigner and makes a 2 MB APK.
- **It uses the TV's own ADB, from inside the TV.** The app connects to `127.0.0.1:5555` with its own key (that's the *Allow USB debugging?* prompt) and runs the same commands you would: `cmd package set-home-activity`, `settings put secure screensaver_components`, `pm disable-user`, `pm grant` / `pm revoke` for Continue Watching, and, while Recent Apps is open (it starts off), `dumpsys activity recents`, `ps -A -o NAME`, `settings get secure enabled_accessibility_services` and `default_input_method`, and `am force-stop` for an app you close. Nothing listens to the network.
- **Drawn by hand on one Canvas**, with Android 13 shaders for the frosted glass and the sky (plain fills on Android 8 to 12, with lighter shadows and no GPU layers on 8 to 11 for their older GPUs).
- **A safety net.** A watchdog in a separate process brings back the stock home screen after three crashes in five minutes, and a boot check keeps your choice after TV updates.
- **On LG and Samsung, the same design in plain HTML, CSS and JavaScript** ([`web/`](web)), written for the Chromium of older TVs, with no frameworks. [`webos/build.py`](webos/build.py) packs it with `config.properties` into an `.ipk` without LG's tools, and [`webos/install.py`](webos/install.py) installs it through Developer Mode; on LG it lists and opens apps through the TV's own service bus (Luna), as LG's launcher does. [`tizen/build.py`](tizen/build.py) packs the same app into a Samsung `.wgt`, which uses Tizen's web APIs for the apps and the inputs. To preview it on a computer: `python3 webos/build.py --preview`, then open `out/webos/index.html` (a stand-in lists sample apps).
- **Continue Watching** reads Android TV's Watch Next list (what apps share about what you're watching). Only picking it grants `READ_TV_LISTINGS` through the TV's ADB; *Original* revokes it. Pictures load only from the TV (`content://`, `android.resource://`), never from the network.
- **Data** comes only from TMDB (trending titles, new releases and which app streams what in your country), Open-Meteo (forecast, sunrise and sunset), optionally Binance (24-hour prices when `show_crypto=true`), optionally Watchmode (the picks' own pages in their apps, with a `watchmode_key`), optionally your Plex server (with a `plex_url`), optionally your Immich server (your photos, with an `immich_server` and its key) and optionally Trakt (what you're watching and your watchlist, with a `trakt_client_id`, once you sign in on the TV).

| File | What it is |
|---|---|
| [`HomeView.java`](app/src/io/freemytv/HomeView.java) | The home screen |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | Switching between Free My TV and Original |
| [`Adb.java`](app/src/io/freemytv/Adb.java) | The TV's own ADB, from inside the TV |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | The picks (TMDB) |
| [`KeyPage.java`](app/src/io/freemytv/KeyPage.java), [`Qr.java`](app/src/io/freemytv/Qr.java) | The TMDB and Watchmode keys sent from a phone, and the QR code |
| [`WatchNext.java`](app/src/io/freemytv/WatchNext.java) | Continue Watching from the TV's lists |
| [`WatchArt.java`](app/src/io/freemytv/WatchArt.java) | Continue Watching pictures, only from the TV |
| [`HomeData.java`](app/src/io/freemytv/HomeData.java) | Weather and prices |
| [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) | The shelf, inputs and All Apps |
| [`DreamView.java`](app/src/io/freemytv/DreamView.java) | The screensaver |
| [`SettingsActivity.java`](app/src/io/freemytv/SettingsActivity.java) | The settings screen (the app's icon) |
| [`Prefs.java`](app/src/io/freemytv/Prefs.java) | Settings changed on the TV |
| [`RemoteKeys.java`](app/src/io/freemytv/RemoteKeys.java) | The remote's app buttons (TCL, Sony, Xiaomi and others) |
| [`RecentApps.java`](app/src/io/freemytv/RecentApps.java), [`ScreenBlur.java`](app/src/io/freemytv/ScreenBlur.java) | Recent Apps, over a blurred copy of the screen |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | The safety net |
| [`web/home.js`](web/home.js) | The home screen on LG TVs |
| [`web/tv.js`](web/tv.js) | The TV's apps and inputs, on LG and Samsung |
| [`webos/build.py`](webos/build.py), [`webos/install.py`](webos/install.py) | The LG app package and its install |
| [`tizen/build.py`](tizen/build.py) | The Samsung app package |

The website ([freemytv.io](https://freemytv.io)) is in [`docs/`](docs): plain HTML, CSS and JS on GitHub Pages.

## Translations

On Google TV, LG and Samsung, each language is one file, [`app/assets/i18n/<lang>.json`](app/assets/i18n), keyed by the
English text in the code. *Automatic* follows the TV's language and uses any language that has a file (English otherwise); *Home
Settings* → *Language* lists them all. Today: English, Portuguese, Spanish, German, French, Italian, Dutch, Polish,
Swedish, Danish, Norwegian, Finnish, Czech, Romanian, Turkish, Russian, Ukrainian, Greek, Japanese, Korean, Simplified
and Traditional Chinese, Hindi, Indonesian, Vietnamese and Thai. Portuguese, Spanish, German, Russian, Thai and Turkish were translated by hand and are the
reference (fix them by hand); the others were machine-made, and corrections from native speakers are welcome.

- **Add a language:** `python3 tools/translate.py --new ca`, then fill `app/assets/i18n/ca.json` by hand, or with
  `ANTHROPIC_API_KEY` set, `python3 tools/translate.py --fill --lang ca` (only the strings go to the API).
- **After changing text in the code:** `python3 tools/translate.py` lists what each language lacks; `--fill` translates
  only that (the hand-translated ones, Portuguese, Spanish, German, Russian, Thai and Turkish, are left for a person
  unless `--lang` names them), `--prune` drops strings the code no longer uses.
  Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
- Text built at runtime has placeholders (`"{n} min ago"`), so each language puts the words in its own order. A key
  like `"unit|Automatic"` is the same English in another place, where a language may need another word.
- Counts have plural forms: `"plural|one:{n} season|other:{n} seasons"` is translated with one form per plural
  category the language uses (Russian `"one:{n} сезон|few:{n} сезона|many:{n} сезонов|other:{n} сезона"`, Japanese
  just `"other:{n}シーズン"`), and Android's plural rules pick the one for each number.
- **Limits:** right-to-left languages (Arabic, Hebrew, Persian) would need the layout mirrored, so they aren't there
  yet. Chinese, Japanese, Korean and Hindi are drawn with the TV's system font (Inter has no such letters).

## Privacy

No accounts, no analytics, no tracking. The app talks to TMDB, Open-Meteo (with the coordinates you set, or your time zone's main city), and Binance only when `show_crypto=true`, Watchmode only when you set a key, your Plex server only when you set a `plex_url`, your Immich server only when you set one, and Trakt (api.trakt.tv) only when you set a `trakt_client_id` and sign in on the TV. With `home_assistant_url` set and *Home Settings* → *Home Assistant* on, it talks to that server, yours, and to no other: only while the bottom drawer is open, to read the states of the entities you listed and to call the one you press. Turned off, nothing is asked of it, and the names it read are forgotten. On Google TV and Android TV, the APK from the release asks GitHub (api.github.com) once a week, or when you press *Updates*, for the latest release, and when there's a newer one downloads it from github.com and GitHub's download server (release-assets.githubusercontent.com); a build you made yourself asks only when you press *Updates*. The *Now Playing widget* reads what apps are playing (title, artist, the app) through a notification listener, the only way Android shares it: Free My TV allows the listener only while the widget is on, never reads a notification with it, and asks Android to unbind it as soon as it connects, so it stays unbound. What's playing never leaves the TV. While *Home Settings* → *TMDB key* or *Watchmode key* shows its QR code (5 minutes at most), the TV answers on your home network at that code's address, for one key; the key goes only to that service, to check it. Continue Watching (Home Settings) reads what your apps note on the TV itself and shows only pictures already on the TV: it downloads nothing. *Home Settings* → *Status icons* reads only whether the TV has Wi-Fi or Ethernet, a Bluetooth audio device and a mounted USB drive, and only while the row is on: on Android 12 and newer it asks for *Nearby devices* (BLUETOOTH_CONNECT) for the Bluetooth part, and none of it leaves the TV. With *Shelf* → *Shelf order* on *Last opened first* (Google TV and Android TV), the TV keeps a list of the apps you opened from Free My TV, newest first and without times, only to order the shelf: it never leaves the TV, and switching back to *My order* deletes it. Your settings stay on your computer and your TV.

## Credits

- Built with [Claude Code](https://claude.com/claude-code).
- The LG version builds on what [@danielthejoker18](https://github.com/danielthejoker18) found in [FreeMyTvWebOS](https://github.com/danielthejoker18/FreeMyTvWebOS) (MIT): installing through Developer Mode and opening apps through the TV's service bus.
- On Samsung TVs it installs with [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller) by [@reisxd](https://github.com/reisxd).
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Links to the titles in the streaming apps by [Watchmode](https://api.watchmode.com/).
- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Prices from the [Binance](https://www.binance.com/) public API.
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/) (SIL OFL 1.1). Coin logos: [cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons) (CC0). Samsung app logos: [Simple Icons](https://simpleicons.org) (CC0).
- The QR code encoder follows Project Nayuki's [QR Code generator](https://www.nayuki.io/page/qr-code-generator-library) (MIT).
- Not affiliated with Google, TCL, LG, Samsung or any streaming service. App names and logos belong to their owners.

## License

[MIT](LICENSE). Fork it, change it, ship it.
