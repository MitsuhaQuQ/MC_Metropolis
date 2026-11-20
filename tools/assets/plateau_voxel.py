"""Turn selected PLATEAU LOD2 buildings into Minecraft structure templates (the building library).

Two steps, both from the CityGML zip and the CSV written by plateau_buildings.py:

  extract   reads the LOD2 surfaces (roof, wall, ground ...) of the chosen buildings out of the
            zip, one mesh file per process, into <out>/geom/<id>.json (slow: the mesh files are
            300 MB each)
  build     voxelizes each extracted building at 1 block = 1 m, picks blocks from a palette,
            and writes <out>/<slug>.nbt (vanilla structure template, 1.20.1), <out>/<slug>.png
            (isometric preview from the south-east), <out>/library.json and <out>/preview.html

Voxelizing: the building is turned so its walls run along the axes (dominant wall direction,
modulo 90 degrees), every surface polygon is sampled every 0.25 m by scanlines, columns inside
the ground surface are filled up to their highest surface, and only the shell plus one floor per
storey is kept (interior air is not stored). Surfaces outside the ground footprint stay one block
thick, so eaves overhang. Pitched roofs get stairs facing uphill where the slope is steep enough
and slabs where the surface sits in the lower half of a block.

Usage:
    python -I tools/assets/plateau_voxel.py extract <citygml.zip> <buildings.csv> <out> [--id GMLID ...]
    python -I tools/assets/plateau_voxel.py build <buildings.csv> <out> [--id GMLID ...]

Without --id the built-in list LANDMARKS is used. Source: 3D city models of Project PLATEAU
(MLIT; copyright Kyoto City), PLATEAU site policy (compatible with CC BY 4.0): ATTRIBUTION is
written next to the templates and must ship with them.
"""
import base64
import csv
import gzip
import json
import math
import multiprocessing
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from osm_buildings import project  # noqa: E402

# (gml id, slug, name, palette or None for automatic). Names are what stands at these coordinates
# in central Kyoto; the PLATEAU data itself carries no names.
LANDMARKS = [
    ("bldg_715decd8-65ed-44b0-a98f-a2a3e87c8c7f", "kyoto_station", "京都駅ビル", "glass"),
    ("bldg_e337ac33-306a-49a6-b1ba-a85f0f173d5d", "kyoto_tower", "京都タワー", "white"),
    ("bldg_05e0f78c-9115-4472-babb-0ba715460f02", "toji_pagoda", "東寺 五重塔", "temple"),
    ("bldg_324e7112-55ff-49aa-90f8-e6a40c039892", "toji_kondo", "東寺 金堂", "temple"),
    ("bldg_f1daae71-2f47-426c-84e1-5105ac280c70", "toji_kodo", "東寺 講堂", "temple"),
    ("bldg_9119aef5-ef73-4486-9936-08d6487d80c2", "higashi_honganji_complex", "東本願寺 伽藍群", "temple"),
    ("bldg_2dabc819-9afb-4b97-bdc1-5d525730277e", "higashi_honganji_annex", "東本願寺 付属棟", "temple"),
    ("bldg_0ba99961-de54-4d42-9f8b-d848844d32de", "nishi_honganji_goeido", "西本願寺 御影堂", "temple"),
    ("bldg_1f1d7e7b-4735-4f44-9f57-0218fcc2f49e", "nishi_honganji_amidado", "西本願寺 阿弥陀堂", "temple"),
    ("bldg_572af0ac-c579-44be-aa12-d9eb0616f76e", "nishi_honganji_hall", "西本願寺 寄棟の堂", "temple"),
    ("bldg_28a9a7fc-417a-4f7a-aa7c-1a4d8db69c88", "nijo_ninomaru", "二条城 二の丸御殿", "temple"),
    ("bldg_d93b1456-d906-4e15-8635-d32bc211cc03", "temple_hall_karasuma", "烏丸の寺院本堂", "temple"),
    ("bldg_0ae8336e-dcec-43f9-a494-b71b0295ad64", "minamiza", "南座", "temple"),
    ("bldg_aa7e1082-f9c0-4d5e-bb1b-0e0b7394cd2e", "pref_office_old", "京都府庁旧本館", "brick"),
    ("bldg_47f88f29-94bf-4cf9-8b1f-e5ddc674d930", "city_hall", "京都市役所", "brick"),
    ("bldg_3978ea79-6a7b-4cd5-bf8b-5c479ee49bda", "takashimaya", "髙島屋 京都店", None),
    ("bldg_3313947c-c2d9-4b7c-9692-b8a4568ec600", "daimaru", "大丸 京都店", None),
    ("bldg_f8004f1e-5170-4c7b-94ec-a210f5e8a438", "hotel_okura", "ホテルオークラ京都", None),
    ("bldg_e75c0582-8eae-4342-99ea-d6999f5570fe", "westin_miyako", "ウェスティン都ホテル京都", None),
    ("bldg_32cfd39f-b99c-435d-9737-e7fb9094c5e3", "aeon_mall", "イオンモールKYOTO", None),
    ("bldg_b1b5e06c-47c2-4e90-91f5-d921b3875401", "aquarium", "京都水族館", None),
    ("bldg_d118eb3a-f9be-49e8-b223-e53f755ac502", "railway_museum", "京都鉄道博物館", None),
    ("bldg_34c21757-8a98-4dc8-b7d0-ea85209a07f1", "umekoji_depot", "梅小路の大型倉庫", None),
]

ATTRIBUTION = """出典：3D都市モデル（Project PLATEAU）京都市（2025年度）
Source: 3D city model (Project PLATEAU), Kyoto City (FY2025), https://www.geospatial.jp/ckan/dataset/plateau-26100-kyoto-shi-2025

これらの構造物テンプレートは上記データを加工して作成したものです（ボクセル化、方向の補正、
ブロックの割り当て）。国土交通省および京都市が作成したものではありません。
These structure templates were made by processing the data above (voxelized at 1 m, turned to the
grid, blocks assigned). They were not made by MLIT or Kyoto City.
"""

GML_ID = "{http://www.opengis.net/gml}id"
SURFACES = {"RoofSurface": "roof", "OuterFloorSurface": "roof", "WallSurface": "wall",
            "OuterCeilingSurface": "wall", "ClosureSurface": "wall", "GroundSurface": "ground",
            "BuildingInstallation": "wall"}
STEP = 0.25


def local(tag):
    return tag.rsplit("}", 1)[-1]


# ---------------------------------------------------------------- extract

def surfaces(building):
    """[class, exterior ring, [interior rings]] per LOD2 polygon."""
    out = []

    def ring(e):
        for pl in e.iter():
            if local(pl.tag) == "posList" and pl.text:
                v = [float(x) for x in pl.text.split()]
                return [[v[i], v[i + 1], v[i + 2]] for i in range(0, len(v) - 2, 3)]
        return None

    def walk(e, cls):
        t = local(e.tag)
        if t.startswith(("lod0", "lod1", "lod3", "lod4")) or t == "Opening":
            return
        cls = SURFACES.get(t, cls)
        if t == "Polygon" and cls:
            ext, holes = None, []
            for c in e:
                if local(c.tag) == "exterior":
                    ext = ring(c)
                elif local(c.tag) == "interior":
                    r = ring(c)
                    if r:
                        holes.append(r)
            if ext:
                out.append([cls, ext, holes])
            return
        for c in e:
            walk(c, cls)

    walk(building, None)
    return out


def extract_mesh(args):
    zpath, name, ids = args
    found = {}
    with zipfile.ZipFile(zpath) as z, z.open(name) as fh:
        depth = 0
        for ev, el in ET.iterparse(fh, events=("start", "end")):
            if local(el.tag) != "Building":
                continue
            if ev == "start":
                depth += 1
                continue
            depth -= 1
            if depth:
                continue
            gid = el.get(GML_ID)
            if gid in ids:
                found[gid] = surfaces(el)
            el.clear()
    return name, found


def extract(zpath, rows, out, ids):
    os.makedirs(os.path.join(out, "geom"), exist_ok=True)
    by_mesh = {}
    for gid in ids:
        if os.path.exists(os.path.join(out, "geom", gid + ".json")):
            continue
        by_mesh.setdefault(rows[gid]["mesh"], set()).add(gid)
    if not by_mesh:
        print("all extracted")
        return
    jobs = [(zpath, f"udx/bldg/{m}_bldg_6697_op.gml", s) for m, s in sorted(by_mesh.items())]
    print(f"{sum(len(s) for s in by_mesh.values())} buildings in {len(jobs)} mesh files", flush=True)
    with multiprocessing.Pool(min(len(jobs), max(1, (os.cpu_count() or 2) - 1))) as pool:
        for name, found in pool.imap_unordered(extract_mesh, jobs):
            for gid, surf in found.items():
                with open(os.path.join(out, "geom", gid + ".json"), "w", encoding="utf-8") as fh:
                    json.dump({"id": gid, "surfaces": surf}, fh)
            print(f"{name}: {len(found)} buildings", flush=True)


# ---------------------------------------------------------------- voxelize

def newell(pts):
    nx = ny = nz = 0.0
    for (x1, y1, z1), (x2, y2, z2) in zip(pts, pts[1:] + pts[:1]):
        nx += (y1 - y2) * (z1 + z2)
        ny += (z1 - z2) * (x1 + x2)
        nz += (x1 - x2) * (y1 + y2)
    return nx, ny, nz


def open_ring(r):
    return r[:-1] if len(r) > 1 and r[0] == r[-1] else r


def sample_polygon(pts, holes=(), step=STEP):
    """Points every `step` over a planar polygon with holes (any shape) and along its edges."""
    pts = open_ring(pts)
    holes = [open_ring(h) for h in holes if len(open_ring(h)) >= 3]
    if len(pts) < 3:
        return
    for p, q in zip(pts, pts[1:] + pts[:1]):
        n = max(1, int(math.dist(p, q) / step))
        for i in range(n):
            t = i / n
            yield tuple(p[k] + (q[k] - p[k]) * t for k in range(3))
    n = newell(pts)
    k = max(range(3), key=lambda i: abs(n[i]))
    if abs(n[k]) < 1e-9:
        return
    a, b = [i for i in range(3) if i != k]
    d = sum(n[i] * pts[0][i] for i in range(3))
    edges = [((p[a], p[b]), (q[a], q[b])) for r in [pts] + holes for p, q in zip(r, r[1:] + r[:1])]
    lo, hi = min(p[b] for p in pts), max(p[b] for p in pts)
    t = lo + step / 2
    while t < hi:
        xs = sorted(a1 + (t - b1) * (a2 - a1) / (b2 - b1)
                    for (a1, b1), (a2, b2) in edges if (b1 <= t < b2) or (b2 <= t < b1))
        for i in range(0, len(xs) - 1, 2):
            s = xs[i] + step / 2
            while s < xs[i + 1]:
                p = [0.0, 0.0, 0.0]
                p[a], p[b] = s, t
                p[k] = (d - n[a] * s - n[b] * t) / n[k]
                yield tuple(p)
                s += step
        t += step


def fill_columns(ring, holes=()):
    """Columns (x, z) whose centre lies inside a 2D ring and outside its holes."""
    out = set()
    lo, hi = math.floor(min(p[1] for p in ring)), math.ceil(max(p[1] for p in ring))
    edges = [e for r in [ring] + list(holes) for e in zip(r, r[1:] + r[:1])]
    for z in range(lo, hi + 1):
        t = z + 0.5
        xs = sorted(a1 + (t - b1) * (a2 - a1) / (b2 - b1)
                    for (a1, b1), (a2, b2) in edges if (b1 <= t < b2) or (b2 <= t < b1))
        for i in range(0, len(xs) - 1, 2):
            for x in range(math.ceil(xs[i] - 0.5), math.floor(xs[i + 1] - 0.5) + 1):
                out.add((x, z))
    return out


def to_local(surf):
    """Lat/lon/height -> metres (x east, y up, z south), turned so walls follow the axes."""
    allp = [p for _, ring, _ in surf for p in ring]
    lat0 = sum(p[0] for p in allp) / len(allp)
    lon0 = sum(p[1] for p in allp) / len(allp)
    proj = project(lat0, lon0)

    def loc(ring):
        return [(e, h, -n) for (e, n), h in ((proj(a, b), h) for a, b, h in ring)]
    rings = [(cls, loc(ring), [loc(h) for h in holes]) for cls, ring, holes in surf]
    # Dominant wall direction modulo 90 degrees, weighted by area.
    c4 = s4 = 0.0
    for cls, ring, _ in rings:
        if cls != "wall":
            continue
        nx, _, nz = newell(ring)
        w = math.hypot(nx, nz)
        if w > 1e-6:
            a = math.atan2(nz, nx)
            c4 += w * math.cos(4 * a)
            s4 += w * math.sin(4 * a)
    theta = math.atan2(s4, c4) / 4 if (c4 or s4) else 0.0
    c, s = math.cos(-theta), math.sin(-theta)
    def turn(r):
        return [(x * c - z * s, y, x * s + z * c) for x, y, z in r]
    rings = [(cls, turn(r), [turn(h) for h in hs]) for cls, r, hs in rings]
    grounds = [p[1] for cls, ring, _ in rings if cls == "ground" for p in ring]
    y0 = min(grounds) if grounds else min(p[1] for _, ring, _ in rings for p in ring)
    x0 = min(p[0] for _, ring, _ in rings for p in ring)
    z0 = min(p[2] for _, ring, _ in rings for p in ring)

    def shift(r):
        return [(x - x0, y - y0, z - z0) for x, y, z in r]
    rings = [(cls, shift(r), [shift(h) for h in hs]) for cls, r, hs in rings]
    return rings, math.degrees(theta), (lat0, lon0)


def voxelize(rings):
    surf = {}  # voxel -> class
    height = {}  # column -> highest roof point
    columns = set()
    prio = {"ground": 0, "wall": 1, "roof": 2}
    for cls, ring, holes in rings:
        if cls == "ground":
            columns |= fill_columns([(p[0], p[2]) for p in ring], [[(p[0], p[2]) for p in h] for h in holes])
        for x, y, z in sample_polygon(ring, holes):
            v = (math.floor(x), max(0, math.floor(y - 0.01)), math.floor(z))
            old = surf.get(v)
            if old is None or prio[cls] > prio[old]:
                surf[v] = cls
            if cls == "roof":
                col = (v[0], v[2])
                if y > height.get(col, -1e9):
                    height[col] = y
    if not columns:  # no ground surface: take every column under a roof
        columns = set(height)
    top = {}
    for (x, y, z) in surf:
        if y > top.get((x, z), -1):
            top[(x, z)] = y
    solid = set(surf)
    for col in columns:
        t = top.get(col)
        if t is not None:
            for y in range(t + 1):
                solid.add((col[0], y, col[1]))
    return solid, surf, height


# ---------------------------------------------------------------- palettes

PALETTES = {
    # roof full / stairs / slab, wall, accent (pillars, sills), window, floor, flat roof
    "temple": dict(roof="deepslate_tiles", stairs="deepslate_tile_stairs", slab="deepslate_tile_slab",
                   wall="calcite", lower="dark_oak_planks", pillar="dark_oak_log", band="dark_oak_planks",
                   window=None, floor="spruce_planks", flat="deepslate_tiles"),
    "brick": dict(roof="deepslate_tiles", stairs="deepslate_tile_stairs", slab="deepslate_tile_slab",
                  wall="bricks", lower="stone_bricks", pillar="stone_bricks", band="smooth_stone",
                  window="gray_stained_glass", floor="spruce_planks", flat="smooth_stone"),
    "modern": dict(roof="gray_concrete", stairs="polished_andesite_stairs", slab="polished_andesite_slab",
                   wall="light_gray_concrete", lower="polished_andesite", pillar="light_gray_concrete",
                   band="light_gray_concrete", window="light_gray_stained_glass", floor="smooth_stone",
                   flat="smooth_stone"),
    "white": dict(roof="white_concrete", stairs="quartz_stairs", slab="quartz_slab",
                  wall="white_concrete", lower="white_concrete", pillar="white_concrete", band="white_concrete",
                  window="light_gray_stained_glass", floor="smooth_stone", flat="white_concrete"),
    "glass": dict(roof="gray_concrete", stairs="polished_andesite_stairs", slab="polished_andesite_slab",
                  wall="light_gray_concrete", lower="polished_andesite", pillar="gray_concrete",
                  band="light_gray_concrete", window="light_blue_stained_glass", floor="smooth_stone",
                  flat="smooth_stone", glassy=True),
    "house": dict(roof="deepslate_tiles", stairs="deepslate_tile_stairs", slab="deepslate_tile_slab",
                  wall="white_terracotta", lower="white_terracotta", pillar="stripped_dark_oak_log",
                  band="white_terracotta", window="light_gray_stained_glass", floor="spruce_planks",
                  flat="smooth_stone"),
}


def choose_palette(row):
    shape, pitch = row.get("roof_shape") or "", float(row.get("roof_pitch") or 0)
    pitched = shape and not shape.startswith("flat") and pitch >= 12
    if pitched and (row.get("usage") == "文教厚生施設" or row.get("structure") == "木造・土蔵造"):
        return "temple"
    return "house" if pitched else "modern"


def storey_height(row):
    try:
        h, n = float(row["height"]), int(row["storeys"])
        if n > 0:
            return max(3, min(5, round(h / n)))
    except (KeyError, ValueError):
        pass
    return 4


def assign_blocks(solid, surf, height, pal, sh):
    """Block state per kept voxel: (name, properties)."""
    P = PALETTES[pal]
    pitched_roofs = pal in ("temple", "brick", "house")
    blocks = {}
    dirs = [(1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0), (0, -1, 0)]
    for v in solid:
        x, y, z = v
        open_ = [(dx, dy, dz) for dx, dy, dz in dirs if (x + dx, y + dy, z + dz) not in solid]
        if not open_:
            if y % sh == 0:
                blocks[v] = (P["floor"], None)
            continue
        up = (0, 1, 0) in open_
        side = [d for d in open_ if d[1] == 0]
        if up and (surf.get(v) == "roof" or not side or (x, z) in height):
            blocks[v] = roof_block(v, height, P, pitched_roofs)
            continue
        if not side:  # only open below: an overhang's underside
            blocks[v] = (P["band"], None)
            continue
        corner = len({abs(d[0]) for d in side}) > 1 and len(side) >= 2 and any(d[0] for d in side) and any(d[2] for d in side)
        along = z if side[0][0] else x  # coordinate along the wall
        level = y % sh
        roof_above = (x, y + 1, z) in solid and blocks_is_roof(x, y + 1, z, solid, height)
        if corner or (pal in ("temple",) and along % 4 == 0):
            blocks[v] = (P["pillar"], {"axis": "y"} if P["pillar"].endswith("_log") else None)
        elif roof_above:
            blocks[v] = (P["band"], None)
        elif pal == "temple":
            blocks[v] = (P["lower"] if y < 3 else P["wall"], None)
        elif y == 0:
            blocks[v] = (P["lower"], None)
        elif P.get("glassy") and level != 0:
            blocks[v] = (P["window"] if along % 6 else P["pillar"], None)
        elif P["window"] and 0 < level < sh - 1 and along % 4 in (1, 2):
            blocks[v] = (P["window"], None)
        else:
            blocks[v] = (P["wall"], None)
    return blocks


def blocks_is_roof(x, y, z, solid, height):
    return (x, y + 1, z) not in solid and (x, z) in height


def roof_block(v, height, P, pitched):
    x, y, z = v
    h = height.get((x, z))
    if h is None or not pitched:
        return (P["flat"], None)

    def H(dx, dz):
        return height.get((x + dx, z + dz))
    gx = [g for g in ((H(1, 0) - h) if H(1, 0) is not None else None,
                      (h - H(-1, 0)) if H(-1, 0) is not None else None) if g is not None]
    gz = [g for g in ((H(0, 1) - h) if H(0, 1) is not None else None,
                      (h - H(0, -1)) if H(0, -1) is not None else None) if g is not None]
    gx = sum(gx) / len(gx) if gx else 0.0
    gz = sum(gz) / len(gz) if gz else 0.0
    if math.floor(h - 0.01) != y:  # a step of a steep roof, below the surface
        return (P["roof"], None)
    if max(abs(gx), abs(gz)) >= 0.3:
        if abs(gx) >= abs(gz):
            facing = "east" if gx > 0 else "west"
        else:
            facing = "south" if gz > 0 else "north"
        return (P["stairs"], {"facing": facing, "half": "bottom", "shape": "straight", "waterlogged": "false"})
    if h - y <= 0.5:
        return (P["slab"], {"type": "bottom", "waterlogged": "false"})
    return (P["roof"], None)


# ---------------------------------------------------------------- NBT

def nbt_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def nbt_compound(items):
    """items: list of (name, type id, payload bytes)."""
    out = b"".join(bytes([t]) + nbt_string(n) + p for n, t, p in items)
    return out + b"\x00"


def nbt_int_list(vals):
    return bytes([3]) + struct.pack(">i", len(vals)) + b"".join(struct.pack(">i", v) for v in vals)


def nbt_compound_list(payloads):
    return bytes([10 if payloads else 0]) + struct.pack(">i", len(payloads)) + b"".join(payloads)


def write_nbt(path, blocks, size):
    states, index = [], {}
    entries = []
    for (x, y, z), (name, props) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        key = (name, tuple(sorted(props.items())) if props else ())
        if key not in index:
            index[key] = len(states)
            items = [("Name", 8, nbt_string("minecraft:" + name))]
            if props:
                items.append(("Properties", 10, nbt_compound([(k, 8, nbt_string(v)) for k, v in sorted(props.items())])))
            states.append(nbt_compound(items))
        entries.append(nbt_compound([("pos", 9, nbt_int_list([x, y, z])), ("state", 3, struct.pack(">i", index[key]))]))
    root = nbt_compound([
        ("DataVersion", 3, struct.pack(">i", 3465)),  # 1.20.1
        ("size", 9, nbt_int_list(list(size))),
        ("palette", 9, nbt_compound_list(states)),
        ("blocks", 9, nbt_compound_list(entries)),
        ("entities", 9, nbt_compound_list([])),
    ])
    with gzip.open(path, "wb") as fh:
        fh.write(bytes([10]) + nbt_string("") + root)


# ---------------------------------------------------------------- preview

COLOURS = {
    "deepslate_tiles": 0x3A3A3E, "deepslate_tile_stairs": 0x3A3A3E, "deepslate_tile_slab": 0x3A3A3E,
    "calcite": 0xDFE0DC, "dark_oak_planks": 0x4A3220, "dark_oak_log": 0x3C2E1A, "spruce_planks": 0x725431,
    "bricks": 0x976253, "stone_bricks": 0x7A7A7A, "smooth_stone": 0x9E9E9E, "gray_stained_glass": 0x4C5258,
    "gray_concrete": 0x4A4E52, "polished_andesite_stairs": 0x848685, "polished_andesite_slab": 0x848685,
    "polished_andesite": 0x848685, "light_gray_concrete": 0x9D9D97, "light_gray_stained_glass": 0x6F8190,
    "white_concrete": 0xCFD5D6, "quartz_stairs": 0xEBE5DE, "quartz_slab": 0xEBE5DE,
    "light_blue_stained_glass": 0x7FB2D8, "white_terracotta": 0xD1B2A1, "stripped_dark_oak_log": 0x4A3A28,
}


def render(blocks, size, path, max_px=1800):
    sx, sy, sz = size
    s = 2
    while s < 8 and (sx + sz) * (s + 2) <= max_px and ((sx + sz) * (s + 2)) // 2 + sy * (s + 2) <= max_px:
        s += 2
    w = (sx + sz) * s + 2 * s + 8
    h = (sx + sz) * s // 2 + sy * s + 2 * s + 8
    img = bytearray(b"\xf4\xf1\xea" * (w * h))
    ox, oy = sz * s + s + 4, sy * s + s + 4

    def proj(x, y, z):
        return (x - z) * s, (x + z) * s / 2 - y * s

    def sprite(top):
        faces = {0: [(0, top, 0), (1, top, 0), (1, top, 1), (0, top, 1)],
                 1: [(1, 0, 0), (1, top, 0), (1, top, 1), (1, 0, 1)],
                 2: [(0, 0, 1), (1, 0, 1), (1, top, 1), (0, top, 1)]}
        pix = []
        for py in range(-s * 2, s * 2):
            for px in range(-s * 2, s * 2):
                cx, cy = px + 0.5, py + 0.5
                for f, quad in faces.items():
                    q = [proj(*p) for p in quad]
                    if inside(cx, cy, q):
                        pix.append((px, py, f))
                        break
        return pix

    full, half = sprite(1), sprite(0.5)
    shade = (1.0, 0.78, 0.6)
    order = sorted(blocks, key=lambda v: (v[0] + v[1] + v[2], v[1]))
    for v in order:
        x, y, z = v
        name, props = blocks[v]
        if (x + 1, y, z) in blocks and (x, y, z + 1) in blocks and (x, y + 1, z) in blocks:
            continue
        rgb = COLOURS.get(name, 0xFF00FF)
        r, g, b = rgb >> 16, (rgb >> 8) & 255, rgb & 255
        bx, by = proj(x, y, z)
        bx, by = int(bx) + ox, int(by) + oy
        spr = half if props and props.get("type") == "bottom" else full
        cols = [bytes((int(r * k), int(g * k), int(b * k))) for k in shade]
        for px, py, f in spr:
            X, Y = bx + px, by + py
            if 0 <= X < w and 0 <= Y < h:
                i = (Y * w + X) * 3
                img[i:i + 3] = cols[f]
    write_png(path, w, h, img)


def inside(x, y, poly):
    c = False
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            c = not c
    return c


def write_png(path, w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb[y * w * 3:(y + 1) * w * 3]) for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                 + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


# ---------------------------------------------------------------- build

def build_one(args):
    out, gid, slug, name, pal, row = args
    with open(os.path.join(out, "geom", gid + ".json"), encoding="utf-8") as fh:
        surf = json.load(fh)["surfaces"]
    if not surf:
        return None
    rings, angle, (lat, lon) = to_local(surf)
    solid, cls, height = voxelize(rings)
    pal = pal or choose_palette(row)
    sh = storey_height(row)
    blocks = assign_blocks(solid, cls, height, pal, sh)
    size = tuple(max(v[i] for v in blocks) + 1 for i in range(3))
    write_nbt(os.path.join(out, slug + ".nbt"), blocks, size)
    render(blocks, size, os.path.join(out, slug + ".png"))
    counts = {}
    for n, _ in blocks.values():
        counts[n] = counts.get(n, 0) + 1
    return {"slug": slug, "name": name, "plateau_id": gid, "lat": round(lat, 6), "lon": round(lon, 6),
            "usage": row.get("usage"), "structure": row.get("structure"), "year": row.get("year"),
            "measured_height": row.get("height"), "storeys": row.get("storeys"),
            "roof_shape": row.get("roof_shape"), "roof_pitch": row.get("roof_pitch"),
            "palette": pal, "storey_height": sh, "rotation_deg": round(angle, 1),
            "size": list(size), "blocks": len(blocks), "surfaces": len(surf),
            "block_counts": dict(sorted(counts.items(), key=lambda kv: -kv[1]))}


def build(rows, out, chosen):
    jobs = [(out, gid, slug, name, pal, rows.get(gid, {})) for gid, slug, name, pal in chosen
            if os.path.exists(os.path.join(out, "geom", gid + ".json"))]
    entries = []
    with multiprocessing.Pool(max(1, min(len(jobs), (os.cpu_count() or 2) - 1))) as pool:
        for e in pool.imap(build_one, jobs):
            if e:
                entries.append(e)
                print(f"{e['slug']:24s} {e['name']:16s} {e['size'][0]:4d}x{e['size'][1]:3d}x{e['size'][2]:4d} "
                      f"{e['blocks']:7d} blocks  {e['palette']:6s} turned {e['rotation_deg']:+.1f}", flush=True)
    with open(os.path.join(out, "library.json"), "w", encoding="utf-8") as fh:
        json.dump({"source": "PLATEAU Kyoto City FY2025 LOD2", "attribution": "ATTRIBUTION",
                   "buildings": entries}, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(out, "ATTRIBUTION"), "w", encoding="utf-8") as fh:
        fh.write(ATTRIBUTION)
    html = ["<!doctype html><meta charset=utf-8><title>PLATEAU building library</title>",
            "<style>body{font:14px sans-serif;background:#eee;margin:16px}figure{display:inline-block;"
            "vertical-align:top;margin:8px;background:#fff;padding:8px;max-width:100%}img{max-width:100%}"
            "figcaption{margin-top:4px}</style><h1>PLATEAU building library</h1><p>"
            + ATTRIBUTION.replace("\n", "<br>") + "</p>"]
    for e in entries:
        with open(os.path.join(out, e["slug"] + ".png"), "rb") as fh:
            data = base64.b64encode(fh.read()).decode()
        html.append(f"<figure><img src='data:image/png;base64,{data}'><figcaption><b>{e['name']}</b> "
                    f"{e['slug']} — {e['size'][0]}×{e['size'][1]}×{e['size'][2]}, {e['blocks']} blocks, "
                    f"{e['usage']}, {e['structure']}, {e['year']}, roof {e['roof_shape']} {e['roof_pitch']}°, "
                    f"palette {e['palette']}</figcaption></figure>")
    with open(os.path.join(out, "preview.html"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(html))


def main(argv):
    if len(argv) < 3 or argv[0] not in ("extract", "build"):
        print(__doc__)
        return 2
    cmd = argv[0]
    if cmd == "extract":
        zpath, csvpath, out, rest = argv[1], argv[2], argv[3], argv[4:]
    else:
        zpath, csvpath, out, rest = None, argv[1], argv[2], argv[3:]
    ids = [rest[i + 1] for i, a in enumerate(rest) if a == "--id"]
    chosen = [(g, s, n, p) for g, s, n, p in LANDMARKS if not ids or g in ids]
    known = {c[0] for c in chosen}
    chosen += [(g, g.split("_")[1][:8], g, None) for g in ids if g not in known]
    want = {c[0] for c in chosen}
    rows = {}
    with open(csvpath, encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["id"] in want:
                rows[r["id"]] = r
    missing = want - set(rows)
    if missing:
        print("not in the CSV:", ", ".join(sorted(missing)))
    os.makedirs(out, exist_ok=True)
    if cmd == "extract":
        extract(zpath, rows, out, [g for g in want if g in rows])
    else:
        build(rows, out, chosen)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
