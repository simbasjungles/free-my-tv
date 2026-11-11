# Contributing to Free My TV

Thanks for helping. The full notes for the code are in [CLAUDE.md](CLAUDE.md) (written for any assistant or person);
this page is the short version.

## Before you push

```bash
tools/check.sh            # everything CI runs
tools/check.sh android    # only the Google TV build
tools/check.sh web        # translations, the LG and Samsung packages, ES5 in web/
```

CI (`.github/workflows/check.yml`) runs the same script with JDK 17, build-tools 34.0.0 and android-34. A newer SDK on
your computer can let through a call that fails there.

## What to keep

- **Small and fast.** A plain Java app with no Gradle and no libraries, Java 8 language level, minSdk 26. `tools/check.sh`
  fails when `FreeMyTV.apk` is over its size budget (`APK_MAX_BYTES`): if a change needs the room, say why in the pull request.
- **The way back.** *Original* and the watchdog must keep working. Never uninstall system apps; only `pm disable-user
  --user 0`, through `HomeMode`. The Always list in CLAUDE.md has the rest.
- **No secrets.** Never commit `config.properties`, `*.keystore` or any key.
- **LG and Samsung** (`web/`) must stay ES5 with no CSS variables, grid or backdrop-filter: `tools/check.sh web` checks it.

## Text and languages

On-screen text goes through `Ui.tr("English")` (and `web/strings.js` on LG and Samsung). After changing text, run
`python3 tools/translate.py`: it lists what each language lacks. `python3 tools/translate.py --check` validates the
catalogues. Hand translations (Portuguese, Spanish, German, Russian, Thai, Turkish) are never replaced by machine ones.

## Your TV

Report how Free My TV runs on your TV with the **My TV (works or doesn't)** issue form. A good report becomes a file in
[`docs/devices/`](docs/devices/). Nothing private in a report: no TMDB key, Plex token, Wi-Fi name or account.

## Pull requests

One change per pull request, and say which TV you tested on: anything that turns apps off or changes Home or the
remote's keys needs a real TV, and the template asks for it.
