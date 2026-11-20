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
    png(os.path.join(OUT, 'sidewalk.png'), sidewalk())
    png(os.path.join(OUT, 'sidewalk_side.png'), sidewalk_side())


if __name__ == '__main__':
    main()
