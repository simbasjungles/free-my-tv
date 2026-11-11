## What and why

<!-- One change per pull request. Say what changes for the person using their TV, and why. -->

## Checked

- [ ] `tools/check.sh` passes (Google TV build, translations, LG and Samsung packages). CI runs the same script.
- [ ] The APK is still under its size budget (`tools/check.sh` prints it). If it grew, say why here.
- [ ] New on-screen text goes through `Ui.tr` (Java) or `strings.js` (LG and Samsung), and `python3 tools/translate.py` shows what the other languages lack.
- [ ] No keys, tokens, `config.properties` or `*.keystore` in the change.

## Tested on

<!-- Which TV (brand, model, Android/webOS/Tizen version) you ran this on, or "not on a TV: tests and build only".
     Anything that turns apps off, changes Home or the remote's keys needs a real TV. -->
