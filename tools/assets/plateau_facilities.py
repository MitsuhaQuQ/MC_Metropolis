"""The sizes of a town's public and neighbourhood buildings, from PLATEAU's heights and storeys joined to OSM's tags:
which buildings are schools, police boxes, fire stations, clinics, bathhouses... PLATEAU says only 文教厚生 or 官公庁;
OSM's building and amenity tags say which. For each kind: how many matched, the footprint's length and depth (its
oriented rectangle, PLATEAU's), the height, the storeys, the roof form.

    python -I tools/assets/plateau_facilities.py <plateau buildings.csv> <osm buildings.json> [more pairs...]

The CSV is plateau_buildings.py's; the JSON an Overpass `out tags geom` of buildings (osm_buildings.py's input).
PLATEAU (c) MLIT, CC BY 4.0; OSM (c) OpenStreetMap contributors, ODbL; only the statistics are kept.
"""
import csv
import json
import math
import sys

KINDS = {
    'school': lambda t: t.get('building') == 'school' or t.get('amenity') == 'school',
    'kindergarten': lambda t: t.get('building') == 'kindergarten' or t.get('amenity') in ('kindergarten', 'childcare'),
    'university': lambda t: t.get('building') in ('university', 'college') or t.get('amenity') in ('university', 'college'),
    'clinic': lambda t: t.get('amenity') in ('clinic', 'doctors', 'dentist') or t.get('healthcare') in ('clinic', 'doctor'),
    'police': lambda t: t.get('amenity') == 'police' or t.get('building') == 'police',
    'fire_station': lambda t: t.get('amenity') == 'fire_station' or t.get('building') == 'fire_station',
    'public_bath': lambda t: t.get('amenity') == 'public_bath',
    'post_office': lambda t: t.get('amenity') == 'post_office',
    'community_centre': lambda t: t.get('amenity') == 'community_centre' or t.get('building') == 'civic',
    'supermarket': lambda t: t.get('shop') == 'supermarket',
    'convenience': lambda t: t.get('shop') == 'convenience',
    'bank': lambda t: t.get('amenity') == 'bank',
    'hotel': lambda t: t.get('building') == 'hotel' or t.get('tourism') == 'hotel',
    'toilets': lambda t: t.get('amenity') == 'toilets' or t.get('building') == 'toilets',
}


def main(args):
    rows = {k: [] for k in KINDS}
    for csv_path, osm_path in zip(args[0::2], args[1::2]):
        cell = 0.0003
        grid = {}
        with open(csv_path, encoding='utf-8') as f:
            for r in csv.DictReader(f):
                try:
                    lat, lon = float(r['lat']), float(r['lon'])
                except ValueError:
                    continue
                grid.setdefault((int(lat / cell), int(lon / cell)), []).append(r)
        with open(osm_path, encoding='utf-8') as f:
            data = json.load(f)
        for e in data['elements']:
            t = e.get('tags', {})
            g = e.get('geometry') or []
            if not g:
                continue
            lat = sum(p['lat'] for p in g) / len(g)
            lon = sum(p['lon'] for p in g) / len(g)
            for kind, test in KINDS.items():
                if not test(t):
                    continue
                best = None
                bd = 1e9
                ci, cj = int(lat / cell), int(lon / cell)
                for i in range(ci - 1, ci + 2):
                    for j in range(cj - 1, cj + 2):
                        for r in grid.get((i, j), []):
                            d = math.hypot((float(r['lat']) - lat) * 110540, (float(r['lon']) - lon) * 91300)
                            if d < bd:
                                bd, best = d, r
                if best is not None and bd < 12:
                    rows[kind].append(best)
                break

    def q(v, f):
        v = sorted(v)
        return v[int(f * (len(v) - 1))]

    for kind, rs in rows.items():
        if not rs:
            continue
        print(f'\n{kind}: {len(rs)}')
        for key in ('length', 'depth', 'height', 'storeys', 'area'):
            v = []
            for r in rs:
                try:
                    v.append(float(r[key]))
                except ValueError:
                    pass
            if v:
                print(f'  {key:8s} p10 {q(v, .1):7.1f} p25 {q(v, .25):7.1f} med {q(v, .5):7.1f} p75 {q(v, .75):7.1f} p90 {q(v, .9):7.1f}')
        roofs = {}
        for r in rs:
            roofs[r['roof_shape'] or '?'] = roofs.get(r['roof_shape'] or '?', 0) + 1
        print('  roofs', sorted(roofs.items(), key=lambda kv: -kv[1])[:6])


if __name__ == '__main__':
    main(sys.argv[1:])
