#!/usr/bin/env python3
"""Looks on a TV for apps that watch what you do (screen-content recognition, analytics, telemetry) and prints what it
finds. It only reads: nothing on the TV is changed, turned off or uninstalled.

    python3 tools/privacy_scan.py 192.168.1.50      # connects adb first
    python3 tools/privacy_scan.py                   # the TV adb is already connected to
    python3 tools/privacy_scan.py --packages list.txt   # a saved `adb shell pm list packages` (and --disabled for `-d`)

Two kinds of finding:
  known      Apps this project already documents as watching or reporting (the same ones Free My TV mode turns off on
             the TV brands that ship them: see DEBLOAT in app/src/io/freemytv/HomeMode.java).
  possible   A package whose name suggests it (acr, analytics, telemetry, tracking, statistic). A name proves nothing:
             check what the app is before touching it, and leave anything the TV's picture, sound, inputs or remote use.
Add a package you have checked to KNOWN below, with a line on what it does, in a pull request.
"""
import argparse
import re
import shutil
import subprocess
import sys

# package -> what it does. Only apps already named in HomeMode.DEBLOAT as ACR or telemetry.
KNOWN = {
    'tv.anoki.acr.anokiacroptin': 'Anoki ACR: watches what is on screen (Multilaser, KTC).',
    'tv.anoki.acr.controller': 'Anoki ACR: watches what is on screen (KTC / NextStar).',
    'com.miui.tv.analytics': 'Xiaomi TV telemetry.',
    'com.xiaomi.statistic': 'Xiaomi usage statistics.',
}
WORDS = ('analytics', 'telemetry', 'tracking', 'statistic')


def packages(text):
    """The package ids in `pm list packages` output ("package:com.example.app")."""
    return {m.group(1) for m in re.finditer(r'^package:(?:[^=\s]+=)?([A-Za-z0-9_.]+)\s*$', text, re.M)}


def possible(pkg):
    """A package name that suggests tracking: a word in it, or `acr` as one of its dot-separated parts."""
    low = pkg.lower()
    return 'acr' in low.split('.') or any(w in low for w in WORDS)


def scan(installed, disabled):
    """(known, possible): lists of (package, note, is_off), each sorted by package."""
    known, maybe = [], []
    for pkg in sorted(installed):
        off = pkg in disabled
        if pkg in KNOWN:
            known.append((pkg, KNOWN[pkg], off))
        elif possible(pkg):
            maybe.append((pkg, 'Name suggests tracking: check what it is first.', off))
    return known, maybe


def adb(*args):
    return subprocess.run(['adb', *args], capture_output=True, text=True)


def main():
    ap = argparse.ArgumentParser(description='Read-only scan of a TV for tracking apps.')
    ap.add_argument('ip', nargs='?', help='the TV\'s IP address (adb connects to ip:5555)')
    ap.add_argument('--packages', help='a saved `pm list packages` instead of asking the TV')
    ap.add_argument('--disabled', help='a saved `pm list packages -d`, with --packages')
    args = ap.parse_args()

    if args.packages:
        try:
            installed = packages(open(args.packages, encoding='utf-8').read())
            disabled = packages(open(args.disabled, encoding='utf-8').read()) if args.disabled else set()
        except OSError as e:
            sys.exit('Could not read %s' % e.filename)
    else:
        if not shutil.which('adb'):
            sys.exit('adb is not installed. Install the Android platform tools, or use --packages with a saved list.')
        serial = []
        if args.ip:
            serial = ['-s', args.ip + ':5555']
            out = adb('connect', args.ip + ':5555')
            if 'connected' not in out.stdout:
                sys.exit('Could not connect to %s:5555: %s' % (args.ip, (out.stdout + out.stderr).strip()))
        listed = adb(*serial, 'shell', 'pm', 'list', 'packages')
        if listed.returncode != 0 or not packages(listed.stdout):
            sys.exit('The TV did not list its apps: %s' % (listed.stderr or listed.stdout).strip())
        installed = packages(listed.stdout)
        disabled = packages(adb(*serial, 'shell', 'pm', 'list', 'packages', '-d').stdout)

    known, maybe = scan(installed, disabled)
    print('%d apps on this TV. Nothing was changed.\n' % len(installed))
    for title, rows in (('Known', known), ('Possible', maybe)):
        print('%s: %d' % (title, len(rows)))
        for pkg, note, off in rows:
            print('  [%s] %s\n        %s' % ('off' if off else 'on ', pkg, note))
        print()
    if known or maybe:
        print('Free My TV mode already turns off the known ones on the brands that ship them, and Original brings\n'
              'them back. Anything else is yours to decide; a package turned off with `pm disable-user` comes back\n'
              'with `pm enable <package>`.')
    else:
        print('Nothing known or suspicious by name.')


if __name__ == '__main__':
    main()
