"""Statistics of real Japanese parks from OpenStreetMap, to set the size and the paths of the towns' parks.

    python -I tools/assets/osm_parks.py F:/MC/citybuilder/core/build/osm/kyoto-parks.json [more files...]

Each input is an Overpass `out tags geom` JSON with the parks (leisure=park ways and multipolygons) and the footpaths
(highway=footway|path|pedestrian|steps|cycleway) over an area. Per size class (街区 under 0.5 ha, 近隣 0.5-2,
地区 2-10, 総合 over 10): how many and how large; their shape (compactness, the aspect of their rectangle); the
paths inside (metres per hectare, the share of the park they take at 2.5 m wide, how many entrances cross the edge,
how many loops, the share of path within 6 m of the edge, the share of path nearly straight along the park's axes,
how many junctions per hectare). Data (c) OpenStreetMap contributors, ODbL; only the statistics are kept.
"""
import json
import math
import sys


def project(lat0):
    k = math.cos(math.radians(lat0))
    return lambda lat, lon: (lon * 111320.0 * k, lat * 110540.0)


def area(poly):
    a = 0.0
    for i in range(len(poly)):
        x1, z1 = poly[i]
        x2, z2 = poly[(i + 1) % len(poly)]
        a += x1 * z2 - x2 * z1
    return abs(a) / 2


def perimeter(poly):
    return sum(math.dist(poly[i], poly[(i + 1) % len(poly)]) for i in range(len(poly)))


def inside(poly, x, z):
    c = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, zi = poly[i]
        xj, zj = poly[j]
        if (zi > z) != (zj > z) and x < (xj - xi) * (z - zi) / (zj - zi + 1e-12) + xi:
            c = not c
        j = i
    return c


def edge_distance(poly, x, z):
    best = 1e18
    for i in range(len(poly)):
        ax, az = poly[i]
        bx, bz = poly[(i + 1) % len(poly)]
        dx, dz = bx - ax, bz - az
        t = max(0.0, min(1.0, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz + 1e-12)))
        best = min(best, math.hypot(ax + dx * t - x, az + dz * t - z))
    return best


def axis(poly):
    """The park's main direction (its points' principal axis) and the aspect of its oriented rectangle."""
    n = len(poly)
    mx = sum(p[0] for p in poly) / n
    mz = sum(p[1] for p in poly) / n
    sxx = sum((p[0] - mx) ** 2 for p in poly)
    szz = sum((p[1] - mz) ** 2 for p in poly)
    sxz = sum((p[0] - mx) * (p[1] - mz) for p in poly)
    theta = 0.5 * math.atan2(2 * sxz, sxx - szz)
    ux, uz = math.cos(theta), math.sin(theta)
    us = [(p[0] - mx) * ux + (p[1] - mz) * uz for p in poly]
    vs = [-(p[0] - mx) * uz + (p[1] - mz) * ux for p in poly]
    w, d = max(us) - min(us), max(vs) - min(vs)
    return theta, max(w, d) / max(1e-9, min(w, d))


def rings(element, proj):
    """A park's outer rings: a closed way, or a multipolygon's outer members joined end to end."""
    if element['type'] == 'way':
        g = element.get('geometry') or []
        if len(g) >= 4 and g[0] == g[-1]:
            return [[proj(p['lat'], p['lon']) for p in g[:-1]]]
        return []
    parts = [[(p['lat'], p['lon']) for p in m.get('geometry') or []]
             for m in element.get('members', []) if m.get('role') == 'outer' and m.get('geometry')]
    out = []
    while parts:
        ring = parts.pop(0)
        changed = True
        while ring[0] != ring[-1] and changed:
            changed = False
            for i, p in enumerate(parts):
                if p[0] == ring[-1]:
                    ring += p[1:]
                elif p[-1] == ring[-1]:
                    ring += p[::-1][1:]
                else:
                    continue
                parts.pop(i)
                changed = True
                break
        if len(ring) >= 4 and ring[0] == ring[-1]:
            out.append([proj(lat, lon) for lat, lon in ring[:-1]])
    return out


CLASSES = [('街区 <0.5ha', 0, 5000), ('近隣 0.5-2ha', 5000, 20000), ('地区 2-10ha', 20000, 100000),
           ('総合 >10ha', 100000, 1e18)]


def main(paths):
    parks = []
    ways = []
    for path in paths:
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
        lats = [p['lat'] for e in data['elements'] for p in (e.get('geometry') or [])[:1]]
        proj = project(sum(lats) / max(1, len(lats)))
        for e in data['elements']:
            if e.get('tags', {}).get('leisure') == 'park':
                for ring in rings(e, proj):
                    if len(ring) >= 3 and area(ring) >= 200:
                        parks.append(ring)
            elif e['type'] == 'way' and e.get('geometry'):
                ways.append([proj(p['lat'], p['lon']) for p in e['geometry']])
    # The paths by cell, for each park's own
    cell = 200.0
    grid = {}
    for w in ways:
        for k in range(1, len(w)):
            a, b = w[k - 1], w[k]
            key = (int((a[0] + b[0]) / 2 // cell), int((a[1] + b[1]) / 2 // cell))
            grid.setdefault(key, []).append((a, b))
    rows = {c[0]: [] for c in CLASSES}
    for poly in parks:
        A = area(poly)
        P = perimeter(poly)
        theta, aspect = axis(poly)
        xs = [p[0] for p in poly]
        zs = [p[1] for p in poly]
        segs = []
        for i in range(int(min(xs) // cell), int(max(xs) // cell) + 1):
            for j in range(int(min(zs) // cell), int(max(zs) // cell) + 1):
                segs.extend(grid.get((i, j), []))
        length = 0.0
        near_edge = 0.0
        axial = 0.0
        ends = {}
        entrances = 0
        for a, b in segs:
            ia = inside(poly, *a)
            ib = inside(poly, *b)
            if not ia and not ib:
                continue
            L = math.dist(a, b)
            if ia != ib:
                entrances += 1
                L *= 0.5
            length += L
            mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            if edge_distance(poly, *mid) < 6.0:
                near_edge += L
            ang = math.atan2(b[1] - a[1], b[0] - a[0]) - theta
            if min(abs(math.sin(ang)), abs(math.cos(ang))) < math.sin(math.radians(12)):
                axial += L
            for p in (a, b):
                key = (round(p[0], 1), round(p[1], 1))
                ends[key] = ends.get(key, 0) + 1
        nodes = len(ends)
        edges = sum(ends.values()) // 2
        junctions = sum(1 for v in ends.values() if v >= 3)
        loops = max(0, edges - nodes + 1) if nodes else 0
        ha = A / 10000
        for name, lo, hi in CLASSES:
            if lo <= A < hi:
                rows[name].append(dict(ha=ha, compact=4 * math.pi * A / (P * P), aspect=aspect,
                                       density=length / ha, share=length * 2.5 / A, entrances=entrances,
                                       loops=loops, edge=near_edge / max(1e-9, length), axial=axial / max(1e-9, length),
                                       junctions=junctions / ha, pathless=length < 1.0))
    print(f'{len(parks)} parks, {len(ways)} footpaths')

    def q(vals, f):
        vals = sorted(vals)
        return vals[int(f * (len(vals) - 1))] if vals else float('nan')

    for name, _, _ in CLASSES:
        r = rows[name]
        if not r:
            continue
        with_paths = [x for x in r if not x['pathless']]
        print(f'\n{name}: {len(r)} parks, {sum(x["pathless"] for x in r)} without mapped paths')
        for key in ('ha', 'compact', 'aspect'):
            v = [x[key] for x in r]
            print(f'  {key:10s} p10 {q(v, .1):7.2f}  p25 {q(v, .25):7.2f}  med {q(v, .5):7.2f}  p75 {q(v, .75):7.2f}  p90 {q(v, .9):7.2f}')
        for key in ('density', 'share', 'entrances', 'loops', 'edge', 'axial', 'junctions'):
            v = [x[key] for x in with_paths]
            if v:
                print(f'  {key:10s} p10 {q(v, .1):7.2f}  p25 {q(v, .25):7.2f}  med {q(v, .5):7.2f}  p75 {q(v, .75):7.2f}  p90 {q(v, .9):7.2f}')


if __name__ == '__main__':
    main(sys.argv[1:])
