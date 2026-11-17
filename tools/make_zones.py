#!/usr/bin/env python3
"""Writes web/zones.js and app/assets/zones.json: each time zone's main city, with its country and position, from the
tz database.

    python3 tools/make_zones.py                      # reads /usr/share/zoneinfo (macOS, Linux)
    python3 tools/make_zones.py path/to/zoneinfo

The home screen uses it when it was built without a city (--generic, as in the GitHub release), on LG and Samsung
(zones.js) and on Google TV (zones.json, read by Place): the TV's time zone ("America/Sao_Paulo") gives the weather's
city, and its country (BR) the currency and TMDB region.
zone.tab and tzdata.zi are in the public domain.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Names the zone id can't spell (accents, or a city other than the one in the id).
NAMES = {
    'America/Sao_Paulo': 'São Paulo', 'America/Belem': 'Belém', 'America/Maceio': 'Maceió',
    'America/Araguaina': 'Araguaína', 'America/Cuiaba': 'Cuiabá', 'America/Santarem': 'Santarém',
    'America/Eirunepe': 'Eirunepé', 'America/Bahia': 'Salvador', 'America/Noronha': 'Fernando de Noronha',
    'America/Bogota': 'Bogotá', 'America/Asuncion': 'Asunción', 'America/Merida': 'Mérida',
    'America/Mazatlan': 'Mazatlán', 'America/Cancun': 'Cancún', 'America/Ciudad_Juarez': 'Ciudad Juárez',
    'America/Argentina/Cordoba': 'Córdoba', 'America/Argentina/Tucuman': 'Tucumán',
    'Asia/Ho_Chi_Minh': 'Ho Chi Minh City',
}


def degrees(text, width):
    """"+4230" or "-0740023" (±DDMM[SS] or ±DDDMM[SS]) → decimal degrees, 2 places."""
    sign = -1 if text[0] == '-' else 1
    d, m, s = int(text[1:1 + width]), int(text[1 + width:3 + width]), int(text[3 + width:5 + width] or 0)
    return round(sign * (d + m / 60 + s / 3600), 2)


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else '/usr/share/zoneinfo'
    zones = {}
    with open(os.path.join(base, 'zone.tab'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            country, coords, zone = line.rstrip('\n').split('\t')[:3]
            m = re.match(r'^([+-]\d+)([+-]\d+)$', coords)
            lat, lon = degrees(m.group(1), 2), degrees(m.group(2), 3)
            value = '%s,%s,%s' % (country, ('%g' % lat), ('%g' % lon))
            derived = zone.rsplit('/', 1)[-1].replace('_', ' ')
            if NAMES.get(zone, derived) != derived:
                value += ',' + NAMES[zone]
            zones[zone] = value
    # Old and alternate names the TV may report ("America/Buenos_Aires", "Asia/Calcutta"): "=" + the zone they mean.
    with open(os.path.join(base, 'tzdata.zi'), encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if len(parts) == 3 and parts[0] == 'L' and parts[1] in zones and parts[2] not in zones:
                zones[parts[2]] = '=' + parts[1]
    out = os.path.join(ROOT, 'app', 'assets', 'zones.json')
    with open(out, 'w', encoding='utf-8') as f:
        f.write(json.dumps(zones, ensure_ascii=False, separators=(',', ':'), sort_keys=True) + '\n')
    print('Wrote %s' % os.path.relpath(out, ROOT))


if __name__ == '__main__':
    main()
