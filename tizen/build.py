#!/usr/bin/env python3
"""Builds Free My TV for Samsung TVs (Tizen): FreeMyTV-tizen.wgt, the app package the TV installs.

    python3 tizen/build.py                 # → FreeMyTV-tizen.wgt, not signed yet
    python3 tizen/build.py --sign PROFILE  # signed with a certificate profile of Samsung's Tizen tools
    python3 tizen/build.py --preview       # also leaves the files in out/tizen/ to open index.html in a browser
    python3 tizen/build.py --config other.properties
    python3 tizen/build.py --generic       # without config.properties: the TV's time zone picks the city (releases)

The same home screen as on LG (web/), with tizen/config.xml and tizen/icon.png, in a zip as Tizen Studio makes it.
TVs only install signed packages. TizenBrew Installer signs it with the person's Samsung certificate on 2023 and
newer TVs; --sign uses `tizen package` (Tizen Studio, or Samsung's tizen-sdk-skills) with a profile made there.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.dom.minidom
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import webapp  # noqa: E402

OUT = os.path.join(ROOT, 'out', 'tizen')
WGT = os.path.join(ROOT, 'FreeMyTV-tizen.wgt')


def tizen_cli():
    """Samsung's `tizen` command (Tizen Studio or the Tizen SDK), or None."""
    name = 'tizen.bat' if os.name == 'nt' else 'tizen'
    home = os.path.expanduser('~')
    for base in (os.environ.get('TIZEN_STUDIO', ''), os.path.join(home, 'tizen-studio'),
                 os.path.join(home, 'tizen-sdk'), 'C:\\tizen-studio'):
        path = os.path.join(base, 'tools', 'ide', 'bin', name)
        if base and os.path.exists(path):
            return path
    return shutil.which('tizen')


def sign(profile):
    cli = tizen_cli()
    if not cli:
        sys.exit('Signing needs Samsung\'s Tizen tools (Tizen Studio, or Samsung\'s tizen-sdk-skills). Not found: set '
                 'TIZEN_STUDIO to where they are installed.')
    out = tempfile.mkdtemp()
    try:
        run = subprocess.run([cli, 'package', '-t', 'wgt', '-s', profile, '-o', out, '--', WGT],
                             capture_output=True, text=True)
        made = [n for n in os.listdir(out) if n.endswith('.wgt')]
        if run.returncode != 0 or not made:
            sys.exit('`tizen package` didn\'t sign it:\n' + (run.stdout + run.stderr).strip())
        shutil.move(os.path.join(out, made[0]), WGT)
    finally:
        shutil.rmtree(out, ignore_errors=True)
    with zipfile.ZipFile(WGT) as z:
        names = z.namelist()
    if 'author-signature.xml' not in names or 'signature1.xml' not in names:
        sys.exit('The package came back without its signatures.')


# samsung_home_button=true: what the page needs to bring itself back after Home (web/tv.js, watchBack). Only then,
# because every TV that installs the package reads this, the feature on or off: with background-support the page keeps
# running behind every app (Samsung's FAQ says to leave it on "disable"), and Tizen's own reference says a package
# with required_version under 2.4 (ours is 2.3, for 2015 TVs) and a reload attribute fails to install. A 2023 TV on
# Tizen 9 took it as it is here; no older one has tried.
HOME_BUTTON = '''    <tizen:app-control>
        <tizen:src name="index.html" reload="disable"/>
        <tizen:operation name="http://tizen.org/appcontrol/operation/default"/>
    </tizen:app-control>
'''


def manifest_for(config):
    """tizen/config.xml, with what samsung_home_button needs when the settings ask for it."""
    with open(os.path.join(ROOT, 'tizen', 'config.xml'), encoding='utf-8') as f:
        manifest = f.read()
    if config.get('homeButton'):
        profile, off = '    <tizen:profile name="tv-samsung"/>\n', 'background-support="disable"'
        if manifest.count(profile) != 1 or manifest.count(off) != 1:
            sys.exit('tizen/config.xml changed: samsung_home_button no longer finds where its lines go (tizen/build.py).')
        manifest = manifest.replace(profile, HOME_BUTTON + profile).replace(off, 'background-support="enable"')
    xml.dom.minidom.parseString(manifest.encode('utf-8'))   # a manifest that isn't XML only fails on the TV
    return manifest


def build(preview, config, profile):
    manifest = manifest_for(config)
    app_id = re.search(r'<tizen:application id="([^"]+)"', manifest).group(1)
    version = re.search(r'<widget [^>]*version="([^"]+)"', manifest).group(1)
    app = webapp.files(config, 'tizen/build.py')
    app['config.xml'] = manifest.encode('utf-8')
    with open(os.path.join(ROOT, 'tizen', 'icon.png'), 'rb') as f:
        app['icon.png'] = f.read()

    with zipfile.ZipFile(WGT, 'w', zipfile.ZIP_DEFLATED) as z:
        for path in sorted(app):
            z.writestr(path, app[path])
    if profile:
        sign(profile)

    if preview:
        webapp.preview(app, OUT)
        print('Preview: open out/tizen/index.html (or serve out/tizen) in a browser.')
    print('Built %s (%s %s, %d KB, %s)' % (os.path.basename(WGT), app_id, version, os.path.getsize(WGT) // 1024,
                                           'signed with "%s"' % profile if profile else 'not signed'))
    if config.get('homeButton'):
        print('With samsung_home_button: the app keeps running behind the apps it opens (Home Settings > Home button).')
    if not config['tmdbKey']:
        print('No TMDB key built in: the picks come once one is typed on the TV (Home Settings > TMDB key).')


if __name__ == '__main__':
    args = sys.argv[1:]
    profile = args[args.index('--sign') + 1] if '--sign' in args else None
    build('--preview' in args, webapp.config_from_args(args), profile)
