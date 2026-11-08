#!/usr/bin/env python3
"""Sends a secret key to Free My TV on the TV, so it never goes into the APK (any app on the TV can read an installed
APK) or into config.properties.

    python3 tools/send_key.py 192.168.1.50 immich_api_key     # asks for the key (not shown)
    python3 tools/send_key.py immich_api_key                  # the TV adb is already connected to
    python3 tools/send_key.py --remove immich_api_key         # takes it off the TV

The key is asked for here (or read from standard input when it isn't a terminal) and goes over adb straight into the
app's private files (`content write` to its Secrets provider, which only adb may use); then the TV is asked whether it
arrived (never for the key itself). Android 9 and newer: adb's `content write` isn't there on Android 8. Keys:
  immich_api_key   Immich > Account Settings > API Keys (asset.read, asset.view, asset.download and album.read).
                   Checked against immich_server from config.properties first, when it's set.
  home_assistant_token
                   Home Assistant > your profile > Security > Long-lived access tokens (a user of its own that isn't an
                   administrator is safest). Checked against home_assistant_url first, when it's set, with what each of
                   home_assistant_entities becomes on the TV.
"""
import getpass
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

URI = 'content://io.freemytv.secrets/'
NAMES = ('immich_api_key', 'home_assistant_token')
SERIAL = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def adb(*args, **kw):
    # Not this process's stdin: `adb shell` would read the key piped in for `content write` (and send it to the TV).
    if 'input' not in kw:
        kw['stdin'] = subprocess.DEVNULL
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, **kw)


def prop(key):
    """A value from config.properties (the last one, as build.sh reads it), or ''."""
    value = ''
    try:
        with open(os.path.join(ROOT, 'config.properties'), encoding='utf-8') as f:
            for line in f:
                k, sep, v = line.partition('=')
                if sep and k.strip() == key:
                    value = v.strip().strip('"')
    except OSError:
        pass
    return value


def received(name):
    """When the TV got this key (Secrets' `received`, ms), 0 if it has none, None if it didn't answer."""
    r = adb('shell', 'content', 'query', '--uri', URI + name, text=True)
    m = re.search(r'present=(\d+), received=(\d+)', r.stdout)
    if not m:
        return None
    return int(m.group(2)) if m.group(1) == '1' else 0


def settled(name, done):
    """received(name) once done(it) holds, or its last answer: the app renames the key into place only when the
    write closes, a moment after adb has returned."""
    for _ in range(10):
        r = received(name)
        if done(r):
            return r
        time.sleep(0.5)
    return r


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """The key isn't sent on to wherever a redirect points, as on the TV."""
    def redirect_request(self, *args):
        return None


def check_immich(key):
    """None if the server takes the key for what the TV does with it, else why not; skipped without immich_server."""
    server = prop('immich_server').rstrip('/')
    if not server:
        return None
    opener = urllib.request.build_opener(NoRedirect)

    def call(path, body=None):
        headers = {'x-api-key': key}
        if body is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(server + path, data=None if body is None else json.dumps(body).encode(),
                                     headers=headers)
        return opener.open(req, timeout=8)

    needs = {'search': 'asset.read', 'thumbnail': 'asset.view', 'original': 'asset.download'}
    step = 'search'
    try:
        with call('/api/search/random', {'size': 1, 'type': 'IMAGE'}) as r:
            found = json.load(r)
        if found:   # one photo, as the TV fetches them: its preview and its original (one byte of each)
            asset = found[0]['id']
            for step, path in (('thumbnail', f'/api/assets/{asset}/thumbnail?size=preview'),
                               ('original', f'/api/assets/{asset}/original')):
                with call(path) as r:
                    r.read(1)
        return None
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return 'Immich refused this key.'
        if e.code == 403:
            return (f'Immich took the key but not for {needs[step]}: give it asset.read, asset.view, asset.download '
                    'and album.read.')
        if 300 <= e.code < 400:
            return f'Immich answered with a redirect ({e.code}): give immich_server the address it moves to.'
        return f'Immich answered HTTP {e.code}.'
    except Exception as e:
        return f'Could not reach {server} from this computer ({e}). Send it anyway with --no-check.'


# What the TV shows (HomeAssistant.DOMAINS, SAFE_COVERS, MAX): keep in step.
HA_DOMAINS = ('light', 'switch', 'fan', 'input_boolean', 'cover', 'scene', 'script', 'automation', 'button',
              'input_button')
HA_SAFE_COVERS = ('awning', 'blind', 'curtain', 'shade', 'shutter')
HA_MAX = 8


def check_home_assistant(token):
    """None if the server takes the token (and lists what each entity becomes), else why not; skipped without an address."""
    server = prop('home_assistant_url').rstrip('/')
    if not server:
        return None
    opener = urllib.request.build_opener(NoRedirect)

    def get(path):
        req = urllib.request.Request(server + path, headers={'Authorization': 'Bearer ' + token, 'User-Agent': 'FreeMyTV'})
        with opener.open(req, timeout=8) as r:
            return json.load(r)

    try:
        get('/api/')
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return 'Home Assistant refused this token.'
        if 300 <= e.code < 400:
            return f'Home Assistant answered with a redirect ({e.code}): give home_assistant_url the address it moves to.'
        return f'Home Assistant answered HTTP {e.code}.'
    except Exception as e:
        return f'Could not reach {server} from this computer ({e}). Send it anyway with --no-check.'
    shown = 0
    for entity in [e.strip().lower() for e in prop('home_assistant_entities').split(',') if e.strip()]:
        domain = entity.split('.', 1)[0]
        if domain not in HA_DOMAINS:
            print(f'  {entity}: left out (only {", ".join(HA_DOMAINS)})')
            continue
        if shown == HA_MAX:
            print(f'  {entity}: left out (at most {HA_MAX})')
            continue
        try:
            state = get('/api/states/' + entity)
        except urllib.error.HTTPError as e:
            print(f'  {entity}: ' + ('not found in Home Assistant' if e.code == 404 else f'HTTP {e.code}'))
            continue
        attributes = state.get('attributes') or {}
        dc = str(attributes.get('device_class') or '').lower()
        if domain == 'cover' and dc not in HA_SAFE_COVERS:
            print(f'  {entity}: left out ({"a " + dc if dc else "a cover without a class"}; only {", ".join(HA_SAFE_COVERS)})')
            continue
        shown += 1
        print(f'  {entity}: "{attributes.get("friendly_name") or entity}", {state.get("state")}')
    return None


CHECKS = {'immich_api_key': check_immich, 'home_assistant_token': check_home_assistant}


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0 if args else 2
    remove = '--remove' in args
    check = '--no-check' not in args
    args = [a for a in args if a not in ('--remove', '--no-check')]
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    if args and args[0][0].isdigit():
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target, text=True)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
        SERIAL[:] = ['-s', target]
    if len(args) != 1 or args[0] not in NAMES:
        print('Which key? One of: ' + ', '.join(NAMES))
        return 2
    name = args[0]
    listed = adb('shell', 'pm', 'list', 'packages', 'io.freemytv', text=True)
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return 1
    if 'package:io.freemytv' not in listed.stdout.split():
        print('Free My TV is not installed on this TV.')
        return 1
    sdk = adb('shell', 'getprop', 'ro.build.version.sdk', text=True).stdout.strip()
    if sdk.isdigit() and int(sdk) < 28:
        print('This TV runs Android 8, where adb has no way to write into the app: keys need Android 9 or newer.')
        return 1
    before = received(name)
    if before is None:
        print('The Free My TV on this TV is too old to take keys: install the new FreeMyTV.apk (see Install), then '
              'send it again.')
        return 1

    if remove:
        adb('shell', 'content', 'delete', '--uri', URI + name)
        if settled(name, lambda r: r == 0) != 0:
            print(f'{name} is still on the TV: try again.')
            return 1
        print(f'{name} is off the TV.')
        return 0
    key = (getpass.getpass(f'{name} (not shown): ') if sys.stdin.isatty() else sys.stdin.readline()).strip()
    if not key:
        print('No key given.')
        return 1
    if check and name in CHECKS:
        why = CHECKS[name](key)
        if why:
            print(why)
            return 1
    adb('exec-in', 'content', 'write', '--uri', URI + name, input=key.encode() + b'\n')
    # exec-in says nothing of what the TV did (it returns 0 either way): the TV's own answer tells
    after = settled(name, lambda r: bool(r) and r != before)
    if not after or after == before:
        # On Android 9 (TCL BeyondTV, Mi Box 3), exec-in does not give its input to `content write`.
        # `adb shell` without a terminal gives it.
        adb('shell', 'content', 'write', '--uri', URI + name, input=key.encode() + b'\n')
        after = settled(name, lambda r: bool(r) and r != before)
    if not after or after == before:
        print(f'The TV didn\'t take {name}: it keeps one line of 8 or more printable characters. Nothing changed.')
        return 1
    print(f'Sent {name}. Free My TV uses it from the next time the home screen or its settings open.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
