"""Measures PLATEAU's underground structures (ubld, LOD4: the passages and concourses round Tokyo, Shinjuku, Shibuya
and Ueno stations), the sizes for City Builder's underground stations.

    python -I tools/assets/plateau_underground.py <plateau dir>

<plateau dir> holds <city>.zip as plateau_fetch.py packs them with --kinds=ubld
(F:/MC/citybuilder/core/build/plateau/underground). For each room: its floor and ceiling (area-weighted heights of
its floor and ceiling surfaces), so its clear height; the ground over it (the structure's roof, the highest surfaces);
its plan turned to its own axes (width and length of the least rectangle) and its passage width where it is long and
narrow (area over length). Prints the rooms and percentiles by kind (passage: length at least 3 times width;
concourse: the rest over 300 m2).

Source: 3D city models of Project PLATEAU (MLIT; copyright of each city), PLATEAU site policy (compatible with CC BY
4.0); statistics only, nothing of the meshes is shipped.
"""
import glob
import math
import os
import sys
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from plateau_bridges import Frame, min_rect, area_z, pct, deck_width  # noqa: E402

G = '{http://www.opengis.net/gml}'
B = '{http://www.opengis.net/citygml/building/2.0}'


def rings(el):
    out = []
    for pos in el.iter(G + 'posList'):
        v = [float(t) for t in pos.text.split()]
        out.append([(v[i], v[i + 1], v[i + 2]) for i in range(0, len(v) - 2, 3)])
    return out


def main(src):
    rows = []
    for z in sorted(glob.glob(os.path.join(src, '*.zip'))):
        city = os.path.splitext(os.path.basename(z))[0]
        with zipfile.ZipFile(z) as Z:
            for n in Z.namelist():
                if not n.endswith('.gml'):
                    continue
                root = ET.parse(Z.open(n)).getroot()
                for building in root.iter():
                    if not building.tag.endswith('}UndergroundBuilding') and not building.tag.endswith('}Building'):
                        continue
                    roofs = [r for bb in building.findall(B + 'boundedBy') for s in bb
                             if s.tag.endswith('RoofSurface') for r in rings(s)]
                    for room in building.iter(B + 'Room'):
                        floors, ceilings, all_rings = [], [], []
                        for bb in room.findall(B + 'boundedBy'):
                            for s in bb:
                                rs = rings(s)
                                all_rings.extend(rs)
                                if s.tag.endswith('FloorSurface'):
                                    floors.extend(rs)
                                elif s.tag.endswith('CeilingSurface'):
                                    ceilings.extend(rs)
                        if not floors or not ceilings:  # a bare solid: its lowest and highest flat faces
                            solid = rings(room)
                            flat = [r for r in solid if max(p[2] for p in r) - min(p[2] for p in r) < 0.2]
                            if not flat:
                                continue
                            lo = min(min(p[2] for p in r) for r in flat)
                            hi = max(max(p[2] for p in r) for r in flat)
                            floors = [r for r in flat if r[0][2] < lo + 0.5]
                            ceilings = [r for r in flat if r[0][2] > hi - 0.5]
                            all_rings = solid
                            if not floors or not ceilings or hi - lo < 1.5:
                                continue
                        pts = [p for r in all_rings for p in r]
                        fr = Frame(sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
                        lf = [[fr(p) for p in r] for r in floors]
                        lc = [[fr(p) for p in r] for r in ceilings]
                        floor, area = area_z(lf)
                        ceiling, _ = area_z(lc)
                        if floor is None or ceiling is None:
                            continue
                        _, length, width = min_rect([(p[0], p[1]) for r in lf for p in r])
                        dw = deck_width([[(p[0], p[1]) for p in r] for r in lf])
                        ground = max((p[2] for r in roofs for p in r), default=float('nan'))
                        name = room.find(G + 'name')
                        rows.append({'city': city, 'name': name.text if name is not None else '',
                                     'floor': floor, 'clear': ceiling - floor, 'depth': ground - floor,
                                     'area': area, 'length': length, 'width': width,
                                     'passage': dw[0] if dw else width})
    for r in rows:
        r['kind'] = 'passage' if r['length'] >= 3 * r['passage'] else 'concourse' if r['area'] >= 300 else 'room'
    for kind in ('passage', 'concourse', 'room'):
        rs = [r for r in rows if r['kind'] == kind]
        if not rs:
            continue

        def q(k):
            v = [r[k] for r in rs if r[k] == r[k]]
            return '/'.join('%.1f' % pct(v, f) for f in (.25, .5, .75)) if v else '-'
        print('%-9s n %3d  clear %s  width %s  length %s  area %s  floor below ground %s' % (
            kind, len(rs), q('clear'), q('passage'), q('length'), q('area'), q('depth')))
    by_city = {}
    for r in rows:
        by_city.setdefault(r['city'], []).append(r)
    for city, rs in by_city.items():
        floors = sorted(set(round(r['floor']) for r in rs))
        print('%-9s rooms %3d, floor levels %s' % (city, len(rs), floors))
    return 0


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
