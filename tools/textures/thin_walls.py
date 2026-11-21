"""Writes the thin walls' block states, block and item models, loot tables, tool tags and names for City Builder, and
draws the adjuster's item texture. Run from the repository root: python tools/textures/thin_walls.py

The thin walls (see common's block.ThinWallBlock and block.BuildingBlocks.THIN_WALLS): thin_<material>, a panel a
quarter of a block thick against one face of its block, a straight run, an outer corner or an inner corner's post,
textured with the vanilla block's own textures by reference (nothing of Minecraft's is copied), and the same for this
mod's own wall finishes (tools/textures/wall_textures.py; run that first), thin_<finish>. The adjuster's texture
is drawn here for this mod, so it carries the mod's own licence.
"""
import json
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wall_textures  # noqa: E402  (this mod's own wall finishes, which get thin walls too)

ROOT = os.path.join('common', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'citybuilder')
MODELS = os.path.join(ASSETS, 'models', 'block', 'thin')
ITEMS = os.path.join(ASSETS, 'models', 'item')
BLOCKSTATES = os.path.join(ASSETS, 'blockstates')
TEXTURES = os.path.join(ASSETS, 'textures', 'item')
LOOT = os.path.join(ROOT, 'data', 'citybuilder', 'loot_tables', 'blocks')
TAGS = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'blocks')
LANG = os.path.join(ASSETS, 'lang')

THICKNESS = 4

# Vanilla name: (side texture, top texture, English, Chinese)
MATERIALS = {
    'andesite': ('andesite', 'andesite', 'Andesite', '安山岩'),
    'birch_planks': ('birch_planks', 'birch_planks', 'Birch', '白桦木'),
    'black_concrete': ('black_concrete', 'black_concrete', 'Black Concrete', '黑色混凝土'),
    'bricks': ('bricks', 'bricks', 'Brick', '砖'),
    'brown_terracotta': ('brown_terracotta', 'brown_terracotta', 'Brown Terracotta', '棕色陶瓦'),
    'calcite': ('calcite', 'calcite', 'Calcite', '方解石'),
    'cracked_deepslate_tiles': ('cracked_deepslate_tiles', 'cracked_deepslate_tiles', 'Cracked Deepslate Tile', '裂纹深板岩瓦'),
    'cracked_stone_bricks': ('cracked_stone_bricks', 'cracked_stone_bricks', 'Cracked Stone Brick', '裂纹石砖'),
    'cut_sandstone': ('cut_sandstone', 'sandstone_top', 'Cut Sandstone', '切制砂岩'),
    'cyan_terracotta': ('cyan_terracotta', 'cyan_terracotta', 'Cyan Terracotta', '青色陶瓦'),
    'dark_oak_planks': ('dark_oak_planks', 'dark_oak_planks', 'Dark Oak', '深色橡木'),
    'deepslate_tiles': ('deepslate_tiles', 'deepslate_tiles', 'Deepslate Tile', '深板岩瓦'),
    'diorite': ('diorite', 'diorite', 'Diorite', '闪长岩'),
    'gray_concrete': ('gray_concrete', 'gray_concrete', 'Grey Concrete', '灰色混凝土'),
    'gray_terracotta': ('gray_terracotta', 'gray_terracotta', 'Grey Terracotta', '灰色陶瓦'),
    'green_terracotta': ('green_terracotta', 'green_terracotta', 'Green Terracotta', '绿色陶瓦'),
    'iron_block': ('iron_block', 'iron_block', 'Iron', '铁'),
    'light_blue_terracotta': ('light_blue_terracotta', 'light_blue_terracotta', 'Light Blue Terracotta', '淡蓝色陶瓦'),
    'light_gray_concrete': ('light_gray_concrete', 'light_gray_concrete', 'Light Grey Concrete', '淡灰色混凝土'),
    'light_gray_terracotta': ('light_gray_terracotta', 'light_gray_terracotta', 'Light Grey Terracotta', '淡灰色陶瓦'),
    'mangrove_planks': ('mangrove_planks', 'mangrove_planks', 'Mangrove', '红树木'),
    'mossy_stone_bricks': ('mossy_stone_bricks', 'mossy_stone_bricks', 'Mossy Stone Brick', '苔石砖'),
    'mud_bricks': ('mud_bricks', 'mud_bricks', 'Mud Brick', '泥砖'),
    'orange_terracotta': ('orange_terracotta', 'orange_terracotta', 'Orange Terracotta', '橙色陶瓦'),
    'packed_mud': ('packed_mud', 'packed_mud', 'Packed Mud', '泥坯'),
    'pink_terracotta': ('pink_terracotta', 'pink_terracotta', 'Pink Terracotta', '粉红色陶瓦'),
    'polished_andesite': ('polished_andesite', 'polished_andesite', 'Polished Andesite', '磨制安山岩'),
    'polished_blackstone': ('polished_blackstone', 'polished_blackstone', 'Polished Blackstone', '磨制黑石'),
    'polished_deepslate': ('polished_deepslate', 'polished_deepslate', 'Polished Deepslate', '磨制深板岩'),
    'polished_diorite': ('polished_diorite', 'polished_diorite', 'Polished Diorite', '磨制闪长岩'),
    'sandstone': ('sandstone', 'sandstone_top', 'Sandstone', '砂岩'),
    'smooth_quartz': ('quartz_block_bottom', 'quartz_block_bottom', 'Smooth Quartz', '平滑石英'),
    'smooth_sandstone': ('sandstone_top', 'sandstone_top', 'Smooth Sandstone', '平滑砂岩'),
    'spruce_planks': ('spruce_planks', 'spruce_planks', 'Spruce', '云杉木'),
    'stone': ('stone', 'stone', 'Stone', '石'),
    'stone_bricks': ('stone_bricks', 'stone_bricks', 'Stone Brick', '石砖'),
    'stripped_dark_oak_log': ('stripped_dark_oak_log', 'stripped_dark_oak_log_top', 'Stripped Dark Oak', '去皮深色橡木'),
    'stripped_spruce_log': ('stripped_spruce_log', 'stripped_spruce_log_top', 'Stripped Spruce', '去皮云杉木'),
    'terracotta': ('terracotta', 'terracotta', 'Terracotta', '陶瓦'),
    'white_concrete': ('white_concrete', 'white_concrete', 'White Concrete', '白色混凝土'),
    'white_terracotta': ('white_terracotta', 'white_terracotta', 'White Terracotta', '白色陶瓦'),
    'yellow_terracotta': ('yellow_terracotta', 'yellow_terracotta', 'Yellow Terracotta', '黄色陶瓦'),
}
WOOD = ('_planks', '_log')

# The boxes of each shape against north, in sixteenths: (x0, z0, x1, z1), the full height (core's ThinWall.boxes)
T = THICKNESS
SHAPES = {'straight': [(0, 0, 16, T)], 'outer': [(0, 0, 16, T), (16 - T, T, 16, 16)], 'inner': [(16 - T, 0, 16, T)]}
FACINGS = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


def box(x0, z0, x1, z1):
    """A full-height box, its faces textured to their own extent of the side and top textures (so a panel's faces
    show the part of the block's texture they would on the whole block, and panels side by side run on)."""
    faces = {'down': {'uv': [x0, z0, x1, z1], 'texture': '#top', 'cullface': 'down'},
             'up': {'uv': [x0, z0, x1, z1], 'texture': '#top', 'cullface': 'up'},
             'north': {'uv': [16 - x1, 0, 16 - x0, 16], 'texture': '#side'},
             'south': {'uv': [x0, 0, x1, 16], 'texture': '#side'},
             'west': {'uv': [z0, 0, z1, 16], 'texture': '#side'},
             'east': {'uv': [16 - z1, 0, 16 - z0, 16], 'texture': '#side'}}
    # The faces on the block's own sides are culled against what stands beside them
    for face, edge in (('north', z0 == 0), ('south', z1 == 16), ('west', x0 == 0), ('east', x1 == 16)):
        if edge:
            faces[face]['cullface'] = face
    return {'from': [x0, 0, z0], 'to': [x1, 16, z1], 'faces': faces}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def png(path, pixels):
    """Writes a 16x16 RGBA PNG; pixels[y][x] = (r, g, b, a)."""
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in pixels)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def adjuster_texture():
    """A spanner lying corner to corner: a steel shaft, an open jaw at the top right, a ring at the bottom left."""
    clear = (0, 0, 0, 0)
    px = [[clear] * 16 for _ in range(16)]
    steel, light, dark = (170, 176, 184, 255), (214, 220, 226, 255), (92, 98, 108, 255)
    for i in range(3, 13):
        px[15 - i][i] = steel
        px[15 - i - 1][i] = light
        px[15 - i + 1][i] = dark
    # The jaw: a C opening up and to the right
    for x, y in ((11, 1), (12, 1), (13, 2), (14, 3), (14, 4), (10, 2), (10, 3), (13, 5), (12, 5), (11, 4)):
        px[y][x] = steel
    px[1][12] = light
    # The ring
    for x, y in ((1, 12), (1, 13), (2, 11), (3, 11), (4, 12), (4, 13), (2, 14), (3, 14)):
        px[y][x] = steel
    px[11][2] = light
    return px


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
    """Adds blocks to a vanilla block tag of this mod's data, keeping what it lists already."""
    path = os.path.join(TAGS, *name.split('/')) + '.json'
    have = []
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            have = json.load(f)['values']
    write(path, {'replace': False, 'values': have + [v for v in values if v not in have]})


def main():
    names = {}
    pickaxe, axe, stone_tool = [], [], []
    own = {name: (side, top, en, zh, wood) for name, side, top, en, zh, wood in wall_textures.thin_materials()}
    every = {**{m: (f'minecraft:block/{side}', f'minecraft:block/{top}', en, zh, m.endswith(WOOD))
                for m, (side, top, en, zh) in MATERIALS.items()}, **own}
    for material, (side, top, en, zh, wood) in every.items():
        n = f'thin_{material}'
        textures = {'particle': side, 'side': side, 'top': top}
        for shape, boxes in SHAPES.items():
            write(os.path.join(MODELS, f'{material}_{shape}.json'),
                  {'parent': 'minecraft:block/block', 'textures': textures, 'elements': [box(*b) for b in boxes]})
        write(os.path.join(BLOCKSTATES, f'{n}.json'), {'variants': {
            f'facing={facing},shape={shape}': ({'model': f'citybuilder:block/thin/{material}_{shape}', 'y': y}
                                               if y else {'model': f'citybuilder:block/thin/{material}_{shape}'})
            for facing, y in FACINGS.items() for shape in SHAPES}})
        write(os.path.join(ITEMS, f'{n}.json'), {'parent': f'citybuilder:block/thin/{material}_straight'})
        write(os.path.join(LOOT, f'{n}.json'), {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'citybuilder:{n}'}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
        names[f'block.citybuilder.{n}'] = (f'Thin {en} Wall', f'薄墙（{zh}）')
        (axe if wood else pickaxe).append(f'citybuilder:{n}')
        if material == 'iron_block':
            stone_tool.append(f'citybuilder:{n}')
    add_tag('mineable/pickaxe', pickaxe)
    add_tag('mineable/axe', axe)
    add_tag('needs_stone_tool', stone_tool)
    png(os.path.join(TEXTURES, 'adjuster.png'), adjuster_texture())
    write(os.path.join(ITEMS, 'adjuster.json'), {'parent': 'minecraft:item/handheld',
                                                 'textures': {'layer0': 'citybuilder:item/adjuster'}})
    names['item.citybuilder.adjuster'] = ('Adjuster', '调整扳手')
    add_names(names)


if __name__ == '__main__':
    main()
