#!/usr/bin/env python3
"""Takes Free My TV's settings off a TV, or puts them on one, over adb.

On the TV: Home Settings > Export settings writes freemytv-settings.json, and Home Settings > Import settings reads it.
This tool only carries that file between the TV and this computer, so a reinstall, a factory reset or another TV
doesn't mean choosing everything again. Your choices only: never a key or a token, and no photos.

  python3 tools/settings.py <TV-IP> pull [file]    # after Export settings on the TV; file defaults to freemytv-settings.json
  python3 tools/settings.py <TV-IP> push [file]    # then Import settings on the TV

Needs adb (the Android platform tools) and USB debugging on the TV, as for installing the app.
"""
import json
import os
import shutil
import subprocess
import sys

PACKAGE = 'io.freemytv'
NAME = 'freemytv-settings.json'
REMOTE = '/sdcard/Android/data/%s/files/%s' % (PACKAGE, NAME)
# Where the shell can't open the app's folder in Android/data (some Android 11 builds, Fire OS 8), the app hands the
# file over through this provider (BackupFile): content read gives it, content write replaces it.
URI = 'content://io.freemytv.backup/file'
SERIAL = []


def adb(*args, **kw):
    kw.setdefault('stdin', subprocess.DEVNULL)
    return subprocess.run(['adb'] + SERIAL + list(args), capture_output=True, **kw)


def text(r):
    return (r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace')).strip()


def is_settings(data):
    try:
        d = json.loads(data.decode('utf-8'))
    except ValueError:
        return False
    return isinstance(d, dict) and isinstance(d.get('freemytv'), int) and isinstance(d.get('settings'), dict)


def pull(local):
    r = adb('pull', REMOTE, local)
    if r.returncode == 0:
        return 0
    r2 = adb('exec-out', 'content', 'read', '--uri', URI)
    if r2.returncode == 0 and is_settings(r2.stdout):
        with open(local, 'wb') as f:
            f.write(r2.stdout)
        return 0
    print('No settings file could be taken from the TV. Choose Home Settings > Export settings on the TV first.')
    print('adb said: ' + text(r))
    return 1


def push(local):
    with open(local, 'rb') as f:
        data = f.read()
    adb('shell', 'mkdir', '-p', os.path.dirname(REMOTE))
    r = adb('push', local, REMOTE)
    if r.returncode == 0:
        return 0
    # exec-in says nothing of what the TV did: read it back to know it arrived whole.
    adb('exec-in', 'content', 'write', '--uri', URI, input=data)
    back = adb('exec-out', 'content', 'read', '--uri', URI)
    if back.returncode == 0 and back.stdout == data:
        return 0
    print('Could not copy it to the TV. adb said: ' + text(r))
    return 1


def main():
    global SERIAL
    args = sys.argv[1:]
    if len(args) < 2 or args[1] not in ('pull', 'push'):
        print(__doc__)
        return 0 if not args else 2
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    target = args[0] if ':' in args[0] else args[0] + ':5555'
    out = subprocess.run(['adb', 'connect', target], capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if 'connected' not in out.stdout:
        print('Could not connect to %s: %s' % (target, (out.stdout + out.stderr).strip()))
        return 1
    SERIAL = ['-s', target]
    if PACKAGE not in adb('shell', 'pm', 'list', 'packages', PACKAGE).stdout.decode('utf-8', 'replace'):
        print('Free My TV is not installed on this TV.')
        return 1
    local = args[2] if len(args) > 2 else NAME
    if args[1] == 'pull':
        code = pull(local)
        if code == 0:
            print('Saved %s. Keep it: Import settings on a TV restores it.' % local)
        return code
    try:
        with open(local, 'rb') as f:
            ok = is_settings(f.read())
    except OSError as e:
        print('Could not read %s: %s' % (local, e))
        return 1
    if not ok:
        print('%s is not a Free My TV settings file.' % local)
        return 1
    code = push(local)
    if code == 0:
        print('Sent. On the TV: Home Settings > Import settings.')
    return code


if __name__ == '__main__':
    sys.exit(main())
