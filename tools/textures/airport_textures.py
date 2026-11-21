"""Draws the airport terminal blocks' textures for City Builder and writes their block states, block and item models
and names. Run from the repository root: python tools/textures/airport_textures.py

The textures are drawn by this script for this mod (no outside sources; the pictograms are drawn here in the manner of
airport signs, not copied from any standard's artwork), so they carry the mod's own licence. The stairs' block state
is derived at run time from the vanilla oak stairs' rotation table in the local 1.20.1 client jar (only the table of
rotations, which any stairs block needs; nothing of the jar is copied).

The blocks (see common's block.AirportBlocks; the terminal builder in core places them):
  curtain_wall_gray/_blue/_clear  glass with a thin frame on two edges, so a wall of them shows a one-metre grid
  metal_roof, metal_roof_white    standing-seam metal, the seams along the block's axis; with _slab forms
  ceiling_light                   a flush light panel under a ceiling, light level 15
  terminal_tile                   terrazzo floor tiles with joints a metre apart
  carpet_tile_blue/_orange/_green the gate lounges' tile carpet, laid quarter-turned
  airport_seat                    beam seating, the back on the side it faces (as stairs)
  wayfinding_sign                 a hanging sign, yellow pictogram on black, ICONS below
  flight_board                    a hanging flight information display
  bridge_cladding, bridge_bellows a boarding bridge's ribbed metal, and the black bellows round the aircraft's door
  baggage_belt                    a slab of rubber slats along its axis
  escalator_step                  stairs of grooved aluminium
"""
import json
import os
import struct
import zipfile
import zlib

ASSETS = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder')
TEXTURES = os.path.join(ASSETS, 'textures', 'block')
MODELS = os.path.join(ASSETS, 'models', 'block')
ITEMS = os.path.join(ASSETS, 'models', 'item')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
MC_JAR = os.path.expanduser('~/.gradle/caches/fabric-loom/1.20.1/minecraft-client.jar')


def png(path, pixels):
    """Writes a 16x16 RGBA PNG; pixels[y][x] = (r, g, b) or (r, g, b, a)."""
    raw = b''.join(b'\x00' + bytes(c for p in row for c in (p if len(p) == 4 else (*p, 255))) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)


def noise(x, y, seed):
    """A fixed pseudo-random value in -1..1 for a pixel."""
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 32767.5 - 1.0


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, d):
    return tuple(clamp(v + d) for v in c[:3]) + tuple(c[3:])


def texture(name, f):
    png(os.path.join(TEXTURES, name + '.png'), [[f(x, y) for x in range(16)] for y in range(16)])


# ---------------------------------------------------------------- textures

FRAME = (196, 200, 204)
GLASS = {'gray': (96, 112, 124, 140), 'blue': (92, 146, 190, 130), 'clear': (196, 222, 232, 70)}


def curtain(tint):
    def f(x, y):
        if x == 0 or y == 0:
            return FRAME if (x + y) % 7 else shade(FRAME, -14)
        # A faint reflection running diagonally across the pane
        band = 10 <= (x + 16 - y) % 16 <= 12
        r, g, b, a = tint
        return (clamp(r + (26 if band else 0)), clamp(g + (26 if band else 0)), clamp(b + (24 if band else 0)),
                clamp(a + (30 if band else 0)))
    return f


def seams(base):
    """Standing-seam metal, the seams along the texture's height every half block."""
    def f(x, y):
        n = noise(x, y, 11) * 3
        if x % 8 == 0:
            return shade(base, 30 + n)
        if x % 8 == 1:
            return shade(base, -28 + n)
        return shade(base, n + (4 if x % 8 in (2, 3) else 0))
    return f


def metal_end(base):
    def f(x, y):
        return shade(base, -18 + (10 if y < 2 else 0) + noise(x, y, 12) * 3)
    return f


def light_panel(x, y):
    if x in (0, 15) or y in (0, 15):
        return (150, 152, 156)
    if x in (1, 14) or y in (1, 14):
        return (205, 207, 210)
    return (252, 251, 240) if 3 <= x <= 12 and 3 <= y <= 12 else (240, 240, 232)


TILE = (216, 210, 199)


def terrazzo(x, y):
    if x == 15 or y == 15:
        return (172, 166, 157)
    n = noise(x, y, 21)
    if n > 0.82:
        return (140, 132, 120)
    if n < -0.86:
        return (250, 248, 242)
    if -0.2 < noise(x, y, 22) < -0.12:
        return (190, 172, 150)
    return shade(TILE, noise(x, y, 23) * 3)


CARPETS = {'blue': (42, 54, 92), 'orange': (150, 86, 56), 'green': (56, 96, 72)}


def carpet(base):
    def f(x, y):
        quarter = (x // 8 + y // 8) % 2
        stripe = (x if quarter else y) % 2
        return shade(base, (6 if quarter else -4) + stripe * 4 + noise(x, y, 31) * 4)
    return f


def fabric(x, y):
    return shade((46, 50, 58), noise(x, y, 41) * 5 + (3 if (x + y) % 2 else 0))


def chrome(x, y):
    return shade((192, 196, 200), (16 if y % 4 == 0 else 0) + noise(x, y, 42) * 3)


def ribbed(x, y):
    """A boarding bridge's ribbed metal: ribs along the height every quarter block."""
    k = x % 4
    return shade((178, 182, 186), (22 if k == 0 else -16 if k == 1 else 0) + noise(x, y, 51) * 3)


def bellows(x, y):
    k = y % 3
    return shade((34, 34, 36), (22 if k == 0 else -10 if k == 2 else 0) + noise(x, y, 52) * 3)


def slats(x, y):
    """Rubber slats across the belt's way (the way along the texture's height), each overlapping the next."""
    k = y % 4
    if k == 0:
        return (92, 92, 96)
    return shade((52, 52, 56), -k * 3 + noise(x, y, 61) * 3)


def belt_side(x, y):
    return (196, 198, 202) if y < 3 else shade((120, 122, 126), noise(x, y, 62) * 3)


def grooves(x, y):
    if y < 2:
        return (226, 186, 40)  # the yellow edge at the step's nose
    return shade((176, 180, 184), (-26 if x % 2 else 6) + noise(x, y, 71) * 2)


def skirt(x, y):
    return shade((64, 66, 70), noise(x, y, 72) * 3)


SIGN_BG = (22, 22, 24)
SIGN_FG = (250, 196, 24)
SIGN_EDGE = (70, 72, 76)

# Pictograms, 16 x 10, drawn into rows 3-12 of the sign's face. Order = the block's `icon` property (and core's
# building.airport.Sign).
ICONS = {
    'departures': [
        '..........###...',
        '.........####...',
        '........####....',
        '.##....####.....',
        '..##..#####.....',
        '...#########....',
        '....########....',
        '...####..##.....',
        '..####..........',
        '.####...........'],
    'arrivals': [
        '.####...........',
        '..####..........',
        '...####..##.....',
        '....########....',
        '...#########....',
        '..##..#####.....',
        '.##....####.....',
        '........####....',
        '................',
        '..############..'],
    'baggage': [
        '......####......',
        '......#..#......',
        '...##########...',
        '...#.#....#.#...',
        '...#.#....#.#...',
        '...#.#....#.#...',
        '...#.#....#.#...',
        '...##########...',
        '....#......#....',
        '................'],
    'toilets': [
        '...##......##...',
        '...##......##...',
        '................',
        '..####....####..',
        '.#.##.#..######.',
        '...##....######.',
        '...##...########',
        '..#..#.....##...',
        '..#..#.....##...',
        '..#..#....#..#..'],
    'left': [
        '......#.........',
        '.....##.........',
        '....###.........',
        '...##########...',
        '..###########...',
        '..###########...',
        '...##########...',
        '....###.........',
        '.....##.........',
        '......#.........'],
    'right': [
        '.........#......',
        '.........##.....',
        '.........###....',
        '...##########...',
        '...###########..',
        '...###########..',
        '...##########...',
        '.........###....',
        '.........##.....',
        '.........#......'],
    'up': [
        '.......##.......',
        '......####......',
        '.....######.....',
        '....########....',
        '...##########...',
        '......####......',
        '......####......',
        '......####......',
        '......####......',
        '......####......'],
    'info': [
        '.......##.......',
        '.......##.......',
        '................',
        '......###.......',
        '.......##.......',
        '.......##.......',
        '.......##.......',
        '.......##.......',
        '......####......',
        '................'],
    'restaurant': [
        '...#.#.#....#...',
        '...#.#.#...##...',
        '...#.#.#...##...',
        '...#####...##...',
        '....###....##...',
        '.....#.....##...',
        '.....#......#...',
        '.....#......#...',
        '.....#......#...',
        '.....#......#...'],
    'shopping': [
        '......####......',
        '.....#....#.....',
        '.....#....#.....',
        '...##########...',
        '...##########...',
        '...##########...',
        '...##########...',
        '...##########...',
        '...##########...',
        '...##########...'],
    'elevator': [
        '..############..',
        '..#....##....#..',
        '..#...####...#..',
        '..#..######..#..',
        '..#..........#..',
        '..#..######..#..',
        '..#...####...#..',
        '..#....##....#..',
        '..############..',
        '................'],
    'escalator': [
        '............###.',
        '...........##...',
        '..........##....',
        '.........##.....',
        '........##......',
        '.......##.......',
        '......##........',
        '.....##.........',
        '.####...........',
        '................'],
    'gates': [
        '.......##.......',
        '.......##.......',
        '......####......',
        '..############..',
        '.##############.',
        '.......##.......',
        '.......##.......',
        '.......##.......',
        '.....######.....',
        '................'],
    'bus': [
        '...##########...',
        '...#..#..#..#...',
        '...#..#..#..#...',
        '...##########...',
        '...##########...',
        '...##########...',
        '...#.######.#...',
        '...##########...',
        '....##....##....',
        '................'],
    'train': [
        '....########....',
        '....#......#....',
        '....#......#....',
        '....########....',
        '....########....',
        '....#.####.#....',
        '....########....',
        '.....#....#.....',
        '....#......#....',
        '...#........#...'],
    'parking': [
        '....######......',
        '....##...##.....',
        '....##...##.....',
        '....##...##.....',
        '....######......',
        '....##..........',
        '....##..........',
        '....##..........',
        '....##..........',
        '................'],
}


def sign(rows):
    for r in rows:
        assert len(r) == 16, r
    assert len(rows) == 10

    def f(x, y):
        if y in (2, 13) or (2 <= y <= 13 and x in (0, 15)):
            return SIGN_EDGE
        if 3 <= y <= 12 and rows[y - 3][x] == '#':
            return SIGN_FG
        return SIGN_BG
    return f


def board(x, y):
    """A flight information display: rows of times (yellow), places (white) and remarks (green)."""
    if x in (0, 15) or y in (0, 15):
        return (60, 62, 66)
    if y in (1, 14):
        return (8, 8, 10)
    if y % 3 == 0 and 2 <= y <= 13:
        return (8, 8, 10)
    row = y // 3
    if 1 <= x <= 3:
        return (250, 200, 40) if (x + row) % 4 else (8, 8, 10)
    if 5 <= x <= 11:
        return (232, 232, 232) if noise(x, row, 81) > -0.4 else (8, 8, 10)
    if 13 <= x <= 14:
        return (60, 210, 90) if row % 2 else (8, 8, 10)
    return (8, 8, 10)


def panel_side(x, y):
    return shade((70, 72, 76), noise(x, y, 82) * 3)


# ---------------------------------------------------------------- models and states

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def model(name, data):
    write(os.path.join(MODELS, name + '.json'), data)


def state(name, data):
    write(os.path.join(BLOCKSTATES, name + '.json'), data)


def item(name, parent=None):
    write(os.path.join(ITEMS, name + '.json'), {'parent': parent or f'citybuilder:block/{name}'})


def tex(name):
    return f'citybuilder:block/{name}'


HORIZONTAL = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


def facing_state(name, extra=None):
    variants = {}
    for facing, y in HORIZONTAL.items():
        if extra:
            for key, model_name in extra.items():
                variants[f'facing={facing},{key}'] = {'model': model_name, 'y': y}
        else:
            variants[f'facing={facing}'] = {'model': tex(name), 'y': y}
    state(name, {'variants': variants})


def face(texture, uv=(0, 0, 16, 16), cull=None):
    out = {'uv': list(uv), 'texture': texture}
    if cull:
        out['cullface'] = cull
    return out


def box(frm, to, textures, uvs=None):
    """An element with every face given: textures {face: '#key'}; uvs from the element's own extent unless given."""
    faces = {}
    x0, y0, z0 = frm
    x1, y1, z1 = to
    auto = {'down': (x0, 16 - z1, x1, 16 - z0), 'up': (x0, z0, x1, z1), 'north': (16 - x1, 16 - y1, 16 - x0, 16 - y0),
            'south': (x0, 16 - y1, x1, 16 - y0), 'west': (z0, 16 - y1, z1, 16 - y0),
            'east': (16 - z1, 16 - y1, 16 - z0, 16 - y0)}
    for f, t in textures.items():
        faces[f] = face(t, (uvs or {}).get(f, auto[f]))
    return {'from': list(frm), 'to': list(to), 'faces': faces}


ALL = ('down', 'up', 'north', 'south', 'west', 'east')


def main():
    names = {}
    # Curtain walls (translucent: Forge reads render_type here, Fabric is told in its client entry point)
    for tint in GLASS:
        n = f'curtain_wall_{tint}'
        texture(n, curtain(GLASS[tint]))
        model(n, {'parent': 'minecraft:block/cube_all', 'render_type': 'minecraft:translucent',
                  'textures': {'all': tex(n)}})
        state(n, {'variants': {'': {'model': tex(n)}}})
        item(n)
        names[n] = ({'gray': 'Curtain Wall (Grey)', 'blue': 'Curtain Wall (Blue)', 'clear': 'Curtain Wall (Clear)'}[tint],
                    {'gray': '幕墙玻璃（灰）', 'blue': '幕墙玻璃（蓝）', 'clear': '幕墙玻璃（透明）'}[tint])
    # Standing-seam metal roofs: a pillar along its axis, and a slab with the same seams
    for n, base, en, zh in (('metal_roof', (170, 174, 177), 'Metal Roof', '金属屋面'),
                            ('metal_roof_white', (222, 225, 225), 'White Metal Roof', '白色金属屋面')):
        texture(n, seams(base))
        texture(n + '_end', metal_end(base))
        model(n, {'parent': 'minecraft:block/cube_column', 'textures': {'end': tex(n + '_end'), 'side': tex(n)}})
        state(n, {'variants': {'axis=x': {'model': tex(n), 'x': 90, 'y': 90}, 'axis=y': {'model': tex(n)},
                               'axis=z': {'model': tex(n), 'x': 90}}})
        item(n)
        names[n] = (en, zh)
        slab = n + '_slab'
        st = {'bottom': 'minecraft:block/slab', 'top': 'minecraft:block/slab_top'}
        for kind, parent in st.items():
            model(f'{slab}_{kind}' if kind == 'top' else slab, {
                'parent': parent, 'textures': {'top': tex(n), 'bottom': tex(n), 'side': tex(n + '_end')}})
        variants = {}
        for axis, y in (('x', 90), ('z', 0)):
            variants[f'axis={axis},type=bottom'] = {'model': tex(slab), 'y': y}
            variants[f'axis={axis},type=top'] = {'model': tex(slab + '_top'), 'y': y}
            variants[f'axis={axis},type=double'] = {'model': tex(n), 'x': 90, 'y': y}
        state(slab, {'variants': variants})
        item(slab)
        names[slab] = (en + ' Slab', zh + '半砖')
    # The ceiling light: a panel one sixteenth thick against the ceiling
    texture('ceiling_light', light_panel)
    model('ceiling_light', {'parent': 'minecraft:block/block', 'textures': {'particle': tex('ceiling_light'),
                                                                            'face': tex('ceiling_light'),
                                                                            'edge': tex('ceiling_light')},
                            'elements': [box((0, 15, 0), (16, 16, 16), {f: '#face' if f in ('down', 'up') else '#edge'
                                                                        for f in ALL})]})
    state('ceiling_light', {'variants': {'': {'model': tex('ceiling_light')}}})
    item('ceiling_light')
    names['ceiling_light'] = ('Ceiling Light', '吸顶灯')
    # Floors
    texture('terminal_tile', terrazzo)
    model('terminal_tile', {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex('terminal_tile')}})
    state('terminal_tile', {'variants': {'': {'model': tex('terminal_tile')}}})
    item('terminal_tile')
    names['terminal_tile'] = ('Terrazzo Tile', '水磨石地砖')
    for c, base in CARPETS.items():
        n = f'carpet_tile_{c}'
        texture(n, carpet(base))
        model(n, {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex(n)}})
        state(n, {'variants': {'': {'model': tex(n)}}})
        item(n)
        names[n] = ({'blue': 'Tile Carpet (Blue)', 'orange': 'Tile Carpet (Orange)', 'green': 'Tile Carpet (Green)'}[c],
                    {'blue': '方块地毯（蓝）', 'orange': '方块地毯（橙）', 'green': '方块地毯（绿）'}[c])
    # Beam seating: the back on the north (the side it faces, as stairs), the seat towards the south
    texture('seat_fabric', fabric)
    texture('seat_frame', chrome)
    elements = [
        box((7, 0, 6), (9, 3, 8), {f: '#frame' for f in ALL}),
        box((0, 3, 6), (16, 4, 8), {f: '#frame' for f in ALL}),
        box((1, 4, 2), (15, 7, 14), {f: '#fabric' for f in ALL}),
        box((1, 7, 1), (15, 15, 3), {f: '#fabric' for f in ALL}),
        box((1, 4, 2), (2, 7, 3), {f: '#frame' for f in ALL}),
    ]
    model('airport_seat', {'parent': 'minecraft:block/block',
                           'textures': {'particle': tex('seat_fabric'), 'fabric': tex('seat_fabric'),
                                        'frame': tex('seat_frame')}, 'elements': elements})
    facing_state('airport_seat')
    item('airport_seat')
    names['airport_seat'] = ('Airport Seating', '机场座椅')
    # Hanging signs: a panel across the block (faces north and south) on two rods
    variants = {}
    for i, (icon, rows) in enumerate(ICONS.items()):
        n = f'sign/{icon}'
        texture(n, sign(rows))
        model(n, {'parent': 'minecraft:block/block',
                  'textures': {'particle': tex(n), 'face': tex(n), 'edge': tex('sign/edge')},
                  'elements': [box((0, 2, 7), (16, 14, 9), {'north': '#face', 'south': '#face', 'west': '#edge',
                                                             'east': '#edge', 'up': '#edge', 'down': '#edge'},
                                   {'north': (0, 2, 16, 14), 'south': (0, 2, 16, 14)}),
                               box((3, 14, 7.5), (4, 16, 8.5), {f: '#edge' for f in ALL}),
                               box((12, 14, 7.5), (13, 16, 8.5), {f: '#edge' for f in ALL})]})
        for facing, y in HORIZONTAL.items():
            variants[f'facing={facing},icon={i}'] = {'model': tex(n), 'y': y}
    texture('sign/edge', lambda x, y: SIGN_EDGE)
    state('wayfinding_sign', {'variants': variants})
    item('wayfinding_sign', tex('sign/departures'))
    names['wayfinding_sign'] = ('Airport Sign', '机场指示牌')
    # The flight board: a screen across the block, readable both ways
    texture('flight_board', board)
    texture('panel_side', panel_side)
    model('flight_board', {'parent': 'minecraft:block/block',
                           'textures': {'particle': tex('flight_board'), 'face': tex('flight_board'),
                                        'edge': tex('panel_side')},
                           'elements': [box((0, 0, 6), (16, 16, 10), {'north': '#face', 'south': '#face',
                                                                       'west': '#edge', 'east': '#edge',
                                                                       'up': '#edge', 'down': '#edge'},
                                            {'north': (0, 0, 16, 16), 'south': (0, 0, 16, 16)})]})
    facing_state('flight_board')
    item('flight_board')
    names['flight_board'] = ('Flight Information Display', '航班信息屏')
    # Boarding bridges
    for n, f, en, zh in (('bridge_cladding', ribbed, 'Boarding Bridge Cladding', '登机桥外板'),
                         ('bridge_bellows', bellows, 'Boarding Bridge Bellows', '登机桥折棚')):
        texture(n, f)
        model(n, {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex(n)}})
        state(n, {'variants': {'': {'model': tex(n)}}})
        item(n)
        names[n] = (en, zh)
    # The baggage belt: a slab of slats running along its axis
    texture('baggage_belt', slats)
    texture('baggage_belt_side', belt_side)
    model('baggage_belt', {'parent': 'minecraft:block/slab', 'textures': {
        'top': tex('baggage_belt'), 'bottom': tex('baggage_belt_side'), 'side': tex('baggage_belt_side')}})
    model('baggage_belt_top', {'parent': 'minecraft:block/slab_top', 'textures': {
        'top': tex('baggage_belt'), 'bottom': tex('baggage_belt_side'), 'side': tex('baggage_belt_side')}})
    model('baggage_belt_double', {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
        'top': tex('baggage_belt'), 'bottom': tex('baggage_belt_side'), 'side': tex('baggage_belt_side')}})
    variants = {}
    for axis, y in (('x', 90), ('z', 0)):
        variants[f'axis={axis},type=bottom'] = {'model': tex('baggage_belt'), 'y': y}
        variants[f'axis={axis},type=top'] = {'model': tex('baggage_belt_top'), 'y': y}
        variants[f'axis={axis},type=double'] = {'model': tex('baggage_belt_double'), 'y': y}
    state('baggage_belt', {'variants': variants})
    item('baggage_belt')
    names['baggage_belt'] = ('Baggage Belt', '行李传送带')
    # Escalator steps: stairs, their block state from the vanilla stairs' rotation table
    texture('escalator_step', grooves)
    texture('escalator_skirt', skirt)
    t = {'top': tex('escalator_step'), 'bottom': tex('escalator_skirt'), 'side': tex('escalator_skirt')}
    model('escalator_step', {'parent': 'minecraft:block/stairs', 'textures': t})
    model('escalator_step_inner', {'parent': 'minecraft:block/inner_stairs', 'textures': t})
    model('escalator_step_outer', {'parent': 'minecraft:block/outer_stairs', 'textures': t})
    with zipfile.ZipFile(MC_JAR) as z:
        table = json.loads(z.read('assets/minecraft/blockstates/oak_stairs.json'))
    for v in table['variants'].values():
        v['model'] = v['model'].replace('minecraft:block/oak_stairs', tex('escalator_step'))
    state('escalator_step', table)
    item('escalator_step')
    names['escalator_step'] = ('Escalator Step', '扶梯踏板')
    # Names
    for lang, k in (('en_us', 0), ('zh_cn', 1)):
        path = os.path.join(ASSETS, 'lang', lang + '.json')
        with open(path, encoding='utf-8') as f:
            got = json.load(f)
        for n, v in names.items():
            got[f'block.citybuilder.{n}'] = v[k]
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(got, f, indent=2)
            f.write('\n')
    print(len(names), 'blocks:', ', '.join(names))


if __name__ == '__main__':
    main()
