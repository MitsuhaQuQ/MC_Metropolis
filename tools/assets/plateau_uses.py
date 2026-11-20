"""Measures what a Japanese town's streets and facilities are for, from PLATEAU buildings and OpenStreetMap streets: the
targets for City Builder's street functions and facility sites.

Run from the repository root (standard library only):
    python tools/assets/plateau_uses.py <buildings.csv> <streets.osm> <centre lat> <centre lon> [radius km]

1. Street functions: each building (a CSV written by plateau_buildings.py) goes to the street or lane whose edge lies
   nearest within 15 m (as plateau_lots.py); each OSM way is then a shopping street where shops (shop-houses and
   commercial buildings) make half its buildings, an office street where offices make 40%, a residential street where
   houses and flats make 70%, else mixed; reported as shares of street length by the street's class and distance from
   the centre (ways with fewer than 6 buildings left out).
2. Facilities: buildings of one use within 30 m of each other joined into one site (a school's or temple's buildings),
   counted per km2 within the radius, with their footprints' sums; by the detailed use where the data has it (Tokyo),
   else by the main use.

The data: PLATEAU (the city's; site policy, CC BY 4.0 compatible) and OpenStreetMap (ODbL); statistics only.
"""
import csv
import math
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
import plateau_lots as lots  # noqa: E402

SHOPS = {'店舗等併用住宅', '商業施設', '店舗等併用共同住宅'}
OFFICES = {'業務施設'}
HOMES = {'住宅', '共同住宅'}
FACILITY_DETAILED = {'教育施設': 'school', '医療施設': 'hospital', '宗教施設': 'temple/shrine', '官公庁施設': 'government',
                     '文化施設': 'culture', '厚生施設': 'welfare', 'スポーツ施設': 'sports', '公衆浴場等': 'bathhouse',
                     '宿泊施設': 'hotel'}
FACILITY_MAIN = {'文教厚生施設': 'schools, hospitals, temples (all)', '官公庁施設': 'government', '宿泊施設': 'hotel'}
JOIN = 30.0


def main():
    buildings_path, osm_path = sys.argv[1], sys.argv[2]
    lots.CENTRE[0], lots.CENTRE[1] = float(sys.argv[3]), float(sys.argv[4])
    radius = float(sys.argv[5]) * 1000 if len(sys.argv) > 5 else 2000.0
    segments = lots.load_streets(osm_path)
    cells = lots.index(segments)
    xs = [p for s in segments for p in (s[2][0], s[3][0])]
    zs = [p for s in segments for p in (s[2][1], s[3][1])]
    box = (min(xs), min(zs), max(xs), max(zs))
    way_length = defaultdict(float)
    way_class = {}
    way_mid = {}
    for way, highway, a, b, along, length in segments:
        way_length[way] += length
        way_class[way] = lots.CLASSES[highway]
        way_mid.setdefault(way, a)
    way_uses = defaultdict(lambda: defaultdict(int))
    sites = defaultdict(list)
    detailed_seen = False
    with open(buildings_path, encoding='utf-8') as f:
        for row in csv.DictReader(f):
            x, z = lots.metres(float(row['lat']), float(row['lon']))
            if not (box[0] + 50 < x < box[2] - 50 and box[1] + 50 < z < box[3] - 50):
                continue
            usage = row['usage'] or '不明'
            area = float(row['area'] or 0)
            detailed = row.get('detailed_usage') or ''
            if detailed:
                detailed_seen = True
            if math.hypot(x, z) <= radius:
                kind = FACILITY_DETAILED.get(detailed) if detailed else FACILITY_MAIN.get(usage)
                if kind:
                    sites[kind].append((x, z, area))
            best = None
            for k in cells.get((int(math.floor(x / lots.CELL)), int(math.floor(z / lots.CELL))), ()):
                way, highway, a, b, along0, length = segments[k]
                dx, dz = b[0] - a[0], b[1] - a[1]
                t = max(0.0, min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / (length * length)))
                edge = math.hypot(a[0] + dx * t - x, a[1] + dz * t - z) - lots.WIDTHS[highway] / 2
                if edge <= lots.REACH and (best is None or edge < best[0]):
                    best = (edge, way)
            if best is not None:
                way_uses[best[1]][usage] += 1
    # 1. Street functions by class and ring, as shares of length
    table = defaultdict(lambda: defaultdict(float))
    for way, uses in way_uses.items():
        total = sum(uses.values())
        if total < 6:
            continue
        shops = sum(uses[u] for u in SHOPS) / total
        offices = sum(uses[u] for u in OFFICES) / total
        homes = sum(uses[u] for u in HOMES) / total
        function = 'shopping' if shops >= 0.5 else 'office' if offices >= 0.4 else 'residential' if homes >= 0.7 else 'mixed'
        mid = way_mid[way]
        key = (lots.ring(math.hypot(*mid)), way_class[way])
        table[key][function] += way_length[way]
    print('Street functions, shares of length (ways with 6 buildings or more):')
    print('%-10s %-10s %8s  %9s %7s %12s %6s' % ('ring', 'street', 'km', 'shopping', 'office', 'residential', 'mixed'))
    for rg in ('0-0.5 km', '0.5-1 km', '1-2 km', '2-3 km'):
        for cls in ('arterial', 'collector', 'street', 'lane'):
            row = table.get((rg, cls))
            if not row:
                continue
            total = sum(row.values())
            if total < 1000:
                continue
            print('%-10s %-10s %8.1f  %8.0f%% %6.0f%% %11.0f%% %5.0f%%' % (rg, cls, total / 1000,
                  100 * row['shopping'] / total, 100 * row['office'] / total, 100 * row['residential'] / total,
                  100 * row['mixed'] / total))
    # 2. Facilities: buildings of a use within JOIN of each other as one site
    area_km2 = math.pi * radius * radius / 1e6
    print()
    print('Facility sites within %.1f km (%s), buildings within %d m joined:' % (radius / 1000,
          'by detailed use' if detailed_seen else 'by main use', JOIN))
    for kind, points in sorted(sites.items()):
        grid = defaultdict(list)
        for i, (x, z, a) in enumerate(points):
            grid[(int(x // JOIN), int(z // JOIN))].append(i)
        parent = list(range(len(points)))

        def find(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i
        for i, (x, z, a) in enumerate(points):
            gx, gz = int(x // JOIN), int(z // JOIN)
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    for j in grid.get((gx + dx, gz + dz), ()):
                        if j > i and math.hypot(points[j][0] - x, points[j][1] - z) <= JOIN:
                            parent[find(i)] = find(j)
        footprint = defaultdict(float)
        for i, (x, z, a) in enumerate(points):
            footprint[find(i)] += a
        sizes = sorted(footprint.values())
        print('  %-34s %5d sites, %5.1f per km2, footprint p50 %6.0f m2, p90 %6.0f m2' % (kind, len(sizes),
              len(sizes) / area_km2, sizes[len(sizes) // 2], sizes[len(sizes) * 9 // 10]))


if __name__ == '__main__':
    main()
