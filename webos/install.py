#!/usr/bin/env python3
"""Installs Free My TV on an LG TV in Developer Mode, over your Wi-Fi.

    python3 webos/install.py 192.168.1.50              # install or update, then open it
    python3 webos/install.py 192.168.1.50 --uninstall  # remove it

Before the first install, on the TV: install "Developer Mode" from the LG Content Store, sign in with a free LG
developer account (webostv.developer.lge.com), turn on Dev Mode Status (the TV restarts), then turn on Key Server.
The app shows the TV's IP address and a passphrase.

The first time, this downloads the TV's key from its Key Server and asks for that passphrase (type it here; it is
not stored). The unlocked key is kept in ~/.ssh/freemytv_webos_<IP>, so updates need neither. The app is copied over
SSH (port 9922) and installed with the TV's own installer (com.webos.appInstallService), the way LG's tools do it.
Install flow based on FreeMyTvWebOS by danielthejoker18 (MIT). Needs Python 3 and OpenSSH (ssh, ssh-keygen).
"""
import getpass
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IPK = os.path.join(ROOT, 'FreeMyTV-webos.ipk')
with open(os.path.join(ROOT, 'webos', 'appinfo.json'), encoding='utf-8') as f:
    APP_ID = json.load(f)['id']
TEMP = '/media/developer/temp/freemytv.ipk'
SSH_OPTS = ['-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=' + os.devnull, '-o', 'LogLevel=ERROR',
            '-o', 'HostKeyAlgorithms=+ssh-rsa', '-o', 'PubkeyAcceptedKeyTypes=+ssh-rsa', '-p', '9922']


def key_path(ip):
    return os.path.join(os.path.expanduser('~'), '.ssh', 'freemytv_webos_' + ip.replace('.', '_'))


def lock(path):
    """Only this user may read the key: chmod is enough on macOS and Linux, Windows' OpenSSH checks the file's ACL."""
    os.chmod(path, 0o600)
    if os.name != 'nt':
        return
    # By SID, not name: a domain PC that lost its trust with the domain can't resolve names.
    sid = subprocess.run(['whoami', '/user', '/fo', 'csv', '/nh'], capture_output=True, text=True).stdout.strip().split(',')[-1].strip('"')
    subprocess.run(['icacls', path, '/inheritance:r', '/grant:r', '*%s:F' % sid], capture_output=True)


def fetch_key(ip):
    """The TV's SSH key from its Key Server, unlocked with the passphrase the TV shows."""
    path = key_path(ip)
    if os.path.exists(path):
        return path
    url = 'http://%s:9991/webos_rsa' % ip
    print('Getting the TV\'s key from %s ...' % url)
    try:
        with urllib.request.urlopen(url, timeout=6) as r:
            data = r.read()
    except Exception as e:
        sys.exit('Couldn\'t reach the Key Server (%s). In the TV\'s Developer Mode app, turn on Key Server.' % e)
    passphrase = getpass.getpass('Passphrase shown in Developer Mode on the TV: ').strip()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = tempfile.NamedTemporaryFile(delete=False)
    tmp.write(data)
    tmp.close()
    lock(tmp.name)
    unlock = subprocess.run(['ssh-keygen', '-p', '-P', passphrase, '-N', '', '-f', tmp.name], capture_output=True, text=True)
    if unlock.returncode != 0:
        os.unlink(tmp.name)
        # ssh-keygen's last line says why: a wrong passphrase, or a key file others can read ("bad permissions").
        reason = (unlock.stderr.strip().splitlines() or [''])[-1]
        sys.exit('That passphrase didn\'t unlock the key (%s). Check it on the TV (Developer Mode) and try again.' % reason)
    shutil.move(tmp.name, path)
    lock(path)
    return path


def ssh(ip, key, command, stdin=None, tty=False):
    args = ['ssh'] + (['-tt'] if tty else []) + SSH_OPTS + ['-i', key, 'prisoner@' + ip, command]
    return subprocess.run(args, stdin=stdin, capture_output=True, text=stdin is None)


def luna(ip, key, uri, payload, replies=1, wait=20):
    # luna-send-pub prints nothing without a terminal (-tt), and the installer streams several replies.
    # Older webOS ships BusyBox before 1.30, whose timeout only takes -t SECS.
    # Some (webOS 6) refuse the terminal; there -i streams the replies without one, until the timeout.
    timeout = "if timeout -t 1 true 2>/dev/null; then T='timeout -t %d'; else T='timeout %d'; fi; " % (wait, wait)
    message = "%s '%s'" % (uri, json.dumps(payload, separators=(',', ':')))
    out = ssh(ip, key, timeout + '$T luna-send-pub -n %d %s' % (replies, message), tty=True).stdout or ''
    if not out.strip():
        out = ssh(ip, key, timeout + '$T luna-send-pub -i %s </dev/null' % message).stdout or ''
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args or not re.match(r'^\d{1,3}(\.\d{1,3}){3}$', args[0]):
        sys.exit(__doc__)
    ip = args[0]
    if not shutil.which('ssh') or not shutil.which('ssh-keygen'):
        sys.exit('OpenSSH (ssh and ssh-keygen) is needed.')
    key = fetch_key(ip)

    if '--uninstall' in sys.argv:
        out = luna(ip, key, 'luna://com.webos.appInstallService/dev/remove', {'id': APP_ID}, replies=3, wait=30)
        print('Removed.' if '"returnValue":true' in out else 'The TV said: ' + out.strip())
        return

    if not os.path.exists(IPK):
        subprocess.run([sys.executable, os.path.join(ROOT, 'webos', 'build.py')], check=True)
    print('Copying the app to the TV ...')
    with open(IPK, 'rb') as f:
        copy = ssh(ip, key, 'mkdir -p /media/developer/temp && cat > ' + TEMP, stdin=f)
    if copy.returncode != 0:
        err = copy.stderr.decode('utf-8', 'replace') if isinstance(copy.stderr, bytes) else copy.stderr
        sys.exit('Couldn\'t copy it: %s\nIs Developer Mode still on? If the key changed, delete %s and try again.' % (err.strip(), key))
    print('Installing ...')
    out = luna(ip, key, 'luna://com.webos.appInstallService/dev/install', {'id': APP_ID, 'ipkUrl': TEMP, 'subscribe': True},
               replies=30, wait=60)
    if '"state":"installed"' not in out:
        sys.exit('The TV didn\'t finish installing. It said:\n' + out.strip())
    ssh(ip, key, 'rm -f ' + TEMP)
    luna(ip, key, 'luna://com.webos.applicationManager/launch', {'id': APP_ID})
    print('Installed and open on the TV.')
    print('Tip: while Free My TV is open, hold a number key on the remote to open it with that key from anywhere.')
    print('Developer Mode lasts about 40 days: open the Developer Mode app now and then and press EXTEND.')


if __name__ == '__main__':
    main()
