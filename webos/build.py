#!/usr/bin/env python3
"""Builds Free My TV for LG webOS: FreeMyTV-webos.ipk, the app package the TV's Developer Mode installs.

    python3 webos/build.py            # → FreeMyTV-webos.ipk
    python3 webos/build.py --preview  # also leaves the files in out/webos/ to open index.html in a browser
    python3 webos/build.py --config other.properties
    python3 webos/build.py --generic  # without config.properties: the TV's time zone picks the city (releases)
    python3 webos/build.py --hd       # at 1280 x 720, for LG TVs that aren't 4K (install.py does this by itself)

Reads config.properties (the same file as the Android build), puts the web home screen (web/), the app's symbols
and font (app/assets), the coin logos (app/res/drawable) and webos/appinfo.json together, and packs them the way LG's
ares-package does (an ar archive with control.tar.gz and data.tar.gz), so no LG tools are needed. Python 3 only.
"""
import io
import json
import os
import sys
import tarfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import webapp  # noqa: E402

OUT = os.path.join(ROOT, 'out', 'webos')
IPK = os.path.join(ROOT, 'FreeMyTV-webos.ipk')
# A TV's app list and icons from webos/apps.py, for TVs that won't list their apps to the app (webOS 3).
APP_LIST = os.path.join(ROOT, 'webos-apps.local.json')
APP_ICONS = os.path.join(ROOT, 'webos-icons.local')


def tv_apps():
    """(the apps, {path in the app: icon bytes}) from webos/apps.py, or ([], {}) when it hasn't run here."""
    if not os.path.exists(APP_LIST):
        return [], {}
    with open(APP_LIST, encoding='utf-8') as f:
        apps = json.load(f)
    icons = {}
    for app in apps:
        if app.get('icon'):
            path = os.path.join(APP_ICONS, os.path.basename(app['icon']))
            if os.path.exists(path):
                with open(path, 'rb') as f:
                    icons[app['icon']] = f.read()
            else:
                app['icon'] = ''
    return apps, icons


def tar_gz(entries):
    """A .tar.gz from [(name, bytes or None for a directory)], owned by root, as ares-package makes them."""
    buf = io.BytesIO()
    now = int(time.time())
    with tarfile.open(fileobj=buf, mode='w:gz', format=tarfile.PAX_FORMAT) as tar:
        for name, data in entries:
            info = tarfile.TarInfo(name)
            info.mtime = now
            info.uid = info.gid = 0
            if data is None:
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                tar.addfile(info)
            else:
                info.size = len(data)
                info.mode = 0o644
                tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def ar(members):
    out = io.BytesIO()
    out.write(b'!<arch>\n')
    now = int(time.time())
    for name, data in members:
        out.write(('%-16s%-12d%-6d%-6d%-8s%-10d`\n' % (name, now, 0, 0, '100644', len(data))).encode('ascii'))
        out.write(data)
        if len(data) % 2:
            out.write(b'\n')
    return out.getvalue()


def ar_members(data):
    """[(name, bytes)] of an ar archive, as ar() writes them."""
    members, at = [], 8
    while at + 60 <= len(data):
        name, size = data[at:at + 16].decode('ascii').strip().rstrip('/'), int(data[at + 48:at + 58])
        members.append((name, data[at + 60:at + 60 + size]))
        at += 60 + size + size % 2
    return members


def _is_appinfo(name):
    return name.startswith('usr/palm/applications/') and name.endswith('/appinfo.json') and name.count('/') == 4


def resolution(ipk):
    """The resolution an .ipk's appinfo.json asks for ('' if it has none)."""
    for name, data in ar_members(ipk):
        if name == 'data.tar.gz':
            with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as tar:
                for member in tar.getmembers():
                    if _is_appinfo(member.name):
                        return json.loads(tar.extractfile(member).read().decode('utf-8')).get('resolution', '')
    return ''


def with_resolution(ipk, size):
    """The same .ipk asking for another resolution (install.py, for TVs that aren't 4K: #402)."""
    members = []
    for name, data in ar_members(ipk):
        if name == 'data.tar.gz':
            entries = []
            with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as tar:
                for member in tar.getmembers():
                    body = None if member.isdir() else tar.extractfile(member).read()
                    if _is_appinfo(member.name):
                        body = json.dumps(dict(json.loads(body.decode('utf-8')), resolution=size), indent=2).encode('utf-8')
                    entries.append((member.name, body))
            data = tar_gz(entries)
        members.append((name, data))
    return ar(members)


def build(preview, config, generic=False, hd=False):
    with open(os.path.join(ROOT, 'webos', 'appinfo.json'), encoding='utf-8') as f:
        info = json.load(f)
    app_id, version = info['id'], info['version']
    apps, icons = ([], {}) if generic else tv_apps()   # one TV's list never goes in a release
    if apps:
        config = dict(config, installedApps=apps)
    app = webapp.files(config, 'webos/build.py')
    app.update(icons)
    for name in ('appinfo.json', 'icon.png', 'largeIcon.png'):
        with open(os.path.join(ROOT, 'webos', name), 'rb') as f:
            app[name] = f.read()
    if hd:   # Full HD and HD LGs draw apps at 1280 x 720; asked for 1920 x 1080, some show them in the top left (#402)
        app['appinfo.json'] = json.dumps(dict(info, resolution='1280x720'), indent=2).encode('utf-8')

    base = 'usr/palm/applications/' + app_id
    entries = [('usr', None), ('usr/palm', None), ('usr/palm/applications', None), (base, None)]
    dirs = set()
    for path in sorted(app):
        parent = os.path.dirname(path)
        if parent and parent not in dirs:
            dirs.add(parent)
            entries.append((base + '/' + parent, None))
        entries.append((base + '/' + path, app[path]))
    # The JS service that reaches the TV's remote control API where the page's origin is refused (webos/service).
    service_id = app_id + '.service'
    with open(os.path.join(ROOT, 'webos', 'service', 'service.js'), 'rb') as f:
        service = {'service.js': f.read(),
                   'package.json': json.dumps({'name': service_id, 'version': version, 'main': 'service.js'},
                                              indent=2).encode('utf-8'),
                   'services.json': json.dumps({'id': service_id, 'description': 'Free My TV',
                                                'services': [{'name': service_id, 'description': 'Free My TV'}]},
                                               indent=2).encode('utf-8')}
    service_base = 'usr/palm/services/' + service_id
    entries += [('usr/palm/services', None), (service_base, None)]
    entries += [(service_base + '/' + name, service[name]) for name in sorted(service)]

    package = json.dumps({'id': app_id, 'version': version, 'app': app_id, 'services': [service_id]},
                         indent=2).encode('utf-8')
    entries += [('usr/palm/packages', None), ('usr/palm/packages/' + app_id, None),
                ('usr/palm/packages/' + app_id + '/packageinfo.json', package)]

    size = sum(len(d) for d in app.values()) + sum(len(d) for d in service.values()) + len(package)
    control = ('Package: %s\nVersion: %s\nSection: misc\nPriority: optional\nArchitecture: all\nInstalled-Size: %d\n'
               'Maintainer: Free My TV <https://freemytv.io>\nDescription: A fast, ad-free home screen.\n'
               'webOS-Package-Format-Version: 2\nwebOS-Packager-Version: 1.0.0\n' % (app_id, version, size))
    data = ar([('debian-binary', b'2.0\n'), ('control.tar.gz', tar_gz([('control', control.encode('utf-8'))])),
               ('data.tar.gz', tar_gz(entries))])
    with open(IPK, 'wb') as f:
        f.write(data)

    if preview:
        webapp.preview(app, OUT)
        print('Preview: open out/webos/index.html (or serve out/webos) in a browser.')
    print('Built %s (%s %s, %d KB%s)' % (os.path.basename(IPK), app_id, version, len(data) // 1024, ', 1280 x 720' if hd else ''))
    if apps:
        print('With the TV\'s app list from webos/apps.py (%d apps).' % len(apps))
    if not config['tmdbKey']:
        print('No TMDB key built in: the picks come once one is typed on the TV (Home Settings > TMDB key).')


if __name__ == '__main__':
    build('--preview' in sys.argv, webapp.config_from_args(sys.argv[1:]), '--generic' in sys.argv, '--hd' in sys.argv)
