"""Measures a Japanese town's lots from PLATEAU buildings and OpenStreetMap streets, the targets for City Builder's L7
plots.

Run from the repository root (standard library only):
    python tools/assets/plateau_lots.py <buildings.csv> <streets.osm> [centre lat] [centre lon]
e.g.
    python tools/assets/plateau_lots.py core/build/plateau/kyoto-buildings.csv core/build/osm/kyoto.osm
The centre the rings are measured from defaults to Kyoto's Shijo-Karasuma.

Each building (its footprint's centre, from PLATEAU, a CSV written by plateau_buildings.py) is put to the street or
lane (OSM; service ways, footways and paths as lanes) whose edge lies nearest within 15 m: its side of the street and
its place along it. Along each side of each street the buildings in order give the frontage pitch (the distance from
one to the next, what a lot's frontage is where buildings stand side by side), and each building's back edge how deep
the lots run from the street's edge. Reported by distance from the centre and by the street's class; buildings with no
street or lane within 15 m stand in the blocks' interiors. Lengths in metres, a block each.

The data: PLATEAU (Project PLATEAU, the city's; site policy, CC BY 4.0 compatible) and OpenStreetMap (ODbL);
statistics only, nothing of either is shipped.
"""
import csv
import math
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

CENTRE = [35.0037, 135.7596]  # Kyoto's Shijo-Karasuma, unless given
WIDTHS = {'trunk': 16, 'primary': 16, 'secondary': 12, 'tertiary': 10, 'unclassified': 6, 'residential': 6,
          'living_street': 5, 'service': 3, 'footway': 2, 'path': 2, 'pedestrian': 3, 'steps': 2}
CLASSES = {'trunk': 'arterial', 'primary': 'arterial', 'secondary': 'collector', 'tertiary': 'collector',
           'unclassified': 'street', 'residential': 'street', 'living_street': 'street', 'service': 'lane',
           'footway': 'lane', 'path': 'lane', 'pedestrian': 'lane', 'steps': 'lane'}
REACH = 15.0
CELL = 50.0


def metres(lat, lon):
    return ((lon - CENTRE[1]) * 111320.0 * math.cos(math.radians(CENTRE[0])), (CENTRE[0] - lat) * 111320.0)


def load_streets(path):
    nodes = {}
    ways = []
    for event, element in ET.iterparse(path, events=('end',)):
        if element.tag == 'node':
            nodes[element.get('id')] = metres(float(element.get('lat')), float(element.get('lon')))
            element.clear()
        elif element.tag == 'way':
            tags = {t.get('k'): t.get('v') for t in element.findall('tag')}
            highway = (tags.get('highway') or '').replace('_link', '')
            if highway in WIDTHS:
                refs = [nd.get('ref') for nd in element.findall('nd')]
                ways.append((element.get('id'), highway, refs))
            element.clear()
    segments = []
    for way, highway, refs in ways:
        points = [nodes[r] for r in refs if r in nodes]
        along = 0.0
        for a, b in zip(points, points[1:]):
            length = math.hypot(b[0] - a[0], b[1] - a[1])
            if length > 0.01:
                segments.append((way, highway, a, b, along, length))
            along += length
    return segments


def index(segments):
    cells = defaultdict(list)
    for k, (_, _, a, b, _, _) in enumerate(segments):
        for cx in range(int(math.floor((min(a[0], b[0]) - REACH - 10) / CELL)), int(math.floor((max(a[0], b[0]) + REACH + 10) / CELL)) + 1):
            for cz in range(int(math.floor((min(a[1], b[1]) - REACH - 10) / CELL)), int(math.floor((max(a[1], b[1]) + REACH + 10) / CELL)) + 1):
                cells[(cx, cz)].append(k)
    return cells


def percentiles(values, ps=(25, 50, 75)):
    if not values:
        return ['-'] * len(ps)
    values = sorted(values)
    return ['%.1f' % values[min(len(values) - 1, int(len(values) * p / 100))] for p in ps]


def ring(distance):
    for limit, name in ((500, '0-0.5 km'), (1000, '0.5-1 km'), (2000, '1-2 km'), (3000, '2-3 km')):
        if distance < limit:
            return name
    return '3 km+'


def main():
    buildings_path, osm_path = sys.argv[1], sys.argv[2]
    if len(sys.argv) > 4:
        CENTRE[0], CENTRE[1] = float(sys.argv[3]), float(sys.argv[4])
    segments = load_streets(osm_path)
    cells = index(segments)
    xs = [p for s in segments for p in (s[2][0], s[3][0])]
    zs = [p for s in segments for p in (s[2][1], s[3][1])]
    box = (min(xs), min(zs), max(xs), max(zs))
    # Each building to its street side: (way, side) -> [(along, building)]
    sides = defaultdict(list)
    interior = defaultdict(int)
    total = defaultdict(int)
    depth = defaultdict(list)
    with open(buildings_path, encoding='utf-8') as f:
        for row in csv.DictReader(f):
            x, z = metres(float(row['lat']), float(row['lon']))
            if not (box[0] + 50 < x < box[2] - 50 and box[1] + 50 < z < box[3] - 50):
                continue
            short = float(row['depth'] or 0)
            long_ = float(row['length'] or 0)
            area = float(row['area'] or 0)
            if area < 15:
                continue
            rg = ring(math.hypot(x, z))
            total[rg] += 1
            best = None
            for k in cells.get((int(math.floor(x / CELL)), int(math.floor(z / CELL))), ()):
                way, highway, a, b, along0, length = segments[k]
                dx, dz = b[0] - a[0], b[1] - a[1]
                t = max(0.0, min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / (length * length)))
                edge = math.hypot(a[0] + dx * t - x, a[1] + dz * t - z) - WIDTHS[highway] / 2
                if edge <= REACH and (best is None or edge < best[0]):
                    side = 1 if (x - a[0]) * -dz + (z - a[1]) * dx >= 0 else -1
                    best = (edge, way, highway, along0 + t * length, side, dx / length, dz / length)
            if best is None:
                interior[rg] += 1
                continue
            edge, way, highway, along, side, ux, uz = best
            # The footprint's half extent square to the street, from its rectangle's angle (degrees from east)
            angle = math.radians(float(row['angle'] or 0))
            across = abs(-math.sin(angle) * -uz + math.cos(angle) * ux)
            half_across = 0.5 * (long_ * across + short * math.sqrt(max(0.0, 1 - across * across)))
            depth[(rg, CLASSES[highway])].append(max(0.0, edge + half_across))
            sides[(way, side)].append((along, rg, CLASSES[highway]))
    pitch = defaultdict(list)
    for buildings in sides.values():
        buildings.sort()
        for (a1, rg, cls), (a2, _, _) in zip(buildings, buildings[1:]):
            gap = a2 - a1
            if 1.0 < gap < 80.0:
                pitch[(rg, cls)].append(gap)
    rings = ['0-0.5 km', '0.5-1 km', '1-2 km', '2-3 km', '3 km+']
    print('Buildings within 15 m of a street, along it: frontage pitch (centre to centre) and depth (street edge to')
    print('the building\'s back) p25/p50/p75, by distance from the centre and street class')
    print('%-10s %-10s %7s  %-20s %-20s' % ('ring', 'street', 'count', 'pitch', 'depth'))
    for rg in rings:
        for cls in ('arterial', 'collector', 'street', 'lane'):
            p = pitch[(rg, cls)]
            if len(p) < 30:
                continue
            print('%-10s %-10s %7d  %-20s %-20s' % (rg, cls, len(p), '/'.join(percentiles(p)), '/'.join(percentiles(depth[(rg, cls)]))))
    print()
    print('Buildings with no street or lane within 15 m (the blocks\' true interiors), share of all by ring')
    for rg in rings:
        if total[rg]:
            print('  %-10s %6d of %6d, %.0f%%' % (rg, interior[rg], total[rg], 100.0 * interior[rg] / total[rg]))


if __name__ == '__main__':
    main()
