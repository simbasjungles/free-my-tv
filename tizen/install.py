#!/usr/bin/env python3
"""Installs Free My TV on a Samsung TV in Developer Mode, over your Wi-Fi, without Samsung's sdb.

    python3 tizen/install.py 192.168.1.50              # install or update, then open it
    python3 tizen/install.py 192.168.1.50 --uninstall  # remove it

First build and sign it: python3 tizen/build.py --sign <profile> (see CLAUDE.md). Before the first install, on the
TV: open Apps, type 1 2 3 4 5 on the remote, turn Developer mode on with this computer's IP as Host PC IP, and
restart the TV.

It speaks SDB (Samsung's ADB, port 26101) itself, so it needs only Python 3: no Tizen Studio, and no Rosetta on Apple
Silicon, where Samsung's sdb (an Intel binary) doesn't run. Like Samsung's tools it pushes the package to the folder
the TV names in its capabilities and installs it with `0 vd_appinstall`, the command TizenBrew Installer uses on
every Tizen; `0 was_execute` opens it. Tested on a 2017 UN50MU6100 (Tizen 3.0), a 2019 UN55RU7100 (Tizen 5.0) and a
2024 QA75Q60D (Tizen 9.0).
"""
import os
import re
import socket
import struct
import sys
import time
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WGT = os.path.join(ROOT, 'FreeMyTV-tizen.wgt')
PORT = 26101
MAX_DATA = 4096   # what this side announces; sdbd keeps each packet within both sides' maximum
ON_TV = 'Apps, Settings, Free My TV, Delete'   # removing it on the TV itself


class Sdb:
    """The pieces of the SDB wire protocol this needs: ADB's framing (a 24-byte header whose checksum is a byte sum),
    streams opened with OPEN, and every WRTE answered with OKAY before the next one."""

    def __init__(self, ip):
        self.sock = socket.create_connection((ip, PORT), timeout=15)
        self.buf = b''
        self.next_id = 1
        self.okay, self.inbox, self.closed = {}, {}, set()
        self.send(b'CNXN', 0x01000000, MAX_DATA, b'host::\0')
        cmd, _, max_data, data = self.read()
        if cmd != b'CNXN':
            raise ConnectionError('the TV answered %r instead of connecting' % cmd)
        self.max = min(max_data, MAX_DATA)
        self.banner = data.rstrip(b'\0').decode('utf-8', 'replace')

    def send(self, cmd, arg0, arg1, data=b''):
        c = struct.unpack('<I', cmd)[0]
        self.sock.sendall(struct.pack('<6I', c, arg0, arg1, len(data), sum(data) & 0xFFFFFFFF, c ^ 0xFFFFFFFF) + data)

    def _recv(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError('the TV closed the connection')
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def read(self):
        cmd, arg0, arg1, n, _, _ = struct.unpack('<6I', self._recv(24))
        return struct.pack('<I', cmd), arg0, arg1, self._recv(n) if n else b''

    def pump(self):
        """Reads one packet and files it under its stream: OKAY, data (answered with OKAY) or close."""
        cmd, remote, local, data = self.read()
        if cmd == b'OKAY':
            self.okay[local] = remote
        elif cmd == b'WRTE':
            self.inbox[local] = self.inbox.get(local, b'') + data
            self.send(b'OKAY', local, remote)
        elif cmd == b'CLSE':
            self.closed.add(local)

    def open(self, service):
        local, self.next_id = self.next_id, self.next_id + 1
        self.send(b'OPEN', local, 0, service.encode() + b'\0')
        while local not in self.okay:
            if local in self.closed:
                raise ConnectionError('the TV refused ' + service)
            self.pump()
        return local, self.okay.pop(local)

    def write(self, stream, data):
        local, remote = stream
        self.send(b'WRTE', local, remote, data)
        while local not in self.okay:
            if local in self.closed:
                raise ConnectionError('the TV closed the stream')
            self.pump()
        del self.okay[local]

    def read_until(self, stream, done, seconds):
        """The stream's text until done(text) is true, the TV closes it, or time runs out."""
        local = stream[0]
        end = time.time() + seconds
        while time.time() < end and local not in self.closed and not done(self.inbox.get(local, b'')):
            self.sock.settimeout(max(0.1, end - time.time()))
            try:
                self.pump()
            except socket.timeout:
                break
        self.sock.settimeout(15)
        return self.inbox.pop(local, b'')

    def shell(self, command, until=None, seconds=20):
        stream = self.open('shell:' + command)
        out = self.read_until(stream, lambda t: bool(until and re.search(until, t.decode('utf-8', 'replace'))), seconds)
        return out.decode('utf-8', 'replace')

    def capabilities(self):
        text = self.read_until(self.open('capability:'), lambda t: False, 5).decode('utf-8', 'replace')
        return dict(line.split(':', 1) for line in text.splitlines() if ':' in line)

    def push(self, path, data):
        """Copies data to path on the TV (sync SEND), then checks the TV's answer."""
        stream = self.open('sync:')
        spec = ('%s,%d' % (path, 0o100644)).encode()
        self.write(stream, b'SEND' + struct.pack('<I', len(spec)) + spec)
        step = self.max - 8
        for i in range(0, len(data), step):
            part = data[i:i + step]
            self.write(stream, b'DATA' + struct.pack('<I', len(part)) + part)
        self.write(stream, b'DONE' + struct.pack('<I', int(time.time())))
        answer = self.read_until(stream, lambda t: len(t) >= 8, 60)
        if answer[:4] != b'OKAY':
            raise ConnectionError('the TV didn\'t take the file: ' + answer[8:].decode('utf-8', 'replace'))
        self.send(b'WRTE', stream[0], stream[1], b'QUIT' + struct.pack('<I', 0))


def this_computer(ip):
    """This computer's address on the TV's network (what Host PC IP must be)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((ip, PORT))
        return s.getsockname()[0]
    except OSError:
        return 'this computer\'s IP'


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if len(args) != 1:
        sys.exit(__doc__)
    ip = args[0]
    uninstall = '--uninstall' in sys.argv
    if uninstall:
        # Removing needs only the app's ids: the manifest the package is built from has them, so no build is needed.
        with open(os.path.join(ROOT, 'tizen', 'config.xml'), encoding='utf-8') as f:
            manifest = f.read()
    else:
        if not os.path.exists(WGT):
            sys.exit('Build and sign it first: python3 tizen/build.py --sign <profile>')
        with zipfile.ZipFile(WGT) as z:
            if 'author-signature.xml' not in z.namelist():
                sys.exit('FreeMyTV-tizen.wgt isn\'t signed, and the TV only installs signed apps: '
                         'python3 tizen/build.py --sign <profile>')
            manifest = z.read('config.xml').decode('utf-8')
    tag = re.search(r'<tizen:application[^>]*>', manifest).group(0)
    app_id = re.search(r'\bid="([^"]+)"', tag).group(1)
    package = re.search(r'\bpackage="([^"]+)"', tag).group(1)

    try:
        sdb = Sdb(ip)
    except (OSError, ConnectionError) as e:
        # A 2024 QA75Q60D (Tizen 9) closed the connection like this for a while, across restarts, with the right Host
        # PC IP, then took the same install without a change: what it waited for is unknown.
        dropped = isinstance(e, ConnectionResetError) or 'closed the connection' in str(e)
        mine = this_computer(ip)
        # The Developer mode screen asks for the computer's IP, so that's the one that gets typed here too (#490).
        own = (' %s is this computer\'s address: give the TV\'s (on the TV: Settings, General, Network, Network Status, '
               'IP Settings).' % ip if ip == mine
               else ' Check that %s is the TV\'s address, not this computer\'s.' % ip if isinstance(e, ConnectionRefusedError)
               else '')
        sys.exit('Couldn\'t reach the TV on port %d (%s).%s On the TV: Apps, 1 2 3 4 5, Developer mode on with Host PC '
                 'IP %s, then restart the TV.%s' % (PORT, e, own, mine,
                 ' If it already is, wait a minute after the TV restarts and try again.' if dropped else ''))
    caps = sdb.capabilities()
    print('Connected: %s, Tizen %s.' % (sdb.banner.split(':')[2] if sdb.banner.count(':') >= 2 else sdb.banner,
                                         caps.get('platform_version', '?')))

    if uninstall:
        out = sdb.shell('0 vd_appuninstall ' + package, r'spend time', 120).strip()
        if not re.search(r'uninstall completed', out):
            # Some TVs' sdbd refuses this command and answers nothing.
            sys.exit(('The TV said:\n' + out if out else 'The TV didn\'t answer.') + '\nRemove it on the TV instead: ' + ON_TV + '.')
        print('Removed.')
        return

    # Tizen 2.4's sdbd names no sdk_toolpath and has no /home/owner: its tools' folder is /opt/usr/apps/tmp.
    default_path = '/opt/usr/apps/tmp' if caps.get('platform_version', '').startswith('2.') else '/home/owner/share/tmp/sdk_tools'
    remote = caps.get('sdk_toolpath', default_path).rstrip('/') + '/FreeMyTV-tizen.wgt'
    with open(WGT, 'rb') as f:
        data = f.read()
    print('Copying the app to the TV ...')
    sdb.push(remote, data)
    print('Installing ...')
    out = sdb.shell('0 vd_appinstall %s %s' % (package, remote), r'spend time', 120)
    if not re.search(r'install completed', out):
        hint = ''
        if 'different certificate' in out:
            hint = ('\nThe Free My TV on the TV was signed with another certificate, and the TV only updates it with the '
                    'same one. Remove it on the TV (' + ON_TV + '), then run this again.')
        elif 'failed[118]' in out:
            # Tizen 2.4 gives no reason: a 2016 KU6640 answered this to every build after an install that failed
            # half-way, with nothing to see on the TV, until vd_appuninstall removed what was left (#349).
            hint = ('\nThe TV may still hold a Free My TV signed with another certificate, even one that doesn\'t show '
                    '(a failed install can leave one). Remove it, then run this again:\n    python3 tizen/install.py %s '
                    '--uninstall\n(That also clears what Free My TV keeps on the TV, such as a TMDB key typed there.) '
                    'Still failed[118] after that? Some models refuse developer installs altogether (a 2016 Full HD '
                    'K5670 did, #349): a Google TV streamer on HDMI runs Free My TV there.' % ip)
        elif 'failed[116]' in out:
            # "download failed[116]" comes within milliseconds, before any certificate check, and for any package: a
            # 2020 UE55TU7020 answered it even to a minimal app, and installed once ~70 MB were free (#490). The same
            # on a UE55NU7305 and a UE32N5300 (jellyfin-tizen issue 10).
            hint = ('\nThe TV stopped before installing anything. That usually means it has too little free storage '
                    'left for apps: delete some you don\'t use (Settings, Support, Device Care, Manage Storage on 2020 '
                    'and newer TVs; Apps, Settings on older ones), restart the TV, then run this again.')
        sys.exit('The TV didn\'t finish installing. It said:\n' + out.strip() + hint)
    sdb.shell('0 was_execute ' + app_id, r'launched|spend time', 20)
    print('Installed and open on the TV.')
    print('Tip: turn on Autorun Last App in the TV\'s settings (General, Smart Features; where it is changes by year) '
          'and the TV turns on into Free My TV; on 2019 and 2020 TVs, move it to the first place in the Home bar instead.')


if __name__ == '__main__':
    main()
