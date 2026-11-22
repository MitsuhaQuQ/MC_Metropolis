"""Draws the shops' signs for City Builder and writes their block states, block and item models, loot tables, names and
tool tags. Run from the repository root: python tools/textures/sign_textures.py [--preview <file.png>]

The textures are drawn by this script for this mod (no outside sources), so they carry the mod's own licence. The
lettering is no script's: strokes put together by a fixed seed so that, from the street, a sign reads as a shop's name
(a few strokes across and down, a box, a sweep), without spelling anything or copying any brand.

The blocks (see common's block.BuildingBlocks, ShopSignBlock; core's building.Palette picks them for a building's parts):
- sign_blade_<colour>: a projecting sign (袖看板), a light box 3 pixels thick standing out a block from the wall, one
  character a block down its length, an aluminium edge round each face. Lit.
- fascia_sign_<colour>: a painted fascia board (看板) over a shop front: a whole block, its front lettered, a band top
  and bottom; a wood board with gold letters among them.
- fascia_lit_<colour>: a lit fascia (内照式看板): a whole block whose front glows white, the letters in a colour. Lit.
Each carries `facing` (its front, out of the wall; a blade's faces are its sides) and `glyph`: 0 a plain panel, 1-8
the characters. Models are drawn facing north, the wall to the south.
"""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lantern_door_textures as ld  # noqa: E402

from lantern_door_textures import tint, noise, grid, write, loot, add_names, add_tag, element  # noqa: E402

MODELS, ITEMS, BLOCKSTATES = ld.MODELS, ld.ITEMS, ld.BLOCKSTATES
FACING_Y = ld.FACING_Y
GLYPHS = 8

ALUMINIUM = (188, 192, 196)

# {colour: (background, letters, English, Chinese)}
BLADES = {
    'white': ((236, 234, 226), (34, 32, 32), 'White', '白'),
    'red': ((168, 38, 34), (246, 242, 236), 'Red', '红'),
    'navy': ((34, 50, 92), (244, 244, 240), 'Navy', '藏青'),
    'green': ((38, 92, 62), (244, 244, 236), 'Green', '绿'),
    'yellow': ((230, 192, 62), (34, 30, 28), 'Yellow', '黄'),
    'black': ((32, 32, 34), (236, 200, 96), 'Black', '黑'),
}
FASCIAS = {
    'wood': ((92, 62, 38), (214, 176, 92), 'Wood', '木'),
    'white': ((232, 230, 222), (40, 36, 34), 'White', '白'),
    'navy': ((36, 52, 90), (240, 240, 234), 'Navy', '藏青'),
    'green': ((40, 88, 60), (240, 240, 232), 'Green', '绿'),
    'black': ((34, 34, 36), (236, 232, 222), 'Black', '黑'),
}
# Lit fascias: the glowing face's letters
LIT = {
    'red': ((178, 34, 32), 'Red Letters', '红字'),
    'blue': ((32, 64, 150), 'Blue Letters', '蓝字'),
    'green': ((26, 120, 66), 'Green Letters', '绿字'),
    'black': ((30, 30, 32), 'Black Letters', '黑字'),
}
GLOW = (250, 248, 240)


def glyph(index, size=10):
    """The strokes of character `index` in a size x size box: a set of (x, y) pixels."""
    rng = random.Random(f'glyph-{index}')
    on = set()

    def hline(y, x0, x1):
        for x in range(x0, x1 + 1):
            on.add((x, y))

    def vline(x, y0, y1):
        for y in range(y0, y1 + 1):
            on.add((x, y))

    s = size - 1
    # Each kind twice among the eight, differing in their strokes
    kind = (index - 1) % 4
    if kind == 0:
        # A radical on the left, strokes across on the right
        split = rng.choice((3, 4))
        vline(1, 1, s - 1)
        hline(rng.choice((2, 3)), 0, split - 1)
        on.add((0, s - 2))
        on.add((split - 1, s - 3))
        for y in sorted(rng.sample(range(0, s + 1), 3)):
            hline(y, split + 1, s)
        vline(rng.randrange(split + 2, s), 0, s)
    elif kind == 1:
        # A roof over a box
        hline(1, 0, s)
        vline(s // 2, 0, 1)
        top = rng.choice((3, 4))
        hline(top, 2, s - 2)
        hline(s, 2, s - 2)
        vline(2, top, s)
        vline(s - 2, top, s)
        if rng.random() < 0.5:
            hline((top + s) // 2, 2, s - 2)
        else:
            vline(s // 2, top, s)
            hline(top + 2, 3, s - 3)
    elif kind == 2:
        # Strokes across a stroke down, a sweep out at its foot
        mid = s // 2
        for y in (1, rng.choice((4, 5)), s - 2):
            hline(y, 1 if y != 1 else 2, s - 1 if y != 1 else s - 2)
        vline(mid, 0, s - 2)
        for k in range(1, 4):
            on.add((mid - k, s - 2 + k if s - 2 + k <= s else s))
            on.add((mid + k, s - 2 + k if s - 2 + k <= s else s))
    else:
        # Two halves side by side, each a box or strokes
        for half, x0 in ((0, 0), (1, s // 2 + 1)):
            x1 = x0 + s // 2 - 1
            if rng.random() < 0.5:
                hline(1, x0, x1)
                hline(s - 1, x0, x1)
                vline(x0, 1, s - 1)
                vline(x1, 1, s - 1)
                hline(s // 2, x0, x1)
            else:
                vline((x0 + x1) // 2, 0, s)
                hline(2, x0, x1)
                hline(s - 3, x0, x1)
                on.add((x0, s))
                on.add((x1, s - 1))
    # A dot or a tick somewhere
    if rng.random() < 0.5:
        on.add((rng.randrange(size), 0))
    return {(x, y) for x, y in on if 0 <= x < size and 0 <= y < size}


def lettered(background, letters, index, frame=None, bands=None, glow=False, seed=0):
    """A face: the background, a character of `index` (0: none) centred, an edge all round (frame) or bands at top and
    foot (bands)."""
    strokes = glyph(index) if index else set()

    def f(x, y):
        if frame is not None and (x in (0, 15) or y in (0, 15)):
            return tint(frame, noise(x, y, seed + 1) * 4 + (8 if y == 0 else -8 if y == 15 else 0))
        if bands is not None and y in (0, 1, 14, 15):
            return tint(bands, noise(x, y, seed + 2) * 3 + (-10 if y in (1, 14) else 0))
        if (x - 3, y - 3) in strokes:
            return tint(letters, noise(x, y, seed + 3) * 3)
        shade = 0 if glow else (6 if y < 5 else -4 if y > 11 else 0)
        return tint(background, shade + noise(x, y, seed + 4) * (2 if glow else 4))
    return f


def plain(c, seed, amount=3, shift=0):
    return lambda x, y: tint(c, shift + noise(x, y, seed) * amount)


def tex(name, f):
    ld.block_png('sign/' + name, grid(f))


def ref(name):
    return f'citybuilder:block/sign/{name}'


def blade_model(colour, index):
    # The blade from the wall (south) out to a pixel short of the block's north edge, 3 pixels thick across x
    face = ref(f'blade_{colour}_{index}')
    edge = ref('blade_edge')
    return {'parent': 'minecraft:block/block', 'ambientocclusion': False,
            'textures': {'particle': face, 'face': face, 'edge': edge},
            'elements': [
                {'from': [6.5, 0, 1], 'to': [9.5, 16, 16], 'faces': {
                    'west': {'uv': [0, 0, 16, 16], 'texture': '#face'},
                    'east': {'uv': [16, 0, 0, 16], 'texture': '#face'},
                    'north': {'uv': [6.5, 0, 9.5, 16], 'texture': '#edge'},
                    'up': {'uv': [6.5, 1, 9.5, 16], 'texture': '#edge'},
                    'down': {'uv': [6.5, 1, 9.5, 16], 'texture': '#edge'}}},
                # The bracket into the wall, top and foot
                element((7, 0.5, 15), (9, 1.5, 16), {'up': '#edge', 'down': '#edge', 'west': '#edge', 'east': '#edge'}),
                element((7, 14.5, 15), (9, 15.5, 16), {'up': '#edge', 'down': '#edge', 'west': '#edge', 'east': '#edge'}),
            ]}


def cube_front(front, side):
    return {'parent': 'minecraft:block/orientable', 'textures': {'particle': side, 'front': front, 'side': side,
                                                                  'top': side}}


def draw():
    tex('blade_edge', plain(ALUMINIUM, 70, 4))
    for colour, (background, letters, _, _) in BLADES.items():
        for g in range(GLYPHS + 1):
            tex(f'blade_{colour}_{g}', lettered(background, letters, g, frame=ALUMINIUM, glow=True, seed=71))
    for colour, (background, letters, _, _) in FASCIAS.items():
        band = ld.WOOD_DARK if colour == 'wood' else tint(background, -24)[:3]
        tex(f'fascia_{colour}_side', plain(band, 72, 4))
        for g in range(GLYPHS + 1):
            tex(f'fascia_{colour}_{g}', lettered(background, letters, g, bands=band, seed=73))
    tex('fascia_lit_side', plain(ALUMINIUM, 74, 3, -10))
    for colour, (letters, _, _) in LIT.items():
        for g in range(GLYPHS + 1):
            tex(f'fascia_lit_{colour}_{g}', lettered(GLOW, letters, g, bands=ALUMINIUM, glow=True, seed=75))


def states(models):
    """Variants for every facing and glyph, {glyph: model}."""
    variants = {}
    for g, m in models.items():
        for f, y in FACING_Y.items():
            variants[f'facing={f},glyph={g}'] = {'model': m, 'y': y} if y else {'model': m}
    return {'variants': variants}


def preview(path):
    """One picture of every sign's faces, for looking over: a row a block, its glyphs across."""
    rows = [f'blade_{c}' for c in BLADES] + [f'fascia_{c}' for c in FASCIAS] + [f'fascia_lit_{c}' for c in LIT]
    scale = 4
    width = (GLYPHS + 1) * 17 * scale
    height = len(rows) * 17 * scale
    pixels = [[(96, 96, 96, 255)] * width for _ in range(height)]
    for r, name in enumerate(rows):
        for g in range(GLYPHS + 1):
            face = read(os.path.join(ld.TEXTURES, 'sign', f'{name}_{g}.png'))
            for y in range(16):
                for x in range(16):
                    for dy in range(scale):
                        for dx in range(scale):
                            pixels[(r * 17 + y) * scale + dy][(g * 17 + x) * scale + dx] = face[y][x]
    big_png(path, pixels)


def read(path):
    import zlib
    import struct
    with open(path, 'rb') as f:
        data = f.read()
    pos = 8
    idat = b''
    while pos < len(data):
        n = struct.unpack('>I', data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        if kind == b'IDAT':
            idat += data[pos + 8:pos + 8 + n]
        pos += 12 + n
    raw = zlib.decompress(idat)
    return [[tuple(raw[y * 65 + 1 + x * 4:y * 65 + 5 + x * 4]) for x in range(16)] for y in range(16)]


def big_png(path, pixels):
    import zlib
    import struct
    h = len(pixels)
    w = len(pixels[0])
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in pixels)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def main():
    draw()
    if '--preview' in sys.argv:
        preview(sys.argv[sys.argv.index('--preview') + 1])
    names = {}
    pickaxe = []

    def model(name, m):
        write(os.path.join(MODELS, 'sign', f'{name}.json'), m)
        return f'citybuilder:block/sign/{name}'

    def block(name, en, zh, models):
        write(os.path.join(BLOCKSTATES, f'{name}.json'), states(models))
        write(os.path.join(ITEMS, f'{name}.json'), {'parent': models[1]})
        names[name] = (en, zh)
        pickaxe.append(f'citybuilder:{name}')
        loot(name)

    for colour, (_, _, en, zh) in BLADES.items():
        block(f'sign_blade_{colour}', f'Projecting Sign ({en})', f'袖看板（{zh}）',
              {g: model(f'sign_blade_{colour}_{g}', blade_model(colour, g)) for g in range(GLYPHS + 1)})
    for colour, (_, _, en, zh) in FASCIAS.items():
        block(f'fascia_sign_{colour}', f'Fascia Sign ({en})', f'招牌板（{zh}）',
              {g: model(f'fascia_sign_{colour}_{g}', cube_front(ref(f'fascia_{colour}_{g}'), ref(f'fascia_{colour}_side')))
               for g in range(GLYPHS + 1)})
    for colour, (_, en, zh) in LIT.items():
        block(f'fascia_lit_{colour}', f'Lit Fascia Sign ({en})', f'内照式招牌（{zh}）',
              {g: model(f'fascia_lit_{colour}_{g}', cube_front(ref(f'fascia_lit_{colour}_{g}'), ref('fascia_lit_side')))
               for g in range(GLYPHS + 1)})
    add_names(names)
    add_tag('mineable/pickaxe', pickaxe)


if __name__ == '__main__':
    main()
