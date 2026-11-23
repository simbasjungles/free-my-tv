# LG and Samsung web app: notes

The TVs' quirks behind `web/`, `webos/` and `tizen/`. The rules that apply everywhere are in [CLAUDE.md](../CLAUDE.md).

- LG and Samsung (`web/`): plain ES5 JavaScript and CSS without variables, grid or backdrop-filter, since older TVs
  (webOS 3 and 4, Tizen 3) run a Chromium from 2015 to 2017 (`tools/check.sh` greps for all of these); only transform
  and opacity animate. Tizen 2.4 (2016) runs WebKit 538: no Intl (`fmt` in data.js rounds by hand), SVGs need a width
  and height, and `tv.js` adds a `lite` class there that turns off shadows and transitions, which its GPU can't keep up
  with (LGs whose `navigator.deviceMemory` reads 1, up to about 1.5 GB, get it too: webOS TV Lite sets went from 25 to
  60 fps at a ratio of 2, #318, and from 45-50 to 59 at a ratio of 1, #383; Samsungs too, where a 1.5 GB UN50AU7700 at a
  ratio of 1 still stuttered with `flat` alone, #461; webOS 1 to 3, Chromium 38 with no `deviceMemory`, get it with
  `layered`, each tile and pick on its own compositor layer, measured on a 4K 60UH6150, #476); Tizen 2.4 also
  can't open the TV's settings (`TV.canOpenSettings`). Chromium before 62 (webOS 3 and 4, Tizen 3) draws a variable
  font's weights alike, so `web/fonts/` has Inter in six fixed weights (rsms/inter 4.1) and each `@font-face` falls
  back to `InterVariable.ttf` where WOFF2 isn't known (#477). Without Intl, `toLocaleDateString` ignores its options and
  writes the whole date, so `strings.js` takes weekday and month names from the catalogues there (`I18N.dateNames` in
  data.js: `weekday|`, `month|` and `monthday|` keys, the last in the form next to a day). Tizen 2.3 and 2.4 don't
  trust Let's Encrypt's ISRG root, so Open-Meteo falls back to plain http there (`getMeteo`; only coordinates and the
  forecast go in the clear). On Tizen 2.3 (2015) Netflix, YouTube and Amazon are native apps whose ids start with a
  number (`11101200001.Netflix`, `111299001912.YouTube`, `111299001578.Amazon_Video`, listed as "Amazon Video"):
  `tizen.application.launch` doesn't open them, the TV's DIAL server does (`POST http://127.0.0.1:8001/ws/apps/<name>`,
  `dialLaunch`). On Tizen 3 and 4 (2017 and 2018) `launch` answers success for Netflix and it stays black (#257); Smart
  Hub's number for it through the same server's REST API opens it, as phones do (`POST
  /api/v2/applications/11101200001`, `hubLaunch`; the TV may ask once whether to allow it). On Tizen 9, some
  broadcasters' apps don't open from another app at all (#469): BBC iPlayer (`DqEoaplKlw.bbchybrid`, a 45 KB "hybrid"
  shell that loads the BBC's hosted app; a 2025 QE50LS03F) and SVT Play (an S95F) leave Free My TV and land on Smart
  Hub's home screen, with no error to catch. Tried from the page, all the same: `launch`, `launchAppControl` with the
  default and the `eden_resume` operations (with and without a `PAYLOAD`), `launch` after `hide()`, and DIAL on port
  8001 (`/ws/apps/iPlayer`, refused); the REST API answers 404 for it, by Tizen id and by Smart Hub number. Smart Hub
  itself and `0 was_execute` over SDB open it. Samsung documents no other call a page can make. Untried: DIAL on port
  8080 under the app's registered name (`app_runtitle` in `0 vd_applist`: `uk.co.bbc.iPlayer`), and a service inside
  the package talking to the TV's own SDB port, as TizenBrew's does: a new piece of the app, so the owner's call.
  `web/tv.js` is the TV's side: on LG, Luna (`listLaunchPoints`, `launch`, the inputs; where webOS 6 and newer deny `listLaunchPoints`
  to Developer Mode apps, the list comes from the TV's remote control API on `ws://127.0.0.1:3000` once the TV has
  allowed it (it asks once; a "no" isn't asked again; firmware that gives the page its own origin,
  `file://io.freemytv-webos`, gets 1008 "invalid origin" there, and the call goes through `webos/service`, a JS
  service in the package whose handshake says `file://`; webOS 22 with 2026 firmware tested), and until then from
  `getAppLoadStatus` over a list of known app ids, which shows the logo bundled in `web/icons/` where there is one, otherwise the name on the brand's color);
  on Samsung, `tizen.application` (`getAppsInfo`, `launch`), the `VIDEOSOURCE` property and
  `tizen.tvwindow.setSource` for the inputs, then Samsung's viewer (`org.tizen.tv-viewer`) to show them. The viewer
  plays whatever it played last, Samsung TV Plus's channels too, so Live TV tunes the antenna first (`tuneAntenna`,
  `tizen.tvchannel`, the `tv.channel` privilege): the last antenna channel seen when the home screen comes back
  (`noteChannel`, `fmtv.channel`), else the first one (a 2023 QN55Q60C, Tizen 9.0, went to TV Plus before; the one TV
  it was tried on). Samsung took `tizen.tvchannel` out of its API reference in 2019 (it was public, since Tizen 2.3):
  where it's missing, refuses, or a list or the tuner doesn't answer in 2.5 s, the viewer opens as before. With a
  `watchmode_key`, a Prime Video pick opens its title (`TV.takesTitle`, `samsungDeepLink`: the `eden_resume` operation
  with `{"values": "<GTI>"}` under PAYLOAD, the GTI from Watchmode's web address, #483). Disney+ (Tizen 9: the entity
  id, the address, eden_resume or view, running or not), Netflix and HBO Max open at home, so only Prime is looked up,
  and only when a pick is opened (`Data.titleLink`). Samsung's own
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
