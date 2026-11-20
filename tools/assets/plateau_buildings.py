"""Extract per-building attributes from a PLATEAU CityGML zip, to calibrate the Asian style.

Streams udx/bldg/*_bldg_*.gml straight out of the zip (no extraction, textures skipped) and
writes one CSV row per bldg:Building: mesh, centroid lat/lon, footprint area, minimum
bounding rectangle (depth = short side, length = long side, angle), measured height,
storeys above/below ground, usage, roof type, construction year, structure type,
detailed usage, and the highest LOD present. Codes are translated with the zip's codelists.

Usage:
    python -I tools/assets/plateau_buildings.py <citygml.zip> <out.csv> [--mesh PREFIX ...] [--jobs N]

Source: 3D city models of Project PLATEAU (MLIT; copyright of the local government),
under the PLATEAU site policy (compatible with CC BY 4.0). Only aggregate numbers and
derived templates go into the repo; credit and "加工して作成" go into ATTRIBUTION.
"""
import collections
import csv
import math
import multiprocessing
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from osm_buildings import area, min_rect, project  # noqa: E402

FIELDS = ["id", "mesh", "lat", "lon", "area", "depth", "length", "angle", "height",
          "storeys", "below", "usage", "roof", "year", "structure", "detailed_usage", "lod", "roof_shape", "roof_pitch", "roof_planes"]


def local(tag):
    return tag.rsplit("}", 1)[-1]


def codelist(z, name):
    path = next((n for n in z.namelist() if n.endswith("codelists/" + name)), None)
    if not path:
        return {}
    root = ET.fromstring(z.read(path))
    out = {}
    for d in root.iter():
        if local(d.tag) == "Definition":
            code = desc = None
            for c in d:
                if local(c.tag) == "name":
                    code = (c.text or "").strip()
                elif local(c.tag) == "description":
                    desc = (c.text or "").strip()
            if code:
                out[code] = desc
    return out


def footprint(building):
    """Exterior ring of the LOD0 footprint (or roof edge), as (lat, lon) pairs."""
    for want in ("lod0FootPrint", "lod0RoofEdge"):
        for el in building.iter():
            if local(el.tag) == want:
                for pl in el.iter():
                    if local(pl.tag) == "posList" and pl.text:
                        v = [float(x) for x in pl.text.split()]
                        return [(v[i], v[i + 1]) for i in range(0, len(v) - 2, 3)]
    # Fall back to the lowest LOD1 face.
    best = None
    for pl in building.iter():
        if local(pl.tag) == "posList" and pl.text:
            v = [float(x) for x in pl.text.split()]
            ring = [(v[i], v[i + 1], v[i + 2]) for i in range(0, len(v) - 2, 3)]
            zs = {round(p[2], 2) for p in ring}
            if len(zs) == 1 and len(ring) >= 4 and (best is None or ring[0][2] < best[0][2]):
                best = ring
    return [(p[0], p[1]) for p in best] if best else None


def roof_shape(building):
    """Classify the LOD2 roof from its RoofSurface polygons.

    Returns (shape, pitch in degrees, roof planes) or None without LOD2 roofs. Planes are
    grouped by the compass direction they face (45-degree bins, weighted by area): flat
    (no plane steeper than 5 degrees covers 20% of the roof), mono (one direction),
    gable (two opposite directions), hip (four), hip-gable-like or complex otherwise.
    """
    faces = []
    for el in building.iter():
        if local(el.tag) != "RoofSurface":
            continue
        for pl in el.iter():
            if local(pl.tag) == "posList" and pl.text:
                v = [float(x) for x in pl.text.split()]
                faces.append([(v[i], v[i + 1], v[i + 2]) for i in range(0, len(v) - 2, 3)])
    if not faces:
        return None
    lat0, lon0 = faces[0][0][0], faces[0][0][1]
    proj = project(lat0, lon0)
    total = sloped = 0.0
    dirs = collections.Counter()
    pitches = []
    for ring in faces:
        pts = [(*proj(a, b), h) for a, b, h in ring]
        nx = ny = nz = 0.0
        for (x1, y1, z1), (x2, y2, z2) in zip(pts, pts[1:] + pts[:1]):
            nx += (y1 - y2) * (z1 + z2)
            ny += (z1 - z2) * (x1 + x2)
            nz += (x1 - x2) * (y1 + y2)
        a = math.sqrt(nx * nx + ny * ny + nz * nz) / 2
        if a < 0.5:
            continue
        horiz = abs(nz) / 2  # projected area
        total += horiz
        tilt = math.degrees(math.acos(min(1.0, abs(nz) / 2 / a)))
        if tilt >= 5:
            sloped += horiz
            if nz < 0:
                nx, ny = -nx, -ny
            dirs[int(((math.degrees(math.atan2(nx, ny)) + 22.5) % 360) // 45)] += horiz
            pitches.append((tilt, horiz))
    if total <= 0:
        return None
    if sloped < 0.2 * total:
        return "flat", 0, 0
    major = [d for d, w in dirs.items() if w >= 0.1 * sloped]
    pitches.sort()
    acc, pitch = 0.0, pitches[-1][0]
    for t, w in pitches:
        acc += w
        if acc >= sloped / 2:
            pitch = t
            break
    k = len(major)
    s = set(major)
    if k == 1:
        shape = "mono"
    elif k == 2 and any((d + 4) % 8 in s for d in s):
        shape = "gable"
    elif k == 4 and all((d + 4) % 8 in s for d in s):
        shape = "hip"
    elif k == 3:
        shape = "gable+"  # gable with a wing or a half-hip
    else:
        shape = "complex"
    if sloped < 0.7 * total:
        shape += "/flat"  # sloped parts on a mostly flat roof
    return shape, round(pitch), k


def parse_file(args):
    zpath, name = args
    mesh = re.match(r".*/(\d+)_bldg", name).group(1)
    rows = []
    with zipfile.ZipFile(zpath) as z, z.open(name) as fh:
        depth = 0
        for ev, el in ET.iterparse(fh, events=("start", "end")):
            tag = local(el.tag)
            if tag != "Building":
                continue
            if ev == "start":
                depth += 1
                continue
            depth -= 1
            if depth:  # BuildingPart inside a Building is a "BuildingPart", but be safe
                continue
            row = {"id": el.get("{http://www.opengis.net/gml}id"), "mesh": mesh}
            lod = 0
            for c in el.iter():
                t = local(c.tag)
                txt = (c.text or "").strip()
                if t == "usage" and "usage" not in row: row["usage"] = txt
                elif t == "roofType": row["roof"] = txt
                elif t == "yearOfConstruction": row["year"] = txt
                elif t == "measuredHeight": row["height"] = txt
                elif t == "storeysAboveGround": row["storeys"] = txt
                elif t == "storeysBelowGround": row["below"] = txt
                elif t == "buildingStructureType": row["structure"] = txt
                elif t == "detailedUsage": row["detailed_usage"] = txt
                m = re.match(r"lod(\d)", t)
                if m:
                    lod = max(lod, int(m.group(1)))
            row["lod"] = lod
            rs = roof_shape(el) if lod >= 2 else None
            if rs:
                row["roof_shape"], row["roof_pitch"], row["roof_planes"] = rs
            ring = footprint(el)
            if ring and len(ring) >= 3:
                lat0, lon0 = ring[0]
                proj = project(lat0, lon0)
                pts = [proj(a, b) for a, b in ring]
                if pts[0] == pts[-1]:
                    pts = pts[:-1]
                d, L, ang = min_rect(pts)
                row.update(lat=f"{sum(p[0] for p in ring) / len(ring):.6f}",
                           lon=f"{sum(p[1] for p in ring) / len(ring):.6f}",
                           area=f"{area(pts):.1f}", depth=f"{d:.1f}", length=f"{L:.1f}",
                           angle=f"{ang:.0f}")
            rows.append(row)
            el.clear()
    return name, rows


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    zpath, out = argv[0], argv[1]
    meshes, jobs = [], max(1, (os.cpu_count() or 2) - 1)
    it = iter(argv[2:])
    for a in it:
        if a == "--mesh":
            meshes.append(next(it))
        elif a == "--jobs":
            jobs = int(next(it))
    with zipfile.ZipFile(zpath) as z:
        names = sorted(n for n in z.namelist()
                       if n.startswith("udx/bldg/") and n.endswith(".gml")
                       and (not meshes or any(n.split("/")[-1].startswith(m) for m in meshes)))
        codes = {"usage": codelist(z, "Building_usage.xml"),
                 "roof": codelist(z, "Building_roofType.xml"),
                 "structure": codelist(z, "BuildingDetailAttribute_buildingStructureType.xml"),
                 "detailed_usage": codelist(z, "BuildingDetailAttribute_detailedUsage.xml")}
    print(f"{len(names)} building files, {jobs} jobs", flush=True)
    n = 0
    with open(out, "w", newline="", encoding="utf-8") as fh, multiprocessing.Pool(jobs) as pool:
        w = csv.DictWriter(fh, fieldnames=FIELDS, extrasaction="ignore")
        w.writeheader()
        for i, (name, rows) in enumerate(pool.imap_unordered(parse_file, [(zpath, x) for x in names])):
            for r in rows:
                for k, table in codes.items():
                    if r.get(k) in table:
                        r[k] = table[r[k]]
                w.writerow(r)
            n += len(rows)
            print(f"[{i + 1}/{len(names)}] {name.split('/')[-1]}: {len(rows)} (total {n})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
