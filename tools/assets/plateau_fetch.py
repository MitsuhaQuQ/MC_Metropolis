"""Downloads part of a PLATEAU city: the building and road meshes within a radius of a point, and the building
codelists, packed as a zip laid out as PLATEAU's own (udx/bldg, udx/tran, codelists) for plateau_buildings.py.

Run from the repository root (standard library only):
    python tools/assets/plateau_fetch.py <city code> <name> <lat> <lon> <radius km> [out dir] [--kinds=bldg,tran]

--kinds picks the feature types (PLATEAU's udx folders), e.g. --kinds=brid,trk for bridges and tracks.

The meshes are listed by the PLATEAU data catalogue (api.plateauview.mlit.go.jp); a radius of 99 takes the whole city.
Files already downloaded are kept. Source: 3D city models of Project PLATEAU (MLIT; copyright of the local government),
under the PLATEAU site policy ("３．著作権について"); for statistics, nothing of it shipped.
"""
import json
import math
import os
import sys
import urllib.request
import zipfile

CATALOGUE = 'https://api.plateauview.mlit.go.jp/datacatalog/citygml/'
CODELISTS = ['Building_usage.xml', 'Building_roofType.xml', 'BuildingDetailAttribute_buildingStructureType.xml',
             'BuildingDetailAttribute_detailedUsage.xml']
AGENT = {'User-Agent': 'citybuilder-tools/1.0'}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=120) as response:
        return response.read()


def centre(code):
    """The middle of a third-level (1 km) mesh."""
    lat = int(code[0:2]) / 1.5 + int(code[4]) * 5 / 60 + int(code[6]) * 30 / 3600 + 15 / 3600
    lon = int(code[2:4]) + 100 + int(code[5]) * 7.5 / 60 + int(code[7]) * 45 / 3600 + 22.5 / 3600
    return lat, lon


def km(a, b):
    return math.hypot((a[0] - b[0]) * 111.32, (a[1] - b[1]) * 111.32 * math.cos(math.radians(a[0])))


def main():
    kinds = ('bldg', 'tran')
    for a in [a for a in sys.argv if a.startswith('--kinds=')]:
        kinds = tuple(a[len('--kinds='):].split(','))
        sys.argv.remove(a)
    code, name, lat, lon, radius = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])
    out = sys.argv[6] if len(sys.argv) > 6 else os.path.join('core', 'build', 'plateau')
    city = json.loads(get(CATALOGUE + code))['cities'][0]
    base = city['url'][:-len('.zip')]
    folder = os.path.join(out, name)
    files = []
    for kind in kinds:
        for f in city['files'].get(kind, []):
            if len(f['code']) == 8 and km(centre(f['code']), (lat, lon)) <= radius:
                files.append((kind, f['url']))
    total = 0
    for kind, url in files:
        path = os.path.join(folder, 'udx', kind, url.rsplit('/', 1)[1])
        if not os.path.exists(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
            try:
                data = get(url)
            except Exception as e:  # listed by the catalogue but not there: left out, said so
                print('missing %s %s: %s' % (kind, url.rsplit('/', 1)[1], e), flush=True)
                continue
            with open(path, 'wb') as f:
                f.write(data)
        total += os.path.getsize(path)
        print('%s %s %.1f MB' % (kind, os.path.basename(path), os.path.getsize(path) / 1e6), flush=True)
    for listed in CODELISTS:
        path = os.path.join(folder, 'codelists', listed)
        if not os.path.exists(path):
            try:
                data = get(base + '/codelists/' + listed)
            except Exception as e:  # a city without the list: its codes stay untranslated
                print('no codelist %s: %s' % (listed, e))
                continue
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'wb') as f:
                f.write(data)
    # Packed as PLATEAU's own zip, stored (the GML compresses well but is read once)
    packed = os.path.join(out, name + '.zip')
    with zipfile.ZipFile(packed, 'w', zipfile.ZIP_STORED) as z:
        for root, _, names in os.walk(folder):
            for n in names:
                full = os.path.join(root, n)
                z.write(full, os.path.relpath(full, folder).replace(os.sep, '/'))
    print('%s: %d meshes, %.0f MB, packed in %s (%s %s, %s)' % (name, len(files), total / 1e6, packed,
                                                                 city['cityName'], city['year'], city['spec']))


if __name__ == '__main__':
    main()
