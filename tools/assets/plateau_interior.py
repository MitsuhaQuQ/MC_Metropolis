"""Measures what fills the inside of a Japanese town's blocks, from PLATEAU buildings and OpenStreetMap streets: the
targets for City Builder's block interiors (behind the lots along the streets).

Run from the repository root (standard library only):
    python tools/assets/plateau_interior.py <buildings.csv> <streets.osm> <centre lat> <centre lon> [radius km]

Within the radius:
1. Each building (PLATEAU's footprint as an oriented rectangle: centre, length, depth, angle) is put by its centre:
   on the front if a street's edge (OSM residential and up) lies within 15 m; behind, on a lane, if a lane's edge
   (service ways, footways, paths, steps) lies within 8 m; else deep inside its block (reached by a private way: a
   flag lot's pole, a passage under a building). For each, the share of buildings and of footprint, the footprint's
   median, the storeys and the uses.
2. The land: streets and lanes drawn to their widths on a 2 m grid, each cell's distance to the nearest street edge;
   the land beyond 15 m of every street (a block's inside, past the lots along its streets) and how much of it the
   buildings cover, by distance band (15-25, 25-40, 40-60, over 60 m), and what share of it lies within 8 m of a lane.

The data: PLATEAU (the city's; site policy, CC BY 4.0 compatible) and OpenStreetMap (ODbL); statistics only.
"""
import csv
import math
import sys
from collections import defaultdict, deque

sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
import plateau_lots as lots  # noqa: E402

FRONT = 15.0
LANE = 8.0
GRID = 2.0
BANDS = ((15, 25), (25, 40), (40, 60), (60, 1e9))


def use_name(usage):
    return {'住宅': 'house', '共同住宅': 'flats', '店舗等併用住宅': 'shop-house', '店舗等併用共同住宅': 'shop-flats',
            '商業施設': 'shop', '業務施設': 'office', '文教厚生施設': 'school/temple/hospital', '工場': 'works',
            '運輸倉庫施設': 'warehouse', '官公庁施設': 'government', '宿泊施設': 'hotel', '供給処理施設': 'utility',
            '作業所併用住宅': 'workshop-house', '農林漁業用施設': 'farm'}.get(usage, usage or 'unknown')


def main():
    buildings_path, osm_path = sys.argv[1], sys.argv[2]
    lots.CENTRE[0], lots.CENTRE[1] = float(sys.argv[3]), float(sys.argv[4])
    radius = float(sys.argv[5]) * 1000 if len(sys.argv) > 5 else 1500.0
    segments = lots.load_streets(osm_path)
    streets = [s for s in segments if lots.CLASSES[s[1]] != 'lane']
    lanes = [s for s in segments if lots.CLASSES[s[1]] == 'lane']
    street_cells = lots.index(streets)
    lane_cells = lots.index(lanes)

    def edge_distance(x, z, segs, cells):
        best = 1e9
        for k in cells.get((int(math.floor(x / lots.CELL)), int(math.floor(z / lots.CELL))), ()):
            _, highway, a, b, _, length = segs[k]
            dx, dz = b[0] - a[0], b[1] - a[1]
            t = max(0.0, min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / (length * length)))
            d = math.hypot(a[0] + dx * t - x, a[1] + dz * t - z) - 0.5 * lots.WIDTHS[highway]
            best = min(best, d)
        return best

    # 1. The buildings by where they stand
    kinds = defaultdict(lambda: {'n': 0, 'area': 0.0, 'areas': [], 'storeys': [], 'uses': defaultdict(int)})
    rects = []
    for row in csv.DictReader(open(buildings_path, encoding='utf-8')):
        x, z = lots.metres(float(row['lat']), float(row['lon']))
        if math.hypot(x, z) > radius:
            continue
        area = float(row['area'] or 0)
        if area < 10:
            continue
        front = edge_distance(x, z, streets, street_cells)
        lane = edge_distance(x, z, lanes, lane_cells)
        kind = 'front' if front <= FRONT else 'lane' if lane <= LANE else 'deep'
        k = kinds[kind]
        k['n'] += 1
        k['area'] += area
        k['areas'].append(area)
        if row['storeys']:
            k['storeys'].append(float(row['storeys']))
        k['uses'][use_name(row['usage'])] += 1
        length = float(row['length'] or 0) or math.sqrt(area)
        depth = float(row['depth'] or 0) or area / max(1.0, length)
        rects.append((x, z, length, depth, math.radians(float(row['angle'] or 0))))
    total_n = sum(k['n'] for k in kinds.values())
    total_area = sum(k['area'] for k in kinds.values())
    print('Buildings within %.1f km: %d' % (radius / 1000, total_n))
    print('%-6s %6s %6s %8s %8s  %s' % ('where', 'share', 'area', 'p50 m2', 'storeys', 'uses (share of its buildings)'))
    for name in ('front', 'lane', 'deep'):
        k = kinds[name]
        if not k['n']:
            continue
        uses = sorted(k['uses'].items(), key=lambda u: -u[1])[:5]
        print('%-6s %5.0f%% %5.0f%% %8s %8s  %s' % (name, 100 * k['n'] / total_n, 100 * k['area'] / total_area,
                                                lots.percentiles(k['areas'], (50,))[0],
                                                lots.percentiles(k['storeys'], (50,))[0],
                                                ', '.join('%s %.0f%%' % (u, 100 * c / k['n']) for u, c in uses)))

    # 2. The land: distance from each cell to the nearest street edge, on the grid
    n = int(math.ceil(2 * radius / GRID))
    origin = -radius
    INF = 1 << 30
    dist = [INF] * (n * n)
    queue = deque()

    def draw(segs, mark):
        for _, highway, a, b, _, length in segs:
            half = 0.5 * lots.WIDTHS[highway]
            i0 = max(0, int((min(a[0], b[0]) - half - origin) / GRID))
            i1 = min(n - 1, int((max(a[0], b[0]) + half - origin) / GRID))
            j0 = max(0, int((min(a[1], b[1]) - half - origin) / GRID))
            j1 = min(n - 1, int((max(a[1], b[1]) + half - origin) / GRID))
            dx, dz = b[0] - a[0], b[1] - a[1]
            for i in range(i0, i1 + 1):
                x = origin + (i + 0.5) * GRID
                for j in range(j0, j1 + 1):
                    z = origin + (j + 0.5) * GRID
                    t = max(0.0, min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / (length * length)))
                    if math.hypot(a[0] + dx * t - x, a[1] + dz * t - z) <= half:
                        mark(i * n + j)

    def street(k):
        if dist[k] != 0:
            dist[k] = 0
            queue.append(k)
    draw(streets, street)
    # Breadth first in steps of the grid (a city-block distance, close enough at these spans), to 80 m
    limit = int(80 / GRID)
    while queue:
        k = queue.popleft()
        d = dist[k]
        if d >= limit:
            continue
        i, j = divmod(k, n)
        for q in ((k - n) if i > 0 else -1, (k + n) if i < n - 1 else -1, (k - 1) if j > 0 else -1, (k + 1) if j < n - 1 else -1):
            if q >= 0 and dist[q] > d + 1:
                dist[q] = d + 1
                queue.append(q)
    lane_near = bytearray(n * n)

    def near_lane(k):
        lane_near[k] = 1
    # Lanes drawn 8 m wider either side: the land a lane reaches
    widened = [(w, h, a, b, al, le) for (w, h, a, b, al, le) in lanes]
    saved = dict(lots.WIDTHS)
    for key in list(lots.WIDTHS):
        lots.WIDTHS[key] = saved[key] + 2 * LANE
    draw(widened, near_lane)
    lots.WIDTHS.clear()
    lots.WIDTHS.update(saved)
    covered = bytearray(n * n)
    for x, z, length, depth, angle in rects:
        ux, uz = math.cos(angle), math.sin(angle)
        reach = 0.5 * math.hypot(length, depth)
        for i in range(max(0, int((x - reach - origin) / GRID)), min(n - 1, int((x + reach - origin) / GRID)) + 1):
            cx = origin + (i + 0.5) * GRID
            for j in range(max(0, int((z - reach - origin) / GRID)), min(n - 1, int((z + reach - origin) / GRID)) + 1):
                cz = origin + (j + 0.5) * GRID
                u = (cx - x) * ux + (cz - z) * uz
                v = -(cx - x) * uz + (cz - z) * ux
                if abs(u) <= 0.5 * length and abs(v) <= 0.5 * depth:
                    covered[i * n + j] = 1
    print()
    print('Land by distance from a street edge (inside the circle), its building cover and its reach to a lane:')
    print('%-10s %9s %7s %8s' % ('band m', 'area ha', 'cover', 'by lane'))
    front_land = [0, 0]
    for k in range(n * n):
        i, j = divmod(k, n)
        if math.hypot(origin + (i + 0.5) * GRID, origin + (j + 0.5) * GRID) > radius:
            continue
        if 0 < dist[k] * GRID <= FRONT:
            front_land[0] += 1
            front_land[1] += covered[k]
    print('%-10s %9.1f %6.0f%% %8s' % ('0-15', front_land[0] * GRID * GRID / 1e4, 100 * front_land[1] / max(1, front_land[0]), '-'))
    for lo, hi in BANDS:
        cells = cover = lane = 0
        for k in range(n * n):
            d = dist[k] * GRID
            if not (lo < d <= hi) and not (hi > 1e8 and dist[k] == INF):
                continue
            i, j = divmod(k, n)
            if math.hypot(origin + (i + 0.5) * GRID, origin + (j + 0.5) * GRID) > radius:
                continue
            cells += 1
            cover += covered[k]
            lane += lane_near[k]
        print('%-10s %9.1f %6.0f%% %7.0f%%' % ('%d-%s' % (lo, '' if hi > 1e8 else int(hi)), cells * GRID * GRID / 1e4,
                                              100 * cover / max(1, cells), 100 * lane / max(1, cells)))


if __name__ == '__main__':
    main()
