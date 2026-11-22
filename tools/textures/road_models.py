"""Writes the road blocks' block states and models for City Builder. Run from the repository root:
python tools/textures/road_models.py (after road_textures.py, which draws the textures they use).

Asphalt and the marked asphalt come full height or lowered (low=true, 13/16 high: a carriageway beside pavements, see
PavingBlock); the marked asphalt has a line at one of ANGLES directions and OFFSETS positions (see MarkingBlock).
The pavement's sides are kerb stones. Asphalt, its markings and the pavement also come cut to eighths (height 1-8,
for a street rising on a ramp a little at a time): their tops at 2 * height sixteenths, less the kerb's 3 where
lowered (PavingBlock.top), on a parent per height (paved_<sixteenths>).
"""
import json
import math
import os

ASSETS = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
MODELS = os.path.join(ASSETS, 'models', 'block')
ANGLES = 16
OFFSETS = 8
LOW = 13
# A block drawn facing north, turned for the other facings
FACINGS = (('north', 0), ('east', 90), ('south', 180), ('west', 270))


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def top(height, low):
    """A paving block's top in sixteenths (PavingBlock.top)."""
    return max(1, 2 * height - (16 - LOW if low else 0))


TOPS = sorted({top(h, low) for h in range(1, 9) for low in (False, True)})
HEIGHTS = range(1, 9)


def paved_parent(t):
    """A block t sixteenths high: the top face textured #top, the sides #side cut to that height, #side under."""
    side = {'uv': [0, 16 - t, 16, 16], 'texture': '#side'}
    faces = {'down': {'uv': [0, 0, 16, 16], 'texture': '#side', 'cullface': 'down'},
             'up': {'uv': [0, 0, 16, 16], 'texture': '#top'}}
    for face in ('north', 'south', 'west', 'east'):
        faces[face] = dict(side, cullface=face)
    return {'parent': 'minecraft:block/block',
            'textures': {'particle': '#side'},
            'elements': [{'from': [0, 0, 0], 'to': [16, t, 16], 'faces': faces}]}


def cut(base, textures):
    """Writes a paving block's models cut to each height other than whole (base) and lowered (base_low); returns
    the model name for a height and lowering."""
    folder, name = os.path.split(base)
    for t in TOPS:
        if t not in (16, LOW):
            write(os.path.join(MODELS, folder, f'{name}_t{t}.json'),
                  {'parent': f'citybuilder:block/paved_{t}', 'textures': textures})

    def model(height, low):
        t = top(height, low)
        return f'citybuilder:block/{base}' + ('' if t == 16 else '_low' if t == LOW else f'_t{t}')
    return model


def lowered_parent():
    """A block LOW sixteenths high: the top face textured #top, the sides #side cut to that height."""
    side = {'uv': [0, 16 - LOW, 16, 16], 'texture': '#side'}
    faces = {'down': {'uv': [0, 0, 16, 16], 'texture': '#side', 'cullface': 'down'},
             'up': {'uv': [0, 0, 16, 16], 'texture': '#top'}}
    for face in ('north', 'south', 'west', 'east'):
        faces[face] = dict(side, cullface=face)
    return {'parent': 'minecraft:block/block',
            'textures': {'particle': '#side'},
            'elements': [{'from': [0, 0, 0], 'to': [16, LOW, 16], 'faces': faces}]}


KERB_ANGLES = 32


def kerb_edge(angle, offset):
    """A kerb crossing its block: lowered asphalt LOW high all over, and the pavement's paving raised on top of it
    where it lies to the normal's side of the line, in strips a pixel wide along x, cut to the pixel. The normal is
    (-sin, cos) of angle * 360 / KERB_ANGLES degrees; the line crosses at one of OFFSETS positions along it across the
    block's span that way, as the road lines do (see KerbEdgeBlock)."""
    theta = 2 * math.pi * angle / KERB_ANGLES
    nx, nz = -math.sin(theta), math.cos(theta)
    span = 0.5 * (abs(nx) + abs(nz))
    centre = -span + (offset + 0.5) * 2 * span / OFFSETS
    base_faces = {'down': {'uv': [0, 0, 16, 16], 'texture': '#asphalt', 'cullface': 'down'},
                  'up': {'uv': [0, 0, 16, 16], 'texture': '#asphalt'}}
    for face in ('north', 'south', 'west', 'east'):
        base_faces[face] = {'uv': [0, 16 - LOW, 16, 16], 'texture': '#asphalt', 'cullface': face}
    elements = [{'from': [0, 0, 0], 'to': [16, LOW, 16], 'faces': base_faces}]
    # The raised strips: for each pixel column along x, where along z it lies on the pavement's side
    strips = []
    for i in range(16):
        r = centre - (i + 0.5 - 8) / 16 * nx
        if abs(nz) < 1e-9:
            z0, z1 = (0, 16) if r < 0 else (0, 0)
        elif nz > 0:
            z0, z1 = max(0, min(16, round(8 + 16 * r / nz))), 16
        else:
            z0, z1 = 0, max(0, min(16, round(8 + 16 * r / nz)))
        strips.append((z0, z1))
    i = 0
    while i < 16:
        z0, z1 = strips[i]
        j = i
        while j + 1 < 16 and strips[j + 1] == (z0, z1):
            j += 1
        if z1 > z0:
            x0, x1 = i, j + 1
            faces = {'up': {'uv': [x0, z0, x1, z1], 'texture': '#top'}}
            sides = {'north': (z0 == 0, [16 - x1, 0, 16 - x0, 16 - LOW]), 'south': (z1 == 16, [x0, 0, x1, 16 - LOW]),
                     'west': (x0 == 0, [z0, 0, z1, 16 - LOW]), 'east': (x1 == 16, [16 - z1, 0, 16 - z0, 16 - LOW])}
            for face, (outer, uv) in sides.items():
                faces[face] = dict({'uv': uv, 'texture': '#side'}, **({'cullface': face} if outer else {}))
            elements.append({'from': [x0, LOW, z0], 'to': [x1, 16, z1], 'faces': faces})
        i = j + 1
    return {'parent': 'minecraft:block/block',
            'textures': {'particle': 'citybuilder:block/sidewalk', 'top': 'citybuilder:block/sidewalk',
                         'side': 'citybuilder:block/sidewalk_side', 'asphalt': 'citybuilder:block/asphalt'},
            'elements': elements}


def main():
    write(os.path.join(MODELS, 'lowered.json'), lowered_parent())
    for t in TOPS:
        write(os.path.join(MODELS, f'paved_{t}.json'), paved_parent(t))
    # Kerbs crossing their blocks at an angle
    variants = {}
    for angle in range(KERB_ANGLES):
        for offset in range(OFFSETS):
            name = f'curb_edge_{angle}_{offset}'
            write(os.path.join(MODELS, 'curb_edge', f'{name}.json'), kerb_edge(angle, offset))
            variants[f'angle={angle},offset={offset}'] = {'model': f'citybuilder:block/curb_edge/{name}'}
    write(os.path.join(BLOCKSTATES, 'curb_edge.json'), {'variants': variants})
    write(os.path.join(ASSETS, 'models', 'item', 'curb_edge.json'), {'parent': 'citybuilder:block/curb_edge/curb_edge_4_4'})
    # Asphalt
    write(os.path.join(MODELS, 'asphalt.json'), {'parent': 'minecraft:block/cube_all',
                                                 'textures': {'all': 'citybuilder:block/asphalt'}})
    write(os.path.join(MODELS, 'asphalt_low.json'), {'parent': 'citybuilder:block/lowered',
                                                     'textures': {'top': 'citybuilder:block/asphalt',
                                                                  'side': 'citybuilder:block/asphalt'}})
    model = cut('asphalt', {'top': 'citybuilder:block/asphalt', 'side': 'citybuilder:block/asphalt'})
    write(os.path.join(BLOCKSTATES, 'asphalt.json'), {'variants': {
        f'height={h},low={low}': {'model': model(h, low == 'true')} for h in HEIGHTS for low in ('false', 'true')}})
    # Asphalt painted white all over (a zebra crossing's stripe)
    write(os.path.join(MODELS, 'asphalt_white.json'), {'parent': 'minecraft:block/cube_bottom_top',
                                                       'textures': {'top': 'citybuilder:block/asphalt_white',
                                                                    'side': 'citybuilder:block/asphalt',
                                                                    'bottom': 'citybuilder:block/asphalt'}})
    write(os.path.join(MODELS, 'asphalt_white_low.json'), {'parent': 'citybuilder:block/lowered',
                                                           'textures': {'top': 'citybuilder:block/asphalt_white',
                                                                        'side': 'citybuilder:block/asphalt'}})
    model = cut('asphalt_white', {'top': 'citybuilder:block/asphalt_white', 'side': 'citybuilder:block/asphalt'})
    write(os.path.join(BLOCKSTATES, 'asphalt_white.json'), {'variants': {
        f'height={h},low={low}': {'model': model(h, low == 'true')} for h in HEIGHTS for low in ('false', 'true')}})
    # The streets' details: painted or fitted asphalt, full height or lowered, some turned by facing
    for name, facing in (('asphalt_hatch', True), ('road_arrow_shaft', True), ('road_arrow_head', True),
                         ('manhole', False), ('drain_grate', True)):
        top = f'citybuilder:block/{name}'
        write(os.path.join(MODELS, f'{name}.json'), {'parent': 'minecraft:block/cube_bottom_top',
                                                     'textures': {'top': top, 'side': 'citybuilder:block/asphalt',
                                                                  'bottom': 'citybuilder:block/asphalt'}})
        write(os.path.join(MODELS, f'{name}_low.json'), {'parent': 'citybuilder:block/lowered',
                                                         'textures': {'top': top, 'side': 'citybuilder:block/asphalt'}})
        variants = {}
        model = cut(name, {'top': top, 'side': 'citybuilder:block/asphalt'})
        for low in ('false', 'true'):
            for h in HEIGHTS:
                m = model(h, low == 'true')
                if facing:
                    for direction, y in FACINGS:
                        variants[f'facing={direction},height={h},low={low}'] = dict({'model': m}, **({'y': y} if y else {}))
                else:
                    variants[f'height={h},low={low}'] = {'model': m}
        write(os.path.join(BLOCKSTATES, f'{name}.json'), {'variants': variants})
    # Paving: their own tops, kerb stones on the sides; tactile guide bars turned by facing
    for name, facing in (('tactile_line', True), ('tactile_dot', False), ('sidewalk_interlocking', False),
                         ('sidewalk_granite', False), ('curb', False)):
        write(os.path.join(MODELS, f'{name}.json'), {'parent': 'minecraft:block/cube_bottom_top',
                                                     'textures': {'top': f'citybuilder:block/{name}',
                                                                  'side': 'citybuilder:block/sidewalk_side',
                                                                  'bottom': 'citybuilder:block/sidewalk_side'}})
        if facing:
            variants = {f'facing={d}': dict({'model': f'citybuilder:block/{name}'}, **({'y': y} if y else {}))
                        for d, y in FACINGS}
        else:
            variants = {'': {'model': f'citybuilder:block/{name}'}}
        write(os.path.join(BLOCKSTATES, f'{name}.json'), {'variants': variants})
    # Marked asphalt
    for colour in ('white', 'yellow'):
        variants = {}
        for angle in range(ANGLES):
            for offset in range(OFFSETS):
                name = f'{colour}_{angle}_{offset}'
                top = f'citybuilder:block/line/{name}'
                write(os.path.join(MODELS, 'line', f'{name}.json'), {
                    'parent': 'minecraft:block/cube_bottom_top',
                    'textures': {'top': top, 'side': 'citybuilder:block/asphalt', 'bottom': 'citybuilder:block/asphalt'}})
                write(os.path.join(MODELS, 'line', f'{name}_low.json'), {
                    'parent': 'citybuilder:block/lowered',
                    'textures': {'top': top, 'side': 'citybuilder:block/asphalt'}})
                model = cut(f'line/{name}', {'top': top, 'side': 'citybuilder:block/asphalt'})
                for h in HEIGHTS:
                    for low in ('false', 'true'):
                        variants[f'angle={angle},height={h},low={low},offset={offset}'] = {'model': model(h, low == 'true')}
        write(os.path.join(BLOCKSTATES, f'asphalt_line_{colour}.json'), {'variants': variants})
    # Pavement: paving slabs on top, kerb stones on the sides
    write(os.path.join(MODELS, 'sidewalk.json'), {'parent': 'minecraft:block/cube_bottom_top',
                                                  'textures': {'top': 'citybuilder:block/sidewalk',
                                                               'side': 'citybuilder:block/sidewalk_side',
                                                               'bottom': 'citybuilder:block/sidewalk_side'}})
    model = cut('sidewalk', {'top': 'citybuilder:block/sidewalk', 'side': 'citybuilder:block/sidewalk_side'})
    write(os.path.join(BLOCKSTATES, 'sidewalk.json'), {'variants': {
        f'height={h}': {'model': model(h, False)} for h in HEIGHTS}})


if __name__ == '__main__':
    main()
