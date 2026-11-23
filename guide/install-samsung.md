# Installing it on a Samsung TV (Tizen)

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
   the login Keychain). Certificate passwords are theirs: they type them in their own terminal, never in the chat. On Linux those passwords live in the GNOME keyring: `tizen package` reads them by running `secret-tool`, which a headless shell (a WSL install, a container) has not, and `security-profiles add` writes no `.pwd` file, so signing stops at `CertificationException: Invaild password` even though `keytool` opens the `.p12` with that same password. Leaving the profile item's `password=""` makes the CLI ask instead, and answering that prompt (a pseudo-terminal, not a pipe: without a console it throws a NullPointerException) signs it.
5. **Settings:** the same `config.properties`; the TMDB key rules (step 4 of [install-android.md](install-android.md)) apply.
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
   with another certificate and not shown on the TV: `--uninstall`, then install again (2016 KU6640 and KU6400, #349;
   the KU6400 took the Tizen certificate after that, while a Samsung one, Public with its DUID, kept getting 118). Still
   118 after that, for a package id the TV has never seen and from Samsung's own `tizen install` with an install
   permit: first try what fixed a UA40K5300 on the same platform, a SHA-256 signature and a Tizen certificate
   ([`tizen/install-118.md`](../tizen/install-118.md), #417; recent Tizen Studios sign with SHA-512, which 2.3 and 2.4 reject). The
   signer patch there changes their Tizen Studio, so ask first. If it still fails, the model refuses developer installs
   (a 2016 Full HD K5670, `16_HAWKM_FHD`, #349): suggest a Google TV streamer on HDMI.
   `download failed[116]` comes within milliseconds, before any certificate check, for any package: the TV has too
   little storage left for apps. A 2020 UE55TU7020 (#490) even refused a minimal app, while TizenBrew Installer said
   "Package installed" anyway; with ~70 MB freed (Settings, Support, Device Care, Manage Storage) and a restart, the
   install went through. `install.py` says so.
   On a 2015 UN55JU6800 (Tizen 2.3, `15_HAWKM_UHD_2D_SC`) every package asking for `tv.inputdevice`, `application.launch`, `tv.window` or the Samsung `network.public` privilege was refused with `install failed[PKGMGR_ERROR]` at the same point, while the same files without them installed; changing the manifest also has to go through `--uninstall` first, since the TV refuses a changed one as an update. Once that TV was signed into a Samsung account, the build carrying none of those privileges launched apps and switched to the TV source anyway, so try that before buying a certificate: the same set does not trust the `VD DEVELOPER Partner CA Class` that issues them now, and refused even a minimal package signed with one (the DUID from port 8001 published as `uuid:` was accepted by the certificate tool).
   With Samsung's tools instead: `sdb connect <IP>`, `tizen install -n FreeMyTV-tizen.wgt -t <serial from sdb
   devices>` and `tizen run -p FreeMyTVio.FreeMyTV -t <serial>`. Updates need the same certificate: the TV refuses
   one signed by another (uninstall first). A retail TV's sdbd only takes files (`filesync_support:push`): nothing
   can be pulled from it. To look inside the running app, `0 debug FreeMyTVio.FreeMyTV 300` over SDB opens it with
   a Web Inspector port (Runtime.evaluate works on Tizen 3; screenshots don't; Tizen 2.4 doesn't answer it, nor
   `0 vd_applist`). Tizen 2.3 (a 2015 UE48JU6075) answers it on port 7011, one WebSocket connection per launch
   (`ws://<IP>:7011/devtools/page/1`; `0 kill FreeMyTVio` first to launch it again), and `0 applist` lists its web
   apps (not the native ones: see Tizen 2.3 in [web-notes.md](web-notes.md)).
8. **Tell them:** *Autorun Last App* in the TV's settings (under Start Screen Options; where it is changes by year)
   makes the TV turn on into Free My TV on a 2017 set; 2019 and 2020 sets don't reopen Developer Mode apps with it
   (#356), so there they move it to the first place in the Home bar. A Tizen Web Service declared `on-boot` that
   reopens it when the TV comes on worked on a 2019 RU7099 and a 2020 Q80T (a fork's opt-in build, #356), but only
   signed with a Partner-level distributor certificate bound to that TV's DUID, which the person requests from Samsung
   with their own account (Samsung decides who gets that level): the release and TizenBrew's builds can't carry it,
   a build with another author certificate means uninstalling first (Home Settings and the TMDB key go with it), and
   certificates, their passwords and the DUID never go in the chat.
   *Home button* (`samsung_home_button=true` in their own build, then Home Settings, `Prefs.homeReturns`;
   `TV.canReturnHome`, not Tizen 2) brings Free My TV back when Home is pressed in an app opened from it: no app may
   take Samsung's Home key, so `watchBack` asks the TV's own server (`GET 127.0.0.1:8001/api/v2/applications/<id>`,
   `visible`) every 0.4 s while that app is in front, and once it isn't (twice in a row), `comeBack` opens Free My TV
   as an app control. It can't tell Home from anything else that takes the screen (another input, an app opened by
   voice or from a phone). Tested on a 2023 QN55Q60C (Tizen 9.0). What it needs: `background-support="enable"`
   (without it the page freezes once hidden, and nothing is asked), the manifest's `<tizen:app-control>` with
   `reload="disable"` (without it the page loads again: 2 to 5 s instead of 0.8), and the app's Smart Hub number.
   The two manifest pieces go in only with that setting (`manifest_for` in `tizen/build.py`), never in the release,
   until someone has installed and updated such a build on older TVs: every TV reads the manifest, the feature on or
   off; Samsung's FAQ says to leave `background-support` on "disable" (the page then runs behind every app: `resting()`
   in home.js keeps it from fetching or drawing there); and Tizen's reference says `reload` with `required_version`
   under 2.4 fails to install (ours is 2.3; Tizen 9 took it anyway, and Samsung's own guide for 2015 TVs pairs it
   with a `devel.api.version` 2.4 metadata line, untried here). The Smart Hub number (`HUB_IDS`; `0 vd_applist` over SDB lists each `app_id`): by its Tizen id the server doesn't
   know the built-in apps, and says YouTube (`9Ur5IzDKqV.TizenYouTube`, which runs as `com.samsung.tv.cobalt-yt`) is
   never visible. The app's own status listener (`addAppStatusChangeListener`) says nothing there, and a POST to that
   server from the app is refused (from a computer it works). A Tizen Web Service could watch without the page, but it
   needs a Partner certificate (see `on-boot` above).

