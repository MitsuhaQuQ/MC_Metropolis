"""Draws the temple and shrine decoration blocks' 16x16 textures and writes their block states, models, item models,
loot tables and names for City Builder. Run from the repository root: python tools/textures/temple_blocks.py

Ground: stone paving (石畳), white gravel (白砂), shrine pebbles (玉砂利), moss (苔), raked gravel (砂紋: straight, and
the rings round a rock group in 4 quadrants of 3 x 3 blocks, each block drawn where it lies, so the rings run on from
block to block), stepping stones in moss (飛石), garden rock (庭石). Garden: split-bamboo fence (建仁寺垣). Inside:
tatami (畳; a mat is two blocks facing apart, the seam at the outer ends), fusuma (襖, plain and gold leaf, two blocks
high), shoji (障子), carved transom (欄間), coffered ceiling (格天井), cushion (座布団), incense burner (香炉), paper
lantern (提灯), sacred rope (注連縄), offering box (賽銭箱).

The textures are made by this script for this mod (no outside sources), so they carry the mod's own licence. The
bottom of the moss block uses the game's own dirt texture by reference (nothing copied).
"""
import json
import math
import os
import struct
import zlib

ASSETS = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder')
TEX = os.path.join(ASSETS, 'textures', 'block')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
MODELS = os.path.join(ASSETS, 'models', 'block')
ITEMS = os.path.join(ASSETS, 'models', 'item')
LOOT = os.path.join('common', 'src', 'main', 'resources', 'data', 'citybuilder', 'loot_tables', 'blocks')
LANG = os.path.join(ASSETS, 'lang')

# Ring raking round a rock group: quadrants by the direction from the rocks' centre (a block corner), clockwise.
QUADRANTS = {'south': (1, 1), 'west': (-1, 1), 'north': (-1, -1), 'east': (1, -1)}
RING_RADIUS = 46  # pixels from the centre; beyond it the raking runs straight along x

NAMES = {  # id: (English, Chinese)
    'ishidatami': ('Stone Paving (Ishidatami)', '石畳'),
    'shirasu': ('White Gravel (Shirasu)', '白砂'),
    'tamajari': ('Shrine Pebbles (Tamajari)', '玉砂利'),
    'koke': ('Moss Ground (Koke)', '苔'),
    'raked_gravel': ('Raked Gravel', '砂紋'),
    'raked_gravel_ring': ('Raked Gravel Rings', '砂紋（同心円）'),
    'tobiishi': ('Stepping Stone in Moss (Tobiishi)', '飞石'),
    'niwaishi': ('Garden Rock (Niwaishi)', '庭石'),
    'bamboo_fence': ('Split Bamboo Fence (Kenninji-gaki)', '建仁寺垣'),
    'tatami': ('Tatami', '榻榻米'),
    'fusuma': ('Fusuma', '襖'),
    'fusuma_gold': ('Gold Leaf Fusuma', '金箔襖'),
    'shoji': ('Shoji', '障子'),
    'ranma': ('Carved Transom (Ranma)', '欄間'),
    'coffered_ceiling': ('Coffered Ceiling (Goutenjou)', '格天井'),
    'zabuton': ('Zabuton', '座布団'),
    'koro': ('Incense Burner (Koro)', '香炉'),
    'chochin': ('Paper Lantern (Chochin)', '提灯'),
    'shimenawa': ('Sacred Rope (Shimenawa)', '注连绳'),
    'saisen_bako': ('Offering Box (Saisen-bako)', '赛钱箱'),
}


# ---------------------------------------------------------------- drawing

def png(name, pixels):
    """Writes a 16x16 RGB PNG; pixels[y][x] = (r, g, b)."""
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 2, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    os.makedirs(TEX, exist_ok=True)
    with open(os.path.join(TEX, name + '.png'), 'wb') as f:
        f.write(data)


def noise(x, y, seed):
    """A fixed pseudo-random value in -1..1 for a pixel."""
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 32767.5 - 1.0


def clamp(v):
    return max(0, min(255, int(round(v))))


def tint(c, k):
    return tuple(clamp(v + k) for v in c)


def grid(f):
    return [[f(x, y) for x in range(16)] for y in range(16)]


GRAVEL = (222, 220, 210)


def gravel(x, y, base=GRAVEL, seed=5):
    return tint(base, noise(x, y, seed) * 9 + noise(x // 2, y // 2, seed + 1) * 4)


def stripes(phase):
    """The raking's profile across its grooves, period 4: a lit crest, a shaded groove."""
    return (16, 3, -26, -3)[phase % 4]


def draw_textures():
    # Stone paving: courses of granite slabs of varied lengths, joints dark.
    joints_a, joints_b = (0, 7, 12), (3, 10)

    def paving(x, y):
        course = 0 if y < 8 else 1
        if y in (0, 8):
            return (88, 86, 82)
        if x in (joints_a if course == 0 else joints_b):
            return (92, 90, 86)
        slab = sum(1 for j in (joints_a if course == 0 else joints_b) if x > j)
        base = 150 + (slab * 13 + course * 21) % 26 - 13
        n = noise(x, y, 7) * 10
        return (clamp(base + n), clamp(base - 2 + n), clamp(base - 6 + n))
    png('ishidatami', grid(paving))
    png('shirasu', grid(gravel))

    def pebbles(x, y):
        # Round pebbles on a hexagonal-ish grid, varied greys, dark gaps.
        best, shade = 99.0, 0
        for cy in range(-2, 20, 4):
            off = 2 if (cy // 4) % 2 else 0
            for cx in range(-2 + off, 20, 4):
                d = math.hypot(x - cx, y - cy)
                if d < best:
                    best, shade = d, noise(cx, cy, 9)
        if best > 1.8:
            return (70, 70, 68)
        g = 128 + shade * 30 - best * 6
        return (clamp(g), clamp(g), clamp(g + 3))
    png('tamajari', grid(pebbles))
    moss = (64, 96, 42)
    png('koke', grid(lambda x, y: tint(moss, noise(x, y, 11) * 12 + noise(x // 3, y // 3, 12) * 8)))
    png('koke_side', grid(lambda x, y: tint(moss, noise(x, y, 11) * 12) if y < 3 + (noise(x, 0, 13) > 0.3)
                          else tint((110, 80, 52), noise(x, y, 14) * 10)))
    png('raked_gravel', grid(lambda x, y: tint(gravel(x, y), stripes(y))))
    for facing, (sx, sz) in QUADRANTS.items():
        for part in range(9):
            i, j = part % 3, part // 3

            def ring(x, y, i=i, j=j, sx=sx, sz=sz):
                # Pixel centre's offset from the rocks' centre, in pixels, in world orientation.
                wx = sx * (i * 16 + (x if sx > 0 else 15 - x) + 0.5)
                wz = sz * (j * 16 + (y if sz > 0 else 15 - y) + 0.5)
                r = math.hypot(wx, wz)
                if r < RING_RADIUS:
                    return tint(gravel(x, y), stripes(int(r) - 1))
                return tint(gravel(x, y), stripes(y))
            png(f'raked_gravel_ring_{facing}_{part}', grid(ring))

    def stone(x, y):
        # A flat stepping stone, its corners rounded off into the moss.
        if math.hypot(max(0, abs(x - 7.5) - 3.5), max(0, abs(y - 7.5) - 3.5)) > 3.6:
            return tint(moss, noise(x, y, 11) * 12)
        return tint((128, 124, 116), noise(x, y, 15) * 10 + noise(x // 2, y // 3, 16) * 6)
    png('tobiishi_stone', grid(stone))

    def rock(x, y):
        n = noise(x, y, 17) * 12 + noise(x // 3, y // 2, 18) * 10
        if noise(x // 2, y // 2, 19) > 0.75:
            return tint((120, 128, 92), n)  # lichen
        if (x * 3 + y * 7) % 23 == 0:
            return (48, 46, 44)  # cracks
        return tint((92, 88, 82), n)
    png('niwaishi', grid(rock))
    # Kenninji fence: split bamboo standing close, two bamboo rails tied across.
    bamboo = (176, 142, 82)

    def fence(x, y):
        if y in (3, 4, 11, 12):
            k = 10 if y in (3, 11) else -8
            return (40, 34, 28) if x % 8 == 3 else tint((120, 104, 60), k)
        stave = x % 3
        return tint(bamboo, (-26, 8, -2)[stave] + noise(x, y // 4, 20) * 5)
    png('bamboo_fence', grid(fence))
    png('bamboo_fence_edge', grid(lambda x, y: tint(bamboo, -10 + noise(x, y, 21) * 6)))
    # Tatami: woven rush along the mat (v), dark cloth borders (畳縁) along the long sides, the seam at v = 0.
    rush = (178, 176, 104)

    def tatami(x, y):
        if x <= 1 or x >= 14:
            return tint((38, 48, 36), (6 if x in (1, 14) else 0) + noise(x, y, 22) * 3)
        if y == 0:
            return tint(rush, -34)
        return tint(rush, (4 if y % 2 else -6) + noise(x, y, 23) * 6 + (x % 2) * -3)
    png('tatami', grid(tatami))
    png('tatami_side', grid(lambda x, y: tint(rush, -20 + noise(x, y, 24) * 6)))
    # Fusuma: paper in a black lacquered frame, the round pull (引手) on the lower block.
    paper = (226, 214, 184)
    leaf = (214, 172, 72)

    def fusuma(base, upper, pattern):
        def f(x, y):
            if x == 0 or x == 15 or (upper and y == 0) or (not upper and y >= 14):
                return (34, 26, 22)
            if not upper and 3 <= y <= 5 and 11 <= x <= 13 and (x, y) != (12, 4):
                return (24, 20, 18)
            return tint(base, pattern(x, y))
        return f
    plain = lambda x, y: noise(x, y, 25) * 4 + (6 if (x * 5 + y * 3) % 11 == 0 else 0)  # faint mica pattern
    png('fusuma_lower', grid(fusuma(paper, False, plain)))
    png('fusuma_upper', grid(fusuma(paper, True, plain)))

    def gold(x, y, upper):
        k = (6 if ((x // 4) + (y // 4)) % 2 else -4) + noise(x, y, 26) * 4
        if upper and (abs(y - 6 - (x - 8) * 0.4) < 1.4 and 3 <= x <= 13):
            return -150  # a pine bough painted across the leaf
        if upper and 4 <= y <= 9 and math.hypot(x - 5, y - 6) < 2.6:
            return -140
        return k
    png('fusuma_gold_lower', grid(fusuma(leaf, False, lambda x, y: gold(x, y, False))))
    png('fusuma_gold_upper', grid(fusuma(leaf, True, lambda x, y: gold(x, y, True))))
    png('fusuma_edge', grid(lambda x, y: (34, 26, 22)))
    # Shoji: white paper on a light wooden lattice (横繁: cells wider than tall).
    wood = (168, 128, 80)
    png('shoji', grid(lambda x, y: tint(wood, noise(x, y, 27) * 4) if x % 8 == 0 or y % 4 == 0
                      else tint((242, 238, 226), noise(x, y, 28) * 3)))
    png('shoji_edge', grid(lambda x, y: tint(wood, noise(x, y, 29) * 4)))

    # Ranma: dark wood carved with clouds, the relief catching the light.
    def ranma(x, y):
        if y == 0 or y == 15:
            return (60, 40, 26)
        wave = math.sin((x + y * 0.3) * math.pi / 4) * 3 + 8
        if abs(y - wave) < 1.2 or abs(y - wave + 5) < 0.9 or abs(y - wave - 4) < 0.9:
            return tint((150, 108, 60), noise(x, y, 30) * 6)
        return tint((70, 48, 30), noise(x, y, 31) * 4)
    png('ranma', grid(ranma))
    png('ranma_edge', grid(lambda x, y: (60, 40, 26)))

    # Coffered ceiling: a lacquered frame (each block one coffer), a red line inside, a gilded flower in the middle.
    def coffer(x, y):
        if x in (0, 15) or y in (0, 15):
            return (44, 30, 22)
        if x in (1, 14) or y in (1, 14):
            return (150, 46, 34)
        d = math.hypot(x - 7.5, y - 7.5)
        petal = abs(math.cos(2 * math.atan2(y - 7.5, x - 7.5)))
        if d < 1.2:
            return (120, 40, 30)
        if d < 2.2 + 2.2 * petal:
            return tint((212, 170, 70), noise(x, y, 32) * 6)
        return tint((40, 92, 84), noise(x, y, 33) * 6)  # green ground (緑青)
    png('coffered_ceiling', grid(coffer))
    png('coffered_ceiling_side', grid(lambda x, y: (44, 30, 22)))
    # Cushion, incense burner, paper lantern, rope, offering box.
    purple = (104, 46, 102)
    png('zabuton', grid(lambda x, y: (220, 190, 90) if 7 <= x <= 8 and 7 <= y <= 8 else
                        tint(purple, noise(x, y, 34) * 5 + (-10 if x in (1, 14) or y in (1, 14) else 0))))
    bronze = (96, 80, 50)
    png('koro', grid(lambda x, y: tint(bronze, noise(x, y, 35) * 10 + (18 if y in (2, 3) else 0))))
    png('koro_top', grid(lambda x, y: tint(bronze, 10) if x in (0, 15) or y in (0, 15)
                         else tint((150, 146, 140), noise(x, y, 36) * 14)))
    red = (186, 38, 32)

    def chochin(x, y):
        if y % 3 == 0:
            return tint(red, -40)  # the ribs
        if 6 <= x <= 9 and 3 <= y <= 13 and (x == 7 or y in (4, 8, 12) or (x + y) % 5 == 0):
            return (24, 20, 20)  # the name, brushed in black
        return tint(red, noise(x, y, 37) * 6)
    png('chochin', grid(chochin))
    png('chochin_cap', grid(lambda x, y: tint((30, 28, 26), noise(x, y, 38) * 4)))
    straw = (196, 176, 108)
    png('shimenawa', grid(lambda x, y: tint(straw, (14 if (x + y) % 6 < 3 else -14) + noise(x, y, 39) * 5)))
    png('shide', grid(lambda x, y: tint((246, 246, 240), noise(x, y, 40) * 2)))
    keyaki = (120, 72, 38)
    png('saisen_bako_top', grid(lambda x, y: (30, 22, 16) if x % 3 == 0 and 1 <= y <= 14 else
                                tint(keyaki, noise(x, y, 41) * 6)))
    png('saisen_bako', grid(lambda x, y: (196, 160, 70) if (x in (0, 15) and y in (0, 1, 14, 15)) or
                            (y in (0, 15) and x in (0, 1, 14, 15)) else
                            (230, 226, 214) if 5 <= x <= 10 and 4 <= y <= 11 and (x in (6, 9) or y in (5, 8, 10))
                            else tint(keyaki, noise(x, y, 42) * 6 + (y % 4 == 0) * -10)))


# ---------------------------------------------------------------- models

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def model(name, data):
    write(os.path.join(MODELS, name + '.json'), data)


def state(name, data):
    write(os.path.join(BLOCKSTATES, name + '.json'), data)


def t(name):
    return 'citybuilder:block/' + name


def m(name):
    return 'citybuilder:block/' + name


def box(frm, to, faces, rotation=None):
    e = {'from': frm, 'to': to, 'faces': faces}
    if rotation:
        e['rotation'] = rotation
    return e


def all_faces(tex, uv=None, cull=None):
    out = {}
    for f in ('north', 'south', 'west', 'east', 'up', 'down'):
        out[f] = {'texture': tex}
        if uv:
            out[f]['uv'] = uv
    return out


def facing_variants(model_name, key='facing', base=None):
    rot = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
    v = {}
    for d, r in rot.items():
        k = f'{key}={d}' if base is None else f'{base},{key}={d}'
        v[k] = {'model': m(model_name)} | ({'y': r} if r else {})
    return v


def pane(name, pane_tex, edge_tex, condition=None):
    """Models and multipart entries of a pane (as the game's glass panes): a post, sides, no-sides."""
    for part in ('post', 'side', 'side_alt', 'noside', 'noside_alt'):
        model(f'{name}_{part}', {'parent': f'minecraft:block/template_glass_pane_{part}',
                                 'textures': {'pane': t(pane_tex), 'edge': t(edge_tex)}})
    when = condition or {}
    parts = [({}, 'post', 0), ({'north': 'true'}, 'side', 0), ({'east': 'true'}, 'side', 90),
             ({'south': 'true'}, 'side_alt', 0), ({'west': 'true'}, 'side_alt', 90),
             ({'north': 'false'}, 'noside', 0), ({'east': 'false'}, 'noside_alt', 0),
             ({'south': 'false'}, 'noside_alt', 90), ({'west': 'false'}, 'noside', 270)]
    out = []
    for cond, part, y in parts:
        entry = {'apply': {'model': m(f'{name}_{part}')} | ({'y': y} if y else {})}
        c = when | cond
        if c:
            entry['when'] = c
        out.append(entry)
    return out


def write_models():
    for name in ('ishidatami', 'shirasu', 'tamajari', 'niwaishi'):
        model(name, {'parent': 'minecraft:block/cube_all', 'textures': {'all': t(name)}})
        state(name, {'variants': {'': {'model': m(name)}}})
    model('koke', {'parent': 'minecraft:block/cube_bottom_top',
                   'textures': {'top': t('koke'), 'side': t('koke_side'), 'bottom': 'minecraft:block/dirt'}})
    state('koke', {'variants': {'': {'model': m('koke')}}})
    model('raked_gravel', {'parent': 'minecraft:block/cube_bottom_top',
                           'textures': {'top': t('raked_gravel'), 'side': t('shirasu'), 'bottom': t('shirasu')}})
    state('raked_gravel', {'variants': {'axis=x': {'model': m('raked_gravel')},
                                        'axis=y': {'model': m('raked_gravel')},
                                        'axis=z': {'model': m('raked_gravel'), 'y': 90}}})
    ring = {}
    for facing in QUADRANTS:
        for part in range(9):
            n = f'raked_gravel_ring_{facing}_{part}'
            model('ring/' + n, {'parent': 'minecraft:block/cube_bottom_top',
                                'textures': {'top': t(n), 'side': t('shirasu'), 'bottom': t('shirasu')}})
            ring[f'facing={facing},part={part}'] = {'model': m('ring/' + n)}
    state('raked_gravel_ring', {'variants': ring})
    # Stepping stone: moss, a flat stone standing 2 pixels proud of it.
    model('tobiishi', {'parent': 'minecraft:block/block', 'textures': {
        'particle': t('koke'), 'top': t('koke'), 'side': t('koke_side'), 'bottom': 'minecraft:block/dirt',
        'stone': t('tobiishi_stone'), 'rock': t('niwaishi')},
        'elements': [box([0, 0, 0], [16, 16, 16], {
            'down': {'texture': '#bottom', 'cullface': 'down'}, 'up': {'texture': '#top'},
            'north': {'texture': '#side', 'cullface': 'north'}, 'south': {'texture': '#side', 'cullface': 'south'},
            'west': {'texture': '#side', 'cullface': 'west'}, 'east': {'texture': '#side', 'cullface': 'east'}}),
            box([2, 16, 2], [14, 18, 14], {'up': {'texture': '#stone', 'uv': [2, 2, 14, 14]},
                                           **{f: {'texture': '#rock', 'uv': [2, 0, 14, 2]}
                                              for f in ('north', 'south', 'west', 'east')}})]})
    state('tobiishi', {'variants': {'': {'model': m('tobiishi')}}})
    state('bamboo_fence', {'multipart': pane('bamboo_fence', 'bamboo_fence', 'bamboo_fence_edge')})
    state('shoji', {'multipart': pane('shoji', 'shoji', 'shoji_edge')})
    state('ranma', {'multipart': pane('ranma', 'ranma', 'ranma_edge')})
    for name in ('fusuma', 'fusuma_gold'):
        parts = []
        for half in ('lower', 'upper'):
            parts += pane(f'{name}_{half}', f'{name}_{half}', 'fusuma_edge', {'half': half})
        state(name, {'multipart': parts})
    model('tatami', {'parent': 'minecraft:block/cube_bottom_top',
                     'textures': {'top': t('tatami'), 'side': t('tatami_side'), 'bottom': t('tatami_side')}})
    state('tatami', {'variants': facing_variants('tatami')})
    model('coffered_ceiling', {'parent': 'minecraft:block/cube_bottom_top',
                               'textures': {'bottom': t('coffered_ceiling'), 'top': t('coffered_ceiling_side'),
                                            'side': t('coffered_ceiling_side')}})
    state('coffered_ceiling', {'variants': {'': {'model': m('coffered_ceiling')}}})
    model('zabuton', {'parent': 'minecraft:block/block', 'textures': {'particle': t('zabuton'), 'c': t('zabuton')},
                      'elements': [box([2, 0, 2], [14, 3, 14], all_faces('#c'))]})
    state('zabuton', {'variants': {'': {'model': m('zabuton')}}})
    legs = [box([x, 0, z], [x + 2, 2, z + 2], all_faces('#b')) for x, z in ((4, 4), (10, 4), (7, 10))]
    model('koro', {'parent': 'minecraft:block/block', 'textures': {'particle': t('koro'), 'b': t('koro'),
                                                                    'top': t('koro_top')},
                   'elements': legs + [box([3, 2, 3], [13, 8, 13], all_faces('#b') | {'up': {'texture': '#top'}}),
                                       box([2, 8, 2], [14, 9, 14], all_faces('#b') | {'up': {'texture': '#top'}})]})
    state('koro', {'variants': {'': {'model': m('koro')}}})
    model('chochin', {'parent': 'minecraft:block/block', 'textures': {'particle': t('chochin'), 's': t('chochin'),
                                                                       'c': t('chochin_cap')},
                      'elements': [box([3, 2, 3], [13, 14, 13], all_faces('#s') | {'up': {'texture': '#c'},
                                                                                   'down': {'texture': '#c'}}),
                                   box([5, 14, 5], [11, 16, 11], all_faces('#c')),
                                   box([5, 0, 5], [11, 2, 11], all_faces('#c'))]})
    state('chochin', {'variants': {'': {'model': m('chochin')}}})
    # The rope along x, high in the block; two paper streamers (紙垂) and a straw tassel hang from it.
    shide = [box([x, y0, 7.5], [x + 3, y1, 8.5], all_faces('#p')) for x, y0, y1 in ((3, 5, 11), (10, 4, 11))]
    model('shimenawa', {'parent': 'minecraft:block/block', 'textures': {'particle': t('shimenawa'),
                                                                         'r': t('shimenawa'), 'p': t('shide')},
                        'elements': [box([0, 11, 5.5], [16, 16, 10.5], all_faces('#r'))] + shide
                        + [box([7, 6, 7], [8.5, 11, 8.5], all_faces('#r'))]})
    state('shimenawa', {'variants': {'axis=x': {'model': m('shimenawa')},
                                     'axis=z': {'model': m('shimenawa'), 'y': 90}}})
    model('saisen_bako', {'parent': 'minecraft:block/block', 'textures': {
        'particle': t('saisen_bako'), 'f': t('saisen_bako'), 'top': t('saisen_bako_top')},
        'elements': [box([1, 0, 3], [15, 10, 13], all_faces('#f') | {'up': {'texture': '#top'}})]})
    state('saisen_bako', {'variants': facing_variants('saisen_bako')})


def write_items_loot_lang():
    flat = {'bamboo_fence': 'bamboo_fence', 'shoji': 'shoji', 'ranma': 'ranma', 'fusuma': 'fusuma_lower',
            'fusuma_gold': 'fusuma_gold_lower'}
    for name in NAMES:
        if name in flat:
            item = {'parent': 'minecraft:item/generated', 'textures': {'layer0': t(flat[name])}}
        elif name == 'raked_gravel_ring':
            item = {'parent': m('ring/raked_gravel_ring_south_0')}
        else:
            item = {'parent': m(name)}
        write(os.path.join(ITEMS, name + '.json'), item)
        write(os.path.join(LOOT, name + '.json'), {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'citybuilder:' + name}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    for lang, idx in (('en_us', 0), ('zh_cn', 1)):
        path = os.path.join(LANG, lang + '.json')
        with open(path, encoding='utf-8') as f:
            names = json.load(f)
        for name, pair in NAMES.items():
            names['block.citybuilder.' + name] = pair[idx]
        write(path, names)


if __name__ == '__main__':
    draw_textures()
    write_models()
    write_items_loot_lang()
