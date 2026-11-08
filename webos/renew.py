#!/usr/bin/env python3
"""Renews LG Developer Mode so Free My TV keeps working, without the EXTEND button.

    python3 webos/renew.py 192.168.1.50

Developer Mode lasts about 40 days; this resets the timer. Reads the TV's Developer Mode
session token over the SSH key install.py cached (~/.ssh/freemytv_webos_<IP>), and posts it
to LG's renewal endpoint. Install Free My TV first (webos/install.py), which caches the key;
this never asks for the passphrase, so it runs unattended.

Run it on a schedule (weekly is plenty, the window is ~40 days). The TV must be reachable on
your network with Developer Mode on; when it isn't, this says so and changes nothing.

  cron (macOS, Linux), weekly:  0 3 * * 0 /usr/bin/python3 /path/to/webos/renew.py 192.168.1.50
  Windows (Task Scheduler):     schtasks /create /tn FreeMyTVRenew /sc weekly /d SUN /st 03:00 \
                                  /tr "python \"C:\\path\\to\\webos\\renew.py\" 192.168.1.50"

Needs Python 3 and OpenSSH (ssh). The token is a credential: it is never printed or logged,
and the request to LG never follows a redirect, so it can't travel to another host.
"""
import os
import re
import shutil
import sys
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import install  # noqa: E402  (key_path and ssh: the same key and SSH as the installer)

# Developer Mode writes the session token here; some webOS leave a copy in /tmp.
TOKEN_FILES = ('/var/luna/preferences/devmode_enabled', '/tmp/webos_devmode_token.txt')
RENEW_HOST = 'https://developer.lge.com/secure/ResetDevModeSession.dev'


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    # The token is in the URL; never let a redirect carry it to another host.
    def redirect_request(self, *args, **kwargs):
        return None


def read_token(ip, key):
    """The TV's Developer Mode session token, read over SSH ('' when the TV can't be reached)."""
    command = ' || '.join('cat %s 2>/dev/null' % f for f in TOKEN_FILES)
    result = install.ssh(ip, key, command)
    if result.returncode != 0 and not (result.stdout or '').strip():
        reason = (result.stderr or '').strip().splitlines()
        sys.exit('Couldn\'t reach the TV over SSH%s. Is it on, on this network, with Developer Mode on?'
                 % (' (%s)' % reason[-1] if reason else ''))
    return (result.stdout or '').strip()


def renew(ip):
    key = install.key_path(ip)
    if not os.path.exists(key):
        sys.exit('No saved key for %s. Install Free My TV first: python3 webos/install.py %s' % (ip, ip))

    token = read_token(ip, key)
    if not re.fullmatch(r'[0-9a-fA-F]{32,128}', token):
        sys.exit('The TV didn\'t give a Developer Mode token. Is Developer Mode still on? Open its app on the TV.')

    url = RENEW_HOST + '?' + urllib.parse.urlencode({'sessionToken': token})
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(url, timeout=10) as r:
            body = r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        sys.exit('LG refused the renewal (HTTP %s).' % e.code)
    except urllib.error.URLError as e:
        sys.exit('Couldn\'t reach LG to renew it (%s).' % e.reason)

    if '"result":"success"' in body:
        print('Renewed: Developer Mode is good for about 40 more days.')
    else:
        # LG's reply names the problem; it never echoes the token.
        sys.exit('LG didn\'t renew it. It said:\n' + body.strip())


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args or not re.match(r'^\d{1,3}(\.\d{1,3}){3}$', args[0]):
        sys.exit(__doc__)
    if not shutil.which('ssh'):
        sys.exit('OpenSSH (ssh) is needed.')
    renew(args[0])


if __name__ == '__main__':
    main()
