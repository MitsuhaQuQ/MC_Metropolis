"""Survey vanilla structure templates (.nbt) for import into citybuilder.

Reads every .nbt under the given directories (no third-party libraries) and
reports, per template: size, DataVersion, block count, palette, jigsaw
connectors, and an estimate of the storey height (from horizontal slabs of
solid blocks). Also lists blocks that do not exist in 1.20.1 and the jigsaw
pools the templates refer to, so a kit can be reconstructed.

Usage:
    python -I tools/assets/nbt_survey.py <dir-or-file> ... [--csv out.csv] [--json out.json]

Only reads the files; never runs anything from them.
"""
import collections
import csv
import gzip
import io
import json
import os
import struct
import sys

# DataVersion of 1.20.1; templates saved by later versions may use blocks or
# block states that 1.20.1 does not know.
DATA_VERSION_1_20_1 = 3465

# Blocks added after 1.20.1 (1.20.3 – 1.21.x). Not exhaustive for every
# variant, but covers the families a building could contain.
POST_1_20_1_PREFIXES = (
    "minecraft:crafter", "minecraft:trial_spawner", "minecraft:vault",
    "minecraft:heavy_core", "minecraft:copper_bulb", "minecraft:copper_door",
    "minecraft:copper_trapdoor", "minecraft:copper_grate", "minecraft:chiseled_copper",
    "minecraft:exposed_copper_", "minecraft:weathered_copper_", "minecraft:oxidized_copper_",
    "minecraft:waxed_copper_", "minecraft:waxed_exposed_copper_",
    "minecraft:waxed_weathered_copper_", "minecraft:waxed_oxidized_copper_",
    "minecraft:exposed_chiseled_copper", "minecraft:weathered_chiseled_copper",
    "minecraft:oxidized_chiseled_copper", "minecraft:waxed_chiseled_copper",
    "minecraft:tuff_", "minecraft:polished_tuff", "minecraft:chiseled_tuff",
    "minecraft:tuff_brick", "minecraft:chiseled_tuff_bricks",
    "minecraft:pale_", "minecraft:creaking_heart", "minecraft:resin_",
    "minecraft:chiseled_resin_bricks", "minecraft:open_eyeblossom",
    "minecraft:closed_eyeblossom", "minecraft:leaf_litter", "minecraft:bush",
    "minecraft:firefly_bush", "minecraft:short_dry_grass", "minecraft:tall_dry_grass",
    "minecraft:cactus_flower", "minecraft:wildflowers", "minecraft:dried_ghast",
    "minecraft:test_block", "minecraft:test_instance_block",
)
# Renamed after 1.20.1 (new name -> 1.20.1 name).
RENAMED = {"minecraft:short_grass": "minecraft:grass"}

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void"}
NON_SOLID_HINTS = ("glass", "pane", "door", "trapdoor", "fence", "wall_torch", "torch",
                   "lantern", "flower", "sign", "banner", "carpet", "rail", "button",
                   "lever", "pressure_plate", "ladder", "vine", "leaves", "bars",
                   "chain", "candle", "head", "skull", "pot", "rod", "water", "lava",
                   "grass", "fern", "bed", "chest", "barrel", "jigsaw", "structure_block")


# ---------------------------------------------------------------- NBT reader

class _Reader:
    def __init__(self, data):
        self.b = io.BytesIO(data)

    def read(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.b.read(size))[0]

    def string(self):
        n = self.read(">H")
        return self.b.read(n).decode("utf-8", "replace")

    def payload(self, tag):
        if tag == 1: return self.read(">b")
        if tag == 2: return self.read(">h")
        if tag == 3: return self.read(">i")
        if tag == 4: return self.read(">q")
        if tag == 5: return self.read(">f")
        if tag == 6: return self.read(">d")
        if tag == 7:
            n = self.read(">i"); return self.b.read(n)
        if tag == 8: return self.string()
        if tag == 9:
            t = self.read(">b"); n = self.read(">i")
            return [self.payload(t) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                t = self.read(">b")
                if t == 0: return out
                name = self.string()
                out[name] = self.payload(t)
        if tag == 11:
            n = self.read(">i"); return list(struct.unpack(f">{n}i", self.b.read(4 * n)))
        if tag == 12:
            n = self.read(">i"); return list(struct.unpack(f">{n}q", self.b.read(8 * n)))
        raise ValueError(f"bad tag {tag}")


def read_nbt(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    r = _Reader(data)
    tag = r.read(">b")
    if tag != 10:
        raise ValueError("root is not a compound")
    r.string()
    return r.payload(10)


# ---------------------------------------------------------------- analysis

def _solid(name):
    return name not in AIR and not any(h in name for h in NON_SOLID_HINTS)


def analyse(path):
    root = read_nbt(path)
    size = root.get("size", [0, 0, 0])
    sx, sy, sz = size
    # Templates either have one palette or several ("palettes", random variants).
    palette = root.get("palette") or (root.get("palettes") or [[]])[0]
    names = [p.get("Name", "?") for p in palette]
    counts = collections.Counter()
    solid_by_y = collections.Counter()
    jigsaws = []
    for b in root.get("blocks", []):
        name = names[b["state"]]
        counts[name] += 1
        x, y, z = b["pos"]
        if _solid(name):
            solid_by_y[y] += 1
        if name == "minecraft:jigsaw":
            nbt = b.get("nbt", {})
            props = palette[b["state"]].get("Properties", {})
            jigsaws.append({
                "pos": [x, y, z],
                "orientation": props.get("orientation"),
                "name": nbt.get("name"),
                "target": nbt.get("target"),
                "pool": nbt.get("pool"),
                "final_state": nbt.get("final_state"),
                "joint": nbt.get("joint"),
            })
    # Storey estimate: layers where solid blocks cover at least half the footprint
    # are floor slabs; the median gap between consecutive slabs is the storey height.
    area = max(1, sx * sz)
    slabs = sorted(y for y, n in solid_by_y.items() if n >= 0.5 * area)
    gaps = [b - a for a, b in zip(slabs, slabs[1:]) if b - a > 1]
    storey = sorted(gaps)[len(gaps) // 2] if gaps else None
    newer = sorted({n for n in names if n.startswith(POST_1_20_1_PREFIXES) or n in RENAMED})
    modded = sorted({n.split(":")[0] for n in names if ":" in n and not n.startswith("minecraft:")})
    non_air = sum(v for k, v in counts.items() if k not in AIR)
    return {
        "file": path,
        "data_version": root.get("DataVersion"),
        "size": [sx, sy, sz],
        "blocks": non_air,
        "palette_size": len(names),
        "variants": len(root.get("palettes") or []) or 1,
        "entities": len(root.get("entities", [])),
        "jigsaws": jigsaws,
        "storey": storey,
        "slabs": slabs,
        "newer_blocks": newer,
        "modded_namespaces": modded,
        "top_blocks": [[k, v] for k, v in counts.most_common(12) if k not in AIR],
    }


def scale_class(info):
    """Rough check against the design doc's scale table (section 6)."""
    sx, sy, sz = info["size"]
    if info["storey"] and info["storey"] < 3:
        return "too-low-storey"
    if info["jigsaws"]:
        return "kit-piece"
    if max(sx, sz) <= 9 and sy <= 10:
        return "vanilla-scale"
    return "ok"


# ---------------------------------------------------------------- main

def _files(args):
    for a in args:
        if os.path.isfile(a):
            yield a
        else:
            for d, _, fs in os.walk(a):
                for f in sorted(fs):
                    if f.endswith(".nbt"):
                        yield os.path.join(d, f)


def main(argv):
    out_csv = out_json = None
    paths = []
    it = iter(argv)
    for a in it:
        if a == "--csv": out_csv = next(it)
        elif a == "--json": out_json = next(it)
        else: paths.append(a)
    if not paths:
        print(__doc__); return 2
    results, errors = [], []
    for f in _files(paths):
        try:
            info = analyse(f)
            info["class"] = scale_class(info)
            results.append(info)
        except Exception as e:  # report and keep going
            errors.append((f, repr(e)))

    versions = collections.Counter(r["data_version"] for r in results)
    classes = collections.Counter(r["class"] for r in results)
    storeys = collections.Counter(r["storey"] for r in results if r["storey"])
    newer = collections.Counter(b for r in results for b in r["newer_blocks"])
    modded = collections.Counter(n for r in results for n in r["modded_namespaces"])
    pools = collections.Counter(j["pool"] for r in results for j in r["jigsaws"] if j["pool"])
    blocks = collections.Counter()
    for r in results:
        for k, v in r["top_blocks"]:
            blocks[k] += v

    print(f"templates: {len(results)}  errors: {len(errors)}")
    print("DataVersion:", dict(sorted(versions.items(), key=lambda kv: str(kv[0]))))
    print(f"  newer than 1.20.1 ({DATA_VERSION_1_20_1}):",
          sum(v for k, v in versions.items() if k and k > DATA_VERSION_1_20_1))
    print("classes:", dict(classes))
    print("storey heights:", dict(sorted(storeys.items())))
    print("blocks not in 1.20.1:", dict(newer) or "none")
    print("non-vanilla namespaces:", dict(modded) or "none")
    print(f"jigsaw pools referenced: {len(pools)}")
    for p, n in pools.most_common(15):
        print(f"  {n:5} {p}")
    print("most used blocks:")
    for k, v in blocks.most_common(20):
        print(f"  {v:8} {k}")
    for f, e in errors[:20]:
        print("ERROR", f, e)

    if out_csv:
        with open(out_csv, "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(["file", "data_version", "sx", "sy", "sz", "blocks", "palette",
                        "variants", "entities", "jigsaws", "storey", "class", "newer_blocks"])
            for r in results:
                w.writerow([r["file"], r["data_version"], *r["size"], r["blocks"],
                            r["palette_size"], r["variants"], r["entities"], len(r["jigsaws"]),
                            r["storey"] or "", r["class"], " ".join(r["newer_blocks"])])
    if out_json:
        with open(out_json, "w", encoding="utf-8") as fh:
            json.dump(results, fh, ensure_ascii=False, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
