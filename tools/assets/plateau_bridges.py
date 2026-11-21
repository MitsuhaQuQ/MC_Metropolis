"""Measures bridges and viaducts on PLATEAU, the sizes for City Builder's bridge, viaduct and station models
(core.building.rail).

    python -I tools/assets/plateau_bridges.py survey <plateau dir> <out>
    python -I tools/assets/plateau_bridges.py stations <buildings dir> <out>
    python -I tools/assets/plateau_bridges.py specs <out>
    gradlew :core:railModels -Pin=<out>/rail_specs.json -Pout=<out>/models
    python -I tools/assets/plateau_bridges.py build <out>

`specs` writes sample structures (SPECS) for core.building.rail; every size in them is a parameter of the generators,
the samples only show the range. `build` turns the generated models into structure templates (.nbt, 1.20.1), previews
(.png) and preview.html, as plateau_voxel.py does for the building library.

`stations` reads the <city>-buildings.csv that plateau_buildings.py writes (F:/MC/citybuilder/core/build/plateau) and
measures the platform canopies and station buildings of the stations in STATIONS.

<plateau dir> holds <city>.zip as plateau_fetch.py packs them with --kinds=brid,trk (F:/MC/citybuilder/core/build/
plateau/bridges). Writes <out>/bridges.csv, one row a bridge, and prints the percentiles by kind.

Each brid:Bridge is measured on its own surfaces, turned to its own axis (the long side of the least rectangle round
its outline): length and width; the deck's top (the floor surfaces, area weighted) and underside (the ceiling
surfaces); the ground (the lowest tenth of its points, where the piers stand); so the clearance under it and the
girder's depth. Piers are the construction elements marked 橋脚, or else the walls standing down from the underside to
the ground, gathered into bents across the axis: the spans between bents, the columns in a bent and their size.
Railings and sound walls (installations, or walls rising above the deck at its edges) give the parapet's height.

Kinds: PLATEAU's function code (道路橋 01, 鉄道橋 02, 横断歩道橋 07, ペデストリアンデッキ 08, ...); where it says 不明 (99,
most of central Tokyo's), by shape: width 7-13 m, deck 5 m or more up and 60 m or longer as a railway viaduct
("rail?"), wider and as high as an elevated road ("road?"), narrow ones with stairs as footbridges.

Source: 3D city models of Project PLATEAU (MLIT; copyright of each city), PLATEAU site policy (compatible with CC BY
4.0); statistics only, nothing of the meshes is shipped.
"""
import csv
import glob
import math
import os
import re
import struct
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = {
    'gml': 'http://www.opengis.net/gml',
    'brid': 'http://www.opengis.net/citygml/bridge/2.0',
    'core': 'http://www.opengis.net/citygml/2.0',
}
G = '{%s}' % NS['gml']
B = '{%s}' % NS['brid']
FUNCTION = {'01': 'road', '02': 'rail', '03': 'aqueduct', '04': 'cable', '05': 'side walk', '06': 'canal',
            '07': 'footbridge', '08': 'deck', '99': 'unknown'}
CLASS = {'01': 'girder', '02': 'truss', '03': 'arch', '04': 'rigid frame', '05': 'suspension', '06': 'cable-stayed',
         '99': 'unknown'}


def local(tag):
    return tag.rsplit('}', 1)[-1]


def rings_of(element):
    """Every polygon's exterior ring under an element, as [(lat, lon, z)]."""
    out = []
    for pos in element.iter(G + 'posList'):
        v = [float(t) for t in pos.text.split()]
        out.append([(v[i], v[i + 1], v[i + 2]) for i in range(0, len(v) - 2, 3)])
    return out


class Frame:
    """Local metres round a latitude, east x and north y."""

    def __init__(self, lat, lon):
        self.lat, self.lon = lat, lon
        self.kx = 111320.0 * math.cos(math.radians(lat))

    def __call__(self, p):
        return (p[1] - self.lon) * self.kx, (p[0] - self.lat) * 111320.0, p[2]


def hull(points):
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def min_rect(points):
    """The least-area rectangle round the points: (angle of its long side, length, width)."""
    h = hull(points)
    best = None
    for a, b in zip(h, h[1:] + h[:1]):
        ang = math.atan2(b[1] - a[1], b[0] - a[0])
        c, s = math.cos(ang), math.sin(ang)
        us = [p[0] * c + p[1] * s for p in h]
        vs = [-p[0] * s + p[1] * c for p in h]
        du, dv = max(us) - min(us), max(vs) - min(vs)
        if best is None or du * dv < best[0]:
            best = (du * dv, ang, du, dv) if du >= dv else (du * dv, ang + math.pi / 2, dv, du)
    if best is None:
        return 0.0, 0.0, 0.0
    return best[1], best[2], best[3]


def area_z(rings):
    """Area-weighted median height of near-horizontal surfaces, with their plan area."""
    items = []
    for r in rings:
        a = 0.0
        for p, q in zip(r, r[1:] + r[:1]):
            a += p[0] * q[1] - q[0] * p[1]
        a = abs(a) / 2
        zs = [p[2] for p in r]
        if a > 0.05 and max(zs) - min(zs) < max(1.0, 0.15 * math.sqrt(a)):
            items.append((sum(zs) / len(zs), a))
    if not items:
        return None, 0.0
    items.sort()
    total = sum(a for _, a in items)
    acc = 0.0
    for z, a in items:
        acc += a
        if acc >= total / 2:
            return z, total
    return items[-1][0], total


def deck_width(rings, cell=0.5):
    """The deck's width where it runs, not its outline's: the floor surfaces rasterised, each cell's distance to
    the deck's edge (chamfer), twice the ridge's distance. Returns (median, 10th, 90th percentile, plan length:
    the floor's area over its median width)."""
    rings = [r for r in rings if len(r) >= 3]
    if not rings:
        return None
    xs = [p[0] for r in rings for p in r]
    ys = [p[1] for r in rings for p in r]
    x0, y0 = min(xs) - 1, min(ys) - 1
    nx, ny = int((max(xs) - x0) / cell) + 3, int((max(ys) - y0) / cell) + 3
    if nx * ny > 6_000_000:
        cell *= 2
        nx, ny = int((max(xs) - x0) / cell) + 3, int((max(ys) - y0) / cell) + 3
    grid = bytearray(nx * ny)
    for r in rings:
        poly = [((p[0] - x0) / cell, (p[1] - y0) / cell) for p in r]
        lo, hi = int(min(p[1] for p in poly)), int(max(p[1] for p in poly)) + 1
        for j in range(max(lo, 0), min(hi, ny)):
            yc = j + 0.5
            cuts = []
            for (ax, ay), (bx, by) in zip(poly, poly[1:] + poly[:1]):
                if (ay > yc) != (by > yc):
                    cuts.append(ax + (yc - ay) * (bx - ax) / (by - ay))
            cuts.sort()
            for a, b in zip(cuts[0::2], cuts[1::2]):
                for i in range(max(int(a + 0.5), 0), min(int(b + 0.5), nx)):
                    grid[j * nx + i] = 1
    INF = 1 << 30
    d = [0 if not g else INF for g in grid]
    for j in range(1, ny - 1):
        row = j * nx
        for i in range(1, nx - 1):
            k = row + i
            if d[k]:
                d[k] = min(d[k], d[k - 1] + 3, d[k - nx] + 3, d[k - nx - 1] + 4, d[k - nx + 1] + 4)
    for j in range(ny - 2, 0, -1):
        row = j * nx
        for i in range(nx - 2, 0, -1):
            k = row + i
            if d[k]:
                d[k] = min(d[k], d[k + 1] + 3, d[k + nx] + 3, d[k + nx + 1] + 4, d[k + nx - 1] + 4)
    ridge = []
    for j in range(1, ny - 1):
        for i in range(1, nx - 1):
            k = j * nx + i
            v = d[k]
            if v and v >= d[k - 1] and v >= d[k + 1] and v >= d[k - nx] and v >= d[k + nx]                     and (v > d[k - 1] or v > d[k + 1]) and (v > d[k - nx] or v > d[k + nx]) or                     v and v >= max(d[k - 1], d[k + 1], d[k - nx], d[k + nx]) and v > min(d[k - 1], d[k + 1]) + 1:
                ridge.append(v)
    if not ridge:
        return None
    ws = [2 * v / 3 * cell for v in ridge]
    area = sum(grid) * cell * cell
    w = pct(ws, 0.5)
    return w, pct(ws, 0.1), pct(ws, 0.9), area / max(w, 0.5)


def pct(values, q):
    v = sorted(values)
    if not v:
        return float('nan')
    i = (len(v) - 1) * q
    lo = int(i)
    return v[lo] + (v[min(lo + 1, len(v) - 1)] - v[lo]) * (i - lo)


def measure(bridge, city):
    code = {}
    for key in ('function', 'class', 'yearOfConstruction'):
        e = bridge.find(B + key)
        if e is not None and e.text:
            code[key] = e.text.strip()
    name = bridge.find(G + 'name')
    surfaces = {}
    for bb in bridge.findall(B + 'boundedBy'):
        for s in bb:
            surfaces.setdefault(local(s.tag), []).extend(rings_of(s))
    piers, others = [], []
    for oc in bridge.findall(B + 'outerBridgeConstruction'):
        for el in oc:
            f = el.find(B + 'function')
            (piers if f is not None and f.text and f.text.strip() == '01' else others).append(rings_of(el))
    installs = []
    for oi in bridge.findall(B + 'outerBridgeInstallation'):
        for el in oi:
            f = el.find(B + 'function')
            installs.append(((f.text.strip() if f is not None and f.text else '90'), rings_of(el)))
    allr = [r for rs in surfaces.values() for r in rs] + [r for p in piers for r in p] \
        + [r for o in others for r in o] + [r for _, rs in installs for r in rs]
    if not allr:
        for el in bridge.iter():
            if local(el.tag) in ('lod1Solid', 'lod2Solid', 'lod3Solid', 'lod2MultiSurface'):
                allr.extend(rings_of(el))
    if not allr:
        return None
    pts = [p for r in allr for p in r]
    fr = Frame(sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
    loc = lambda rs: [[fr(p) for p in r] for r in rs]  # noqa: E731
    lp = [fr(p) for p in pts]
    ang, length, width = min_rect([(p[0], p[1]) for p in lp])
    c, s = math.cos(ang), math.sin(ang)
    u = lambda p: p[0] * c + p[1] * s  # noqa: E731
    v = lambda p: -p[0] * s + p[1] * c  # noqa: E731
    zs = sorted(p[2] for p in lp)
    ground = pct(zs, 0.03)
    top, top_area = area_z(loc(surfaces.get('OuterFloorSurface', []) + surfaces.get('RoofSurface', [])))
    under, _ = area_z(loc(surfaces.get('OuterCeilingSurface', [])))
    if top is None:  # LOD1 or unlabelled: the top of the solid
        top = pct(zs, 0.97)
    floor = loc(surfaces.get('OuterFloorSurface', []) + surfaces.get('RoofSurface', []))
    dw = deck_width([[(p[0], p[1]) for p in r] for r in floor if max(p[2] for p in r) > top - 1.5])
    row = {
        'city': city, 'id': bridge.get(G + 'id'), 'name': name.text if name is not None else '',
        'function': FUNCTION.get(code.get('function', '99'), code.get('function')),
        'class': CLASS.get(code.get('class', '99'), code.get('class')), 'year': code.get('yearOfConstruction', ''),
        'length': round(length, 1), 'width': round(width, 1), 'ground': round(ground, 2),
        'deck': round(top - ground, 2), 'clearance': round(under - ground, 2) if under is not None else '',
        'girder': round(top - under, 2) if under is not None else '',
        'deck_width': round(dw[0], 1) if dw else '', 'width_p10': round(dw[1], 1) if dw else '',
        'width_p90': round(dw[2], 1) if dw else '', 'run': round(dw[3], 1) if dw else '',
        'lon': round(fr.lon, 6), 'lat': round(fr.lat, 6), 'axis': round(math.degrees(ang) % 180, 1),
    }
    # Piers: marked ones, or else the walls reaching from near the ground to near the underside.
    columns = []
    for pr in piers:
        q = [p for r in loc(pr) for p in r]
        if q:
            columns.append(q)
    if not columns and under is not None and under - ground > 2.5:
        walls = loc(surfaces.get('WallSurface', []))
        for r in walls:
            rz = [p[2] for p in r]
            if min(rz) < ground + 1.0 and max(rz) > under - 1.0 and max(rz) < top + 0.5:
                columns.append(r)
    bents = []
    for q in columns:
        us, vs = [u(p) for p in q], [v(p) for p in q]
        bents.append(((min(us) + max(us)) / 2, (min(vs) + max(vs)) / 2, max(us) - min(us), max(vs) - min(vs)))
    bents.sort()
    groups = []
    for b in bents:  # walls of one column, or columns of one bent, lie within 2.5 m along the axis
        if groups and b[0] - groups[-1][-1][0] < 2.5:
            groups[-1].append(b)
        else:
            groups.append([b])
    if len(groups) >= 2:
        centres = [sum(b[0] for b in g) / len(g) for g in groups]
        spans = [b - a for a, b in zip(centres, centres[1:])]
        row['bents'] = len(groups)
        row['span'] = round(pct(spans, 0.5), 1)
        row['span_max'] = round(max(spans), 1)
        cols = []
        for g in groups:  # columns: distinct positions across the axis, 1.5 m apart
            vs = sorted(b[1] for b in g)
            n = 1 + sum(1 for a, b in zip(vs, vs[1:]) if b - a > 1.5)
            cols.append(n)
        row['columns'] = round(pct(cols, 0.5))
        if piers:
            row['pier_along'] = round(pct([b[2] for b in bents], 0.5), 1)
            row['pier_across'] = round(pct([b[3] for b in bents], 0.5), 1)
    # Parapets: installations marked 高欄/防護柵/遮音壁, or walls rising above the deck along its edges.
    para = {}
    for f, rs in installs:
        if f in ('05', '06', '07'):
            hz = max(p[2] for r in loc(rs) for p in r) - top
            if 0.3 < hz < 8:
                para[f] = max(para.get(f, 0), hz)
    if not para:
        rise = []
        for r in loc(surfaces.get('WallSurface', [])):
            rz = [p[2] for p in r]
            if min(rz) > top - 0.6 and 0.3 < max(rz) - top < 6:
                rise.append(max(rz) - top)
        if rise:
            para['edge'] = pct(rise, 0.5)
    row['parapet'] = round(max(para.values()), 2) if para else ''
    row['soundwall'] = 1 if '07' in para else ''
    row['stairs'] = sum(1 for f, _ in installs if f == '13')
    row['kind'] = kind(row)
    return row


def kind(row):
    f, d = row['function'], row['deck']
    w = row['deck_width'] if row.get('deck_width') not in ('', None) else row['width']
    ln = max(row['length'], row['run'] or 0) if row.get('run') not in ('', None) else row['length']
    if f not in ('unknown', None):
        return f
    if w < 6.5 and d > 4 and ln < 80:
        return 'footbridge?'
    if 7 <= w <= 13 and d >= 5 and ln >= 60:
        return 'rail?'
    if w > 13 and d >= 6 and ln >= 60:
        return 'road?'
    return 'unknown'


def survey(src, out):
    os.makedirs(out, exist_ok=True)
    rows = []
    for z in sorted(glob.glob(os.path.join(src, '*.zip'))):
        city = os.path.splitext(os.path.basename(z))[0]
        with zipfile.ZipFile(z) as Z:
            for n in Z.namelist():
                if '/brid/' not in n or not n.endswith('.gml'):
                    continue
                with Z.open(n) as fh:
                    for _, el in ET.iterparse(fh, events=('end',)):
                        if el.tag == B + 'Bridge':
                            r = measure(el, city)
                            if r:
                                rows.append(r)
                            el.clear()
        print('%s: %d bridges' % (city, sum(1 for r in rows if r['city'] == city)), flush=True)
    keys = ['city', 'id', 'name', 'kind', 'function', 'class', 'year', 'length', 'width', 'deck_width', 'width_p10', 'width_p90',
            'run', 'ground', 'deck',
            'clearance', 'girder', 'bents', 'span', 'span_max', 'columns', 'pier_along', 'pier_across', 'parapet',
            'soundwall', 'stairs', 'axis', 'lat', 'lon']
    with open(os.path.join(out, 'bridges.csv'), 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, keys, extrasaction='ignore')
        w.writeheader()
        for r in rows:
            w.writerow(r)
    report(rows)


def report(rows):
    kinds = sorted(set(r['kind'] for r in rows))
    print('%-12s %4s  %-20s %-20s %-20s %-20s %-20s %-20s %-16s %-16s' % (
        'kind', 'n', 'run', 'deck_width', 'deck', 'clearance', 'girder', 'span', 'parapet', 'pier'))
    for k in kinds:
        rs = [r for r in rows if r['kind'] == k]

        def q(key):
            v = [float(r[key]) for r in rs if r.get(key) not in ('', None)]
            if not v:
                return '-'
            return '%.1f/%.1f/%.1f (%d)' % (pct(v, .25), pct(v, .5), pct(v, .75), len(v))
        print('%-12s %4d  %-20s %-20s %-20s %-20s %-20s %-20s %-16s %-16s' % (
            k, len(rs), q('run'), q('deck_width'), q('deck'), q('clearance'), q('girder'), q('span'), q('parapet'),
            q('pier_across')))
        cols = [r['columns'] for r in rs if r.get('columns')]
        if cols:
            print('%16s columns a bent: %s' % ('', {n: cols.count(n) for n in sorted(set(cols))}))


# Stations whose platforms PLATEAU has as buildings (運輸施設 / 運輸倉庫施設): (city csv, name, lat, lon). Which are
# elevated is measured (the canopies' height), not taken from here.
STATIONS = [
    ('kyoto', '京都', 34.9858, 135.7588), ('kyoto', '二条', 35.0108, 135.7417), ('kyoto', '円町', 35.0146, 135.7322),
    ('kyoto', '丹波口', 34.9898, 135.7432), ('kyoto', '嵯峨嵐山', 35.0181, 135.6813), ('kyoto', '花園', 35.0168, 135.7203),
    ('kyoto', '太秦', 35.0175, 135.7105), ('kyoto', '山科', 34.9930, 135.8160), ('kyoto', '東福寺', 34.9811, 135.7695),
    ('kyoto', '稲荷', 34.9671, 135.7701), ('kyoto', '桂川', 34.9500, 135.7120), ('kyoto', '西大路', 34.9817, 135.7330),
    ('himeji', '姫路', 34.8268, 134.6907), ('kanazawa', '金沢', 36.5781, 136.6478),
    ('taito', '上野', 35.7138, 139.7770), ('taito', '御徒町', 35.7075, 139.7748), ('taito', '鶯谷', 35.7212, 139.7781),
    ('taito', '浅草橋', 35.6975, 139.7863), ('taito', '秋葉原', 35.6984, 139.7731), ('chuo', '東京', 35.6812, 139.7671),
]


def stations(src, out):
    """Platform canopies (long, narrow transport buildings within 250 m of a station) and the station buildings
    beside them (compact transport buildings within 120 m of a canopy). A station is elevated where its canopies'
    tops stand 9 m or more over the ground."""
    by_city = {}
    for city, name, lat, lon in STATIONS:
        by_city.setdefault(city, []).append((name, lat, lon))
    canopies, houses = [], []
    for city, sts in by_city.items():
        path = os.path.join(src, city + '-buildings.csv')
        if not os.path.exists(path):
            print('no', path)
            continue
        with open(path, encoding='utf-8') as fh:
            rows = [r for r in csv.DictReader(fh) if '運輸' in r['usage'] + r['detailed_usage']
                    and '倉庫施設等' not in r['detailed_usage']]
        for name, lat, lon in sts:
            kx = 111320.0 * math.cos(math.radians(lat))
            near = []
            for r in rows:
                d = math.hypot((float(r['lat']) - lat) * 111320.0, (float(r['lon']) - lon) * kx)
                if d < 250:
                    near.append((d, r))
            cs = [(d, r) for d, r in near if float(r['length']) >= 40 and float(r['length']) >= 4 * float(r['depth'])
                  and 3 <= float(r['depth']) <= 20 and 3 <= float(r['height'] or 0) <= 16]
            if not cs:
                print('%-8s no canopies' % name)
                continue
            top = pct([float(r['height']) for _, r in cs], 0.5)
            elevated = top >= 9
            for d, r in cs:
                canopies.append({'station': name, 'elevated': elevated, 'length': float(r['length']),
                                 'depth': float(r['depth']), 'height': float(r['height'])})
            for d, r in near:
                L, D = float(r['length']), float(r['depth'])
                if L < 4 * D and float(r['area']) >= 80 and 3 <= float(r['height'] or 0) <= 40:
                    near_c = min(math.hypot((float(r['lat']) - float(c['lat'])) * 111320.0,
                                            (float(r['lon']) - float(c['lon'])) * kx) for _, c in cs)
                    if near_c < 120:
                        houses.append({'station': name, 'elevated': elevated, 'length': L, 'depth': D,
                                       'height': float(r['height']), 'storeys': r['storeys'], 'roof': r['roof_shape']})
            print('%-8s %-8s canopies %2d: length %s, depth %s, top %.1f' % (
                name, 'elevated' if elevated else 'ground', len(cs),
                '/'.join('%.0f' % pct([float(r['length']) for _, r in cs], q) for q in (.25, .5, .75)),
                '/'.join('%.1f' % pct([float(r['depth']) for _, r in cs], q) for q in (.25, .5, .75)), top))
    os.makedirs(out, exist_ok=True)
    for fname, rows in (('canopies.csv', canopies), ('station_houses.csv', houses)):
        if rows:
            with open(os.path.join(out, fname), 'w', newline='', encoding='utf-8') as fh:
                w = csv.DictWriter(fh, list(rows[0].keys()))
                w.writeheader()
                w.writerows(rows)
    for elevated in (False, True):
        for label, rows, keys in (('canopy', canopies, ('length', 'depth', 'height')),
                                  ('station building', houses, ('length', 'depth', 'height'))):
            rs = [r for r in rows if r['elevated'] == elevated]
            if rs:
                print('%-8s %-16s n %3d  ' % ('elevated' if elevated else 'ground', label, len(rs)) + '  '.join(
                    '%s %s' % (k, '/'.join('%.1f' % pct([r[k] for r in rs], q) for q in (.25, .5, .75)))
                    for k in keys))


# Sample structures for the preview, each a size the survey found (the defaults) or a variation of it: the generators
# take every size as a parameter, these only show the range. Keys as RailModels reads them.
SPECS = [
    {'slug': 'viaduct_double', 'name': '複線高架橋（ラーメン高架）', 'kind': 'viaduct', 'length': 104, 'level': 9},
    {'slug': 'viaduct_over_road', 'name': '複線高架橋・道路を跨ぐ鋼桁', 'kind': 'viaduct', 'length': 110, 'level': 10,
     'keepClear': [[44, 74]], 'soundWall': True},
    {'slug': 'viaduct_truss_valley', 'name': '複線トラス橋・谷越え', 'kind': 'viaduct', 'length': 150, 'level': 10,
     'keepClear': [[40, 110]], 'ground': [[0, 0], [75, -8], [150, 0]], 'palette': 'WEATHERED'},
    {'slug': 'viaduct_ramp_single', 'name': '単線高架橋・地平から上る勾配', 'kind': 'viaduct', 'length': 140, 'level': 4,
     'levelEnd': 10, 'tracks': 1},
    {'slug': 'viaduct_quadruple', 'name': '複々線高架橋', 'kind': 'viaduct', 'length': 80, 'level': 9, 'tracks': 4,
     'spans': [10, 20, 45]},
    {'slug': 'viaduct_road', 'name': '道路高架橋（4車線）', 'kind': 'viaduct', 'deck': 'ROAD', 'length': 96, 'level': 9,
     'width': 21, 'soundWall': True},
    {'slug': 'footbridge_2lane', 'name': '横断歩道橋（2車線）', 'kind': 'footbridge', 'clearSpan': 9, 'width': 3,
     'palette': 'BLUE'},
    {'slug': 'footbridge_6lane_slope', 'name': '横断歩道橋（6車線・坂道）', 'kind': 'footbridge', 'clearSpan': 26,
     'width': 5, 'ground': [[-20, 2], [60, -2]], 'palette': 'WEATHERED'},
    {'slug': 'elevated_island', 'name': '高架駅・島式（道路を跨ぐ）', 'kind': 'elevated_station', 'section': 'TPT',
     'platformWidth': 11, 'platformLength': 160, 'level': 9, 'keepClear': [[30, 44]]},
    {'slug': 'elevated_side', 'name': '高架駅・相対式', 'kind': 'elevated_station', 'section': 'PTTP', 'platformWidth': 6,
     'platformLength': 120, 'level': 10, 'palette': 'BLUE'},
    {'slug': 'elevated_quadruple', 'name': '高架駅・複々線（島式2面）', 'kind': 'elevated_station', 'section': 'TPTTPT',
     'platformWidth': 11, 'platformLength': 200, 'level': 10},
    {'slug': 'ground_side_beside', 'name': '地上駅・相対式、跨線橋と地平駅舎', 'kind': 'ground_station', 'section': 'PTTP',
     'platformWidth': 5, 'platformLength': 100, 'house': 'BESIDE', 'houseWidth': 16, 'houseDepth': 10, 'storeys': 1,
     'palette': 'WEATHERED'},
    {'slug': 'ground_island_beside', 'name': '地上駅・島式、跨線橋と2階建て駅舎', 'kind': 'ground_station',
     'section': 'TPT', 'platformWidth': 9, 'platformLength': 120, 'house': 'BESIDE', 'houseWidth': 24,
     'houseDepth': 12, 'storeys': 2},
    {'slug': 'ground_over', 'name': '地上駅・橋上駅舎（2面3線）', 'kind': 'ground_station', 'section': 'PTPTT',
     'platformWidth': 7, 'platformLength': 140, 'house': 'OVER', 'houseWidth': 6, 'houseDepth': 14,
     'ground': [[0, 0], [70, -3], [148, 0]]},
    {'slug': 'viaduct_curve', 'name': '複線高架橋・半径160の曲線（緩和曲線30、MTRの軌道どおり）', 'kind': 'viaduct', 'length': 160, 'level': 9,
     'mtr': {'before': 30, 'radius': 160, 'turn': 35, 'transition': 30}},
    {'slug': 'brick_yurakucho', 'name': '煉瓦アーチ高架・高架下店舗（有楽町型）、道路を跨ぐ鋼桁', 'kind': 'brick_viaduct',
     'length': 150, 'level': 10, 'bay': 10, 'tracks': 4, 'keepClear': [[64, 82]], 'seed': 12},
    {'slug': 'brick_curve', 'name': '煉瓦アーチ高架・曲線（半径200）', 'kind': 'brick_viaduct', 'length': 120, 'level': 10,
     'bay': 9, 'tracks': 2, 'mtr': {'before': 20, 'radius': 200, 'turn': -22, 'transition': 20}, 'seed': 5},
    {'slug': 'shinkansen_frame', 'name': '新幹線ラーメン高架橋（3径間ユニット）', 'kind': 'frame_viaduct', 'length': 150,
     'level': 12, 'bay': 8, 'unitBays': 3},
    {'slug': 'shinkansen_valley_road', 'name': '新幹線高架・谷と道路を越える（鋼桁）', 'kind': 'frame_viaduct',
     'length': 200, 'level': 15, 'bay': 9, 'unitBays': 3, 'keepClear': [[96, 126]],
     'ground': [[0, 0], [100, -6], [200, 0]], 'palette': 'BLUE'},
    {'slug': 'shinkansen_curve', 'name': '新幹線高架・曲線（半径600）', 'kind': 'frame_viaduct', 'length': 200,
     'level': 12, 'bay': 8, 'unitBays': 3, 'mtr': {'before': 40, 'radius': 600, 'turn': 10, 'transition': 60}},
    {'slug': 'elevated_curve', 'name': '高架駅・曲線（半径400、緩和曲線、ホーム端はMTRのホーム用に空けてある、駅舎部は直線）', 'kind': 'elevated_station',
     'section': 'TPT', 'platformWidth': 11, 'platformLength': 160, 'level': 9, 'mtr': {'before': 108, 'radius': 400, 'turn': 8, 'transition': 20}},
    {'slug': 'ground_over_curve', 'name': '地上駅・橋上駅舎、曲線（半径800）', 'kind': 'ground_station', 'section': 'PTTP',
     'platformWidth': 5, 'platformLength': 120, 'house': 'OVER', 'houseWidth': 6, 'houseDepth': 14,
     'mtr': {'before': 0, 'radius': 800, 'turn': 8, 'transition': 30}},
    {'slug': 'underground_island', 'name': '地下駅・島式（開削2層、出入口4か所）', 'kind': 'underground_station',
     'section': 'TPT', 'platformWidth': 9, 'platformLength': 120, 'cover': 3,
     'exits': [[30, 1, 6, 1, 3], [30, -1, 6, -1, 3], [100, 1, 6, -1, 3], [100, -1, 6, 1, 3]]},
    {'slug': 'underground_side', 'name': '地下駅・相対式（浅め、出入口2か所）', 'kind': 'underground_station',
     'section': 'PTTP', 'platformWidth': 5, 'platformLength': 80, 'cover': 2, 'concourseHeight': 4,
     'exits': [[20, 1, 5, 1, 3], [60, -1, 5, -1, 3]]},
    {'slug': 'underground_curve', 'name': '地下駅・島式、曲線（半径600、MTRの軌道どおり）', 'kind': 'underground_station',
     'section': 'TPT', 'platformWidth': 9, 'platformLength': 100, 'exits': [[20, 1, 6, 1, 3]],
     'mtr': {'before': 40, 'radius': 600, 'turn': 6, 'transition': 20}},
    {'slug': 'cut_cover_tunnel', 'name': '開削トンネル（複線、勾配と曲線）', 'kind': 'cut_cover_tunnel', 'length': 160,
     'level': -14, 'levelEnd': -18, 'mtr': {'before': 30, 'radius': 300, 'turn': 20, 'transition': 30}},
    {'slug': 'deep_station', 'name': '深層駅・三連形シールド（島式、長い階段とエスカレーターで浅い改札階へ）', 'kind': 'deep_station',
     'platformLength': 120, 'platformWidth': 9, 'level': -32},
    {'slug': 'deep_station_curve', 'name': '深層駅・曲線（半径800、MTRの軌道どおり）', 'kind': 'deep_station',
     'platformLength': 100, 'platformWidth': 11, 'level': -36,
     'mtr': {'before': 30, 'radius': 800, 'turn': 5, 'transition': 20}},
    {'slug': 'twin_tube_station', 'name': '深層駅・単線シールド二本（相対式、中通路と連絡通路、長い階段で浅い改札階へ）',
     'kind': 'twin_tube_station', 'platformLength': 120, 'platformWidth': 4, 'level': -32},
    {'slug': 'twin_tube_station_curve', 'name': '深層駅・単線シールド二本・曲線（半径600）', 'kind': 'twin_tube_station',
     'platformLength': 100, 'platformWidth': 5, 'level': -30,
     'mtr': {'before': 30, 'radius': 600, 'turn': 6, 'transition': 20}},
    {'slug': 'shield_tunnel', 'name': 'シールドトンネル（単線2本、勾配と曲線）', 'kind': 'shield_tunnel', 'length': 160,
     'level': -30, 'levelEnd': -34, 'mtr': {'before': 30, 'radius': 400, 'turn': 15, 'transition': 30}},
    {'slug': 'ground_single', 'name': '地上駅・単線1面', 'kind': 'ground_station', 'section': 'TP', 'platformWidth': 5,
     'platformLength': 60, 'house': 'BESIDE', 'houseWidth': 12, 'houseDepth': 8, 'storeys': 1, 'palette': 'WEATHERED',
     'canopyLength': 20},
]

ATTRIBUTION = """出典：3D都市モデル（Project PLATEAU）千代田区・中央区・台東区・新宿区・渋谷区（2025年度）、横浜市（2024年度）、
京都市（2025年度）の橋梁モデル、京都市・台東区・中央区（2025年度）の建築物モデル
Source: 3D city models (Project PLATEAU): bridges of Chiyoda, Chuo, Taito, Shinjuku and Shibuya (FY2025), Yokohama
(FY2024) and Kyoto (FY2025); buildings of Kyoto, Taito and Chuo (FY2025). https://www.geospatial.jp/ckan/dataset/?q=plateau

これらの構造物テンプレートは上記データから測った寸法（幅員、桁下高さ、桁高、支間、上屋の長さ・奥行・高さ）を既定値として
プログラムで生成したものです。国土交通省および各自治体が作成したものではありません。
These structure templates were generated by program, with sizes measured on the data above (deck widths, heights and
clearances, girder depths, spans, platform canopies' length, depth and height) as defaults; they were not made by MLIT
or the municipalities. Nothing of the meshes is included.
"""


def specs(out):
    import json
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, 'rail_specs.json')
    with open(path, 'w', encoding='utf-8') as fh:
        json.dump(SPECS, fh, ensure_ascii=False, indent=1)
    print(path)


def colours(names, cache):
    """Average texture colour of each block (its full block's texture), read from the vanilla client jar; only the
    averages are kept, cached in `cache`."""
    import io
    import json
    import plateau_voxel as pv
    got = {}
    if os.path.exists(cache):
        with open(cache, encoding='utf-8') as fh:
            got = {k: tuple(v) for k, v in json.load(fh).items()}
    missing = [n for n in names if n not in got]
    if missing:
        with zipfile.ZipFile(pv.MC_JAR) as z:
            for n in missing:
                if n.startswith('citybuilder:'):
                    base = os.path.join(MOD_TEXTURES, n.split(':', 1)[1])
                    path = next((base + suffix + '.png' for suffix in ('', '_board', '_side')
                                 if os.path.exists(base + suffix + '.png')), None)
                    if path is None:
                        continue
                    with open(path, 'rb') as fh:
                        data = fh.read()
                    img = pv.Image.open(io.BytesIO(data)).convert('RGBA')
                    tot = [0.0] * 4
                    for r, g, b, a in img.get_flattened_data():
                        tot[0] += r * a
                        tot[1] += g * a
                        tot[2] += b * a
                        tot[3] += a
                    got[n] = tuple(round(c / max(tot[3], 1)) for c in tot[:3])
                    continue
                tex = n
                for suffix in ('_slab', '_stairs'):
                    if tex.endswith(suffix):
                        tex = tex[:-len(suffix)]
                data = None
                for t in (tex, tex + '_top', tex + 's'):
                    try:
                        data = z.read('assets/minecraft/textures/block/%s.png' % t)
                        break
                    except KeyError:
                        pass
                if data is None:
                    continue
                img = pv.Image.open(io.BytesIO(data)).convert('RGBA')
                w = img.size[0]
                img = img.crop((0, 0, w, min(w, img.size[1])))
                tot = [0.0] * 4
                for r, g, b, a in img.get_flattened_data():
                    tot[0] += r * a
                    tot[1] += g * a
                    tot[2] += b * a
                    tot[3] += a
                got[n] = tuple(round(c / max(tot[3], 1)) for c in tot[:3])
        with open(cache, 'w', encoding='utf-8') as fh:
            json.dump(got, fh, indent=0)
    return got


MOD_TEXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'common', 'src', 'main',
                            'resources', 'assets', 'citybuilder', 'textures', 'block')


def state(s):
    """'ns:x[a=b]{K:v}' -> ('x' for vanilla, 'citybuilder:x' for this mod's, {'a': 'b'} or None); the block
    entity's data ({K:v}) is kept apart in NBT_OF."""
    nbt = None
    if s.endswith('}') and '{' in s:
        s, _, nbt = s.partition('{')
        nbt = '{' + nbt
    name, _, props = s.partition('[')
    if name.startswith('minecraft:'):
        name = name[len('minecraft:'):]
    props = dict(kv.split('=', 1) for kv in props.rstrip(']').split(',')) if props else None
    if nbt:
        props = dict(props or {})
        props['__nbt__'] = nbt
    return name, props


def snbt(text):
    """The flat SNBT of a marker's data ({K:1,K:2.5f,K:"s"}) as NBT payload items (name, type, bytes)."""
    import re
    import plateau_voxel as pv
    items = []
    for key, value in re.findall(r'(\w+):("[^"]*"|-?[0-9.]+f?)', text):
        if value.startswith('"'):
            items.append((key, 8, pv.nbt_string(value[1:-1])))
        elif value.endswith('f'):
            items.append((key, 5, struct.pack('>f', float(value[:-1]))))
        else:
            items.append((key, 3, struct.pack('>i', int(value))))
    return items


def write_nbt(path, blocks, size):
    """plateau_voxel.write_nbt, keeping this mod's namespace."""
    import gzip
    import plateau_voxel as pv
    states, index, entries = [], {}, []
    for (x, y, z), (name, props) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        nbt = None
        if props and '__nbt__' in props:
            props = dict(props)
            nbt = props.pop('__nbt__')
            props = props or None
        key = (name, tuple(sorted(props.items())) if props else ())
        if key not in index:
            index[key] = len(states)
            items = [('Name', 8, pv.nbt_string(name if ':' in name else 'minecraft:' + name))]
            if props:
                items.append(('Properties', 10, pv.nbt_compound([(k, 8, pv.nbt_string(v))
                                                                 for k, v in sorted(props.items())])))
            states.append(pv.nbt_compound(items))
        entry = [('pos', 9, pv.nbt_int_list([x, y, z])), ('state', 3, struct.pack('>i', index[key]))]
        if nbt:  # the block entity's data, with its id
            entry.append(('nbt', 10, pv.nbt_compound(snbt(nbt) + [('id', 8, pv.nbt_string(name))])))
        entries.append(pv.nbt_compound(entry))
    root = pv.nbt_compound([
        ('DataVersion', 3, struct.pack('>i', 3465)),  # 1.20.1
        ('size', 9, pv.nbt_int_list(list(size))),
        ('palette', 9, pv.nbt_compound_list(states)),
        ('blocks', 9, pv.nbt_compound_list(entries)),
        ('entities', 9, pv.nbt_compound_list([])),
    ])
    with gzip.open(path, 'wb') as fh:
        fh.write(bytes([10]) + pv.nbt_string('') + root)


def build(out):
    """Structure templates (.nbt), isometric previews (.png), library.json and preview.html from <out>/models."""
    import base64
    import json
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import plateau_voxel as pv
    names = {s['slug']: s for s in SPECS}
    models = {}
    for f in sorted(glob.glob(os.path.join(out, 'models', '*.json'))):
        with open(f, encoding='utf-8') as fh:
            models[os.path.splitext(os.path.basename(f))[0]] = json.load(fh)
    used = sorted({state(b[3])[0] for m in models.values() for b in m['blocks']})
    used = sorted(set(used) | {pv.FULL_OF.get(n, pv.PREVIEW_ALIAS.get(n, n)) for n in used})  # as render looks up
    bc = colours(used, os.path.join(out, 'block_colours.json'))
    entries = []
    for slug, model in models.items():
        blocks = {(x, y, z): state(st) for x, y, z, st in model['blocks']}
        spec = names.get(slug, {})
        size = tuple(model['size'])
        write_nbt(os.path.join(out, slug + '.nbt'), blocks, size)
        # The catenary's thin steel drawn as posts (the renderer takes names ending in _fence for those)
        thin = {}
        underground = spec.get('kind') in ('underground_station', 'cut_cover_tunnel', 'deep_station', 'shield_tunnel', 'twin_tube_station')
        for k, (name, props) in blocks.items():
            if name == 'air':  # the ground taken away: nothing to draw
                continue
            if underground and k[2] > model['origin'][2] + 1:  # cut open along the middle to show the inside
                continue
            if props and '__nbt__' in props:
                props = {p: v for p, v in props.items() if p != '__nbt__'} or None
            if name in ('citybuilder:catenary_beam', 'citybuilder:catenary_arm'):  # a bar across: drawn as a top slab
                props = {'type': 'top'}
            elif name.startswith(('citybuilder:catenary_', 'citybuilder:curve_marker')):
                bc[name + '_fence'] = bc.get(name, (168, 172, 174))
                name = name + '_fence'
            thin[k] = (name, props)
        pv.render(thin, size, os.path.join(out, slug + '.png'), bc)
        e = {'slug': slug, 'name': spec.get('name', slug), 'kind': spec.get('kind'), 'size': list(size),
             'origin': model['origin'], 'level': model['level'], 'tracks': model['tracks'], 'spans': model['spans'],
             'blocks': len(blocks), 'spec': spec}
        for k in ('platformStart', 'platformEnd', 'stairLength'):
            if k in model:
                e[k] = model[k]
        entries.append(e)
        print('%-24s %4dx%3dx%3d %7d blocks' % (slug, size[0], size[1], size[2], len(blocks)), flush=True)
    with open(os.path.join(out, 'library.json'), 'w', encoding='utf-8') as fh:
        json.dump({'attribution': 'ATTRIBUTION', 'structures': entries}, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(out, 'ATTRIBUTION'), 'w', encoding='utf-8') as fh:
        fh.write(ATTRIBUTION)
    html = ['<!doctype html><meta charset=utf-8><title>Bridges, viaducts and stations</title>',
            '<style>body{font:14px sans-serif;background:#eee;margin:16px}figure{display:inline-block;'
            'vertical-align:top;margin:8px;background:#fff;padding:8px;max-width:100%}img{max-width:100%}'
            'figcaption{margin-top:4px;max-width:900px}</style><h1>Bridges, viaducts and stations</h1><p>'
            + ATTRIBUTION.replace('\n', '<br>') + '</p>']
    kinds = {'CONCRETE': '', 'STEEL_GIRDER': ' steel', 'TRUSS': ' truss'}
    for e in entries:
        with open(os.path.join(out, e['slug'] + '.png'), 'rb') as fh:
            data = base64.b64encode(fh.read()).decode()
        spans = ', '.join('%d%s' % (b - a, kinds[k]) for a, b, k in e['spans'])
        params = ', '.join('%s %s' % (k, v) for k, v in e['spec'].items() if k not in ('slug', 'name', 'kind'))
        html.append("<figure><img src='data:image/png;base64,%s'><figcaption><b>%s</b> %s — %s, %d blocks; "
                    "spans: %s; parameters: %s</figcaption></figure>" % (
                        data, e['name'], e['slug'], '×'.join(map(str, e['size'])), e['blocks'], spans or '-',
                        params))
    with open(os.path.join(out, 'preview.html'), 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(html))


def main(argv):
    if len(argv) >= 2 and argv[0] == 'specs':
        specs(argv[1])
        return 0
    if len(argv) >= 2 and argv[0] == 'build':
        build(argv[1])
        return 0
    if len(argv) >= 3 and argv[0] == 'stations':
        stations(argv[1], argv[2])
        return 0
    if len(argv) >= 3 and argv[0] == 'survey':
        survey(argv[1], argv[2])
        return 0
    if len(argv) >= 2 and argv[0] == 'report':
        with open(argv[1], encoding='utf-8') as fh:
            rows = list(csv.DictReader(fh))
        for r in rows:
            for k in ('length', 'width', 'deck'):
                r[k] = float(r[k])
            r['run'] = float(r['run']) if r.get('run') else ''
            r['deck_width'] = float(r['deck_width']) if r.get('deck_width') else ''
            r['columns'] = int(r['columns']) if r.get('columns') else None
        report(rows)
        return 0
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
