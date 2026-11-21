"""Draws the buildings' window blocks for City Builder and writes their block states, block and item models, loot
tables and names. Run from the repository root: python tools/textures/window_textures.py

The textures are drawn by this script for this mod (no outside sources), so they carry the mod's own licence.

The blocks (see common's block.BuildingBlocks; core's building.Palette picks them for a building's windows and shop
fronts): window_<frame>_<glass>, a whole block of glazing, translucent, drawn the same on every side. Its frame runs
along its top and bottom and down its left edge, so windows side by side make one band of glass with a mullion every
metre, and turn a corner unbroken. Frames: aluminium (the usual Japanese sash), dark (offices' black frames), wood
(houses and machiya), white; glass: clear, blue, grey.
"""
import json
import os
import struct
import zlib

ASSETS = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder')
TEXTURES = os.path.join(ASSETS, 'textures', 'block')
MODELS = os.path.join(ASSETS, 'models', 'block')
ITEMS = os.path.join(ASSETS, 'models', 'item')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
LOOT = os.path.join('common', 'src', 'main', 'resources', 'data', 'citybuilder', 'loot_tables', 'blocks')
LANG = os.path.join(ASSETS, 'lang')

FRAMES = {'aluminum': ((190, 194, 198), 'Aluminium', '铝框'),
          'dark': ((52, 54, 58), 'Dark', '黑框'),
          'wood': ((112, 80, 50), 'Wooden', '木框'),
          'white': ((232, 232, 226), 'White', '白框')}
GLASS = {'clear': ((196, 222, 232, 70), 'Clear', '透明'),
         'blue': ((92, 146, 190, 130), 'Blue', '蓝色'),
         'gray': ((96, 112, 124, 140), 'Grey', '灰色')}


def png(path, pixels):
    """Writes a 16x16 RGBA PNG; pixels[y][x] = (r, g, b, a)."""
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in pixels)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def clamp(v):
    return max(0, min(255, int(v)))


def shade(c, d):
    return tuple(clamp(v + d) for v in c[:3])


def window(frame, glass):
    """A metre of glazing: the frame along the top and the sill along the bottom, a mullion down the left edge; the
    glass with a faint reflection running across it diagonally."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if y == 0:
                row.append((*shade(frame, 10), 255))
            elif y == 15:
                row.append((*shade(frame, -22), 255))
            elif x == 0:
                row.append((*frame, 255))
            else:
                band = 9 <= (x + 16 - y) % 16 <= 11
                r, g, b, a = glass
                d = 26 if band else 0
                row.append((clamp(r + d), clamp(g + d), clamp(b + d), clamp(a + (30 if band else 0))))
        rows.append(row)
    return rows


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def add_names(names):
    """Adds the blocks' names to the language files as lines of their own, leaving the rest of each file as it is."""
    for lang, index in (('en_us', 0), ('zh_cn', 1)):
        path = os.path.join(LANG, f'{lang}.json')
        with open(path, encoding='utf-8') as f:
            text = f.read()
        known = json.loads(text)
        lines = [f'  "block.citybuilder.{n}": {json.dumps(v[index], ensure_ascii=lang == "en_us")}'
                 for n, v in names.items() if f'block.citybuilder.{n}' not in known]
        if not lines:
            continue
        end = text.rstrip().rindex('}')
        body = text[:end].rstrip()
        text = body + ',\n' + ',\n'.join(lines) + '\n}\n'
        with open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(text)


def main():
    names = {}
    for frame, (fc, fen, fzh) in FRAMES.items():
        for glass, (gc, gen, gzh) in GLASS.items():
            n = f'window_{frame}_{glass}'
            png(os.path.join(TEXTURES, f'{n}.png'), window(fc, gc))
            write(os.path.join(MODELS, f'{n}.json'), {'parent': 'minecraft:block/cube_all',
                                                      'render_type': 'minecraft:translucent',
                                                      'textures': {'all': f'citybuilder:block/{n}'}})
            write(os.path.join(BLOCKSTATES, f'{n}.json'), {'variants': {'': {'model': f'citybuilder:block/{n}'}}})
            write(os.path.join(ITEMS, f'{n}.json'), {'parent': f'citybuilder:block/{n}'})
            write(os.path.join(LOOT, f'{n}.json'), {
                'type': 'minecraft:block',
                'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'citybuilder:{n}'}],
                           'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
            names[n] = (f'{fen} Window ({gen})', f'窗（{fzh}、{gzh}玻璃）')
    add_names(names)


if __name__ == '__main__':
    main()
