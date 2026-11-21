"""Draws the buildings' roofing for City Builder and writes its block states, block and item models, loot tables, tool
tags and names. Run from the repository root: python tools/textures/roof_textures.py

The textures are drawn by this script for this mod (no outside sources), so they carry the mod's own licence.

The blocks (see common's block.RoofBlock, block.RoofSlabBlock and block.BuildingBlocks.ROOFS; core's building.Palette
picks them for a building's pitched roof): roof_<roofing>, a whole block (a roof's ridge), and roof_<roofing>_slab, its
steps. Each has an axis, the line of its eaves: the tiles' courses run along it, the tiles' rolls or the sheets' seams
down the slope across it. Drawn with the courses along x; the axis z models are turned a quarter.

Japanese roofings: kawara (桟瓦), the silvered smoked tile (いぶし瓦) of most older houses and the glazed black and
brown of others; colour slate (化粧スレート) on newer houses, grey and black; standing-seam steel (ガルバリウム鋼板)
silver, green and red; copper sheet (銅板), new and gone green.
"""
import json
import os
import struct
import zlib

ROOT = os.path.join('common', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'citybuilder')
TEXTURES = os.path.join(ASSETS, 'textures', 'block', 'roof')
MODELS = os.path.join(ASSETS, 'models', 'block', 'roof')
ITEMS = os.path.join(ASSETS, 'models', 'item')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
LOOT = os.path.join(ROOT, 'data', 'citybuilder', 'loot_tables', 'blocks')
TAGS = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'blocks')
LANG = os.path.join(ASSETS, 'lang')

# name: (pattern, base colour, English, Chinese)
ROOFS = {
    'kawara': ('kawara', (104, 109, 116), 'Smoked Tile', '熏银瓦'),
    'kawara_black': ('kawara', (46, 48, 54), 'Black Glazed Tile', '黑釉瓦'),
    'kawara_brown': ('kawara', (118, 70, 46), 'Brown Glazed Tile', '褐釉瓦'),
    'slate_gray': ('slate', (100, 102, 106), 'Grey Slate', '灰色彩钢板瓦'),
    'slate_black': ('slate', (50, 52, 56), 'Black Slate', '黑色彩钢板瓦'),
    'metal_silver': ('metal', (164, 168, 172), 'Silver Steel Roofing', '银色钢板屋面'),
    'metal_green': ('metal', (66, 108, 92), 'Green Steel Roofing', '绿色钢板屋面'),
    'metal_red': ('metal', (126, 72, 62), 'Red Steel Roofing', '红色钢板屋面'),
    'copper': ('copper', (150, 102, 76), 'Copper Roofing', '铜板屋面'),
    'copper_green': ('copper', (92, 156, 136), 'Green Copper Roofing', '绿锈铜板屋面'),
}


def clamp(v):
    return max(0, min(255, int(round(v))))


def noise(x, y, salt):
    """A fixed pseudo-random number from -1 to 1 for a pixel."""
    h = (x * 374761393 + y * 668265263 + salt * 2246822519) & 0xffffffff
    h = ((h ^ (h >> 13)) * 1274126177) & 0xffffffff
    return ((h ^ (h >> 16)) & 0xffff) / 32767.5 - 1.0


def shade(c, d):
    return tuple(clamp(v + d) for v in c)


def kawara(base, salt):
    """Pan tiles (桟瓦) in courses, each tile 4 wide: a roll catching the light, the pan beside it in shadow; each
    course's lower edge waved as the tiles' are, lower under the roll than the pan, a shadow below it on the course
    beneath; tile to tile a little variation in tone."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            k = x % 4
            # The course's lower edge: a row lower under the roll
            edge = 3 if k in (0, 1) else 2
            r = (y - edge) % 4
            across = [20, 13, -1, -20][k]
            lap = -13 if r == 1 else -4 if r == 2 else 5 if r == 0 else 0
            tile = 5 * noise(x // 4, (y - edge) // 4, salt)
            row.append(shade(base, across + tile + lap + 2 * noise(x, y, salt + 1)) + (255,))
        rows.append(row)
    return rows


def slate(base, salt):
    """Colour slate (化粧スレート): long thin shingles in courses 4 deep, a whole block long, each course's joints
    halfway along the one below's; a deep shadow under each course's lower edge, the joints faint; shingle to
    shingle a little variation."""
    rows = []
    for y in range(16):
        row = []
        course = y // 4
        for x in range(16):
            xs = (x + 8 * (course % 2)) % 16
            joint = xs == 0
            tile = 5 * noise((x + 8 * (course % 2)) // 16, course, salt)
            edge = -24 if y % 4 == 3 else 6 if y % 4 == 0 else 0
            d = tile + edge + (-10 if joint else 0) + 2 * noise(x, y, salt + 1)
            row.append(shade(base, d) + (255,))
        rows.append(row)
    return rows


def metal(base, salt):
    """Standing-seam sheet: a seam every 4, lit on one side, shadowed on the other, the pans between flat."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d = [22, -14, 0, 2][x % 4] + 1.5 * noise(x, y, salt)
            row.append(shade(base, d) + (255,))
        rows.append(row)
    return rows


def copper(base, salt):
    """Flat-lock copper sheet: panels 8 wide and 4 deep, staggered, the locked seams a lighter line, the panels
    mottled as copper weathers."""
    rows = []
    for y in range(16):
        row = []
        course = y // 4
        for x in range(16):
            xs = (x + 4 * (course % 2)) % 16
            seam = xs % 8 == 0 or y % 4 == 0
            d = (16 if seam else 0) + 7 * noise(x // 2, y // 2, salt) + 3 * noise(x, y, salt + 1)
            row.append(shade(base, d) + (255,))
        rows.append(row)
    return rows


PATTERNS = {'kawara': kawara, 'slate': slate, 'metal': metal, 'copper': copper}


def png(path, pixels):
    """Writes a 16x16 RGBA PNG; pixels[y][x] = (r, g, b, a)."""
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in pixels)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def loot(name):
    return {'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'citybuilder:{name}'}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}]}


def slab_loot(name):
    """A slab drops one, a double slab two."""
    return {'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'citybuilder:{name}', 'functions': [
                {'function': 'minecraft:set_count', 'count': 2.0, 'add': False,
                 'conditions': [{'condition': 'minecraft:block_state_property', 'block': f'citybuilder:{name}',
                                 'properties': {'type': 'double'}}]},
                {'function': 'minecraft:explosion_decay'}]}]}]}


def add_names(names):
    """Adds names to the language files as lines of their own, leaving the rest of each file as it is."""
    for lang, index in (('en_us', 0), ('zh_cn', 1)):
        path = os.path.join(LANG, f'{lang}.json')
        with open(path, encoding='utf-8') as f:
            text = f.read()
        known = json.loads(text)
        lines = [f'  {json.dumps(key)}: {json.dumps(v[index], ensure_ascii=lang == "en_us")}'
                 for key, v in names.items() if key not in known]
        if not lines:
            continue
        end = text.rstrip().rindex('}')
        text = text[:end].rstrip() + ',\n' + ',\n'.join(lines) + '\n}\n'
        with open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(text)


def add_tag(name, values):
    path = os.path.join(TAGS, *name.split('/')) + '.json'
    have = []
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            have = json.load(f)['values']
    write(path, {'replace': False, 'values': have + [v for v in values if v not in have]})


def main():
    names = {}
    blocks = []
    for i, (name, (pattern, base, en, zh)) in enumerate(ROOFS.items()):
        png(os.path.join(TEXTURES, f'{name}.png'), PATTERNS[pattern](base, 17 * i + 3))
        tex = f'citybuilder:block/roof/{name}'
        textures = {'bottom': tex, 'top': tex, 'side': tex}
        block, slab = f'roof_{name}', f'roof_{name}_slab'
        write(os.path.join(MODELS, f'{name}.json'), {'parent': 'minecraft:block/cube_bottom_top', 'textures': textures})
        write(os.path.join(MODELS, f'{name}_slab.json'), {'parent': 'minecraft:block/slab', 'textures': textures})
        write(os.path.join(MODELS, f'{name}_slab_top.json'), {'parent': 'minecraft:block/slab_top', 'textures': textures})

        def turned(model, axis):
            return {'model': model, 'y': 90} if axis == 'z' else {'model': model}

        whole = f'citybuilder:block/roof/{name}'
        write(os.path.join(BLOCKSTATES, f'{block}.json'),
              {'variants': {f'axis={a}': turned(whole, a) for a in ('x', 'z')}})
        write(os.path.join(BLOCKSTATES, f'{slab}.json'), {'variants': {
            f'axis={a},type={t}': turned({'bottom': f'citybuilder:block/roof/{name}_slab',
                                          'top': f'citybuilder:block/roof/{name}_slab_top', 'double': whole}[t], a)
            for a in ('x', 'z') for t in ('bottom', 'top', 'double')}})
        write(os.path.join(ITEMS, f'{block}.json'), {'parent': whole})
        write(os.path.join(ITEMS, f'{slab}.json'), {'parent': f'citybuilder:block/roof/{name}_slab'})
        write(os.path.join(LOOT, f'{block}.json'), loot(block))
        write(os.path.join(LOOT, f'{slab}.json'), slab_loot(slab))
        names[f'block.citybuilder.{block}'] = (f'{en} Roof', f'屋面（{zh}）')
        names[f'block.citybuilder.{slab}'] = (f'{en} Roof Slab', f'屋面台阶（{zh}）')
        blocks += [f'citybuilder:{block}', f'citybuilder:{slab}']
    add_tag('mineable/pickaxe', blocks)
    add_tag('slabs', [b for b in blocks if b.endswith('_slab')])
    add_names(names)


if __name__ == '__main__':
    main()
