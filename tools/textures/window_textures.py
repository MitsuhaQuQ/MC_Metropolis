"""Draws the buildings' window blocks for City Builder and writes their block states, block and item models, loot
tables and names. Run from the repository root: python tools/textures/window_textures.py

The textures are drawn by this script for this mod (no outside sources), so they carry the mod's own licence.

The blocks (see common's block.BuildingBlocks; core's building.Palette picks them for a building's windows and shop
fronts): window_<frame>_<glass>, joined to the windows beside it as a pane is (its block state is the pane's), but
framed and set back: the glass two pixels thick in the middle of the block, a head along its top, a sill a pixel proud
of the glass along its foot, a mullion at the middle; so a wall keeps its depth round its windows, and windows side by
side run on as one band. And curtain_<glass>: a curtain wall's glass, whole blocks (side by side one sheet, as glass
blocks are). Frames: aluminium (the usual Japanese sash), dark (offices' black frames), wood
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


def frame_texture(frame):
    """A window's frame: the colour, a little lighter along the top of each pixel row's grain."""
    return [[(*shade(frame, 8 if y % 4 == 0 else -4 if y % 4 == 3 else 0), 255) for x in range(16)] for y in range(16)]


def glass_texture(glass):
    """A window's glass: the tint, with a faint reflection running across it diagonally."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            band = 9 <= (x + 16 - y) % 16 <= 11
            r, g, b, a = glass
            d = 26 if band else 0
            row.append((clamp(r + d), clamp(g + d), clamp(b + d), clamp(a + (30 if band else 0))))
        rows.append(row)
    return rows


def element(frm, to, texture, faces):
    """A box with the faces named, each textured to its own extent of the texture."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    uv = {'down': [x0, z0, x1, z1], 'up': [x0, z0, x1, z1], 'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0],
          'south': [x0, 16 - y1, x1, 16 - y0], 'west': [z0, 16 - y1, z1, 16 - y0], 'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0]}
    return {'from': list(frm), 'to': list(to), 'faces': {f: {'uv': uv[f], 'texture': texture} for f in faces}}


SIDES = ('down', 'up', 'west', 'east')


def post_model(frame, glass):
    """The middle of a window: the mullion, the full height, and the sill's foot under it."""
    return {'parent': 'minecraft:block/block', 'render_type': 'minecraft:translucent',
            'textures': {'particle': f'citybuilder:block/window_glass_{glass}', 'frame': f'citybuilder:block/window_frame_{frame}'},
            'elements': [element((7, 0, 7), (9, 16, 9), '#frame', ('down', 'up', 'north', 'south', 'west', 'east')),
                         element((6, 0, 6), (10, 1, 10), '#frame', ('up', 'north', 'south', 'west', 'east'))]}


def side_model(frame, glass):
    """A window's half towards the north, from its middle to the block's edge: the glass two pixels thick, the head
    along its top and the sill a pixel proud of it either side along its foot; no end faces, so windows side by side
    run on as one."""
    return {'parent': 'minecraft:block/block', 'render_type': 'minecraft:translucent',
            'textures': {'particle': f'citybuilder:block/window_glass_{glass}', 'frame': f'citybuilder:block/window_frame_{frame}',
                         'glass': f'citybuilder:block/window_glass_{glass}'},
            'elements': [element((7, 1, 0), (9, 15, 7), '#glass', ('west', 'east')),
                         element((7, 15, 0), (9, 16, 7), '#frame', ('up', 'down', 'west', 'east')),
                         element((6, 0, 0), (10, 1, 7), '#frame', ('up', 'west', 'east'))]}


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
    for frame, (fc, _, _) in FRAMES.items():
        png(os.path.join(TEXTURES, f'window_frame_{frame}.png'), frame_texture(fc))
    for glass, (gc, _, _) in GLASS.items():
        png(os.path.join(TEXTURES, f'window_glass_{glass}.png'), glass_texture(gc))
    for frame, (fc, fen, fzh) in FRAMES.items():
        for glass, (gc, gen, gzh) in GLASS.items():
            n = f'window_{frame}_{glass}'
            # The first version's whole-block glazing, gone
            for old in (os.path.join(TEXTURES, f'{n}.png'), os.path.join(MODELS, f'{n}.json')):
                if os.path.exists(old):
                    os.remove(old)
            write(os.path.join(MODELS, f'{n}_post.json'), post_model(frame, glass))
            write(os.path.join(MODELS, f'{n}_side.json'), side_model(frame, glass))
            side = f'citybuilder:block/{n}_side'
            write(os.path.join(BLOCKSTATES, f'{n}.json'), {'multipart': [
                {'apply': {'model': f'citybuilder:block/{n}_post'}},
                {'when': {'north': 'true'}, 'apply': {'model': side}},
                {'when': {'east': 'true'}, 'apply': {'model': side, 'y': 90}},
                {'when': {'south': 'true'}, 'apply': {'model': side, 'y': 180}},
                {'when': {'west': 'true'}, 'apply': {'model': side, 'y': 270}}]})
            write(os.path.join(ITEMS, f'{n}.json'), {'parent': 'minecraft:item/generated',
                                                    'textures': {'layer0': f'citybuilder:block/window_glass_{glass}'}})
            write(os.path.join(LOOT, f'{n}.json'), {
                'type': 'minecraft:block',
                'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'citybuilder:{n}'}],
                           'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
            names[n] = (f'{fen} Window ({gen})', f'窗（{fzh}、{gzh}玻璃）')
    # A curtain wall's glass, whole blocks of the windows' glass
    for glass, (gc, gen, gzh) in GLASS.items():
        n = f'curtain_{glass}'
        write(os.path.join(MODELS, f'{n}.json'), {'parent': 'minecraft:block/cube_all', 'render_type': 'minecraft:translucent',
                                                 'textures': {'all': f'citybuilder:block/window_glass_{glass}'}})
        write(os.path.join(BLOCKSTATES, f'{n}.json'), {'variants': {'': {'model': f'citybuilder:block/{n}'}}})
        write(os.path.join(ITEMS, f'{n}.json'), {'parent': f'citybuilder:block/{n}'})
        write(os.path.join(LOOT, f'{n}.json'), {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'citybuilder:{n}'}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
        names[n] = (f'Curtain Wall Glass ({gen})', f'幕墙玻璃（{gzh}）')
    add_names(names)


if __name__ == '__main__':
    main()
