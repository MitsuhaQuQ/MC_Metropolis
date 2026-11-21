"""Draws the buildings' lanterns, lamps and doors for City Builder and writes their block states, block and item models,
loot tables, names and tool tags. Run from the repository root: python tools/textures/lantern_door_textures.py

The textures are drawn by this script for this mod (no outside sources), so they carry the mod's own licence.

The blocks (see common's block.BuildingBlocks; core's building.Palette picks them for a building's parts):
- chochin_red, chochin_white: a paper lantern (提灯) hung from the eaves beside a shop's or machiya's door, or from a
  covered walk's roof: a ribbed paper body between black lacquered caps, on a cord; a brushed mark on its front.
- andon, andon_sign: a standing paper box lamp (置き行灯) by a shop's door, on four short legs; the sign's paper
  brushed with a mark on its front and back.
- porch_light_black, porch_light_silver: a small modern wall light by a house's or flat's door (玄関灯): frosted glass
  in a metal frame on a wall plate.
- gate_light: a lamp standing on a gate post; toro_light: a stone lantern's fire box (火袋), stone posts round paper.
- koshi_door: a dark wooden lattice door (格子戸) over frosted paper; entrance_door_silver, entrance_door_bronze: an
  aluminium entrance door with frosted glass (アルミ玄関ドア); shop_door: a shop's glass door in a thin frame.
Lamps carry `facing`: their front (for a wall light, out of the wall it is fixed to); models are drawn facing north.
Doors are vanilla door blocks with vanilla door block states; their models are drawn here too.
"""
import json
import os
import struct
import zlib

ROOT = os.path.join('common', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'citybuilder')
TEXTURES = os.path.join(ASSETS, 'textures', 'block')
ITEM_TEXTURES = os.path.join(ASSETS, 'textures', 'item')
MODELS = os.path.join(ASSETS, 'models', 'block')
ITEMS = os.path.join(ASSETS, 'models', 'item')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
LOOT = os.path.join(ROOT, 'data', 'citybuilder', 'loot_tables', 'blocks')
LANG = os.path.join(ASSETS, 'lang')
TAGS = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'blocks')


# ---------------------------------------------------------------- drawing

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


def noise(x, y, seed):
    """A fixed pseudo-random value in -1..1 for a pixel."""
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 32767.5 - 1.0


def tint(c, d, a=255):
    return (clamp(c[0] + d), clamp(c[1] + d), clamp(c[2] + d), a)


def grid(f):
    return [[f(x, y) for x in range(16)] for y in range(16)]


def block_png(name, pixels):
    png(os.path.join(TEXTURES, name + '.png'), pixels)


# A brushed mark (not a character of any script, only strokes that read as one from afar): a vertical stroke, three
# crossing it and a hook, in a box x 0..4, y 0..6, set at (ox, oy)
MARK = {(2, 0), (2, 1), (2, 2), (2, 3), (2, 4), (2, 5), (1, 6), (0, 1), (1, 1), (3, 1), (4, 1), (0, 3), (1, 3), (3, 3),
        (4, 3), (1, 5), (3, 5), (4, 6), (4, 5)}


def marked(x, y, ox, oy):
    return (x - ox, y - oy) in MARK


RED = (184, 36, 30)
PAPER = (240, 234, 218)
LACQUER = (28, 24, 22)
WOOD_DARK = (58, 38, 24)
WOOD = (104, 70, 42)
GLOW = (255, 238, 196)
STONE = (148, 146, 138)
ALUMINIUM = (186, 190, 194)
BRONZE = (82, 64, 50)
BLACK_METAL = (40, 40, 42)


def draw():
    # The paper lanterns: ribs every other row, a brushed mark on the front (the north face spans x 4..12, y 4..13)
    def chochin(paper, ink):
        def f(x, y):
            if marked(x, y, 6, 5):
                return tint(ink, noise(x, y, 3) * 4)
            return tint(paper, (-26 if y % 2 == 0 else 0) + noise(x, y, 1) * 5)
        return f

    block_png('lamp/chochin_red', grid(chochin(RED, (20, 16, 14))))
    block_png('lamp/chochin_white', grid(chochin(PAPER, (22, 18, 16))))
    block_png('lamp/chochin_red_plain', grid(lambda x, y: tint(RED, (-26 if y % 2 == 0 else 0) + noise(x, y, 2) * 5)))
    block_png('lamp/chochin_white_plain', grid(lambda x, y: tint(PAPER, (-22 if y % 2 == 0 else 0) + noise(x, y, 2) * 4)))
    block_png('lamp/lacquer', grid(lambda x, y: tint(LACQUER, noise(x, y, 4) * 5 + (12 if (x + y) % 7 == 0 else 0))))
    block_png('lamp/cord', grid(lambda x, y: tint((150, 120, 70), noise(x, y, 5) * 10 + (-20 if (x + y) % 3 == 0 else 0))))

    # The andon: paper, the frame's thin bars drawn along its edges and across its middle
    def andon(mark):
        def f(x, y):
            if x in (0, 15) or y in (0, 15) or y == 8:
                return tint(WOOD_DARK, noise(x, y, 6) * 5)
            if mark and marked(x, y, 6, 1) and y < 8 or mark and marked(x, y - 8, 6, 1) and y > 8:
                return tint((24, 20, 18), noise(x, y, 7) * 4)
            return tint(PAPER, noise(x, y, 8) * 4 + (4 if (x * 3 + y) % 5 == 0 else 0))
        return f

    block_png('lamp/andon_paper', grid(andon(False)))
    block_png('lamp/andon_sign', grid(andon(True)))
    block_png('lamp/wood_dark', grid(lambda x, y: tint(WOOD_DARK, noise(x, y, 9) * 6 + (-8 if x % 4 == 0 else 0))))

    # Frosted glass, glowing: light in the middle, a little darker towards the edges
    def frosted(x, y):
        edge = min(x, 15 - x, y, 15 - y)
        return tint(GLOW, -24 + 4 * min(edge, 5) + noise(x, y, 10) * 3)

    block_png('lamp/frosted', grid(frosted))
    block_png('lamp/metal_black', grid(lambda x, y: tint(BLACK_METAL, noise(x, y, 11) * 3 + (10 if y == 0 else 0))))
    block_png('lamp/metal_silver', grid(lambda x, y: tint(ALUMINIUM, noise(x, y, 12) * 3 + (14 if y == 0 else -6 if y == 15 else 0))))

    # The stone lantern's fire box: stone, rough
    block_png('lamp/stone', grid(lambda x, y: tint(STONE, noise(x, y, 13) * 12 + (-14 if noise(x // 2, y // 2, 14) > 0.7 else 0))))
    block_png('lamp/paper_window', grid(lambda x, y: tint((250, 226, 170), noise(x, y, 15) * 4 + (-30 if x in (5, 10) or y == 7 else 0))))


# ---------------------------------------------------------------- doors

def door_textures(name, frame, fill):
    """A door's two halves (bottom, top) and its item's icon. fill(x, y) gives the panel for y in 0..31 (from the
    door's top), None where the frame is."""
    def full(x, y):
        v = fill(x, y)
        return v if v is not None else tint(frame, noise(x, y, 20) * 3 + (8 if x in (0,) else -8 if x == 15 else 0))

    top = [[full(x, y) for x in range(16)] for y in range(16)]
    bottom = [[full(x, y + 16) for x in range(16)] for y in range(16)]
    block_png(f'door/{name}_top', top)
    block_png(f'door/{name}_bottom', bottom)
    whole = top + bottom
    icon = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(8):
            icon[y][4 + x] = whole[2 * y][2 * x]
    png(os.path.join(ITEM_TEXTURES, f'{name}.png'), icon)


def koshi(x, y):
    # A frame two pixels each side, rails top and foot and at the lock rail; fine vertical bars a pixel apart over
    # frosted paper, a solid board at the foot
    if x < 2 or x > 13 or y < 2 or y in (15, 16) or y > 25:
        return None
    if x % 2 == 1:
        return tint(WOOD_DARK, noise(x, y, 21) * 6 - 6)
    if y in (8, 22):
        return tint(WOOD_DARK, noise(x, y, 22) * 6)
    return tint((226, 216, 192), noise(x, y, 23) * 4)


def entrance(frame):
    def f(x, y):
        # A frame two pixels round; a tall panel of frosted glass, with grooves across it; a solid panel at the foot;
        # a long pull handle up its lock side
        if x < 2 or x > 13 or y < 2 or y > 29:
            return None
        if x == 12 and 10 <= y <= 21:
            return tint((214, 216, 218), 10 if y == 10 else 0)
        if x == 12 or x == 13:
            return tint(frame, noise(x, y, 24) * 3 - 4)
        if 4 <= x <= 10 and 4 <= y <= 25:
            if y % 5 == 0:
                return tint(frame, 6, 255)
            return tint((214, 222, 226), noise(x, y, 25) * 3 + (10 if (x - y) % 9 == 0 else 0), 215)
        return tint(frame, noise(x, y, 26) * 3 - 2)
    return f


def shop(x, y):
    # A thin frame, clear glass from top to foot, a push bar across at hand height, a kick plate
    if x < 1 or x > 14 or y < 1 or y > 30:
        return None
    if y in (16, 17) and 3 <= x <= 12:
        return tint((210, 214, 218), 12 if y == 16 else -6)
    if y >= 28:
        return tint(ALUMINIUM, -10)
    band = 9 <= (x + 32 - y) % 16 <= 10
    return (clamp(200 + (30 if band else 0)), clamp(222 + (20 if band else 0)), 232, 80 if band else 56)


DOORS = {
    'koshi_door': (WOOD_DARK, koshi, 'solid', 'Lattice Door (Koshido)', '格子门'),
    'entrance_door_silver': (ALUMINIUM, entrance(ALUMINIUM), 'translucent', 'Aluminium Entrance Door (Silver)', '铝合金玄关门（银色）'),
    'entrance_door_bronze': (BRONZE, entrance(BRONZE), 'translucent', 'Aluminium Entrance Door (Bronze)', '铝合金玄关门（古铜色）'),
    'shop_door': (ALUMINIUM, shop, 'translucent', 'Shop Glass Door', '店铺玻璃门'),
}


# ---------------------------------------------------------------- models

def element(frm, to, faces):
    """A box with the faces named {face: texture}, each textured to its own extent of the texture."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    uv = {'down': [x0, 16 - z1, x1, 16 - z0], 'up': [x0, z0, x1, z1], 'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0],
          'south': [x0, 16 - y1, x1, 16 - y0], 'west': [z0, 16 - y1, z1, 16 - y0], 'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0]}
    return {'from': list(frm), 'to': list(to), 'faces': {f: {'uv': uv[f], 'texture': t} for f, t in faces.items()}}


def every(t, but=()):
    return {f: t for f in ('down', 'up', 'north', 'south', 'west', 'east') if f not in but}


def sides(t):
    return {f: t for f in ('north', 'south', 'west', 'east')}


def lamp_model(textures, elements, render=None):
    m = {'parent': 'minecraft:block/block', 'ambientocclusion': False, 'textures': textures, 'elements': elements}
    if render:
        m['render_type'] = render
    return m


def chochin(colour):
    t = {'particle': f'citybuilder:block/lamp/chochin_{colour}_plain', 'front': f'citybuilder:block/lamp/chochin_{colour}',
         'body': f'citybuilder:block/lamp/chochin_{colour}_plain', 'cap': 'citybuilder:block/lamp/lacquer',
         'cord': 'citybuilder:block/lamp/cord'}
    # An octagon of two crossed boxes, the front (north) the wider face; black caps top and foot; a cord to the eaves
    return lamp_model(t, [
        element((4, 3, 5), (12, 12, 11), {'north': '#front', 'south': '#front', 'west': '#body', 'east': '#body'}),
        element((5, 3, 4), (11, 12, 12), {'north': '#front', 'south': '#front', 'west': '#body', 'east': '#body'}),
        element((5, 12, 5), (11, 13, 11), every('#cap')),
        element((5, 2, 5), (11, 3, 11), every('#cap')),
        element((7.5, 13, 7.5), (8.5, 16, 8.5), every('#cord', ('down', 'up'))),
    ])


CHOCHIN_BOXES = [[4, 2, 4, 12, 13, 12], [7.5, 13, 7.5, 8.5, 16, 8.5]]


def andon(kind):
    paper = 'citybuilder:block/lamp/andon_sign' if kind == 'sign' else 'citybuilder:block/lamp/andon_paper'
    t = {'particle': 'citybuilder:block/lamp/andon_paper', 'front': paper, 'side': 'citybuilder:block/lamp/andon_paper',
         'wood': 'citybuilder:block/lamp/wood_dark'}
    legs = [element((x, 0, z), (x + 1, 2, z + 1), every('#wood', ('up',))) for x in (4, 11) for z in (5, 10)]
    return lamp_model(t, legs + [
        element((4, 2, 5), (12, 15, 11), {'north': '#front', 'south': '#front', 'west': '#side', 'east': '#side',
                                          'down': '#wood'}),
        element((3, 15, 4), (13, 16, 12), every('#wood')),
    ])


ANDON_BOXES = [[4, 0, 5, 12, 15, 11], [3, 15, 4, 13, 16, 12]]


def porch_light(metal):
    t = {'particle': f'citybuilder:block/lamp/metal_{metal}', 'metal': f'citybuilder:block/lamp/metal_{metal}',
         'glass': 'citybuilder:block/lamp/frosted'}
    # Facing north: its plate against the south side of the block (the wall), the lamp standing out north from it
    return lamp_model(t, [
        element((5, 3, 15), (11, 13, 16), every('#metal')),
        element((6, 5, 11), (10, 11, 15), {'north': '#glass', 'west': '#glass', 'east': '#glass'}),
        element((5.5, 11, 10.5), (10.5, 12, 15), every('#metal', ('south',))),
        element((5.5, 4, 10.5), (10.5, 5, 15), every('#metal', ('south',))),
    ])


PORCH_BOXES = [[5, 3, 10, 11, 13, 16]]


def gate_light():
    t = {'particle': 'citybuilder:block/lamp/metal_black', 'metal': 'citybuilder:block/lamp/metal_black',
         'glass': 'citybuilder:block/lamp/frosted'}
    return lamp_model(t, [
        element((5, 0, 5), (11, 1, 11), every('#metal')),
        element((6, 1, 6), (10, 7, 10), sides('#glass')),
        element((5, 7, 5), (11, 8, 11), every('#metal')),
        element((7, 8, 7), (9, 9, 9), every('#metal', ('down',))),
    ])


GATE_BOXES = [[5, 0, 5, 11, 9, 11]]


def toro_light():
    t = {'particle': 'citybuilder:block/lamp/stone', 'stone': 'citybuilder:block/lamp/stone',
         'paper': 'citybuilder:block/lamp/paper_window'}
    posts = [element((x, 1, z), (x + 2, 14, z + 2), sides('#stone')) for x in (3, 11) for z in (3, 11)]
    return lamp_model(t, posts + [
        element((4, 1, 4), (12, 14, 12), sides('#paper')),
        element((3, 0, 3), (13, 1, 13), every('#stone')),
        element((2, 14, 2), (14, 16, 14), every('#stone')),
    ])


TORO_BOXES = [[2, 0, 2, 14, 16, 14]]

LAMPS = {
    'chochin_red': (chochin('red'), 'Paper Lantern (Red)', '提灯（红）'),
    'chochin_white': (chochin('white'), 'Paper Lantern (White)', '提灯（白）'),
    'andon': (andon('plain'), 'Standing Paper Lamp (Andon)', '行灯'),
    'andon_sign': (andon('sign'), 'Andon Sign', '行灯看板'),
    'porch_light_black': (porch_light('black'), 'Porch Light (Black)', '玄关灯（黑）'),
    'porch_light_silver': (porch_light('silver'), 'Porch Light (Silver)', '玄关灯（银）'),
    'gate_light': (gate_light(), 'Gate Post Light', '门柱灯'),
    'toro_light': (toro_light(), 'Stone Lantern Fire Box', '石灯笼火袋'),
}

FACING_Y = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


def door_models(name, render):
    """The eight door models, after vanilla's: a panel three pixels thick against one side, the hinge to the left or
    right, open (turned a quarter about the hinge) or shut."""
    bottom, top = f'citybuilder:block/door/{name}_bottom', f'citybuilder:block/door/{name}_top'
    for half, tex in (('bottom', bottom), ('top', top)):
        for hinge in ('left', 'right'):
            for opened in ('', '_open'):
                # vanilla's own templates hold the panel's box and its uvs; only the textures and render type are ours
                m = {'parent': f'minecraft:block/door_{half}_{hinge}{opened}', 'textures': {'bottom': bottom, 'top': top}}
                if render != 'solid':
                    m['render_type'] = f'minecraft:{render}'
                write(os.path.join(MODELS, 'door', f'{name}_{half}_{hinge}{opened}.json'), m)


def door_states(name):
    variants = {}
    for facing, base in (('east', 0), ('north', 270), ('south', 90), ('west', 180)):
        for half, model_half in (('lower', 'bottom'), ('upper', 'top')):
            for hinge in ('left', 'right'):
                for opened in (False, True):
                    model = f'citybuilder:block/door/{name}_{model_half}_{hinge}' + ('_open' if opened else '')
                    y = (base + (0 if not opened else 90 if hinge == 'left' else 270)) % 360
                    v = {'model': model}
                    if y:
                        v['y'] = y
                    variants[f'facing={facing},half={half},hinge={hinge},open={str(opened).lower()}'] = v
    return {'variants': variants}


# ---------------------------------------------------------------- files

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def loot(name, door=False):
    entry = {'type': 'minecraft:item', 'name': f'citybuilder:{name}'}
    if door:
        # A door drops one item, from its lower half
        entry['conditions'] = [{'condition': 'minecraft:block_state_property', 'block': f'citybuilder:{name}',
                                'properties': {'half': 'lower'}}]
    write(os.path.join(LOOT, f'{name}.json'), {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [entry], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})


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


def add_tag(name, values):
    path = os.path.join(TAGS, *name.split('/')) + '.json'
    have = []
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            have = json.load(f)['values']
    write(path, {'replace': False, 'values': have + [v for v in values if v not in have]})


def main():
    draw()
    names = {}
    axe, pickaxe = [], []
    for name, (model, en, zh) in LAMPS.items():
        write(os.path.join(MODELS, 'lamp', f'{name}.json'), model)
        write(os.path.join(BLOCKSTATES, f'{name}.json'), {'variants': {
            f'facing={f}': ({'model': f'citybuilder:block/lamp/{name}', 'y': y} if y else {'model': f'citybuilder:block/lamp/{name}'})
            for f, y in FACING_Y.items()}})
        write(os.path.join(ITEMS, f'{name}.json'), {'parent': f'citybuilder:block/lamp/{name}'})
        loot(name)
        names[name] = (en, zh)
        (pickaxe if name.startswith(('porch', 'gate', 'toro')) else axe).append(f'citybuilder:{name}')
    for name, (frame, fill, render, en, zh) in DOORS.items():
        door_textures(name, frame, fill)
        door_models(name, render)
        write(os.path.join(BLOCKSTATES, f'{name}.json'), door_states(name))
        write(os.path.join(ITEMS, f'{name}.json'), {'parent': 'minecraft:item/generated',
                                                   'textures': {'layer0': f'citybuilder:item/{name}'}})
        loot(name, door=True)
        names[name] = (en, zh)
        (axe if name == 'koshi_door' else pickaxe).append(f'citybuilder:{name}')
    add_names(names)
    add_tag('mineable/axe', axe)
    add_tag('mineable/pickaxe', pickaxe)
    add_tag('wooden_doors', ['citybuilder:koshi_door'])
    add_tag('doors', [f'citybuilder:{n}' for n in DOORS if n != 'koshi_door'])


if __name__ == '__main__':
    main()
