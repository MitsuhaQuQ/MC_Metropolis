"""Building statistics from OpenStreetMap, to calibrate the procedural Asian-style buildings.

Input: an Overpass JSON file with building ways ("out tags geom"), and optionally the
street extract for the same box (Overpass XML, as used by `gradlew :core:osmStats`).
For every building it computes the footprint area, the minimum-area bounding rectangle
(short side = depth, long side = length), the storeys (building:levels) and height, and
the class and distance of the nearest street. Prints distributions overall, per building
type and per nearest-street class (the "building height follows road width" check in
design doc section 8.5).

Usage:
    python -I tools/assets/osm_buildings.py <buildings.json> [--streets <streets.osm>] [--csv out.csv]

Data is (c) OpenStreetMap contributors, ODbL; only aggregate numbers go into the repo.
"""
import collections
import csv
import json
import math
import sys
import xml.etree.ElementTree as ET

EARTH = 6371008.8


def project(lat0, lon0):
    kx = math.cos(math.radians(lat0)) * math.pi / 180 * EARTH
    ky = math.pi / 180 * EARTH
    return lambda lat, lon: ((lon - lon0) * kx, (lat - lat0) * ky)


def area(pts):
    s = 0.0
    for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]):
        s += x1 * y2 - x2 * y1
    return abs(s) / 2


def hull(pts):
    pts = sorted(set(pts))
    if len(pts) <= 2:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lo, up = [], []
    for p in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    for p in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], p) <= 0:
            up.pop()
        up.append(p)
    return lo[:-1] + up[:-1]


def min_rect(pts):
    """Minimum-area bounding rectangle: (short side, long side, angle of long side in degrees 0-180)."""
    h = hull(pts)
    if len(h) < 3:
        return 0, 0, 0
    best = None
    for (x1, y1), (x2, y2) in zip(h, h[1:] + h[:1]):
        a = math.atan2(y2 - y1, x2 - x1)
        c, s = math.cos(a), math.sin(a)
        us = [x * c + y * s for x, y in h]
        vs = [-x * s + y * c for x, y in h]
        w, d = max(us) - min(us), max(vs) - min(vs)
        if best is None or w * d < best[0]:
            ang = a if w >= d else a + math.pi / 2
            best = (w * d, min(w, d), max(w, d), math.degrees(ang) % 180)
    return best[1], best[2], best[3]


def number(v):
    if v is None:
        return None
    v = v.strip().lower().replace("m", "").replace(",", ".").split(";")[0].strip()
    try:
        return float(v)
    except ValueError:
        return None


# ---------------------------------------------------------------- streets

ROAD_WIDTH = {  # nominal widths in the generator (design doc section 6) for grouping
    "trunk": "trunk", "primary": "primary", "secondary": "secondary",
    "tertiary": "tertiary", "unclassified": "minor", "residential": "minor",
    "living_street": "lane", "service": "lane", "pedestrian": "lane",
    "footway": "path", "path": "path", "steps": "path", "track": "path",
}
CELL = 50.0


class Streets:
    def __init__(self, path, proj):
        root = ET.parse(path).getroot()
        nodes = {n.get("id"): proj(float(n.get("lat")), float(n.get("lon")))
                 for n in root.iter("node")}
        self.grid = collections.defaultdict(list)
        for w in root.iter("way"):
            tags = {t.get("k"): t.get("v") for t in w.iter("tag")}
            hw = tags.get("highway", "").replace("_link", "")
            cls = ROAD_WIDTH.get(hw)
            if not cls:
                continue
            width = number(tags.get("width"))
            pts = [nodes[nd.get("ref")] for nd in w.iter("nd") if nd.get("ref") in nodes]
            for a, b in zip(pts, pts[1:]):
                seg = (a, b, cls, width)
                x0, x1 = sorted((a[0], b[0]))
                y0, y1 = sorted((a[1], b[1]))
                for gx in range(int(x0 // CELL), int(x1 // CELL) + 1):
                    for gy in range(int(y0 // CELL), int(y1 // CELL) + 1):
                        self.grid[(gx, gy)].append(seg)

    def nearest(self, x, y, reach=2):
        best = (1e18, None, None)
        gx, gy = int(x // CELL), int(y // CELL)
        for dx in range(-reach, reach + 1):
            for dy in range(-reach, reach + 1):
                for (ax, ay), (bx, by), cls, width in self.grid.get((gx + dx, gy + dy), ()):
                    vx, vy = bx - ax, by - ay
                    L = vx * vx + vy * vy
                    t = 0 if L == 0 else max(0, min(1, ((x - ax) * vx + (y - ay) * vy) / L))
                    d = math.hypot(ax + t * vx - x, ay + t * vy - y)
                    if d < best[0]:
                        best = (d, cls, width)
        return best

    RANK = ["trunk", "primary", "secondary", "tertiary", "minor", "lane"]

    def frontage(self, x, y, half_depth, reach=15.0):
        """The highest-ranked street (footways ignored) whose centreline comes within
        `reach` of the building's edge: (class, distance from the edge to the centreline)."""
        best = {}
        gx, gy = int(x // CELL), int(y // CELL)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for (ax, ay), (bx, by), cls, _ in self.grid.get((gx + dx, gy + dy), ()):
                    if cls == "path":
                        continue
                    vx, vy = bx - ax, by - ay
                    L = vx * vx + vy * vy
                    t = 0 if L == 0 else max(0, min(1, ((x - ax) * vx + (y - ay) * vy) / L))
                    d = math.hypot(ax + t * vx - x, ay + t * vy - y) - half_depth
                    if d < best.get(cls, 1e18):
                        best[cls] = d
        for cls in self.RANK:
            if best.get(cls, 1e18) <= reach:
                return cls, best[cls]
        return None, None


# ---------------------------------------------------------------- main

def pct(values, ps=(10, 25, 50, 75, 90)):
    v = sorted(values)
    if not v:
        return "-"
    return " ".join(f"{v[min(len(v) - 1, int(p / 100 * len(v)))]:.0f}" for p in ps)


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    src, streets_path, out_csv = argv[0], None, None
    it = iter(argv[1:])
    for a in it:
        if a == "--streets":
            streets_path = next(it)
        elif a == "--csv":
            out_csv = next(it)
    data = json.load(open(src, encoding="utf-8"))
    ways = [e for e in data["elements"] if e["type"] == "way" and e.get("geometry")]
    if not ways:
        print("no buildings")
        return 1
    lat0 = sum(e["geometry"][0]["lat"] for e in ways) / len(ways)
    lon0 = sum(e["geometry"][0]["lon"] for e in ways) / len(ways)
    proj = project(lat0, lon0)
    streets = Streets(streets_path, proj) if streets_path else None

    rows = []
    for e in ways:
        pts = [proj(p["lat"], p["lon"]) for p in e["geometry"]]
        if len(pts) > 1 and pts[0] == pts[-1]:
            pts = pts[:-1]
        if len(pts) < 3:
            continue
        t = e.get("tags", {})
        a = area(pts)
        short, long_, ang = min_rect(pts)
        cx = sum(p[0] for p in pts) / len(pts)
        cy = sum(p[1] for p in pts) / len(pts)
        row = {
            "id": e["id"], "type": t.get("building", "yes"), "area": a,
            "short": short, "long": long_, "angle": ang,
            "levels": number(t.get("building:levels")), "height": number(t.get("height")),
            "fill": a / (short * long_) if short * long_ > 0 else 0,
        }
        if streets:
            cls, gap = streets.frontage(cx, cy, short / 2)
            row.update(road_dist=gap, road=cls or "interior")
        rows.append(row)

    xs = [p for e in ways for p in (proj(g["lat"], g["lon"]) for g in e["geometry"])]
    box = (max(x for x, _ in xs) - min(x for x, _ in xs)) * (max(y for _, y in xs) - min(y for _, y in xs))
    print(f"buildings: {len(rows)}  footprint {sum(r['area'] for r in rows) / 1e6:.2f} km2"
          f"  coverage of box {sum(r['area'] for r in rows) / box:.0%}")
    with_lv = [r for r in rows if r["levels"]]
    with_h = [r for r in rows if r["height"]]
    print(f"tagged levels: {len(with_lv)} ({len(with_lv) / len(rows):.0%})"
          f"  height: {len(with_h)} ({len(with_h) / len(rows):.0%})")
    print("percentiles p10 p25 p50 p75 p90")
    print(f"  area m2      {pct([r['area'] for r in rows])}")
    print(f"  depth (short){pct([r['short'] for r in rows])}")
    print(f"  length (long){pct([r['long'] for r in rows])}")
    print(f"  long/short x10 {pct([10 * r['long'] / r['short'] for r in rows if r['short'] > 0])}")
    print(f"  rect fill %  {pct([100 * r['fill'] for r in rows])}")
    print("levels:", dict(sorted(collections.Counter(int(r['levels']) for r in with_lv).items())))
    if len(with_h) >= 10 and len([r for r in with_h if r["levels"]]) >= 10:
        hs = [r["height"] / r["levels"] for r in with_h if r["levels"]]
        print(f"  m per storey {sorted(hs)[len(hs) // 2]:.2f} (n={len(hs)})")

    print("\nby building type (n, area p25/p50/p75, depth p50, length p50, levels p50 where tagged):")
    by = collections.defaultdict(list)
    for r in rows:
        by[r["type"]].append(r)
    for k, rs in sorted(by.items(), key=lambda kv: -len(kv[1]))[:15]:
        lv = [r["levels"] for r in rs if r["levels"]]
        print(f"  {k:16} {len(rs):6}  {pct([r['area'] for r in rs], (25, 50, 75)):>16}"
              f"  {pct([r['short'] for r in rs], (50,)):>4}  {pct([r['long'] for r in rs], (50,)):>4}"
              f"  {pct(lv, (50,)) if lv else '-':>3} (n={len(lv)})")

    if streets:
        print("\nby frontage street (highest class within 15 m, footways ignored;"
              " n, edge-to-centreline p50, area p50, depth p50, levels p25/p50/p75 where tagged):")
        order = ["trunk", "primary", "secondary", "tertiary", "minor", "lane", "interior"]
        by = collections.defaultdict(list)
        for r in rows:
            by[r["road"]].append(r)
        for k in order:
            rs = by.get(k)
            if not rs:
                continue
            lv = [r["levels"] for r in rs if r["levels"]]
            print(f"  {k:10} {len(rs):6}  {pct([r['road_dist'] for r in rs if r['road_dist'] is not None], (50,)):>4}"
                  f"  {pct([r['area'] for r in rs], (50,)):>5}  {pct([r['short'] for r in rs], (50,)):>4}"
                  f"  {pct(lv, (25, 50, 75)) if lv else '-':>9} (n={len(lv)})")

    if out_csv:
        with open(out_csv, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
