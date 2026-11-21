"""Draws the buildings' wall finishes, floors and foundations for City Builder and writes their block states, block and
item models, loot tables, tool tags and names. Run from the repository root: python tools/textures/wall_textures.py
(then tools/textures/thin_walls.py, which gives the walls thin variants).

The textures are drawn by this script for this mod (no outside sources), so they carry the mod's own licence.

The blocks (see common's block.BuildingBlocks.WALLS; core's building.Palette picks them from the catalogue's palettes):
Japanese finishes, each a whole block, the side texture on the four sides and a plain one top and bottom:
- plaster (漆喰) white, cream and black: trowelled smooth, faint strokes; earth walls (聚楽壁, 荒壁) grainy with sand
  and straw;
- mortar (モルタル吹付): a stippled spray finish;
- ceramic siding (窯業系サイディング): lap boards, brick and stone patterns;
- tiled facades (タイル張り): small tiles (二丁掛) in stretcher bond, a little variation tile to tile;
- concrete: exposed (打ち放し) with its form panels' joints and tie holes (Pコン), painted;
- ALC panels: vertical panels with their joints;
- stone cladding (polished granite, limestone), a fine brick, timber boards (下見板 with battens, charred 焼杉, red
  弁柄 lattice wood), planed posts, a storehouse's namako wall (海鼠壁), corrugated steel siding (角波);
- foundations: concrete (布基礎), granite ashlar, rubble, concrete block (ブロック塀); floors: wood strip flooring and
  vinyl tile.
Every wall material but the floors has a weathered twin, <name>_aged (grime, streaks down it), which the catalogue's
weathering table ages a building's walls into. Colours are muted (CIELAB chroma at most about 30, roughly Munsell
chroma 6, as Kyoto's landscape rules ask of walls); core's CatalogTest checks the palettes' walls from these textures.
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lantern_door_textures as ld  # noqa: E402  (PNG writer and file helpers)

from lantern_door_textures import noise, write, add_names, add_tag  # noqa: E402

TEXTURES = os.path.join(ld.TEXTURES, 'wall')
MODELS = os.path.join(ld.MODELS, 'wall')


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, d):
    return tuple(clamp(v + d) for v in c[:3])


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def smooth_noise(x, y, seed, cell):
    """Value noise on a grid of `cell`, wrapping at 16 so blocks tile."""
    gx, gy = x / cell, y / cell
    x0, y0 = int(math.floor(gx)), int(math.floor(gy))
    fx, fy = gx - x0, gy - y0
    n = 16 // cell

    def v(i, j):
        return noise(i % n, j % n, seed)
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    top = v(x0, y0) + (v(x0 + 1, y0) - v(x0, y0)) * fx
    bottom = v(x0, y0 + 1) + (v(x0 + 1, y0 + 1) - v(x0, y0 + 1)) * fx
    return top + (bottom - top) * fy


# ---------------------------------------------------------------- patterns: f(base, seed) -> f(x, y) -> rgb

def plaster(base, seed):
    """Trowelled plaster: nearly flat, broad soft clouds and a few faint arcs of the trowel."""
    def f(x, y):
        d = 3 * smooth_noise(x, y, seed, 8) + 1.2 * noise(x, y, seed + 1)
        if (x * 3 + y * 5 + int(4 * smooth_noise(x, y, seed + 2, 4))) % 23 == 0:
            d += 3
        return shade(base, d)
    return f


def earth(base, seed, coarse=False):
    """An earth wall: fine sand grains, flecks of straw, the coarse one rougher and darker in patches."""
    def f(x, y):
        d = 4 * smooth_noise(x, y, seed, 4 if coarse else 8) + (5 if coarse else 3) * noise(x, y, seed + 1)
        c = shade(base, d)
        n = noise(x, y, seed + 2)
        if n > (0.82 if coarse else 0.9):
            c = mix(c, (200, 176, 120), 0.45)       # straw
        elif n < -0.9:
            c = shade(c, -14)                       # a grain's shadow
        return c
    return f


def stipple(base, seed):
    """Sprayed mortar (吹付): small bumps lit from above, their undersides in shadow."""
    def f(x, y):
        here = noise(x, y, seed)
        above = noise(x, (y - 1) % 16, seed)
        d = 6 * here - 4 * above + 2 * smooth_noise(x, y, seed + 1, 8)
        return shade(base, d)
    return f


def lap(base, seed, course=4):
    """Lap siding: boards `course` high, each lit along its top and shadowed under the one above; a faint grain and a
    joint in each board, staggered board to board."""
    def f(x, y):
        r = y % course
        board = y // course
        d = {0: -16, 1: 7}.get(r, 0) + (2 if r == course - 1 else 0)
        d += 1.5 * noise(x, y, seed) + 2 * noise(x // 4, board, seed + 1)
        if (x + 5 * board) % 16 == 0 and r != 0:
            d -= 8
        return shade(base, d)
    return f


def bond(base, seed, w, h, joint, joint_colour, vary, rough=1.5, fine=False):
    """Units w x h in stretcher bond (each course offset half a unit), joints 1 pixel, `vary` the unit to unit tone;
    a unit's top edge a little lit, its foot a little shadowed."""
    def f(x, y):
        course = y // h
        ox = (x + (w // 2) * (course % 2)) % 16
        unit = ox // w
        jy, jx = y % h == h - 1, ox % w == w - 1
        if jy or jx:
            return shade(joint_colour, 2 * noise(x, y, seed + 3))
        tone = vary * noise(unit, course, seed)
        hue = noise(unit, course, seed + 7)
        c = shade(base, tone + rough * noise(x, y, seed + 1))
        if fine:
            c = (c[0] + 4 * hue, c[1] + 1 * hue, c[2] - 2 * hue)
        if y % h == 0:
            c = shade(c, 4)
        return c
    return f


def stone_pattern(base, seed):
    """Stone-pattern siding: irregular flat stones in courses of 4 and 6, sunk joints, the faces mottled."""
    rows = [(0, 5), (5, 4), (9, 4), (13, 3)]
    cuts = [(0, 7, 11), (3, 9), (1, 6, 12), (4, 10)]

    def f(x, y):
        for i, (y0, h) in enumerate(rows):
            if y0 <= y < y0 + h:
                ry = y - y0
                xs = sorted(cuts[i])
                stone = sum(1 for c in xs if x >= c)
                if ry == h - 1 or x in xs:
                    return shade(base, -26 + 2 * noise(x, y, seed))
                d = 7 * noise(stone, i, seed + 1) + 4 * smooth_noise(x, y, seed + 2, 4) + 2 * noise(x, y, seed + 3)
                if ry == 0:
                    d += 6
                return shade(base, d)
    return f


def exposed(base, seed):
    """Exposed concrete (打ち放し): one form panel to a block, its joints a fine line on the block's edges, four tie
    holes (Pコン) round and dark with a lighter rim; the face a little mottled and blotched."""
    holes = [(3, 4), (11, 4), (3, 12), (11, 12)]

    def f(x, y):
        for hx, hy in holes:
            dd = (x - hx - 0.5) ** 2 + (y - hy - 0.5) ** 2
            if dd < 0.6:
                return shade(base, -44)
            if dd < 2.2 and (x, y) in ((hx, hy), (hx + 1, hy), (hx, hy + 1), (hx + 1, hy + 1)):
                return shade(base, -30)
        if y == 15 or x == 15:
            return shade(base, -14)
        if y == 0 or x == 0:
            return shade(base, 5)
        d = 5 * smooth_noise(x, y, seed, 8) + 3 * smooth_noise(x, y, seed + 1, 4) + 1.5 * noise(x, y, seed + 2)
        return shade(base, d)
    return f


def painted(base, seed):
    """Painted concrete: flat, a faint texture of the coat."""
    def f(x, y):
        return shade(base, 2.5 * smooth_noise(x, y, seed, 8) + 1.2 * noise(x, y, seed + 1))
    return f


def alc(base, seed):
    """ALC panels: vertical panels 8 wide, their sealed joints a dark line with a lit edge, the faces finely pitted."""
    def f(x, y):
        k = x % 8
        if k == 7:
            return shade(base, -22)
        if k == 0:
            return shade(base, 5)
        d = 1.5 * noise(x, y, seed) + (-4 if noise(x, y, seed + 1) > 0.88 else 0) + 2 * smooth_noise(x, y, seed + 2, 8)
        return shade(base, d)
    return f


def granite(base, seed, panel=16):
    """Polished granite cladding: a speckle of light and dark grains, panels `panel` square with fine joints."""
    def f(x, y):
        if (panel < 16 and (x % panel == panel - 1)) or y == 15 or x == 15:
            return shade(base, -18)
        n = noise(x, y, seed)
        d = 16 if n > 0.8 else -18 if n < -0.75 else 4 * noise(x, y, seed + 1)
        return shade(base, d + 2 * smooth_noise(x, y, seed + 2, 8))
    return f


def limestone(base, seed):
    """Limestone cladding: panels 16 wide and 8 high, warm and calm, a few fossil flecks."""
    def f(x, y):
        if y % 8 == 7 or x == 15:
            return shade(base, -16)
        n = noise(x, y, seed)
        d = 3 * smooth_noise(x, y, seed + 1, 4) + (-8 if n > 0.9 else 1.5 * n)
        return shade(base, d)
    return f


def boards(base, seed, battens=0, charred=False):
    """Horizontal boards (下見板) 4 high, lapped: lit along the top, a shadow under each; grain along them; battens
    (押縁) `battens` apart standing proud; charred boards (焼杉) crazed in small blisters."""
    def f(x, y):
        if battens and x % battens in (battens - 2, battens - 1):
            lit = x % battens == battens - 2
            return shade(base, (10 if lit else -6) + 2 * noise(x, y, seed + 5))
        r = y % 4
        d = {0: 6, 3: -18}.get(r, 0)
        grain = noise(x // 3, y, seed + 1)
        d += 5 * grain + 2 * noise(x, y, seed + 2)
        if charred:
            cell = noise(x // 2, y // 2 + 9 * (y // 4), seed + 3)
            d += 10 if (x % 2 == 0 and cell > 0.3) else -2
        return shade(base, d)
    return f


def timber(base, seed):
    """A planed post: vertical grain, fine and straight, a little darker toward its edges."""
    def f(x, y):
        d = 6 * noise(x, 0, seed) + 2 * noise(x, y // 4, seed + 1) + 1.5 * noise(x, y, seed + 2)
        if x in (0, 15):
            d -= 6
        return shade(base, d)
    return f


def end_grain(base, seed):
    """A post's end: rings round its heart."""
    def f(x, y):
        r = math.hypot(x - 7.5, y - 7.5)
        return shade(base, (6 if int(r) % 3 == 0 else -2) + 2 * noise(x, y, seed) + 8)
    return f


def namako(base, seed):
    """A namako wall (海鼠壁): dark square tiles set diagonally, their joints raised plaster, white and rounded."""
    white = (232, 230, 222)

    def f(x, y):
        a, b = (x + y) % 8, (x - y) % 8
        if a == 0 or b == 0:
            return shade(white, 8 if (a == 0) else -2)
        if a == 1 or b == 1:
            return shade(white, -26)
        return shade(base, 3 * noise(x, y, seed) + 4 * noise((x + y) // 8, (x - y) // 8, seed + 1))
    return f


def corrugated(base, seed):
    """Corrugated steel siding (角波): vertical ribs every 4, lit on one face, shadowed on the other."""
    def f(x, y):
        d = [14, 4, -16, -4][x % 4] + 1.2 * noise(x, y, seed)
        if y == 15:
            d -= 6
        return shade(base, d)
    return f


def foundation(base, seed):
    """A concrete foundation (布基礎): mortar-finished, a little mottled, a drip line near its top."""
    def f(x, y):
        d = 3 * smooth_noise(x, y, seed, 4) + 2 * noise(x, y, seed + 1)
        if y == 1:
            d -= 9
        return shade(base, d)
    return f


def rubble(base, seed):
    """Rubble stone (野面積み): rounded stones of a few sizes, dark gaps between, each stone its own tone."""
    stones = [(2, 2, 3.2), (9, 1, 3.4), (14, 5, 2.6), (5, 8, 3.6), (12, 11, 3.4), (1, 13, 2.8), (7, 14, 2.6),
              (-2, 7, 2.5), (16, 12, 2.5), (18, 2, 3.2), (9, 17, 3.4), (2, 18, 3.2), (7, -2, 2.6)]

    def f(x, y):
        best, which = 9e9, -1
        for i, (sx, sy, r) in enumerate(stones):
            for ox in (-16, 0, 16):
                for oy in (-16, 0, 16):
                    d = math.hypot(x - sx - ox, y - sy - oy) / (1.2 * r)
                    if d < best:
                        best, which = d, i % 9
        if best > 1.05:
            return shade(base, -40 + 3 * noise(x, y, seed))
        d = 10 * noise(which, 0, seed + 1) + 3 * noise(x, y, seed + 2) + (6 if best < 0.5 else 0)
        return shade(base, d)
    return f


def concrete_block(base, seed):
    """Concrete block (ブロック塀): blocks 8 x 4 in stretcher bond, sunk joints, a pitted face."""
    joint = shade(base, -24)
    inner = bond(base, seed, 8, 4, None, joint, 3, rough=1)

    def f(x, y):
        c = inner(x, y)
        return shade(c, -7) if noise(x, y, seed + 9) > 0.86 else c
    return f


def flooring(base, seed):
    """Strip flooring: boards 4 wide running along x, their end joints staggered, each board its own tone."""
    def f(x, y):
        row = x // 4
        off = (5 * row) % 16
        if x % 4 == 3:
            return shade(base, -16)
        if (y + off) % 16 == 0:
            return shade(base, -12)
        board = ((y + off) // 16) + 2 * row
        return shade(base, 7 * noise(board, row, seed) + 3 * noise(x, y // 2, seed + 1))
    return f


def pvc(base, seed):
    """Vinyl floor tiles (Pタイル): 8 square, alternate tiles a shade apart, a fine speckle."""
    def f(x, y):
        t = ((x // 8) + (y // 8)) % 2
        d = (4 if t else -2) + (5 if noise(x, y, seed) > 0.85 else 0) + noise(x, y, seed + 1)
        if x % 8 == 7 or y % 8 == 7:
            d -= 6
        return shade(base, d)
    return f


def tiles(base, seed, colour_vary=6):
    """Small facade tiles (二丁掛) in stretcher bond, 4 wide and 2 high with their joints, the grout lighter."""
    return bond(base, seed, 4, 2, None, mix(base, (210, 206, 196), 0.55), colour_vary, rough=1.0, fine=True)


def brick(base, seed):
    """A fine brick: bricks 8 wide and 2 high, the courses offset, dark-struck mortar, burnt tones brick to brick."""
    return bond(base, seed, 8, 2, None, mix(base, (150, 144, 134), 0.6), 10, rough=2.5, fine=True)


def siding_brick(base, seed):
    """Brick-pattern siding: bricks 8 x 4 pressed into the board, regular, shallow joints."""
    return bond(base, seed, 8, 4, None, shade(base, 26), 6, rough=2)


# ---------------------------------------------------------------- the materials

# name: (side pattern, base colour, English, Chinese, kind, top pattern or None for plain)
# kind: the tool and the vanilla block whose sounds and hardness the Java side copies (BuildingBlocks.WALLS)
P = lambda f, *a, **k: (lambda base, seed: f(base, seed, *a, **k))  # noqa: E731
MATERIALS = {
    # plaster and earth
    'plaster_white': (plaster, (234, 232, 224), 'White Plaster', '白漆喰', 'white_concrete'),
    'plaster_cream': (plaster, (222, 212, 186), 'Cream Plaster', '米色漆喰', 'white_concrete'),
    'plaster_black': (plaster, (50, 50, 52), 'Black Plaster', '黑漆喰', 'black_concrete'),
    'earth_wall': (earth, (178, 150, 110), 'Earth Wall', '聚乐土墙', 'terracotta'),
    'earth_rough': (P(earth, True), (138, 112, 84), 'Rough Earth Wall', '荒壁', 'terracotta'),
    # mortar
    'mortar_white': (stipple, (222, 220, 212), 'White Mortar', '白色砂浆喷涂', 'white_concrete'),
    'mortar_beige': (stipple, (204, 190, 164), 'Beige Mortar', '米色砂浆喷涂', 'white_concrete'),
    'mortar_gray': (stipple, (158, 158, 152), 'Grey Mortar', '灰色砂浆喷涂', 'light_gray_concrete'),
    # ceramic siding
    'siding_white': (lap, (232, 230, 222), 'White Siding', '白色挂板', 'white_concrete'),
    'siding_beige': (lap, (206, 194, 170), 'Beige Siding', '米色挂板', 'white_concrete'),
    'siding_gray': (lap, (150, 152, 150), 'Grey Siding', '灰色挂板', 'light_gray_concrete'),
    'siding_dark': (lap, (74, 76, 80), 'Dark Grey Siding', '深灰挂板', 'gray_concrete'),
    'siding_brown': (lap, (112, 88, 70), 'Brown Siding', '褐色挂板', 'brown_terracotta'),
    'siding_bluegray': (lap, (132, 142, 152), 'Blue-Grey Siding', '蓝灰挂板', 'light_gray_concrete'),
    'siding_rose': (lap, (198, 170, 160), 'Rose Siding', '浅玫瑰色挂板', 'white_concrete'),
    'siding_brick': (siding_brick, (146, 106, 86), 'Brick-Pattern Siding', '砖纹挂板', 'brown_terracotta'),
    'siding_stone': (stone_pattern, (190, 178, 154), 'Stone-Pattern Siding', '石纹挂板', 'white_concrete'),
    # tiled facades
    'tile_white': (tiles, (226, 222, 212), 'White Facade Tile', '白色面砖', 'white_terracotta'),
    'tile_beige': (tiles, (198, 178, 148), 'Beige Facade Tile', '米色面砖', 'white_terracotta'),
    'tile_ochre': (tiles, (188, 158, 112), 'Ochre Facade Tile', '赭色面砖', 'white_terracotta'),
    'tile_brown': (tiles, (126, 86, 66), 'Brown Facade Tile', '褐色面砖', 'brown_terracotta'),
    'tile_dark': (tiles, (90, 70, 60), 'Dark Brown Facade Tile', '深褐面砖', 'brown_terracotta'),
    'tile_gray': (tiles, (142, 142, 140), 'Grey Facade Tile', '灰色面砖', 'light_gray_terracotta'),
    'tile_bluegray': (tiles, (128, 136, 148), 'Blue-Grey Facade Tile', '蓝灰面砖', 'light_gray_terracotta'),
    'tile_rose': (tiles, (178, 142, 130), 'Rose Facade Tile', '玫瑰色面砖', 'white_terracotta'),
    # concrete
    'concrete_exposed': (exposed, (158, 158, 154), 'Exposed Concrete', '清水混凝土', 'light_gray_concrete'),
    'concrete_white': (painted, (228, 228, 224), 'White Painted Concrete', '白色涂装混凝土', 'white_concrete'),
    'concrete_cream': (painted, (222, 210, 180), 'Cream Painted Concrete', '米色涂装混凝土', 'white_concrete'),
    'concrete_rose': (painted, (214, 194, 186), 'Rose Painted Concrete', '浅玫瑰色涂装混凝土', 'white_concrete'),
    'concrete_bluegray': (painted, (178, 186, 194), 'Blue-Grey Painted Concrete', '蓝灰涂装混凝土', 'light_gray_concrete'),
    'concrete_light': (painted, (186, 186, 182), 'Light Grey Painted Concrete', '浅灰涂装混凝土', 'light_gray_concrete'),
    'concrete_gray': (painted, (124, 124, 122), 'Grey Painted Concrete', '灰色涂装混凝土', 'gray_concrete'),
    'concrete_black': (painted, (52, 52, 54), 'Black Painted Concrete', '黑色涂装混凝土', 'black_concrete'),
    'alc_white': (alc, (220, 218, 210), 'White ALC Panel', '白色ALC板', 'white_concrete'),
    'alc_gray': (alc, (168, 168, 164), 'Grey ALC Panel', '灰色ALC板', 'light_gray_concrete'),
    # stone and brick
    'granite_gray': (granite, (150, 148, 146), 'Grey Granite Cladding', '灰色花岗岩饰面', 'polished_andesite'),
    'granite_black': (granite, (56, 56, 60), 'Black Granite Cladding', '黑色花岗岩饰面', 'polished_deepslate'),
    'limestone': (limestone, (206, 196, 174), 'Limestone Cladding', '石灰石饰面', 'smooth_sandstone'),
    'brick_red': (brick, (140, 82, 66), 'Fine Brick', '细砖', 'bricks'),
    'brick_dark': (brick, (98, 68, 58), 'Dark Brick', '深色细砖', 'bricks'),
    # wood
    'board_cedar': (boards, (130, 96, 68), 'Cedar Boards', '杉木下见板', 'spruce_planks'),
    'board_dark': (P(boards, 8), (68, 52, 42), 'Dark Boards', '深色押缘下见板', 'dark_oak_planks'),
    'board_yakisugi': (P(boards, 8, True), (38, 36, 36), 'Charred Cedar Boards', '烧杉板', 'dark_oak_planks'),
    'board_light': (boards, (196, 172, 136), 'Light Boards', '浅色木板', 'birch_planks'),
    'board_bengara': (P(boards, 8), (112, 64, 52), 'Bengara Boards', '红殼木板', 'mangrove_planks'),
    'timber_dark': (timber, (72, 54, 42), 'Dark Timber', '深色木柱', 'dark_oak_planks'),
    'timber_brown': (timber, (120, 90, 64), 'Brown Timber', '褐色木柱', 'spruce_planks'),
    'timber_light': (timber, (178, 146, 106), 'Light Timber', '浅色木柱', 'oak_planks'),
    # storehouse, steel
    'namako': (namako, (64, 66, 70), 'Namako Wall', '海鼠壁', 'deepslate_tiles'),
    'steel_siding': (corrugated, (172, 174, 174), 'Corrugated Steel Siding', '角波钢板', 'iron_block'),
    'steel_siding_gray': (corrugated, (112, 116, 118), 'Grey Corrugated Steel Siding', '灰色角波钢板', 'iron_block'),
    # foundations and yard walls
    'concrete_foundation': (foundation, (150, 150, 146), 'Concrete Foundation', '混凝土基础', 'stone'),
    'stone_ashlar': (P(granite, 8), (152, 148, 142), 'Granite Ashlar', '花岗岩方石', 'stone_bricks'),
    'stone_rubble': (rubble, (132, 128, 120), 'Rubble Stone', '野面石', 'cobblestone'),
    'concrete_block': (concrete_block, (168, 166, 160), 'Concrete Block', '混凝土砌块', 'stone_bricks'),
    # floors
    'flooring_light': (flooring, (186, 150, 106), 'Light Wood Flooring', '浅色木地板', 'oak_planks'),
    'flooring_dark': (flooring, (112, 80, 54), 'Dark Wood Flooring', '深色木地板', 'dark_oak_planks'),
    'floor_pvc': (pvc, (198, 196, 188), 'Vinyl Floor Tile', 'PVC地砖', 'smooth_stone'),
}
# Re-shaped ashlar: courses 4 high, 8 wide, granite speckle with joints both ways
MATERIALS['stone_ashlar'] = (lambda base, seed: bond(base, seed, 8, 4, None, shade(base, -30), 8, rough=7),) \
    + MATERIALS['stone_ashlar'][1:]
FLOORS = ('flooring_light', 'flooring_dark', 'floor_pvc')
FOUNDATIONS = ('concrete_foundation', 'stone_ashlar', 'stone_rubble', 'concrete_block')
WOOD = ('spruce_planks', 'dark_oak_planks', 'birch_planks', 'mangrove_planks', 'oak_planks')
# Tops: a plain face of the material (a post's end grain, a board's edge)
TOPS = {'timber_dark': end_grain, 'timber_brown': end_grain, 'timber_light': end_grain}


def aged(f, seed):
    """A weathered face: darker and greyer, grime soaked in, streaks down from above in two columns (so aged blocks
    stacked run on), blotches here and there."""
    grime = (84, 82, 76)
    streaks = {(seed * 5 + 3) % 16, (seed * 11 + 10) % 16}

    def g(x, y):
        c = f(x, y)
        c = mix(c, grime, 0.12)
        c = shade(c, -6 + 2 * smooth_noise(x, y, seed + 40, 8))
        if x in streaks:
            c = mix(c, grime, 0.22 + 0.08 * noise(x, y // 3, seed + 41))
        elif any((x - s) % 16 in (1, 15) for s in streaks):
            c = mix(c, grime, 0.08)
        c = mix(c, grime, max(0.0, 0.1 * smooth_noise(x, y, seed + 42, 8)))
        return c
    return g


def to_rgba(f):
    return [[shade(f(x, y), 0) + (255,) for x in range(16)] for y in range(16)]


def average(pixels):
    n = 256
    return tuple(sum(p[i] for row in pixels for p in row) / n for i in range(3))


def chroma(c):
    """CIELAB chroma C*ab of an sRGB colour (D65)."""
    def lin(v):
        v /= 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(v) for v in c)
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    a = 500 * (f(x) - f(y))
    bb = 200 * (f(y) - f(z))
    return math.hypot(a, bb)


def names_of():
    """Every block: (name, English, Chinese, template, fresh name)."""
    out = []
    for name, (_, _, en, zh, template) in MATERIALS.items():
        out.append((name, en, zh, template, name))
        if name not in FLOORS:
            out.append((name + '_aged', 'Weathered ' + en, zh + '（旧）', template, name))
    return out


def thin_materials():
    """The materials given thin walls (tools/textures/thin_walls.py): every wall finish fresh and weathered, not the
    floors or foundations: (name, side texture, top texture, English, Chinese, wood)."""
    out = []
    for name, en, zh, template, fresh in names_of():
        if fresh in FLOORS or fresh in FOUNDATIONS:
            continue
        out.append((name, f'citybuilder:block/wall/{name}', f'citybuilder:block/wall/{name}_top', en, zh,
                    template in WOOD))
    return out


def preview(path):
    """A sheet of every material, fresh and weathered, each as 3 x 3 blocks at 4x, for looking at."""
    import struct
    import zlib
    cols, scale, rep = 8, 3, 3
    cell = 16 * rep * scale + 6
    items = []
    for i, (name, (pattern, base, *_)) in enumerate(MATERIALS.items()):
        side = pattern(base, 31 * i + 7)
        items.append(to_rgba(side))
        if name not in FLOORS:
            items.append(to_rgba(aged(side, 31 * i + 7)))
    rows = (len(items) + cols - 1) // cols
    w, h = cols * cell, rows * cell
    img = [[(40, 40, 40)] * w for _ in range(h)]
    for k, px in enumerate(items):
        ox, oy = (k % cols) * cell, (k // cols) * cell
        for y in range(16 * rep * scale):
            for x in range(16 * rep * scale):
                img[oy + y][ox + x] = px[(y // scale) % 16][(x // scale) % 16][:3]
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in img)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    print(' '.join(f'{k}:{n}' for k, n in enumerate(n for n, *_ in names_of())))


def main():
    if len(sys.argv) > 2 and sys.argv[1] == '--preview':
        preview(sys.argv[2])
        return
    names = {}
    pickaxe, axe = [], []
    report = []
    for i, (name, (pattern, base, en, zh, template)) in enumerate(MATERIALS.items()):
        seed = 31 * i + 7
        side = pattern(base, seed)
        top = (TOPS[name](base, seed) if name in TOPS else painted(base, seed + 3))
        variants = [(name, side, top, en, zh)]
        if name not in FLOORS:
            variants.append((name + '_aged', aged(side, seed), aged(top, seed), 'Weathered ' + en, zh + '（旧）'))
        for n, s, t, e, z in variants:
            side_px = to_rgba(s)
            ld.png(os.path.join(TEXTURES, f'{n}.png'), side_px)
            if name not in FLOORS:
                ld.png(os.path.join(TEXTURES, f'{n}_top.png'), to_rgba(t))
            report.append((n, chroma(average(side_px))))
            model = f'citybuilder:block/wall/{n}'
            if n.split('_aged')[0] in FLOORS:
                # A floor shows its boards on top
                textures = {'all': f'citybuilder:block/wall/{n}'}
                write(os.path.join(MODELS, f'{n}.json'), {'parent': 'minecraft:block/cube_all', 'textures': textures})
            else:
                write(os.path.join(MODELS, f'{n}.json'), {'parent': 'minecraft:block/cube_column', 'textures': {
                    'side': f'citybuilder:block/wall/{n}', 'end': f'citybuilder:block/wall/{n}_top'}})
            write(os.path.join(ld.BLOCKSTATES, f'{n}.json'), {'variants': {'': {'model': model}}})
            write(os.path.join(ld.ITEMS, f'{n}.json'), {'parent': model})
            ld.loot(n)
            names[f'block.citybuilder.{n}'] = (e, z)
            (axe if template in WOOD else pickaxe).append(f'citybuilder:{n}')
    add_tag('mineable/pickaxe', pickaxe)
    add_tag('mineable/axe', axe)
    add_names(names)
    worst = sorted(report, key=lambda r: -r[1])[:8]
    print('highest chroma:', ', '.join(f'{n} {c:.1f}' for n, c in worst))
    # The Java side's list (BuildingBlocks.WALLS): name and the vanilla block it copies
    print('java:', ', '.join(f'"{n}", "{t}"' for n, _, _, t, _ in names_of()))


if __name__ == '__main__':
    main()
