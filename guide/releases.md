# Releases

Done by the owner, on the Mac that holds the release key.

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

