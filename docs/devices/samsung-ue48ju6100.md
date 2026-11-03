# Samsung UE48JU6100 (2015, Tizen 2.3.0)

Report: [#317](https://github.com/simbasjungles/free-my-tv/issues/317).

- **Device:** Samsung UE48JU6100, 48" UHD, 2015 "J" series, EU. The TV's own display name reads `[TV] UE48JU6060`.
- **Platform string:** `15_HAWKM_UHD_2D_BASIC_FRC`. **OS:** Tizen 2.3.0, below the README's stated floor of 2.4. armv7, wired network.
- **Free My TV version:** 1.2.0, built from `main` including #267 (`tizen-2.4-compat`). A build from before that commit is untested.

## Install

- The only change needed was `required_version="2.4"` → `"2.3"` in `tizen/config.xml`. `web/` needed no changes.
- A plain self-signed Tizen certificate was accepted: no Samsung account, no DUID.
- `tizen/install.py` worked unmodified. The TV reports no `sdk_toolpath`, so it pushed to `/opt/usr/apps/tmp`.
- `secure_protocol: enabled` did not interfere. Updates signed with the same certificate install over the old build.

## Works

- The home screen draws; the day/night look and the weather card with the configured city work.
- Picks loaded with a TMDB key and `tmdb_region=PT`.

## Not checked

The screensaver over a long period, every drawer entry, every settings screen.
