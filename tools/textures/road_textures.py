"""Draws the road blocks' 16x16 textures for City Builder. Run from the repository root: python tools/textures/road_textures.py

The textures are made by this script for this mod (no outside sources), so they carry the mod's own licence.
"""
import math
import os
import struct
import zlib

OUT = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder', 'textures', 'block')


def png(path, pixels):
    """Writes a 16x16 RGB PNG; pixels[y][x] = (r, g, b)."""
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 2, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(data)


def noise(x, y, seed):
    """A fixed pseudo-random value in -1..1 for a pixel."""
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 32767.5 - 1.0


def clamp(v):
    return max(0, min(255, int(round(v))))


def asphalt(x, y):
    n = noise(x, y, 1) * 7 + noise(x // 2, y // 2, 2) * 4
    return (clamp(56 + n), clamp(57 + n), clamp(61 + n))


def line(colour):
    """Asphalt with a line 3 pixels wide down the middle, along the texture's height (the block's axis)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if 6 <= x <= 8:
                n = noise(x, y, 3) * 6
                row.append(tuple(clamp(c + n) for c in colour))
            else:
                row.append(asphalt(x, y))
        rows.append(row)
    return rows


ANGLES = 16
OFFSETS = 8


def marking(colour, angle, offset):
    """Asphalt with a line 3 pixels wide across it, as the chunk layer places it (core ChunkLayer.mark): the top face
    runs u along +x and v along +z; the line runs at angle * 180 / ANGLES degrees from +x towards +z, at one of OFFSETS
    positions measured along its normal (-sin, cos) across the block's span that way."""
    theta = math.pi * angle / ANGLES
    nx, nz = -math.sin(theta), math.cos(theta)
    span = 0.5 * (abs(nx) + abs(nz))
    centre = -span + (offset + 0.5) * 2 * span / OFFSETS
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            px = (x + 0.5) / 16 - 0.5
            pz = (y + 0.5) / 16 - 0.5
            if abs(px * nx + pz * nz - centre) <= 1.5 / 16:
                n = noise(x, y, 3) * 6
                row.append(tuple(clamp(c + n) for c in colour))
            else:
                row.append(asphalt(x, y))
        rows.append(row)
    return rows


def painted():
    """Asphalt painted white all over, a zebra crossing's stripe: the paint a little worn, the asphalt showing through
    in a few pixels."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if noise(x, y, 6) > 0.82:
                row.append(asphalt(x, y))
            else:
                n = noise(x, y, 7) * 6
                row.append((clamp(226 + n), clamp(226 + n), clamp(220 + n)))
        rows.append(row)
    return rows


WHITE = (228, 228, 222)
YELLOW = (226, 178, 44)


def paint(colour, x, y):
    """A pixel of paint on the road, a little uneven."""
    n = noise(x, y, 8) * 6
    return tuple(clamp(c + n) for c in colour)


def hatch():
    """A zebra zone (douryuutai): white stripes about 30 cm wide leaning at 45 degrees, one every block, so they run on
    from block to block. Drawn for facing north; the block state turns it."""
    return [[paint(WHITE, x, y) if (x + y) % 16 < 5 else asphalt(x, y) for x in range(16)] for y in range(16)]


def arrow_shaft():
    """A road arrow's shaft: a white bar 4 pixels wide (25 cm) down the middle, along north-south."""
    return [[paint(WHITE, x, y) if 6 <= x <= 9 else asphalt(x, y) for x in range(16)] for y in range(16)]


def arrow_head():
    """A road arrow's head pointing north (the texture's top): a white triangle as wide as the block at its base."""
    rows = []
    for y in range(16):
        half = (y + 1) / 2.0
        rows.append([paint(WHITE, x, y) if abs(x + 0.5 - 8) <= half else asphalt(x, y) for x in range(16)])
    return rows


def manhole():
    """A cast-iron manhole cover, 90 cm across, its rim darker and its face a grid of raised studs, in the asphalt."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            r = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            n = noise(x, y, 9) * 4
            if r > 7.4:
                row.append(asphalt(x, y))
            elif r > 6.4:
                row.append((clamp(58 + n), clamp(55 + n), clamp(52 + n)))
            elif (x + y) % 3 == 0 or (x - y) % 3 == 0:
                row.append((clamp(104 + n), clamp(100 + n), clamp(94 + n)))
            else:
                row.append((clamp(74 + n), clamp(71 + n), clamp(67 + n)))
        rows.append(row)
    return rows


def drain_grate():
    """A steel drain grate (gutter cover) in a frame, its bars along north-south with dark gaps between."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            n = noise(x, y, 10) * 5
            if x in (0, 15) or y in (0, 15):
                base = 92
            elif x % 2 == 0:
                base = 26
            else:
                base = 122 if y % 5 else 100
            row.append((clamp(base + n), clamp(base + n), clamp(base - 3 + n)))
        rows.append(row)
    return rows


def tactile(kind):
    """Yellow tactile paving: four tiles to the block (a tile is 30 cm, near enough), guide bars along north-south
    ('line') or warning dots ('dot'), raised, lit from the north-west."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            n = noise(x, y, 11) * 5
            tx, ty = x % 8, y % 8
            base = (214, 168, 34)
            if tx == 7 or ty == 7:
                colour = (150, 116, 28)
            elif kind == 'line' and tx in (1, 2, 4, 5) and 1 <= ty <= 5:
                light = tx in (1, 4)
                colour = (238, 196, 70) if light else (186, 142, 26)
            elif kind == 'dot' and tx in (1, 2, 4, 5) and ty in (1, 2, 4, 5):
                light = tx in (1, 4) and ty in (1, 4)
                shade = tx in (2, 5) and ty in (2, 5)
                colour = (240, 200, 76) if light else (180, 138, 24) if shade else (226, 182, 50)
            else:
                colour = base
            row.append(tuple(clamp(c + n) for c in colour))
        rows.append(row)
    return rows


def interlocking():
    """Interlocking pavers (drawn 4 by 8 pixels, 25 by 50 cm, to read at a distance), in a running bond, red-brown with a few
    tan and dark ones, sand joints."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            ty = y // 4
            sx = x + (4 if ty % 2 else 0)
            tx = (sx // 8) % 2
            joint = y % 4 == 3 or sx % 8 == 7
            n = noise(x, y, 12) * 5
            if joint:
                row.append((clamp(122 + n), clamp(112 + n), clamp(98 + n)))
                continue
            pick = noise(tx + 7 * ty, (sx // 8) % 3, 13)
            if pick > 0.6:
                colour = (176, 128, 92)
            elif pick < -0.6:
                colour = (112, 62, 48)
            else:
                colour = (150, 82, 62)
            row.append(tuple(clamp(c + n) for c in colour))
        rows.append(row)
    return rows


def granite(light=False):
    """Grey granite, speckled black and white: plaza slabs a metre square (the block's edge a joint), or a kerb
    stone's lighter top."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            speck = noise(x, y, 14)
            base = 176 if light else 150
            if not light and (x == 15 or y == 15):
                base = 108
            elif speck > 0.75:
                base -= 60
            elif speck < -0.8:
                base += 40
            n = noise(x, y, 15) * 6
            row.append((clamp(base + n), clamp(base + n), clamp(base + 2 + n)))
        rows.append(row)
    return rows


def sidewalk():
    """Light grey paving slabs, 8 pixels square, offset by half a slab every other row."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            sx = (x + (4 if (y // 8) % 2 else 0)) % 8
            joint = y % 8 == 7 or sx == 7
            n = noise(x, y, 4) * 5
            base = 132 if joint else 172
            row.append((clamp(base + n), clamp(base - 2 + n), clamp(base - 6 + n)))
        rows.append(row)
    return rows


def sidewalk_side():
    """The pavement's side: a kerb stone along the top (the 3 pixels a lowered carriageway leaves showing, about 19 cm,
    a lighter edge on its top), a joint under it, and the darker concrete of the kerb's body below."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            n = noise(x, y, 5) * 5
            if y == 0:
                base = 196
            elif y < 3:
                base = 184 - (6 if x % 8 == 7 else 0)
            elif y == 3:
                base = 120
            else:
                base = 146
            row.append((clamp(base + n), clamp(base - 1 + n), clamp(base - 4 + n)))
        rows.append(row)
    return rows


def main():
    os.makedirs(os.path.join(OUT, 'line'), exist_ok=True)
    png(os.path.join(OUT, 'asphalt.png'), [[asphalt(x, y) for x in range(16)] for y in range(16)])
    for name, colour in (('white', (228, 228, 222)), ('yellow', (226, 178, 44))):
        for angle in range(ANGLES):
            for offset in range(OFFSETS):
                png(os.path.join(OUT, 'line', f'{name}_{angle}_{offset}.png'), marking(colour, angle, offset))
    png(os.path.join(OUT, 'asphalt_white.png'), painted())
    png(os.path.join(OUT, 'asphalt_hatch.png'), hatch())
    png(os.path.join(OUT, 'road_arrow_shaft.png'), arrow_shaft())
    png(os.path.join(OUT, 'road_arrow_head.png'), arrow_head())
    png(os.path.join(OUT, 'manhole.png'), manhole())
    png(os.path.join(OUT, 'drain_grate.png'), drain_grate())
    png(os.path.join(OUT, 'tactile_line.png'), tactile('line'))
    png(os.path.join(OUT, 'tactile_dot.png'), tactile('dot'))
    png(os.path.join(OUT, 'sidewalk_interlocking.png'), interlocking())
    png(os.path.join(OUT, 'sidewalk_granite.png'), granite())
    png(os.path.join(OUT, 'curb.png'), granite(light=True))
    png(os.path.join(OUT, 'sidewalk.png'), sidewalk())
    png(os.path.join(OUT, 'sidewalk_side.png'), sidewalk_side())


if __name__ == '__main__':
    main()
