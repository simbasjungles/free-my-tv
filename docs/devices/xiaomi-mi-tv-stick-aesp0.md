# Xiaomi Mi TV Stick (MiTV-AESP0)

Report: [#279](https://github.com/simbasjungles/free-my-tv/issues/279), by @Yoshi-404.

- **Device:** Xiaomi Mi TV Stick (1st gen), model `MiTV-AESP0`, codename `aquaman`.
- **Platform and OS:** Google TV / Android TV 9 (SDK 28), armeabi-v7a (32-bit), about 1 GB of RAM.
- **Free My TV version:** v1.2, release APK.

## Install

- USB debugging alone was enough. Port 5555 opened directly: `adb connect <IP>:5555` worked with no pairing.
- Accepted the computer's *Allow USB debugging?* prompt; `adb install -r FreeMyTV.apk` installed cleanly.
- Free My TV's own ADB prompt on the TV was accepted without trouble.

## Works

- The Home button switches to Free My TV; the stock launchers (`com.mitv.tvhome.atv`, `com.google.android.tvlauncher`) are handled.
- Smooth and responsive on 1 GB of RAM with about 60 MB free. No crashes or visual bugs.

## Notes

- Its remote has a Home button, so the remote-buttons service stays off there (it only runs on `MiTV-M…` TVs).
- The app has no native code, so a 32-bit device is fine.
- Not reported: what *Original* looked like after switching back.
