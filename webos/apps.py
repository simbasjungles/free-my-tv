#!/usr/bin/env python3
"""Lists an LG TV's apps from your computer, for TVs that won't list them to Free My TV itself.

    python3 webos/apps.py 192.168.1.50     # then python3 webos/build.py and python3 webos/install.py 192.168.1.50

On webOS 3 a Developer Mode app can't see the TV's apps at all: listLaunchPoints is denied, getAppLoadStatus doesn't
exist yet, and the TV's remote control API refuses file:// pages ("invalid origin"). The computer can still ask that
API (port 3000, the one LG's phone app uses, or the same API over TLS on 3001 where the TV resets plain connections,
as a 2024 set does): the TV asks once to allow it, and the key it gives back is kept in
~/.ssh/freemytv_ssap_<IP>. The list goes to webos-apps.local.json and the icons to webos-icons.local/ (their addresses
on the TV only last while it's connected), and webos/build.py builds both into the app. Run it again after installing
or removing apps on the TV. Python 3 only.
"""
import base64
import json
import os
import re
import shutil
import socket
import ssl
import struct
import sys
import tempfile
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIST = os.path.join(ROOT, 'webos-apps.local.json')
ICONS = os.path.join(ROOT, 'webos-icons.local')
IMAGE_KINDS = ('.png', '.jpg', '.jpeg', '.webp', '.gif')
MANIFEST = {'manifestVersion': 1, 'appVersion': '1.0', 'permissions': ['READ_INSTALLED_APPS']}


class Socket:
    """Just enough of a WebSocket client (RFC 6455) for the TV's remote control API: text messages, no extensions."""

    def __init__(self, ip, port=3000):
        self.sock = socket.create_connection((ip, port), timeout=10)
        if port == 3001:   # the same API over TLS, with the TV's own certificate
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            self.sock = ctx.wrap_socket(self.sock)
        key = base64.b64encode(os.urandom(16)).decode('ascii')
        self.sock.sendall(('GET / HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                           'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n' % (ip, port, key)).encode('ascii'))
        self.buf = b''
        while b'\r\n\r\n' not in self.buf:
            self.buf += self.recv()
        head, self.buf = self.buf.split(b'\r\n\r\n', 1)
        if b' 101 ' not in head.split(b'\r\n', 1)[0]:
            raise OSError('the TV refused the connection: ' + head.split(b'\r\n', 1)[0].decode('latin-1'))

    def recv(self):
        chunk = self.sock.recv(65536)
        if not chunk:
            raise OSError('the TV closed the connection')
        return chunk

    def exactly(self, n):
        while len(self.buf) < n:
            self.buf += self.recv()
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def frame(self, opcode, payload):
        mask = os.urandom(4)
        n = len(payload)
        head = struct.pack('!B', 0x80 | opcode)
        head += struct.pack('!B', 0x80 | n) if n < 126 else (struct.pack('!BH', 0x80 | 126, n) if n < 65536
                                                             else struct.pack('!BQ', 0x80 | 127, n))
        self.sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send(self, message):
        self.frame(0x1, json.dumps(message).encode('utf-8'))

    def receive(self, timeout):
        """The next whole text message, as JSON."""
        self.sock.settimeout(timeout)
        parts = []
        while True:
            b0, b1 = self.exactly(2)
            n = b1 & 0x7f
            if n == 126:
                n = struct.unpack('!H', self.exactly(2))[0]
            elif n == 127:
                n = struct.unpack('!Q', self.exactly(8))[0]
            mask = self.exactly(4) if b1 & 0x80 else None
            payload = self.exactly(n)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            opcode = b0 & 0x0f
            if opcode == 0x8:
                raise OSError('the TV closed the connection')
            if opcode == 0x9:
                self.frame(0xA, payload)
            elif opcode in (0x0, 0x1):
                parts.append(payload)
                if b0 & 0x80:
                    return json.loads(b''.join(parts).decode('utf-8'))

    def close(self):
        try:
            self.frame(0x8, b'')
            self.sock.close()
        except OSError:
            pass


def key_path(ip):
    return os.path.join(os.path.expanduser('~'), '.ssh', 'freemytv_ssap_' + ip.replace('.', '_'))


def launch_points(ip):
    """The TV's launcher list, pairing first (the TV asks once)."""
    path = key_path(ip)
    # The TV gives the key, not its address: at a new address (DHCP after a power cut) the newest saved key is tried
    # first, so the TV doesn't ask again (with another TV's key, the TV just asks, as without one).
    folder = os.path.dirname(path)
    saved = [os.path.join(folder, n) for n in os.listdir(folder) if n.startswith('freemytv_ssap_')] if os.path.isdir(folder) else []
    found = path if os.path.exists(path) else max(saved, key=os.path.getmtime) if saved else None
    key = open(found).read().strip() if found else None
    try:
        ws = Socket(ip)
    except OSError:
        ws = Socket(ip, 3001)   # newer webOS (a 2024 LG tested) resets plain connections on 3000
    try:
        payload = {'forcePairing': False, 'pairingType': 'PROMPT', 'manifest': MANIFEST}
        if key:
            payload['client-key'] = key
        ws.send({'type': 'register', 'id': 'register', 'payload': payload})
        if not key:
            print('On the TV, choose Allow ...')
        while True:
            m = ws.receive(timeout=90)   # time to answer the TV's prompt
            if m.get('type') == 'error':
                sys.exit('The TV said: %s' % (m.get('error') or json.dumps(m)))
            if m.get('type') == 'registered':
                os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
                with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as f:
                    f.write(m['payload']['client-key'])
                ws.send({'type': 'request', 'id': 'list', 'uri': 'ssap://com.webos.applicationManager/listLaunchPoints'})
            elif m.get('id') == 'list':
                points = (m.get('payload') or {}).get('launchPoints')
                if not isinstance(points, list):
                    sys.exit('The TV sent no app list: %s' % json.dumps(m)[:300])
                return points
    except socket.timeout:
        sys.exit('No answer from the TV in 90 s: was the prompt on the TV missed?')
    finally:
        ws.close()


def fetch_icon(ip, url, folder, app_id):
    """The icon's file name in folder, or '' when it isn't an image on the TV itself."""
    if not url:
        return ''
    parts = urllib.parse.urlsplit(url)
    ext = os.path.splitext(parts.path)[1].lower()
    if parts.scheme not in ('http', 'https') or parts.hostname != ip or ext not in IMAGE_KINDS:
        return ''
    ctx = None
    if parts.scheme == 'https':   # port 3001 serves them with the TV's own certificate
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(url, timeout=10, context=ctx) as r:
            if not r.headers.get('Content-Type', 'image/').startswith('image/'):
                return ''
            data = r.read()
    except OSError:
        return ''
    name = re.sub(r'[^\w.-]', '_', app_id) + ext
    with open(os.path.join(folder, name), 'wb') as f:
        f.write(data)
    return name


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args or not re.match(r'^\d{1,3}(\.\d{1,3}){3}$', args[0]):
        sys.exit(__doc__)
    ip = args[0]
    try:
        points = launch_points(ip)
    except OSError as e:
        sys.exit('Couldn\'t reach the TV at %s:3000 or 3001 (%s).' % (ip, e))

    # The icons go to a new folder that replaces the old one only once the list is complete.
    folder = tempfile.mkdtemp(prefix='webos-icons-', dir=ROOT)
    try:
        apps = []
        for p in points:
            if not p.get('id'):
                continue
            icon = fetch_icon(ip, p.get('largeIcon') or p.get('icon'), folder, p['id'])
            apps.append({'id': p['id'], 'title': p.get('title') or p['id'], 'icon': 'appicons/' + icon if icon else '',
                         'color': p.get('bgColor') or p.get('iconColor') or ''})
        shutil.rmtree(ICONS, ignore_errors=True)
        os.rename(folder, ICONS)
    except BaseException:
        shutil.rmtree(folder, ignore_errors=True)
        raise
    with open(LIST, 'w', encoding='utf-8') as f:
        json.dump(apps, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print('%d apps (%d icons) in webos-apps.local.json: %s' % (len(apps), sum(1 for a in apps if a['icon']),
                                                              ', '.join(a['title'] for a in apps)))
    print('Now build and install: python3 webos/build.py && python3 webos/install.py %s' % ip)


if __name__ == '__main__':
    main()
