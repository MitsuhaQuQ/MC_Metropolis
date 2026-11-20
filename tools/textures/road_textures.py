"""Draws the road blocks' 16x16 textures for City Builder. Run from the repository root: python tools/textures/road_textures.py

The textures are made by this script for this mod (no outside sources), so they carry the mod's own licence.
"""
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


def main():
    os.makedirs(OUT, exist_ok=True)
    png(os.path.join(OUT, 'asphalt.png'), [[asphalt(x, y) for x in range(16)] for y in range(16)])
    png(os.path.join(OUT, 'asphalt_line_white.png'), line((228, 228, 222)))
    png(os.path.join(OUT, 'asphalt_line_yellow.png'), line((226, 178, 44)))
    png(os.path.join(OUT, 'sidewalk.png'), sidewalk())


if __name__ == '__main__':
    main()
