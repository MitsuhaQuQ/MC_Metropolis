"""Writes the bridge detail blocks' block states, models, item models, loot tables and names for City Builder. Run
from the repository root: python tools/textures/bridge_models.py (after bridge_textures.py).

Edge pieces (parapet, railing, sound barriers, drain pipe) are modelled against the block's north side and turned by
`facing` (see EdgeBlock); the joint runs along x and is turned for axis=z (see JointBlock); the bearing is three
plates (see BearingBlock). Forge reads render_type from the models; Fabric sets the layers in CityBuilderFabricClient.
"""
import json
import os

RES = os.path.join('common', 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'citybuilder')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
MODELS = os.path.join(ASSETS, 'models', 'block')
ITEMS = os.path.join(ASSETS, 'models', 'item')
LOOT = os.path.join(RES, 'data', 'citybuilder', 'loot_tables', 'blocks')
LANG = os.path.join(ASSETS, 'lang')

# name: (English, Chinese)
NAMES = {
    'concrete_parapet': ('Concrete Bridge Parapet', '桥梁混凝土护墙'),
    'bridge_railing': ('Bridge Railing', '桥梁栏杆'),
    'sound_barrier': ('Sound Barrier', '隔音墙'),
    'sound_barrier_clear': ('Clear Sound Barrier', '透光隔音板'),
    'steel_girder_gray': ('Grey Steel Girder', '钢梁（灰）'),
    'steel_girder_green': ('Green Steel Girder', '钢梁（绿）'),
    'steel_girder_blue': ('Blue Steel Girder', '钢梁（蓝）'),
    'bridge_bearing': ('Bridge Bearing', '桥梁支座'),
    'expansion_joint': ('Expansion Joint', '伸缩缝'),
    'deck_drain': ('Deck Drain', '桥面排水口'),
    'drain_pipe': ('Drain Pipe', '排水管'),
    'catenary_pole': ('Catenary Pole', '接触网支柱'),
    'catenary_beam': ('Catenary Portal Beam', '接触网门架横梁'),
    'catenary_arm': ('Catenary Cantilever Arm', '接触网腕臂'),
    'catenary_hanger': ('Catenary Drop Hanger', '接触网吊柱'),
}
FACING_Y = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write('\n')


def tex(name):
    return 'citybuilder:block/' + name


def box(frm, to, side, top, bottom=None):
    """An element from..to (sixteenths); side faces map the texture by position, so a part shows its own slice."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    bottom = bottom or top
    faces = {
        'north': {'uv': [16 - x1, 16 - y1, 16 - x0, 16 - y0], 'texture': '#side'},
        'south': {'uv': [x0, 16 - y1, x1, 16 - y0], 'texture': '#side'},
        'west': {'uv': [z0, 16 - y1, z1, 16 - y0], 'texture': '#side'},
        'east': {'uv': [16 - z1, 16 - y1, 16 - z0, 16 - y0], 'texture': '#side'},
        'up': {'uv': [x0, z0, x1, z1], 'texture': '#top'},
        'down': {'uv': [x0, 16 - z1, x1, 16 - z0], 'texture': '#bottom'},
    }
    for face, edge in (('north', z0 == 0), ('south', z1 == 16), ('west', x0 == 0), ('east', x1 == 16),
                       ('up', y1 == 16), ('down', y0 == 0)):
        if edge:
            faces[face]['cullface'] = face
    return {'from': list(frm), 'to': list(to), 'faces': faces}


def model(elements, side, top, bottom=None, render=None):
    m = {'parent': 'minecraft:block/block',
         'textures': {'particle': tex(side), 'side': tex(side), 'top': tex(top), 'bottom': tex(bottom or top)},
         'elements': elements}
    if render:
        m['render_type'] = 'minecraft:' + render
    return m


def edge(name, side, top, x0, x1, depth, render=None):
    write(os.path.join(MODELS, name + '.json'),
          model([box((x0, 0, 0), (x1, 16, depth), side, top)], side, top, render=render))
    write(os.path.join(BLOCKSTATES, name + '.json'), {'variants': {
        'facing=%s' % f: dict({'model': 'citybuilder:block/' + name}, **({'y': y} if y else {}))
        for f, y in FACING_Y.items()}})


def single(name, elements, side, top, render=None):
    write(os.path.join(MODELS, name + '.json'), model(elements, side, top, render=render))
    write(os.path.join(BLOCKSTATES, name + '.json'), {'variants': {'': {'model': 'citybuilder:block/' + name}}})


def along(name, elements, side, top, render=None):
    """A piece running along x, turned for axis=z."""
    write(os.path.join(MODELS, name + '.json'), model(elements, side, top, render=render))
    write(os.path.join(BLOCKSTATES, name + '.json'), {'variants': {
        'axis=x': {'model': 'citybuilder:block/' + name},
        'axis=z': {'model': 'citybuilder:block/' + name, 'y': 90}}})


def catenary():
    single('catenary_pole', [box((5, 0, 5), (11, 16, 11), 'catenary_pole', 'catenary_pole_top')],
           'catenary_pole', 'catenary_pole_top')
    beam = box((0, 4, 4), (16, 12, 12), 'catenary_beam', 'catenary_beam')
    for face in ('east', 'west'):  # the ends show the beam's square section
        beam['faces'][face]['texture'] = '#top'
        beam['faces'][face]['uv'] = [0, 0, 16, 16]
    for face in ('up', 'down'):  # the top and bottom chords seen from above: the lattice again
        beam['faces'][face]['texture'] = '#side'
        beam['faces'][face]['uv'] = [0, 4, 16, 12]
    write(os.path.join(MODELS, 'catenary_beam.json'), {
        'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout',
        'textures': {'particle': tex('catenary_beam'), 'side': tex('catenary_beam'), 'top': tex('catenary_beam_end'),
                     'bottom': tex('catenary_beam_end')},
        'elements': [beam]})
    write(os.path.join(BLOCKSTATES, 'catenary_beam.json'), {'variants': {
        'axis=x': {'model': 'citybuilder:block/catenary_beam'},
        'axis=z': {'model': 'citybuilder:block/catenary_beam', 'y': 90}}})
    along('catenary_arm', [box((0, 7, 7), (16, 9, 9), 'catenary_arm', 'catenary_arm')], 'catenary_arm', 'catenary_arm')
    single('catenary_hanger', [box((7, 8, 7), (9, 16, 9), 'catenary_hanger', 'catenary_hanger'),
                               box((5, 2, 5), (11, 8, 11), 'catenary_hanger', 'catenary_hanger'),
                               box((5, 0, 5), (11, 2, 11), 'catenary_hanger', 'catenary_hanger')],
           'catenary_hanger', 'catenary_hanger', 'cutout')


def main():
    edge('concrete_parapet', 'concrete_parapet', 'concrete_parapet_top', 0, 16, 5)
    edge('bridge_railing', 'bridge_railing', 'bridge_railing_top', 0, 16, 2, 'cutout')
    edge('sound_barrier', 'sound_barrier', 'sound_barrier', 0, 16, 2)
    edge('sound_barrier_clear', 'sound_barrier_clear', 'sound_barrier_clear', 0, 16, 1, 'translucent')
    edge('drain_pipe', 'drain_pipe', 'drain_pipe', 6, 10, 4)
    for colour in ('gray', 'green', 'blue'):
        name = 'steel_girder_' + colour
        write(os.path.join(MODELS, name + '.json'), {'parent': 'minecraft:block/cube_column',
                                                     'textures': {'side': tex(name), 'end': tex(name + '_top')}})
        write(os.path.join(BLOCKSTATES, name + '.json'), {'variants': {'': {'model': 'citybuilder:block/' + name}}})
    write(os.path.join(MODELS, 'bridge_bearing.json'), model(
        [box((0, 0, 0), (16, 3, 16), 'bridge_bearing', 'bridge_bearing_top'),
         box((2, 3, 2), (14, 13, 14), 'bridge_bearing', 'bridge_bearing_top'),
         box((1, 13, 1), (15, 16, 15), 'bridge_bearing', 'bridge_bearing_top')],
        'bridge_bearing', 'bridge_bearing_top'))
    write(os.path.join(BLOCKSTATES, 'bridge_bearing.json'), {'variants': {'': {'model': 'citybuilder:block/bridge_bearing'}}})
    write(os.path.join(MODELS, 'expansion_joint.json'), {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
        'top': tex('expansion_joint'), 'bottom': tex('deck_side'), 'side': tex('deck_side')}})
    write(os.path.join(BLOCKSTATES, 'expansion_joint.json'), {'variants': {
        'axis=x': {'model': 'citybuilder:block/expansion_joint'},
        'axis=z': {'model': 'citybuilder:block/expansion_joint', 'y': 90}}})
    write(os.path.join(MODELS, 'deck_drain.json'), {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
        'top': tex('deck_drain'), 'bottom': tex('deck_side'), 'side': tex('deck_side')}})
    write(os.path.join(BLOCKSTATES, 'deck_drain.json'), {'variants': {'': {'model': 'citybuilder:block/deck_drain'}}})
    catenary()
    for name in NAMES:
        write(os.path.join(ITEMS, name + '.json'), {'parent': 'citybuilder:block/' + name})
        write(os.path.join(LOOT, name + '.json'), {'type': 'minecraft:block', 'pools': [{
            'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'citybuilder:' + name}],
            'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    for lang, i in (('en_us', 0), ('zh_cn', 1)):
        path = os.path.join(LANG, lang + '.json')
        with open(path, encoding='utf-8') as f:
            names = json.load(f)
        for name, both in NAMES.items():
            names['block.citybuilder.' + name] = both[i]
        write(path, names)


if __name__ == '__main__':
    main()
