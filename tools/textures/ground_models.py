"""Writes the block states, block and item models, loot tables, names and tool tags of City Builder's graded ground:
layers in eighths and 45 degree slopes. Run from the repository root: python tools/textures/ground_models.py

No textures of its own: the grass, earth and snow use the vanilla textures (referenced, not copied), the ashlar this
mod's (tools/textures/wall_textures.py).

The blocks (see common's block.GroundBlocks; common's world.CityChunks lays them on graded land):
- grass_layer, dirt_layer, snow_layer: ground in eighths of a block (`layers` 1-8, as snow), the top of land graded to a
  fractional height: an embankment's back slope at 1 in 2 rises half a block a column. Snow has its own layer block:
  worldgen's snow pass resets a vanilla snow layer it finds on top to one.
- grass_slope, dirt_slope, snow_slope, stone_ashlar_slope: a 45 degree slope across a block, `shape` as a stair's:
  straight, rising towards `facing`, a plane at 45 degrees over fine steps (models only take boxes, turned at most
  45 degrees about one axis); outer and inner corners (high at the corner between `facing` and the next way clockwise,
  or along both those sides) in cells of four pixels. The face of a 1 in 1 graded bank, or of a masonry slope
  (間知ブロック積み) under a raised street; a bank running diagonally is a run of outer and inner corners.
The grass's top faces carry tint index 0, coloured by the biome in the client (Fabric's colour providers, Forge's
colour handler event).
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lantern_door_textures import write, loot, add_names, add_tag, MODELS, ITEMS, BLOCKSTATES  # noqa: E402

FACING_Y = {'north': 0, 'east': 90, 'south': 180, 'west': 270}

# name: (top, side, bottom, tinted top, grass edge on the side, en, zh)
GROUND = {
    'grass': ('minecraft:block/grass_block_top', 'minecraft:block/grass_block_side', 'minecraft:block/dirt', True, True,
              'Grass', '草'),
    'dirt': ('minecraft:block/dirt', 'minecraft:block/dirt', 'minecraft:block/dirt', False, False, 'Earth', '土'),
    'snow': ('minecraft:block/snow', 'minecraft:block/snow', 'minecraft:block/snow', False, False, 'Snow', '雪'),
    'stone_ashlar': ('citybuilder:block/wall/stone_ashlar', 'citybuilder:block/wall/stone_ashlar',
                     'citybuilder:block/wall/stone_ashlar_top', False, False, 'Ashlar', '間知石'),
}
LAYERS = ('grass', 'dirt', 'snow')
SLOPES = ('grass', 'dirt', 'snow', 'stone_ashlar')


def face(texture, cull=None, tint=False, uv=None):
    f = {'texture': texture}
    if uv:
        f['uv'] = uv
    if cull:
        f['cullface'] = cull
    if tint:
        f['tintindex'] = 0
    return f


def layer_model(kind, n):
    top, side, bottom, tinted, edge, _, _ = GROUND[kind]
    h = 2 * n
    # A grass layer's sides show the grass block side's top strip (the turf's edge), not its earth below
    uv = [0, 0, 16, h] if edge else [0, 16 - h, 16, 16]
    sides = {d: face('#side', d, uv=uv) for d in ('north', 'south', 'east', 'west')}
    return {
        'parent': 'minecraft:block/block',
        'textures': {'particle': side, 'top': top, 'side': side, 'bottom': bottom},
        'elements': [{'from': [0, 0, 0], 'to': [16, h, 16],
                      'faces': {**sides, 'up': face('#top', 'up' if n == 8 else None, tinted),
                                'down': face('#bottom', 'down')}}]}


def slope_model(kind):
    """Rising towards the north: a back wall, steps of two pixels under the slope, and the slope's plane over them."""
    tinted = GROUND[kind][3]
    elements = [{'from': [0, 0, 0], 'to': [16, 15, 1],
                 'faces': {'north': face('#side', 'north'), 'east': face('#side'), 'west': face('#side'),
                           'up': face('#side'), 'down': face('#bottom', 'down')}}]
    for k in range(7):
        z0, z1 = 1 + 2 * k, 3 + 2 * k
        h = 16 - z1
        elements.append({'from': [0, 0, z0], 'to': [16, h, z1],
                         'faces': {'east': face('#side'), 'west': face('#side'),
                                   'up': face('#side'), 'down': face('#bottom', 'down')}})
    # The plane: 16 root 2 long, through the block's middle, turned 45 degrees about x so its north end is at the top
    half = 8 * math.sqrt(2)
    elements.append({'from': [0, 8, round(8 - half, 4)], 'to': [16, 8, round(8 + half, 4)],
                     'rotation': {'origin': [8, 8, 8], 'axis': 'x', 'angle': 45},
                     'faces': {'up': face('#top', tint=tinted, uv=[0, 0, 16, 16]),
                               'down': face('#top', tint=tinted, uv=[0, 0, 16, 16])}})
    return {'parent': 'minecraft:block/block', 'textures': textures(kind), 'elements': elements}


def textures(kind):
    top, side, bottom, _, _, _, _ = GROUND[kind]
    # Under a grass slope its steps' sides are earth (the grass block's side would stripe them green)
    return {'particle': side, 'top': top if kind != 'stone_ashlar' else side,
            'side': bottom if kind == 'grass' else side, 'bottom': bottom}


def corner_model(kind, shape):
    """A corner, rising towards the north and east: the outer only at the north-east corner (the lower of the two
    slopes), the inner along both sides (the higher); in cells of four pixels, each as high as the slope over its
    middle."""
    tinted = GROUND[kind][3]
    elements = []
    for i in range(4):
        for j in range(4):
            x = 4 * i + 2
            z = 4 * j + 2
            north = 16 - z
            east = x
            h = max(1, round(min(north, east) if shape == 'outer' else max(north, east)))
            faces = {'up': face('#top', 'up' if h == 16 else None, tinted), 'down': face('#bottom', 'down')}
            for d, edge in (('north', j == 0), ('south', j == 3), ('west', i == 0), ('east', i == 3)):
                faces[d] = face('#side', d if edge else None)
            elements.append({'from': [4 * i, 0, 4 * j], 'to': [4 * i + 4, h, 4 * j + 4], 'faces': faces})
    return {'parent': 'minecraft:block/block', 'textures': textures(kind), 'elements': elements}


def main():
    names = {}
    shovel, pickaxe = [], []
    for kind in LAYERS:
        name = f'{kind}_layer'
        for n in range(1, 9):
            write(os.path.join(MODELS, 'ground', f'{name}_{n}.json'), layer_model(kind, n))
        write(os.path.join(BLOCKSTATES, f'{name}.json'), {'variants': {
            f'layers={n}': {'model': f'citybuilder:block/ground/{name}_{n}'} for n in range(1, 9)}})
        write(os.path.join(ITEMS, f'{name}.json'), {'parent': f'citybuilder:block/ground/{name}_2'})
        loot(name)
        en, zh = GROUND[kind][5], GROUND[kind][6]
        names[name] = (f'{en} Layer', f'{zh}层')
        shovel.append(f'citybuilder:{name}')
    for kind in SLOPES:
        name = f'{kind}_slope'
        write(os.path.join(MODELS, 'ground', f'{name}.json'), slope_model(kind))
        for shape in ('outer', 'inner'):
            write(os.path.join(MODELS, 'ground', f'{name}_{shape}.json'), corner_model(kind, shape))
        variants = {}
        for shape in ('straight', 'outer', 'inner'):
            model = f'citybuilder:block/ground/{name}' + ('' if shape == 'straight' else f'_{shape}')
            for f, y in FACING_Y.items():
                variants[f'facing={f},shape={shape}'] = {'model': model, 'y': y} if y else {'model': model}
        write(os.path.join(BLOCKSTATES, f'{name}.json'), {'variants': variants})
        write(os.path.join(ITEMS, f'{name}.json'), {'parent': f'citybuilder:block/ground/{name}'})
        loot(name)
        en, zh = GROUND[kind][5], GROUND[kind][6]
        names[name] = (f'{en} Slope', f'{zh}斜面')
        (pickaxe if kind == 'stone_ashlar' else shovel).append(f'citybuilder:{name}')
    add_names(names)
    add_tag('mineable/shovel', shovel)
    add_tag('mineable/pickaxe', pickaxe)


if __name__ == '__main__':
    main()
