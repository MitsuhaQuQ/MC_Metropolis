"""Writes the road blocks' block states and models for City Builder. Run from the repository root:
python tools/textures/road_models.py (after road_textures.py, which draws the textures they use).

Asphalt and the marked asphalt come full height or lowered (low=true, 13/16 high: a carriageway beside pavements, see
PavingBlock); the marked asphalt has a line at one of ANGLES directions and OFFSETS positions (see MarkingBlock).
The pavement's sides are kerb stones.
"""
import json
import os

ASSETS = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
MODELS = os.path.join(ASSETS, 'models', 'block')
ANGLES = 16
OFFSETS = 8
LOW = 13


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


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


def main():
    write(os.path.join(MODELS, 'lowered.json'), lowered_parent())
    # Asphalt
    write(os.path.join(MODELS, 'asphalt.json'), {'parent': 'minecraft:block/cube_all',
                                                 'textures': {'all': 'citybuilder:block/asphalt'}})
    write(os.path.join(MODELS, 'asphalt_low.json'), {'parent': 'citybuilder:block/lowered',
                                                     'textures': {'top': 'citybuilder:block/asphalt',
                                                                  'side': 'citybuilder:block/asphalt'}})
    write(os.path.join(BLOCKSTATES, 'asphalt.json'), {'variants': {
        'low=false': {'model': 'citybuilder:block/asphalt'},
        'low=true': {'model': 'citybuilder:block/asphalt_low'}}})
    # Asphalt painted white all over (a zebra crossing's stripe)
    write(os.path.join(MODELS, 'asphalt_white.json'), {'parent': 'minecraft:block/cube_bottom_top',
                                                       'textures': {'top': 'citybuilder:block/asphalt_white',
                                                                    'side': 'citybuilder:block/asphalt',
                                                                    'bottom': 'citybuilder:block/asphalt'}})
    write(os.path.join(MODELS, 'asphalt_white_low.json'), {'parent': 'citybuilder:block/lowered',
                                                           'textures': {'top': 'citybuilder:block/asphalt_white',
                                                                        'side': 'citybuilder:block/asphalt'}})
    write(os.path.join(BLOCKSTATES, 'asphalt_white.json'), {'variants': {
        'low=false': {'model': 'citybuilder:block/asphalt_white'},
        'low=true': {'model': 'citybuilder:block/asphalt_white_low'}}})
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
                variants[f'angle={angle},low=false,offset={offset}'] = {'model': f'citybuilder:block/line/{name}'}
                variants[f'angle={angle},low=true,offset={offset}'] = {'model': f'citybuilder:block/line/{name}_low'}
        write(os.path.join(BLOCKSTATES, f'asphalt_line_{colour}.json'), {'variants': variants})
    # Pavement: paving slabs on top, kerb stones on the sides
    write(os.path.join(MODELS, 'sidewalk.json'), {'parent': 'minecraft:block/cube_bottom_top',
                                                  'textures': {'top': 'citybuilder:block/sidewalk',
                                                               'side': 'citybuilder:block/sidewalk_side',
                                                               'bottom': 'citybuilder:block/sidewalk_side'}})


if __name__ == '__main__':
    main()
