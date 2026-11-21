"""Measures airport terminal buildings on PLATEAU, the sizes for City Builder's terminal models (core.building.airport),
and turns the generated models into structure templates, previews and floor plans.

Two steps (standard library, plus Pillow and plateau_voxel.py's block colours for `build`):

  survey   reads each terminal in TERMINALS out of its city's CityGML zip (written by plateau_fetch.py) and writes
           <out>/survey/<slug>.json and <slug>.png: the footprint turned to the terminal's own axes (x along the
           frontage, z from the landside face towards the apron), and with LOD2 the height of the highest surface over
           every square metre, which shows the main hall, the piers and the curbside canopy apart. Prints the extents.
  build    reads the models written by `gradlew :core:terminalModels` (<out>/models/<slug>.json) and writes for each
           <out>/<slug>.nbt (vanilla structure template, 1.20.1), <slug>_apron.png, <slug>_landside.png and
           <slug>_cut.png (isometric views from the apron, from the landside, and cut open at the gates' level),
           <slug>_plan<n>.png (each floor from above, coloured by zone, with what stands on it), library.json and
           preview.html, one page for all.

Usage:
    python -I tools/assets/plateau_terminals.py survey <plateau dir> <out>
    python -I tools/assets/plateau_terminals.py specs <out>      (the presets and the composed variants)
    gradlew :core:terminalModels -Pin=<out>/terminal_specs.json -Pout=<out>/models
    python -I tools/assets/plateau_terminals.py build <out>

<plateau dir> holds <city>.zip as plateau_fetch.py packs them (F:/MC/citybuilder/core/build/plateau/airports). Source:
3D city models of Project PLATEAU (MLIT; copyright of each city), PLATEAU site policy (compatible with CC BY 4.0); the
models are generated from the measured sizes, nothing of the meshes is shipped, but ATTRIBUTION names the sources.
"""
import base64
import json
import math
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from osm_buildings import min_rect, project  # noqa: E402

try:
    from PIL import Image
except ImportError:  # python -I leaves out the user site-packages, where pip --user puts Pillow
    import site
    sys.path.append(site.getusersitepackages())
    from PIL import Image

# (city zip, gml id, slug, name). The PLATEAU data carries no names: these are what stands there.
TERMINALS = [
    ("ota", "bldg_85660669-ad73-413e-b750-b1c28309955a", "haneda_t1", "羽田空港 第1ターミナル"),
    ("ota", "bldg_8f8402e5-225b-4511-9689-490bdf162865", "haneda_t2", "羽田空港 第2ターミナル"),
    ("fukuoka", "bldg_cbe224f6-ecd0-4295-a2bc-1561f25a939b", "fukuoka_domestic", "福岡空港 国内線旅客ターミナル"),
    ("naha", "bldg_58956136-bff0-4430-98b0-fa3368fd5294", "naha", "那覇空港 旅客ターミナル"),
    ("takamatsu", "bldg_f66318c6-8c83-4c33-b038-e7ae00e1d0e3", "takamatsu", "高松空港 旅客ターミナル"),
    ("makinohara", "bldg_34537112-85ff-40b1-8676-71c7572d99f4", "shizuoka", "富士山静岡空港 旅客ターミナル"),
]

ATTRIBUTION = """出典：3D都市モデル（Project PLATEAU）大田区（2025年度）、福岡市（2024年度）、那覇市（2020年度）、
高松市（2022年度）、牧之原市（2023年度）
Source: 3D city models (Project PLATEAU) of Ota City (FY2025), Fukuoka City (FY2024), Naha City (FY2020),
Takamatsu City (FY2022) and Makinohara City (FY2023), https://www.geospatial.jp/ckan/dataset/?q=plateau

これらの構造物テンプレートは上記データの寸法（外形、高さ、階数）をもとに生成したものです（内部の区画は
各空港の公開フロアマップの構成を参考に一般化）。国土交通省および各市が作成したものではありません。
These structure templates were generated from the sizes measured on the data above (outline, height, storeys); the
interior layouts generalize the airports' public floor maps. They were not made by MLIT or the cities.
"""

GML_ID = "{http://www.opengis.net/gml}id"


def local(tag):
    return tag.rsplit("}", 1)[-1]


def find_building(zpath, gid):
    """The bldg:Building element with this id, read straight out of the zip."""
    with zipfile.ZipFile(zpath) as z:
        for name in z.namelist():
            if "/bldg/" not in name or not name.endswith(".gml"):
                continue
            with z.open(name) as fh:
                for _, el in ET.iterparse(fh, events=("end",)):
                    if local(el.tag) == "Building" and el.get(GML_ID) == gid:
                        return el
    return None


def polygons(building, kinds):
    """Rings of (lat, lon, h) under the given surface kinds (local tag names), or all posLists with kinds None."""
    out = []

    def walk(el, inside):
        t = local(el.tag)
        here = inside or kinds is None or t in kinds
        if t == "posList" and here and el.text:
            v = [float(x) for x in el.text.split()]
            out.append([(v[i], v[i + 1], v[i + 2]) for i in range(0, len(v) - 2, 3)])
        for c in el:
            walk(c, here)
    walk(building, False)
    return out


def raster(rings, cell=1.0):
    """The highest point over every cell: {(i, j): h}, by scanlines through each polygon's projection."""
    top = {}
    for ring in rings:
        if len(ring) < 3:
            continue
        # The plane through the ring (Newell), to read its height anywhere inside
        nx = ny = nz = 0.0
        for (x1, y1, z1), (x2, y2, z2) in zip(ring, ring[1:] + ring[:1]):
            nx += (y1 - y2) * (z1 + z2)
            ny += (z1 - z2) * (x1 + x2)
            nz += (x1 - x2) * (y1 + y2)
        if abs(nz) < 1e-6 * (abs(nx) + abs(ny) + 1e-9):
            continue  # a wall
        cx = sum(p[0] for p in ring) / len(ring)
        cy = sum(p[1] for p in ring) / len(ring)
        cz = sum(p[2] for p in ring) / len(ring)
        ys = [p[1] for p in ring]
        for j in range(int(math.floor(min(ys) / cell)), int(math.floor(max(ys) / cell)) + 1):
            yc = (j + 0.5) * cell
            xs = []
            for (x1, y1, _), (x2, y2, _) in zip(ring, ring[1:] + ring[:1]):
                if (y1 <= yc) != (y2 <= yc):
                    xs.append(x1 + (yc - y1) * (x2 - x1) / (y2 - y1))
            xs.sort()
            for a, b in zip(xs[0::2], xs[1::2]):
                for i in range(int(math.floor(a / cell + 0.5)), int(math.floor(b / cell - 0.5)) + 1):
                    xc = (i + 0.5) * cell
                    h = cz - (nx * (xc - cx) + ny * (yc - cy)) / nz
                    if h > top.get((i, j), -1e9):
                        top[(i, j)] = h
    return top


def survey_one(plateau, city, gid, slug, name, out):
    el = find_building(os.path.join(plateau, city + ".zip"), gid)
    if el is None:
        print(f"{slug}: {gid} not found")
        return None
    lod2 = polygons(el, {"RoofSurface", "OuterFloorSurface", "OuterCeilingSurface"})
    rings = lod2 or polygons(el, None)
    lat0, lon0 = rings[0][0][0], rings[0][0][1]
    proj = project(lat0, lon0)
    pts = [[(*proj(a, b), h) for a, b, h in r] for r in rings]
    ground = min(q[2] for r in polygons(el, None) for q in r)
    flat = [(p[0], p[1]) for r in pts for p in r]
    _, _, ang = min_rect(flat)
    # Turned so the long side runs along x; z across, from the landside (the side away from the apron, decided below)
    c, s = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    turned = [[(x * c + y * s, -x * s + y * c, h - ground) for x, y, h in r] for r in pts]
    top = raster(turned)
    if not top:
        return None
    i0 = min(k[0] for k in top)
    j0 = min(k[1] for k in top)
    w = max(k[0] for k in top) - i0 + 1
    d = max(k[1] for k in top) - j0 + 1
    grid = [[None] * w for _ in range(d)]
    for (i, j), h in top.items():
        grid[j - j0][i - i0] = round(max(0.0, h), 1)
    heights = sorted(h for row in grid for h in row if h is not None)
    entry = {"slug": slug, "name": name, "city": city, "plateau_id": gid, "lod": 2 if lod2 else 1,
             "angle_deg": round(ang, 1), "length": w, "depth": d, "area": len(heights),
             "height_max": heights[-1], "height_p50": heights[len(heights) // 2],
             "heights": grid}
    os.makedirs(os.path.join(out, "survey"), exist_ok=True)
    with open(os.path.join(out, "survey", slug + ".json"), "w", encoding="utf-8") as fh:
        json.dump(entry, fh)
    plan_png(grid, heights[-1], os.path.join(out, "survey", slug + ".png"))
    print(f"{slug:18s} LOD{entry['lod']} {w:4d} x {d:4d} m, {len(heights):6d} m2, height p50 {entry['height_p50']}"
          f" max {entry['height_max']}, turned {ang:.0f} deg", flush=True)
    return entry


def plan_png(grid, hmax, path):
    d, w = len(grid), len(grid[0])
    img = Image.new("RGB", (w, d), (255, 255, 255))
    px = img.load()
    for j in range(d):
        for i in range(w):
            h = grid[j][i]
            if h is not None:
                t = h / max(1.0, hmax)
                px[i, j] = (int(40 + 200 * t), int(60 + 120 * (1 - abs(2 * t - 1))), int(220 - 180 * t))
    scale = max(1, 1200 // max(w, d))
    img.resize((w * scale, d * scale), Image.NEAREST).save(path)


def profile(entry, axis):
    """The median height of each row (axis 'z': across the depth) or column ('x': along the length)."""
    grid = entry["heights"]
    out = []
    if axis == "z":
        for row in grid:
            hs = sorted(h for h in row if h is not None)
            out.append(hs[len(hs) // 2] if hs else None)
    else:
        for i in range(len(grid[0])):
            hs = sorted(row[i] for row in grid if row[i] is not None)
            out.append(hs[len(hs) // 2] if hs else None)
    return out


def survey(plateau, out):
    for city, gid, slug, name in TERMINALS:
        e = survey_one(plateau, city, gid, slug, name, out)
        if e and e["lod"] == 2:
            for axis in ("z", "x"):
                p = profile(e, axis)
                step = max(1, len(p) // 60)
                print(f"  {axis} profile every {step} m:", " ".join("-" if h is None else f"{h:.0f}" for h in p[::step]))


# The terminal models: presets of core.building.airport's units, sizes from `survey` (the footprint turned to its axes,
# z from the landside; roof heights from LOD2, or LOD1's measured height) and floors from the airports' floor maps.
def W(role, x, z, length, depth, height, levels, gates="", eave=None, roof="FLAT", overhang=0, deck=False):
    return dict(role=role, x=x, z=z, length=length, depth=depth, height=height, eave=height if eave is None else eave,
                roof=roof, levels=levels, gates=gates, overhang=overhang, deck=deck)


SPECS = {
    # LOD2, 939 x 266 m. Main hall x 204-727, z 51-141, roof 30.6 m (p90 37.9, gently arched); the band in front
    # (z 0-51, 17.7 m) is the canopy over the double-deck curb; a concourse along the apron (16 m), a pier from the
    # middle (x 398-547, to z 240), wings out to piers at both ends (x 45-85 and 851-891, z 30-235). Floor maps: B1
    # stations, 1F arrivals, 2F check-in from the upper road and departures, 3F-5F the market place, 6F deck; 24
    # bridges, 7 checkpoints along the frontage.
    "haneda_t1": dict(levels=[0, 7, 13, 18, 23], checkIn="DEPARTURES", palette="SILVER", curbDepth=30,
                      gateSpacing=50, bridgeLength=30, upperFrom=0.3, upperTo=0.7, voidDepth=0.42, bay=18, wings=[
            W("HALL", 204, 51, 523, 90, 34, 5, eave=27, roof="VAULT", overhang=30),
            W("CONCOURSE", 204, 141, 523, 24, 16, 3, "S"),
            W("CONCOURSE", 430, 165, 86, 70, 16, 3, "SWE", deck=True),
            W("CONCOURSE", 85, 80, 119, 50, 16, 3, "S"),
            W("CONCOURSE", 45, 30, 40, 205, 16, 3, "WES"),
            W("CONCOURSE", 727, 76, 124, 48, 16, 3, "S"),
            W("CONCOURSE", 851, 35, 40, 195, 16, 3, "WES")]),
    # LOD2, 948 x 415 m (apron on the north in PLATEAU's frame, turned here). Main hall about x 140-800, 120 m deep,
    # roof 29.6 m (p90 35.7); a concourse along it (18 m), piers at both ends reaching 390 m from the landside. Floor
    # maps: B1 stations, 1F arrivals, 2F check-in and departures, 3F-4F market place, 5F deck; 26 bridges.
    "haneda_t2": dict(levels=[0, 7, 13, 18, 23], checkIn="DEPARTURES", palette="SILVER", curbDepth=30,
                      gateSpacing=50, bridgeLength=30, upperFrom=0.35, upperTo=0.65, voidDepth=0.4, bay=18, wings=[
            W("HALL", 140, 0, 660, 120, 33, 5, eave=27, roof="WAVE", overhang=30),
            W("CONCOURSE", 140, 120, 660, 30, 18, 3, "S", deck=True),
            W("CONCOURSE", 70, 120, 70, 50, 18, 3, ""),
            W("CONCOURSE", 20, 150, 50, 250, 18, 3, "WES"),
            W("CONCOURSE", 800, 120, 60, 50, 18, 3, ""),
            W("CONCOURSE", 860, 150, 50, 250, 18, 3, "WES")]),
    # LOD1, 409 x 151 m, 40.8 m measured (the tallest part). The main block x 124-316, 94 m deep, behind a concourse
    # along the whole apron face (31 m deep). Floor maps: 1F check-in beside arrivals at the curb, 2F security and
    # gates, 3F the arrivals concourse and the food hall, 4F deck; 12 bridges.
    "fukuoka_domestic": dict(levels=[0, 7, 13, 19], checkIn="GROUND", palette="SILVER", curbDepth=24, gateSpacing=34,
                             bridgeLength=28, upperFrom=0.15, upperTo=0.85, voidDepth=0.5, bay=18, wings=[
            W("HALL", 124, 0, 192, 94, 28, 4, overhang=14, deck=True),
            W("CONCOURSE", 0, 94, 409, 31, 20, 3, "SWE")]),
    # LOD1, 347 x 239 m, 34.3 m measured. A long block along the landside (z 0-72) and a finger out to the apron
    # (x 305-335, to z 237). Floor maps: 1F arrivals, 2F security and gates, 3F check-in from the upper road and decks,
    # 4F restaurants; the international part at one end.
    "naha": dict(levels=[0, 7, 13, 19, 25], checkIn="UPPER", palette="WHITE", curbDepth=28, gateSpacing=44,
                 bridgeLength=30, upperFrom=0.25, upperTo=0.75, voidDepth=0.45, international=0.25, bay=18, wings=[
            W("HALL", 0, 0, 347, 72, 31, 5, eave=26, roof="VAULT", overhang=24, gates="S"),
            W("CONCOURSE", 305, 72, 30, 165, 19, 3, "WES", deck=True)]),
    # LOD1, 165 x 55 m, 15.5 m, 3 storeys. Floor maps: 1F check-in and arrivals, 2F shops, security and gates,
    # 3F deck over a skywalk; international at one end; 4-5 bridges.
    "takamatsu": dict(levels=[0, 6, 11], checkIn="GROUND", palette="TIMBER", curbDepth=20, gateSpacing=30,
                      bridgeLength=26, upperFrom=0.3, upperTo=0.7, voidDepth=0.45, international=0.2, bay=15, wings=[
            W("HALL", 0, 0, 165, 55, 16, 3, "S", overhang=8, deck=True)]),
    # LOD1, 200 x 74 m, 3 storeys (10.3 m measured is low for them: 17 m, to fit the third). Shigeru Ban's flat roof
    # with a deep timber eave over the landside; 1F check-in and arrivals, 2F security and gates, 3F restaurant and
    # deck; 5 bridges.
    "shizuoka": dict(levels=[0, 6, 11], checkIn="GROUND", palette="TIMBER", curbDepth=22, gateSpacing=36,
                     bridgeLength=26, upperFrom=0.25, upperTo=0.75, voidDepth=0.45, international=0.3, bay=15, wings=[
            W("HALL", 0, 0, 200, 74, 17, 3, "S", overhang=14)]),
}

# Terminals drawn by core.building.airport.TerminalComposer from interchangeable units: (slug, seed, big, pinned units)
VARIANTS = [
    ("variant_linear", 3, False, dict(plan="LINEAR")),
    ("variant_concourse", 5, False, dict(plan="FRONT_CONCOURSE", roof="WAVE", deck=False)),
    ("variant_piers", 11, True, dict(plan="PIERS", checkIn="UPPER")),
    ("variant_end_piers", 21, True, dict(plan="END_PIERS", checkIn="DEPARTURES", roof="VAULT")),
    ("variant_front_big", 8, True, dict(plan="FRONT_CONCOURSE", checkIn="GROUND", roof="SHED")),
]


def specs(out):
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, "terminal_specs.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump([dict(slug=k, **v) for k, v in SPECS.items()]
                  + [dict(slug=k, compose=seed, big=big, **pins) for k, seed, big, pins in VARIANTS], fh, indent=1,
                  ensure_ascii=False)
    print(path)


def main(argv):
    if len(argv) >= 3 and argv[0] == "survey":
        survey(argv[1], argv[2])
        return 0
    if len(argv) >= 2 and argv[0] == "specs":
        specs(argv[1])
        return 0
    if len(argv) >= 2 and argv[0] == "build":
        build(argv[1])
        return 0
    print(__doc__)
    return 2


# ---------------------------------------------------------------- build: templates, views, plans

MC_JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/1.20.1/minecraft-client.jar")
# This mod's blocks: their textures in common's assets (drawn by tools/textures/airport_textures.py)
MOD_TEXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "common", "src", "main",
                            "resources", "assets", "citybuilder", "textures", "block")
MOD_TEXTURE = {"airport_seat": "seat_fabric", "wayfinding_sign": "sign/departures", "metal_roof_slab": "metal_roof",
               "metal_roof_white_slab": "metal_roof_white", "escalator_step": "escalator_step"}
ZONE_COLOURS = {
    "c": (190, 190, 190), "a": (255, 214, 153), "b": (255, 179, 102), "h": (204, 170, 136), "u": (255, 140, 140),
    "k": (153, 204, 255), "s": (255, 102, 102), "i": (230, 120, 200), "y": (190, 225, 255), "p": (255, 240, 120),
    "r": (255, 200, 200), "g": (140, 220, 140), "w": (230, 240, 230), "l": (180, 220, 200), "o": (200, 200, 220),
    "t": (160, 200, 230), "e": (120, 120, 200), "v": (245, 245, 255), "d": (120, 200, 120), "j": (150, 150, 150),
}
THIN = ("_pane", "_bars", "_fence", "_slab", "_stairs", "_pressure_plate")


def texture_names(name):
    """Texture files to try for a block, most likely first."""
    stem = name
    for suffix in ("_stairs", "_slab", "_pane", "_fence", "_pressure_plate", "_wall"):
        if stem.endswith(suffix):
            stem = stem[:-len(suffix)]
    tries = [name, stem, stem + "_planks", stem + "s", stem + "_side", stem + "_block"]
    if stem.startswith("smooth_quartz") or stem == "quartz":
        tries.insert(0, "quartz_block_bottom")
    return tries


def average(img):
    w = img.size[0]
    img = img.convert("RGBA").crop((0, 0, w, min(w, img.size[1])))
    tot = [0.0, 0.0, 0.0, 0.0]
    for r, g, b, a in (img.get_flattened_data() if hasattr(img, "get_flattened_data") else img.getdata()):
        tot[0] += r * a
        tot[1] += g * a
        tot[2] += b * a
        tot[3] += a
    return [round(c / max(1.0, tot[3])) for c in tot[:3]]


def colours(names, cache):
    """The average colour of each block's texture: this mod's from its assets, vanilla's from the 1.20.1 client jar,
    read locally; only the vanilla averages are kept (cached), nothing of the jar is written out."""
    got = {}
    if os.path.exists(cache):
        with open(cache, encoding="utf-8") as fh:
            got = json.load(fh)
    mod = {}
    for n in names:
        path = os.path.join(MOD_TEXTURES, MOD_TEXTURE.get(n, n) + ".png")
        if os.path.exists(path):
            mod[n] = average(Image.open(path))
    names = [n for n in names if n not in mod]
    missing = [n for n in names if n not in got]
    if missing:
        import io
        with zipfile.ZipFile(MC_JAR) as z:
            have = set(z.namelist())
            for n in missing:
                got[n] = [255, 0, 255]
                for t in texture_names(n):
                    path = f"assets/minecraft/textures/block/{t}.png"
                    if path in have:
                        got[n] = average(Image.open(io.BytesIO(z.read(path))))
                        break
        with open(cache, "w", encoding="utf-8") as fh:
            json.dump(got, fh, indent=0)
    got.update(mod)
    return {k: tuple(v) for k, v in got.items()}


def base_name(state):
    return state.split("[", 1)[0].split(":", 1)[-1]


def iso(blocks, size, path, bc, flip=False, ymax=None, max_px=2800):
    """An isometric view from the south-east (the apron side), or with flip from the north-west (the landside); with
    ymax only the blocks up to it (the building cut open)."""
    from PIL import ImageDraw
    sx, sy, sz = size
    s = 2
    while s < 6 and (sx + sz) * (s + 1) <= max_px:
        s += 1
    w = (sx + sz) * s + 8
    h = (sx + sz) * s // 2 + sy * s + 8
    img = Image.new("RGB", (w, h), (244, 241, 234))
    dr = ImageDraw.Draw(img)
    ox, oy = sz * s + 4, sy * s + 4
    pos = {}
    for (x, y, z), st in blocks.items():
        if ymax is not None and y > ymax:
            continue
        if flip:
            x, z = sx - 1 - x, sz - 1 - z
        pos[(x, y, z)] = st
    for (x, y, z) in sorted(pos, key=lambda v: (v[0] + v[2], v[1])):
        if all(n in pos and not base_name(pos[n]).endswith(THIN) for n in ((x + 1, y, z), (x, y, z + 1), (x, y + 1, z))):
            continue
        r, g, b = bc.get(base_name(pos[(x, y, z)]), (255, 0, 255))
        px, py = ox + (x - z) * s, oy + (x + z) * s / 2 - y * s
        top = [(px, py), (px + s, py + s / 2), (px, py + s), (px - s, py + s / 2)]
        right = [(px, py + s), (px + s, py + s / 2), (px + s, py + 1.5 * s), (px, py + 2 * s)]
        left = [(px - s, py + s / 2), (px, py + s), (px, py + 2 * s), (px - s, py + 1.5 * s)]
        for poly, k in ((top, 1.0), (right, 0.8), (left, 0.62)):
            dr.polygon(poly, fill=(int(r * k), int(g * k), int(b * k)))
    img.save(path, optimize=True)


def plan(model, blocks, level, path, bc):
    """A floor seen from above: its zones' colours, with what stands on it over them (full-height walls dark,
    furniture in its own colour); the roof's plan shows the decks."""
    sx, sy, sz = model["size"]
    levels = model["levels"]
    rows = model["zones"][level]
    k = 3 if max(sx, sz) <= 500 else 2
    img = Image.new("RGB", (sx * k, sz * k), (255, 255, 255))
    px = img.load()
    roof = level == len(levels)
    for z in range(sz):
        row = rows[z]
        for x in range(sx):
            code = row[x]
            col = ZONE_COLOURS.get(code, (255, 255, 255))
            if not roof:
                y0 = levels[level] + 1
                stack = [blocks.get((x, y0 + d, z)) for d in range(3)]
                if all(stack):
                    col = (70, 70, 80)
                elif stack[0]:
                    c = bc.get(base_name(stack[0]), (0, 0, 0))
                    col = tuple(int(0.35 * a + 0.65 * b) for a, b in zip(col, c))
            elif code == ".":
                continue
            for i in range(k):
                for j in range(k):
                    px[x * k + i, z * k + j] = col
    img.save(path, optimize=True)


def floor_name(level, levels):
    return "roof" if level == levels else f"{level + 1}F"


def build(out):
    from plateau_voxel import write_nbt, parse_state
    models = os.path.join(out, "models")
    with open(os.path.join(out, "terminal_specs.json"), encoding="utf-8") as fh:
        specs = {d["slug"]: d for d in json.load(fh)}
    names = {slug: name for _, _, slug, name in TERMINALS}
    entries = []
    legend = {}
    for slug, spec in specs.items():
        path = os.path.join(models, slug + ".json")
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as fh:
            model = json.load(fh)
        legend = model["legend"]
        blocks = {(x, y, z): st for x, y, z, st in model["blocks"]}
        size = tuple(model["size"])
        bc = colours(sorted({base_name(st) for st in blocks.values()}), os.path.join(out, "block_colours.json"))
        write_nbt(os.path.join(out, slug + ".nbt"), {k: parse_state(v) for k, v in blocks.items()}, size)
        iso(blocks, size, os.path.join(out, slug + "_apron.png"), bc)
        iso(blocks, size, os.path.join(out, slug + "_landside.png"), bc, flip=True)
        iso(blocks, size, os.path.join(out, slug + "_cut.png"), bc, ymax=model["levels"][1] + 4)
        floors = []
        for level in range(len(model["zones"])):
            if any(c != "." and c != "c" for row in model["zones"][level] for c in row):
                plan(model, blocks, level, os.path.join(out, f"{slug}_plan{level}.png"), bc)
                floors.append(level)
        used = sorted({c for level in floors for row in model["zones"][level] for c in row} - {"."})
        u = model["units"]
        entries.append({"slug": slug, "name": names.get(slug) or "生成例 " + slug.split("_", 1)[1],
                        "preset": "compose" not in spec, "size": list(size), "blocks": len(blocks),
                        "gates": len(model["gates"]), "check_in": u["checkIn"], "palette": u["palette"],
                        "wings": len(u["wings"]), "levels": len(model["levels"]), "floors": floors, "zones": used,
                        "units": u, "spec": spec})
        print(f"{slug:20s} {size[0]:4d} x {size[1]:3d} x {size[2]:4d} {len(blocks):8d} blocks {len(model['gates']):3d}"
              f" gates", flush=True)
    with open(os.path.join(out, "library.json"), "w", encoding="utf-8") as fh:
        json.dump({"source": "PLATEAU (Ota, Fukuoka, Naha, Takamatsu, Makinohara)", "attribution": "ATTRIBUTION",
                   "legend": legend, "terminals": entries}, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(out, "ATTRIBUTION"), "w", encoding="utf-8") as fh:
        fh.write(ATTRIBUTION)
    html = ["<!doctype html><meta charset=utf-8><title>Airport terminals</title><style>body{font:14px sans-serif;"
            "background:#eee;margin:16px}section{background:#fff;padding:12px;margin:12px 0}img{max-width:100%;"
            "display:block;margin:6px 0}.sw{display:inline-block;width:14px;height:14px;vertical-align:middle;"
            "margin:0 4px 0 12px;border:1px solid #888}h3{margin:12px 0 2px}</style><h1>空港ターミナル</h1><p>"
            + ATTRIBUTION.replace("\n", "<br>") + "</p>"]
    for e in entries:
        sw = "".join(f"<span class=sw style='background:rgb{ZONE_COLOURS.get(c, (255, 255, 255))}'></span>"
                     f"{legend.get(c, [c, c])[1]}" for c in e["zones"])
        html.append(f"<section><h2>{e['name']}</h2><p>{e['slug']} — {e['size'][0]}×{e['size'][1]}×{e['size'][2]}, "
                    f"{e['blocks']} blocks, {e['wings']} wings, {e['gates']} bridges, check-in {e['check_in']}, "
                    f"palette {e['palette']}{'' if e['preset'] else ', composed from units'}</p><p>{sw}</p>"
                    f"<h3>apron side</h3><img src='{e['slug']}_apron.png'><h3>landside</h3>"
                    f"<img src='{e['slug']}_landside.png'><h3>cut at the gates' level</h3>"
                    f"<img src='{e['slug']}_cut.png'>")
        for level in e["floors"]:
            html.append(f"<h3>{floor_name(level, e['levels'])}</h3><img src='{e['slug']}_plan{level}.png'>")
        html.append("</section>")
    with open(os.path.join(out, "preview.html"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(html))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
