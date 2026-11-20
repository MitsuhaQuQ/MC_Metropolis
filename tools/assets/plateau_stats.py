"""Aggregate statistics from the CSV written by plateau_buildings.py.

Usage:
    python -I tools/assets/plateau_stats.py <buildings.csv> [--center LAT,LON] [--box S,W,N,E] [--streets streets.osm]

Prints, for the targets of design doc section 6 (storey height, height, footprint, roof):
  - storey height (measured height / storeys) by usage and structure
  - storeys, footprint depth and length, roof type, structure by usage
  - footprint by storey class (what a procedural building of N storeys occupies)
  - rings by distance from the centre: building coverage, median storeys, wooden share, era
"""
import collections
import csv
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

KYOTO_CENTER = (35.0037, 135.7595)  # Shijo-Karasuma


def f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def pct(values, ps=(25, 50, 75)):
    v = sorted(x for x in values if x is not None)
    if not v:
        return "-"
    return "/".join(f"{v[min(len(v) - 1, int(p / 100 * len(v)))]:.1f}".rstrip("0").rstrip(".") for p in ps)


def share(rows, key, top=6):
    c = collections.Counter(r.get(key) or "-" for r in rows)
    n = sum(c.values())
    return ", ".join(f"{k} {v / n:.0%}" for k, v in c.most_common(top))


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    center, box, streets_path = KYOTO_CENTER, None, None
    it = iter(argv[1:])
    for a in it:
        if a == "--center":
            center = tuple(map(float, next(it).split(",")))
        elif a == "--streets":
            streets_path = next(it)
        elif a == "--box":
            box = tuple(map(float, next(it).split(",")))
    rows = []
    with open(argv[0], encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            for k in ("lat", "lon", "area", "depth", "length", "height", "storeys", "year"):
                r[k] = f(r[k])
            if r["lat"] is None or not r["area"] or r["area"] < 4:
                continue
            # PLATEAU writes 0001 for an unknown year and 0 for unknown storeys.
            if r["year"] is not None and r["year"] < 1600:
                r["year"] = None
            if not r["storeys"]:
                r["storeys"] = None
            if box and not (box[0] <= r["lat"] <= box[2] and box[1] <= r["lon"] <= box[3]):
                continue
            dy = (r["lat"] - center[0]) * 111.2
            dx = (r["lon"] - center[1]) * 111.2 * math.cos(math.radians(center[0]))
            r["dist"] = math.hypot(dx, dy)
            if r["storeys"] is not None and r["storeys"] >= 1 and r["height"] and r["height"] > 0:
                r["sh"] = r["height"] / r["storeys"]
            else:
                r["sh"] = None
            rows.append(r)
    print(f"buildings: {len(rows)}   with storeys: {sum(1 for r in rows if r['storeys'])}"
          f"   with height: {sum(1 for r in rows if r['height'])}   roof type: {sum(1 for r in rows if r['roof'])}"
          f"   year: {sum(1 for r in rows if r['year'])}   LOD2+: {sum(1 for r in rows if r['lod'] in ('2', '3'))}")
    print("structure:", share(rows, "structure"))
    print("roof (where given):", share([r for r in rows if r["roof"]], "roof", 10))
    print("percentiles below are p25/p50/p75; lengths in m (= blocks)\n")

    print("by usage: n | storeys | height | m/storey | area | depth | length | wooden | roof")
    by = collections.defaultdict(list)
    for r in rows:
        by[r["usage"] or "-"].append(r)
    for k, rs in sorted(by.items(), key=lambda kv: -len(kv[1]))[:14]:
        wood = sum(1 for r in rs if (r["structure"] or "").startswith("木造")) / len(rs)
        roofs = [r for r in rs if r["roof"]]
        print(f"  {k:12} {len(rs):7} | {pct(r['storeys'] for r in rs):>7} | {pct(r['height'] for r in rs):>12}"
              f" | {pct(r['sh'] for r in rs):>11} | {pct(r['area'] for r in rs):>14} | {pct(r['depth'] for r in rs):>11}"
              f" | {pct(r['length'] for r in rs):>12} | {wood:.0%} | {share(roofs, 'roof', 3) if roofs else '-'}")

    print("\nstorey height by structure (m/storey, buildings with height and storeys):")
    by = collections.defaultdict(list)
    for r in rows:
        if r["sh"]:
            by[r["structure"] or "-"].append(r)
    for k, rs in sorted(by.items(), key=lambda kv: -len(kv[1]))[:8]:
        print(f"  {k:16} {len(rs):7}  {pct(r['sh'] for r in rs)}")

    print("\nby storeys: n | area | depth | length | height | m/storey | main usage")
    classes = [(1, 1), (2, 2), (3, 3), (4, 5), (6, 9), (10, 14), (15, 24), (25, 99)]
    for lo, hi in classes:
        rs = [r for r in rows if r["storeys"] and lo <= r["storeys"] <= hi]
        if rs:
            print(f"  {lo:>2}-{hi:<2} {len(rs):7} | {pct(r['area'] for r in rs):>14} | {pct(r['depth'] for r in rs):>11}"
                  f" | {pct(r['length'] for r in rs):>12} | {pct(r['height'] for r in rs):>12}"
                  f" | {pct(r['sh'] for r in rs):>11} | {share(rs, 'usage', 3)}")

    print("\nLOD2 roofs by usage: n | shape shares | pitch p25/p50/p75 (sloped roofs)")
    lod2 = [r for r in rows if r.get("roof_shape")]
    for r in lod2:
        r["roof_pitch"] = f(r["roof_pitch"])
    groups = collections.defaultdict(list)
    for r in lod2:
        groups[r["usage"] or "-"].append(r)
    for k, rs in sorted(groups.items(), key=lambda kv: -len(kv[1]))[:10]:
        print(f"  {k:12} {len(rs):6} | {share(rs, 'roof_shape', 5)} | {pct(r['roof_pitch'] for r in rs if r['roof_pitch'])}")
    print("LOD2 roofs by storeys and structure:")
    for lo, hi in classes:
        rs = [r for r in lod2 if r["storeys"] and lo <= r["storeys"] <= hi]
        if rs:
            print(f"  {lo:>2}-{hi:<2} {len(rs):6} | {share(rs, 'roof_shape', 4)} | {pct(r['roof_pitch'] for r in rs if r['roof_pitch'])}")
    for k in ("木造・土蔵造", "鉄骨造", "鉄筋コンクリート造"):
        rs = [r for r in lod2 if r["structure"] == k]
        if rs:
            print(f"  {k:10} {len(rs):6} | {share(rs, 'roof_shape', 4)} | {pct(r['roof_pitch'] for r in rs if r['roof_pitch'])}")

    if streets_path:
        from osm_buildings import Streets, project
        proj = project(*center)
        streets = Streets(streets_path, proj)
        groups = collections.defaultdict(list)
        for r in rows:
            x, y = proj(r["lat"], r["lon"])
            cls, gap = streets.frontage(x, y, (r["depth"] or 0) / 2)
            r["gap"] = gap
            groups[cls or "interior"].append(r)
        print("\nby frontage street (highest OSM class within 15 m of the edge, footways ignored):"
              " n | storeys p50/p75/p90 | >=4F | >=6F | height p50/p90 | depth | edge to centreline | usage")
        for k in ["trunk", "primary", "secondary", "tertiary", "minor", "lane", "interior"]:
            rs = groups.get(k)
            if not rs:
                continue
            st = [r["storeys"] for r in rs if r["storeys"]]
            print(f"  {k:9} {len(rs):6} | {pct(st, (50, 75, 90)):>7} | {sum(1 for s in st if s >= 4) / max(1, len(st)):4.0%}"
                  f" | {sum(1 for s in st if s >= 6) / max(1, len(st)):4.0%} | {pct((r['height'] for r in rs), (50, 90)):>9}"
                  f" | {pct(r['depth'] for r in rs):>11} | {pct(r['gap'] for r in rs):>11} | {share(rs, 'usage', 3)}")

    print("\nrings from centre: n | coverage | storeys p50/p75/p90 | >=6F share | wooden | built <1950 | 1950-79 | 1980+")
    for lo, hi in [(0, 0.5), (0.5, 1), (1, 1.5), (1.5, 2), (2, 3), (3, 4), (4, 6), (6, 8), (8, 12), (12, 99)]:
        rs = [r for r in rows if lo <= r["dist"] < hi]
        if not rs:
            continue
        ring = math.pi * (min(hi, 30) ** 2 - lo ** 2) * 1e6
        cov = sum(r["area"] for r in rs) / ring
        st = [r["storeys"] for r in rs if r["storeys"]]
        ys = [r["year"] for r in rs if r["year"]]
        tall = sum(1 for s in st if s >= 6) / max(1, len(st))
        wood = sum(1 for r in rs if (r["structure"] or "").startswith("木造")) / len(rs)
        e1 = sum(1 for y in ys if y < 1950) / max(1, len(ys))
        e2 = sum(1 for y in ys if 1950 <= y < 1980) / max(1, len(ys))
        print(f"  {lo:>4}-{hi:<4} km {len(rs):7} | {cov:5.0%} | {pct(st, (50, 75, 90)):>8} | {tall:5.1%}"
              f" | {wood:4.0%} | {e1:4.0%} | {e2:4.0%} | {1 - e1 - e2 if ys else 0:4.0%}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
