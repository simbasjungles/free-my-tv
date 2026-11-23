# Installing it on an LG TV (webOS)

On LG it's an app, installed through LG's Developer Mode, and it changes nothing else on the TV: LG's home screen
stays, and Free My TV opens with a long press of a number key.

1. **Ask** for the TV's IP address and their city, if they didn't say. Tools: Python 3 and OpenSSH (`ssh`,
   `ssh-keygen`), already on macOS, Linux and Windows 10+.
2. **On the TV** (they do it): LG Content Store → *Developer Mode* → sign in with a free LG developer account
   (webostv.developer.lge.com) → *Dev Mode Status* on (the TV restarts) → *Key Server* on. It shows the IP and a
   passphrase.
3. **Settings:** the same `config.properties` as on Android (the first `python3 webos/build.py` creates it). The
   TMDB key rules (step 4 of [install-android.md](install-android.md)) apply.
4. **Build:** `python3 webos/build.py` must end with `Built FreeMyTV-webos.ipk`.
5. **Install:** the first time needs the TV's passphrase, typed by them: ask them to run
   `python3 webos/install.py <IP>` in their own terminal (yours can't answer its prompt, and the passphrase doesn't go
   in the chat). It keeps the unlocked key in `~/.ssh/freemytv_webos_<IP>` (dots as underscores), so later updates
   run from your terminal with the same command. The TV's SSH host key is kept in `~/.ssh/freemytv_known_hosts` and
   checked from then on (a TV whose Developer Mode was set up again is refused until its line there is deleted, #474),
   and a TV that got a new address uses the newest key saved, with the Key Server off (`install.saved_key`, #475; also
   `renew.py` and `apps.py`). On a TV that isn't 4K (`getSystemInfo` says `"UHD":"false"`), it
   changes the package's `resolution` to 1280x720 before copying it: LG's Full HD and HD sets draw apps at that size,
   and a 2019 HD one (webOS 3.9) showed a 1920x1080 app in its top-left two thirds (#402). `--1080p` installs it as
   built; `python3 webos/build.py --hd` builds it at 1280x720.
6. **Tell them:** while Free My TV is open, holding a number key pins it to that key (on a remote without number keys,
   it's in LG's app list on the home screen). Developer Mode lasts about 40 days; *EXTEND* in that app renews it, or
   `python3 webos/renew.py <IP>` from the computer that installed it, on a schedule if they like (if it runs out, the TV
   removes developer apps, and installing again brings it back). `--uninstall` removes Free My TV.
7. **Empty shelf on webOS 3** (user agent Chrome 38): the app can't list the TV's apps there (`listLaunchPoints`
   denied, no `getAppLoadStatus`, and the remote control API answers `invalid origin` to file:// pages). Run
   `python3 webos/apps.py <IP>`: the TV asks them once to allow it, then build and install again. It writes
   `webos-apps.local.json` and `webos-icons.local/` (git-ignored), which only the LG build packs, never `--generic`.
   Again after they install or remove apps. Where the TV resets plain connections on port 3000 (a 2024 set, #355), it
   uses the same API over TLS on 3001, and fetches the icons over https (#357).

