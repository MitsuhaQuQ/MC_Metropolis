"""Draws the bridge detail blocks' 16x16 textures for City Builder. Run from the repository root:
python tools/textures/bridge_textures.py (then tools/textures/bridge_models.py)

Parapets, railings, sound barriers, painted plate girders, bearings, expansion joints, drains. The textures are made
by this script for this mod (no outside sources), so they carry the mod's own licence.
"""
import os
import struct
import zlib

OUT = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder', 'textures', 'block')
CLEAR = (0, 0, 0, 0)


def png(name, pixels):
    """Writes a 16x16 RGBA PNG; pixels[y][x] = (r, g, b) or (r, g, b, a)."""
    raw = b''.join(b'\x00' + bytes(c for p in row for c in (p if len(p) == 4 else p + (255,))) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    with open(os.path.join(OUT, name + '.png'), 'wb') as f:
        f.write(data)


def noise(x, y, seed):
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 32767.5 - 1.0


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, k, n=0.0):
    return tuple(clamp(v * k + n) for v in c[:3]) + tuple(c[3:])


def grid(f):
    return [[f(x, y) for x in range(16)] for y in range(16)]


CONCRETE = (196, 196, 190)
ASPHALT = (58, 59, 63)


def concrete(x, y, seed=11):
    n = noise(x, y, seed) * 6 + noise(x // 3, y // 3, seed + 1) * 4
    return shade(CONCRETE, 1.0, n)


def parapet_side(x, y):
    # A wall parapet: the coping's edge two pixels down, a drip groove under it, a form-tie dot mid-height
    if y <= 1:
        return shade(concrete(x, y), 1.06)
    if y == 2:
        return shade(concrete(x, y), 0.78)
    if (x, y) in ((3, 9), (12, 9)):
        return shade(CONCRETE, 0.7)
    return concrete(x, y)


def parapet_top(x, y):
    return shade(concrete(x, y, 13), 1.05)


RAIL_PAINT = (176, 184, 190)


def railing(x, y):
    # Posts at the block's edge, a handrail on top, two rails below; the rest open
    if x <= 1:
        return shade(RAIL_PAINT, 0.82 if x == 1 else 0.95)
    if y <= 2:
        return shade(RAIL_PAINT, 1.08 if y == 0 else 0.92 if y == 2 else 1.0)
    if y in (7, 8, 12, 13):
        return shade(RAIL_PAINT, 1.0 if y in (7, 12) else 0.85)
    return CLEAR


def railing_top(x, y):
    return shade(RAIL_PAINT, 1.08, noise(x, y, 5) * 3)


BARRIER = (150, 156, 160)


def barrier(x, y):
    # Ribbed metal panels between H posts: a post at the block's edge, ribs every two pixels
    if x <= 1:
        return shade((96, 100, 104), 0.9 if x == 1 else 1.0)
    if y in (0, 15):
        return shade(BARRIER, 0.75)
    return shade(BARRIER, 1.06 if y % 2 == 0 else 0.86, noise(x, y, 7) * 2)


def barrier_clear(x, y):
    if x <= 1:
        return shade((96, 100, 104), 0.9 if x == 1 else 1.0)
    if y in (0, 15):
        return (120, 124, 128, 255)
    g = 8 if (x + y) % 11 == 0 else 0
    return (186 + g, 216 + g, 228 + g, 96)


PAINT = {'gray': (126, 131, 136), 'green': (84, 112, 82), 'blue': (86, 126, 160)}


def girder_side(colour):
    base = PAINT[colour]

    def f(x, y):
        # The web, a vertical stiffener in the middle (lit on its left, shadowed on its right), bolts along the
        # flanges at the top and bottom
        n = noise(x, y, 17) * 3
        if y in (0, 15):
            return shade(base, 0.8, n)
        if y in (1, 14) and x % 3 == 1:
            return shade(base, 0.6)
        if x == 7:
            return shade(base, 1.18, n)
        if x == 8:
            return shade(base, 1.05, n)
        if x == 9:
            return shade(base, 0.78, n)
        return shade(base, 1.0, n)
    return f


def girder_top(colour):
    base = PAINT[colour]

    def f(x, y):
        if (x % 5 == 2) and (y % 5 == 2):
            return shade(base, 0.6)
        return shade(base, 0.95, noise(x, y, 19) * 3)
    return f


STEEL = (112, 114, 118)


def bearing_side(x, y):
    # Top plate, laminated rubber with steel shims, base plate (rows as the model's elements: 0-2, 3-12, 13-15)
    if y <= 2:
        return shade(STEEL, 1.05 if y == 0 else 0.9)
    if y >= 13:
        return shade(STEEL, 0.95 if y == 13 else 0.8)
    if y % 3 == 0:
        return shade(STEEL, 1.1)
    return shade((38, 38, 40), 1.0, noise(x, y, 23) * 3)


def bearing_top(x, y):
    if (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        return shade(STEEL, 0.55)
    return shade(STEEL, 1.0, noise(x, y, 29) * 4)


def asphalt(x, y):
    n = noise(x, y, 1) * 7 + noise(x // 2, y // 2, 2) * 4
    return shade(ASPHALT, 1.0, n)


def joint_top(x, y):
    # A finger joint across the block along x: the steel plates' interlocking fingers in rows 5-10
    if 7 <= y <= 8:
        return shade((150, 152, 156), 0.85 if y == 8 else 1.0)
    if 5 <= y <= 6 and x % 4 < 2 or 9 <= y <= 10 and x % 4 >= 2:
        return shade((150, 152, 156), 1.05)
    if 5 <= y <= 10:
        return (30, 30, 32)
    if y in (4, 11):
        return shade((150, 152, 156), 0.8)
    return asphalt(x, y)


def deck_side(x, y):
    return concrete(x, y, 31)


def drain_top(x, y):
    # A cast-iron grate set in the asphalt by the parapet
    if 3 <= x <= 12 and 3 <= y <= 12:
        if x in (3, 12) or y in (3, 12):
            return (70, 70, 72)
        return (24, 24, 26) if x % 2 == 0 else (82, 82, 84)
    return asphalt(x, y)


PIPE = (188, 188, 182)


def pipe(x, y):
    # A grey PVC pipe, shaded round, a clamp band at the top
    k = (0.8, 0.95, 1.08, 0.9)[min(3, x % 4)]
    if y <= 1:
        return shade((90, 92, 96), k)
    return shade(PIPE, k, noise(x, y, 37) * 2)


def main():
    os.makedirs(OUT, exist_ok=True)
    png('concrete_parapet', grid(parapet_side))
    png('concrete_parapet_top', grid(parapet_top))
    png('bridge_railing', grid(railing))
    png('bridge_railing_top', grid(railing_top))
    png('sound_barrier', grid(barrier))
    png('sound_barrier_clear', grid(barrier_clear))
    for colour in PAINT:
        png('steel_girder_%s' % colour, grid(girder_side(colour)))
        png('steel_girder_%s_top' % colour, grid(girder_top(colour)))
    png('bridge_bearing', grid(bearing_side))
    png('bridge_bearing_top', grid(bearing_top))
    png('expansion_joint', grid(joint_top))
    png('deck_side', grid(deck_side))
    png('deck_drain', grid(drain_top))
    png('drain_pipe', grid(pipe))


if __name__ == '__main__':
    main()
