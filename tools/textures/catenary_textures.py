"""Draws the catenary blocks' 16x16 textures for City Builder. Run from the repository root:
python tools/textures/catenary_textures.py (then tools/textures/bridge_models.py)

Galvanised steel poles (an H section), the portal's lattice beam, the cantilever arm and the drop hanger with its
porcelain insulators. Made by this script for this mod (no outside sources), so they carry the mod's own licence.
"""
import os
import struct
import zlib

OUT = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder', 'textures', 'block')
CLEAR = (0, 0, 0, 0)
STEEL = (168, 172, 174)
DARK = (112, 116, 120)
PORCELAIN = (122, 84, 58)


def png(name, pixels):
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


def shade(c, k, n=0.0):
    return tuple(max(0, min(255, int(round(v * k + n)))) for v in c[:3])


def grid(f):
    return [[f(x, y) for x in range(16)] for y in range(16)]


def pole(x, y):
    # An H section seen from its flange: the web's edge a shade darker down the middle, bolt plates every 8
    n = noise(x, y, 3) * 3
    if y % 8 == 0:
        return shade(STEEL, 0.85, n)
    return shade(STEEL, 0.9 if x in (7, 8) else 1.02, n)


def pole_top(x, y):
    # The H itself, seen from above (the model is 6 wide, so the middle six columns show)
    if 5 <= x <= 10 and (y in (5, 10) or x in (7, 8)):
        return shade(STEEL, 1.0)
    return shade(DARK, 1.0)


def beam(x, y):
    # A lattice girder from the side: chords top and bottom (the model's 4..12), Warren diagonals between
    if y in (4, 5) or y in (10, 11):
        return shade(STEEL, 1.0 if y in (4, 10) else 0.85)
    if 6 <= y <= 9:
        d =(x - (y - 6)) % 8 == 0 or (x + (y - 6)) % 8 == 7
        return shade(STEEL, 0.95) if d or x % 8 == 0 else CLEAR
    return CLEAR


def beam_end(x, y):
    if 4 <= x <= 11 and 4 <= y <= 11:
        return shade(STEEL, 0.9) if x in (4, 11) or y in (4, 11) else CLEAR
    return CLEAR


def arm(x, y):
    # A round tube, lit along its top
    k = {7: 1.12, 8: 0.95}.get(y)
    return shade(STEEL, k, noise(x, y, 5) * 2) if k else CLEAR


def hanger(x, y):
    # The drop: a tube down from the beam (rows 0-7), then a string of porcelain discs (8-13), the clamp (14-15)
    if y <= 7:
        return shade(STEEL, 1.05 if x == 7 else 0.9) if x in (7, 8) else CLEAR
    if y <= 13:
        if y % 2 == 0:
            return shade(PORCELAIN, 1.1 if x in (6, 7) else 0.9) if 5 <= x <= 10 else CLEAR
        return shade(PORCELAIN, 0.8) if 6 <= x <= 9 else CLEAR
    return shade(DARK, 1.0) if 5 <= x <= 10 else CLEAR


def main():
    os.makedirs(OUT, exist_ok=True)
    png('catenary_pole', grid(pole))
    png('catenary_pole_top', grid(pole_top))
    png('catenary_beam', grid(beam))
    png('catenary_beam_end', grid(beam_end))
    png('catenary_arm', grid(arm))
    png('catenary_hanger', grid(hanger))


if __name__ == '__main__':
    main()
