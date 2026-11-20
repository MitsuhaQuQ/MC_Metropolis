"""Writes the block states, block and item models, loot tables and names of City Builder's landmark blocks (see
landmark_textures.py, which draws their textures). Run from the repository root:
python tools/textures/landmark_models.py

Surfaces: the track, turf (two mowing shades) and infield soil, each also with a white line at one of ANGLES directions
and OFFSETS positions (MarkingBlock; its low state is not used here and shows the same model). Stadium seats
(SeatBlock: facing is the side the backrest is on). A Fresnel lens and an LED panel.
"""
import json
import os

ASSETS = os.path.join('common', 'src', 'main', 'resources', 'assets', 'citybuilder')
DATA = os.path.join('common', 'src', 'main', 'resources', 'data', 'citybuilder')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
MODELS = os.path.join(ASSETS, 'models', 'block')
ITEMS = os.path.join(ASSETS, 'models', 'item')
ANGLES = 16
OFFSETS = 8
SEATS = {'blue': ('Blue', '蓝'), 'light_blue': ('Light Blue', '浅蓝'), 'red': ('Red', '红'),
         'green': ('Green', '绿'), 'orange': ('Orange', '橙'), 'white': ('White', '白')}
# block -> (English, Chinese)
NAMES = {'track': ('All-Weather Track', '全天候跑道'), 'track_line_white': ('Track with White Line', '跑道（白线）'),
         'turf': ('Pitch Turf', '球场草皮'), 'turf_dark': ('Pitch Turf (Dark Stripe)', '球场草皮（深色条纹）'),
         'turf_line_white': ('Pitch Turf with White Line', '球场草皮（白线）'),
         'turf_dark_line_white': ('Pitch Turf (Dark Stripe) with White Line', '球场草皮（深色条纹、白线）'),
         'soil': ('Infield Soil', '内野土'), 'soil_line_white': ('Infield Soil with White Line', '内野土（白线）'),
         'fresnel_lens': ('Fresnel Lens', '菲涅耳透镜'), 'led_panel': ('LED Scoreboard Panel', '记分牌 LED 面板')}
for c, (en, zh) in SEATS.items():
    NAMES[f'seat_{c}'] = (f'{en} Stadium Seat', f'{zh}色看台座椅')


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write('\n')


def block(name):
    return f'citybuilder:block/{name}'


def simple(name, model, item_parent=None):
    write(os.path.join(MODELS, f'{name}.json'), model)
    write(os.path.join(BLOCKSTATES, f'{name}.json'), {'variants': {'': {'model': block(name)}}})
    write(os.path.join(ITEMS, f'{name}.json'), {'parent': item_parent or block(name)})


def surface(name, side, bottom):
    """A surface block and its marked twin (name_line_white)."""
    simple(name, {'parent': 'minecraft:block/cube_bottom_top',
                  'textures': {'top': block(name), 'side': block(side), 'bottom': block(bottom)}})
    variants = {}
    for angle in range(ANGLES):
        for offset in range(OFFSETS):
            line = f'{name}_white_{angle}_{offset}'
            write(os.path.join(MODELS, 'line', f'{line}.json'), {
                'parent': 'minecraft:block/cube_bottom_top',
                'textures': {'top': f'citybuilder:block/line/{line}', 'side': block(side), 'bottom': block(bottom)}})
            for low in ('false', 'true'):
                variants[f'angle={angle},low={low},offset={offset}'] = {'model': f'citybuilder:block/line/{line}'}
    marked = f'{name}_line_white'
    write(os.path.join(BLOCKSTATES, f'{marked}.json'), {'variants': variants})
    write(os.path.join(ITEMS, f'{marked}.json'), {'parent': f'citybuilder:block/line/{name}_white_0_4'})


def seat_parent():
    """A stadium seat, its back on the north side: a moulded pan on a steel bracket, the backrest behind it."""
    def box(frm, to, tex):
        return {'from': frm, 'to': to, 'faces': {f: {'texture': tex} for f in
                                                 ('down', 'up', 'north', 'south', 'west', 'east')}}
    return {'parent': 'minecraft:block/block', 'textures': {'particle': '#seat'},
            'elements': [box([7, 0, 6], [9, 5, 10], '#metal'),
                         box([2, 5, 3], [14, 7, 14], '#seat'),
                         box([2, 7, 1], [14, 15, 3], '#seat')]}


def main():
    surface('track', 'track', 'track')
    surface('turf', 'turf_side', 'soil')
    surface('turf_dark', 'turf_side', 'soil')
    surface('soil', 'soil', 'soil')
    write(os.path.join(MODELS, 'seat.json'), seat_parent())
    for c in SEATS:
        name = f'seat_{c}'
        write(os.path.join(MODELS, f'{name}.json'), {'parent': 'citybuilder:block/seat', 'textures': {
            'seat': block(name), 'metal': 'minecraft:block/light_gray_concrete'}})
        write(os.path.join(BLOCKSTATES, f'{name}.json'), {'variants': {
            f'facing={f}': {'model': block(name), **({'y': r} if r else {})}
            for f, r in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}})
        write(os.path.join(ITEMS, f'{name}.json'), {'parent': block(name)})
    simple('fresnel_lens', {'parent': 'minecraft:block/cube_all', 'textures': {'all': block('fresnel_lens')}})
    simple('led_panel', {'parent': 'minecraft:block/cube_all', 'textures': {'all': block('led_panel')}})
    for name in NAMES:
        write(os.path.join(DATA, 'loot_tables', 'blocks', f'{name}.json'), {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'citybuilder:{name}'}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    for lang, i in (('en_us', 0), ('zh_cn', 1)):
        path = os.path.join(ASSETS, 'lang', f'{lang}.json')
        with open(path, encoding='utf-8') as f:
            names = json.load(f)
        for name, pair in NAMES.items():
            names[f'block.citybuilder.{name}'] = pair[i]
        write(path, names)


if __name__ == '__main__':
    main()
