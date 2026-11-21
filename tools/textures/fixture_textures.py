"""Draws the buildings' small fixtures for City Builder and writes their block states, block and item models, loot
tables, names and tool tags. Run from the repository root: python tools/textures/fixture_textures.py

The textures are drawn by this script for this mod (no outside sources), so they carry the mod's own licence.

The blocks (see common's block.BuildingBlocks; core's building.Palette picks them for a building's parts):
- ac_unit_white, ac_unit_beige: an air conditioner's outdoor unit (室外機), 12 x 10 x 5 pixels: a fan grille behind
  the front, louvres round its sides, its pipes out of its right side (seen from the front) in an insulated cover.
  `mount` floor: on two feet (the ground, a balcony), its back to the wall; wall: hung on two steel brackets (under a
  window), the pipes into the wall; roof: on a galvanised stand, the pipes down to the roof.
- vending_red, vending_blue, vending_white: a drinks vending machine (自動販売機), two blocks tall (`half`): rows of
  sample cans behind a lit window, the buttons, a coin and note panel, the drop at its foot.
- water_heater: a gas water heater (給湯器) on a wall (`half` upper) over its pipe cover down to the ground (lower).
- postbox: a red pillar post box (郵便ポスト): two slots, a white plate with the postal mark, on a pedestal.
- downpipe_gray, downpipe_beige, downpipe_brown, downpipe_black: a downpipe (竪樋) a block's width from the wall,
  held off it by a bracket.
- solar_panel: two solar modules on a low frame, half a block tall.
- tv_antenna: a television aerial (八木アンテナ) on a mast: a boom with its elements, a reflector at its back.
- arcade_louvre: a covered street's louvred roof: four slats on edge in the top of the block, along `axis`.
Fixtures carry `facing`: their front (out of the wall their back is to); models are drawn facing north, the wall to
the south.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lantern_door_textures as ld  # noqa: E402  (drawing and file helpers shared with the lamps and doors)

from lantern_door_textures import tint, noise, grid, write, loot, add_names, add_tag  # noqa: E402

MODELS, ITEMS, BLOCKSTATES = ld.MODELS, ld.ITEMS, ld.BLOCKSTATES
FACING_Y = ld.FACING_Y

IVORY = (226, 224, 214)
BEIGE = (214, 204, 182)
STEEL = (150, 154, 156)
DARK = (52, 54, 56)


def tex(name, pixels):
    ld.block_png('fixture/' + name, pixels)


# ---------------------------------------------------------------- drawing

def ac_front(casing):
    # The front face is uv [2, 3, 14, 13] of the texture: 12 x 10. The fan's grille in a circle on the left, a plain
    # panel on the right with a maker's badge; the foot a little shaded
    def f(x, y):
        lx, ly = x - 2, y - 3
        if not (0 <= lx < 12 and 0 <= ly < 10):
            return tint(casing, noise(x, y, 31) * 3)
        dx, dy = lx - 4.5, ly - 4.5
        d = math.hypot(dx, dy)
        if d < 4.3:
            if d < 1.2:
                return tint((170, 172, 172), 0)
            if abs(d - 2.7) < 0.45 or d > 3.85:
                return tint((198, 200, 200), noise(x, y, 32) * 4)
            if lx in (4, 5) or ly in (4, 5):
                return tint((160, 162, 162), 0)
            # The fan's blades behind, swept round
            a = (math.atan2(dy, dx) + d * 0.5) % (2 * math.pi / 3)
            return tint((82, 86, 88) if a < 1.2 else (52, 54, 56), noise(x, y, 33) * 3)
        if lx == 10 and ly == 1:
            return (40, 80, 150, 255)
        if ly == 9:
            return tint(casing, -26)
        if ly == 0:
            return tint(casing, 10)
        return tint(casing, noise(x, y, 34) * 3)
    return f


def louvred(casing):
    # The sides and back: louvres, a darker slit every other row, the casing's edge at top and foot
    def f(x, y):
        if y in (0, 15) or x in (0, 15):
            return tint(casing, -6)
        return tint(casing, (-34 if y % 2 == 1 and 2 <= x <= 13 else 0) + noise(x, y, 35) * 3)
    return f


def taped(x, y):
    # Pipe insulation wound with tape: diagonal bands
    return tint((232, 228, 210), (-18 if (x + y) % 4 == 0 else 0) + noise(x, y, 36) * 3)


def plain(colour, seed, spread=3, edge=0):
    def f(x, y):
        e = edge if x in (0, 15) or y in (0, 15) else 0
        return tint(colour, noise(x, y, seed) * spread + e)
    return f


CAN_COLOURS = [(200, 40, 40), (240, 240, 240), (40, 120, 200), (230, 180, 40), (60, 150, 70), (120, 70, 40),
               (30, 30, 30), (230, 110, 40), (160, 60, 160)]


def can(x, row):
    return CAN_COLOURS[(x * 7 + row * 5) % len(CAN_COLOURS)]


def vending_upper(body):
    # Face uv [1, 3, 15, 16]: the body round a lit window with two rows of cans, their price tags under each row
    def f(x, y):
        if not (2 <= x <= 13 and 4 <= y <= 14):
            return tint(body, noise(x, y, 40) * 3 + (14 if y == 3 else 0))
        if y in (5, 6, 7) and x % 2 == 0 or y in (10, 11, 12) and x % 2 == 0:
            c = can(x, 0 if y < 9 else 1)
            return tint(c, 14 if y in (5, 10) else 0)
        if y in (8, 13):
            return (60, 60, 60, 255) if x % 2 == 0 else (250, 250, 246, 255)
        return tint((250, 250, 244), noise(x, y, 41) * 2)
    return f


def vending_lower(body):
    # Face uv [1, 0, 15, 16]: a third row of cans and the buttons under it, then the coin and note panel on the right,
    # the drop at the foot behind its flap, a dark kick plate
    def f(x, y):
        if 2 <= x <= 13 and y <= 3:
            if y in (0, 1, 2) and x % 2 == 0:
                return tint(can(x, 2), 14 if y == 0 else 0)
            return tint((250, 250, 244), noise(x, y, 42) * 2)
        if 2 <= x <= 13 and y == 4:
            return (70, 150, 230, 255) if x % 2 == 0 else (230, 230, 230, 255)
        if 10 <= x <= 13 and 6 <= y <= 10:
            if x == 11 and y in (7, 8):
                return (20, 20, 20, 255)
            if y == 9 and x in (11, 12, 13):
                return (20, 20, 20, 255)
            return tint((90, 92, 96), noise(x, y, 43) * 2)
        if 3 <= x <= 10 and 11 <= y <= 13:
            return tint((40, 40, 42), 10 if y == 11 else 0)
        if y >= 15:
            return tint(DARK, -10)
        return tint(body, noise(x, y, 44) * 3)
    return f


def vending_side(body):
    # A paler band down the middle of each side, where a maker's name would be
    def f(x, y):
        band = 6 <= x <= 9
        return tint(body, (30 if band and body[0] + body[1] + body[2] < 600 else -14 if band else 0)
                    + noise(x, y, 45) * 3 - (6 if x in (0, 15) else 0))
    return f


def heater_front(x, y):
    # Face uv [4, 3, 12, 13]: a white casing, the exhaust grille near its top, a maker's line and a lamp
    lx, ly = x - 4, y - 3
    if 1 <= lx <= 6 and 1 <= ly <= 3:
        return tint((70, 72, 74), 0) if ly % 2 == 1 else tint((150, 152, 154), 0)
    if ly == 6 and 2 <= lx <= 5:
        return (120, 130, 150, 255)
    if lx == 6 and ly == 8:
        return (80, 200, 90, 255)
    return tint((238, 238, 234), noise(x, y, 50) * 2 - (8 if ly == 9 else 0))


def postbox_front(x, y):
    # Face uv [4, 1, 12, 14]: the slots for letters and for large post under the cap, the white plate with the postal
    # mark (a bar over a T), the collection times' small plate
    lx, ly = x - 4, y - 1
    red = (196, 28, 30)
    if ly == 2 and (1 <= lx <= 2 or 4 <= lx <= 6):
        return (24, 16, 16, 255)
    if ly == 3 and (1 <= lx <= 2 or 4 <= lx <= 6):
        return tint(red, -40)
    if 1 <= lx <= 6 and 4 <= ly <= 9:
        mark = (ly in (5, 7) and 2 <= lx <= 5) or (ly == 8 and lx in (3, 4))
        return tint(red, 0) if mark else (244, 244, 240, 255)
    if 2 <= lx <= 5 and ly == 11:
        return (232, 232, 226, 255)
    return tint(red, noise(x, y, 52) * 4 + (10 if lx == 0 else -10 if lx == 7 else 0))


def solar_top(x, y):
    # Two modules side by side in aluminium frames, their cells in a grid of finer lines; a sheen across them
    if x in (0, 15) or y in (0, 7, 8, 15):
        return tint((190, 194, 198), noise(x, y, 60) * 3)
    if x % 4 == 0 or y % 4 == 3:
        return tint((70, 90, 130), 0)
    sheen = 18 if 3 <= (x + y) % 11 <= 4 else 0
    return tint((30, 44, 84), sheen + noise(x, y, 61) * 4)


def solar_side(x, y):
    if y == 8:
        return tint((190, 194, 198), 0)
    return tint((96, 100, 104), noise(x, y, 62) * 3 - (14 if x % 8 == 0 else 0))


def pipe(colour):
    # A round pipe: lighter down its middle, darker at its sides (each face shows a three-pixel strip)
    def f(x, y):
        shade = {6: -16, 7: 8, 8: 8, 9: -16}.get(x, 0)
        return tint(colour, shade + noise(x, y, 70) * 2)
    return f


DOWNPIPES = {'gray': ((158, 160, 160), 'Grey', '灰'), 'beige': ((210, 198, 170), 'Beige', '米色'),
             'brown': ((104, 74, 52), 'Brown', '褐'), 'black': ((48, 48, 50), 'Black', '黑')}
VENDING = {'red': ((190, 28, 34), 'Red', '红'), 'blue': ((32, 82, 168), 'Blue', '蓝'),
           'white': ((234, 234, 230), 'White', '白')}
AC = {'white': (IVORY, 'White', '白'), 'beige': (BEIGE, 'Beige', '米色')}


def draw():
    for name, (casing, _, _) in AC.items():
        tex(f'ac_front_{name}', grid(ac_front(casing)))
        tex(f'ac_side_{name}', grid(louvred(casing)))
        tex(f'ac_top_{name}', grid(plain(casing, 37, 3, -6)))
    tex('pipe_taped', grid(taped))
    tex('steel', grid(plain(STEEL, 38, 6)))
    tex('rubber', grid(plain((40, 40, 40), 39, 4)))
    for name, (body, _, _) in VENDING.items():
        tex(f'vending_{name}_upper', grid(vending_upper(body)))
        tex(f'vending_{name}_lower', grid(vending_lower(body)))
        tex(f'vending_{name}_side', grid(vending_side(body)))
    tex('vending_top', grid(plain((120, 122, 124), 46, 4, -10)))
    tex('heater_front', grid(heater_front))
    tex('casing_white', grid(plain((236, 236, 232), 51, 2)))
    tex('pipe_cover', grid(lambda x, y: tint((222, 218, 204), noise(x, y, 53) * 2 - (14 if y % 8 == 7 else 0))))
    tex('postbox_front', grid(postbox_front))
    tex('postbox_red', grid(plain((196, 28, 30), 54, 4)))
    tex('postbox_cap', grid(plain((178, 24, 26), 55, 3, -10)))
    tex('solar_top', grid(solar_top))
    tex('solar_side', grid(solar_side))
    for name, (colour, _, _) in DOWNPIPES.items():
        tex(f'pipe_{name}', grid(pipe(colour)))
    tex('aluminium', grid(plain((206, 210, 214), 63, 5)))
    # Street lights: a galvanised pole, a dark housing, the LEDs under it, a lantern's frosted glass
    tex('galvanised', grid(lambda x, y: tint((156, 160, 158), noise(x, y, 65) * 6 + (8 if x % 4 == 1 else 0))))
    tex('housing', grid(plain((70, 74, 78), 66, 3, -8)))
    tex('led', grid(lambda x, y: (255, 255, 250, 255) if 2 <= x <= 13 and 2 <= y <= 13 and (x + y) % 2 == 0
                    else (236, 242, 255, 255) if 1 <= x <= 14 and 1 <= y <= 14 else tint((70, 74, 78), -8)))
    tex('lamp_glass', grid(lambda x, y: tint((255, 246, 222), -8 * (y % 5 == 0) + noise(x, y, 67) * 3)))
    tex('louvre', grid(lambda x, y: tint((214, 216, 218), noise(x, y, 64) * 3 + (14 if y == 0 else -10 if y == 15 else 0))))


# ---------------------------------------------------------------- models

def box(frm, to, faces):
    """A box with faces {face: texture or (texture, uv)}; a face without its own uv shows its own extent."""
    e = ld.element(frm, to, {f: (v if isinstance(v, str) else v[0]) for f, v in faces.items()})
    for f, v in faces.items():
        if not isinstance(v, str):
            e['faces'][f]['uv'] = list(v[1])
    return e


every, sides = ld.every, ld.sides


def model(textures, elements):
    textures = dict(textures)
    return {'parent': 'minecraft:block/block', 'ambientocclusion': False, 'textures': textures, 'elements': elements}


def ac_unit(colour, mount):
    t = {'particle': f'citybuilder:block/fixture/ac_side_{colour}', 'front': f'citybuilder:block/fixture/ac_front_{colour}',
         'side': f'citybuilder:block/fixture/ac_side_{colour}', 'top': f'citybuilder:block/fixture/ac_top_{colour}',
         'pipe': 'citybuilder:block/fixture/pipe_taped', 'steel': 'citybuilder:block/fixture/steel',
         'rubber': 'citybuilder:block/fixture/rubber'}
    front = ('#front', (2, 3, 14, 13))

    def body(y0, z0):
        return box((2, y0, z0), (14, y0 + 10, z0 + 5), {'north': front, 'south': '#side', 'west': '#side',
                                                        'east': '#side', 'up': '#top', 'down': '#top'})

    if mount == 'floor':
        parts = [box((x, 0, 10.5), (x + 2, 1, 14.5), every('#rubber', ('up',))) for x in (3, 11)]
        parts += [body(1, 10),
                  box((0.5, 6, 11.5), (2, 7, 13), every('#pipe', ('east',))),
                  box((0.5, 4, 11.5), (2, 5, 13), every('#pipe', ('east',))),
                  box((0, 0, 13), (2, 9, 16), every('#pipe', ('south',)))]
    elif mount == 'wall':
        parts = [body(4, 9)]
        for x in (3, 12):
            # An angle bracket: its arm under the unit back to the wall, a strut stepping down to a plate on the wall
            parts += [box((x, 3, 9), (x + 1, 4, 16), every('#steel')),
                      box((x, 2, 13), (x + 1, 3, 16), every('#steel', ('up',))),
                      box((x, 1, 14.5), (x + 1, 2, 16), every('#steel', ('up',))),
                      box((x - 0.5, 0, 15.5), (x + 1.5, 4, 16), every('#steel', ('south',)))]
        parts += [box((0.5, 8, 10.5), (2, 9, 12), every('#pipe', ('east',))),
                  box((0.5, 6, 10.5), (2, 7, 12), every('#pipe', ('east',))),
                  box((0, 6, 12), (2, 9, 16), every('#pipe', ('south',)))]
    else:
        parts = [box((x, 0, 4), (x + 2, 3, 14), every('#steel')) for x in (3, 11)]
        parts += [box((2.5, 2, 4), (13.5, 3, 5), every('#steel')), box((2.5, 2, 13), (13.5, 3, 14), every('#steel')),
                  body(3, 6.5),
                  box((0.5, 8, 8), (2, 9, 9.5), every('#pipe', ('east',))),
                  box((0.5, 6, 8), (2, 7, 9.5), every('#pipe', ('east',))),
                  box((0, 0, 9.5), (2, 9, 11), every('#pipe'))]
    return model(t, parts)


def vending(colour, half):
    t = {'particle': f'citybuilder:block/fixture/vending_{colour}_side',
         'front': f'citybuilder:block/fixture/vending_{colour}_{half}',
         'side': f'citybuilder:block/fixture/vending_{colour}_side', 'top': 'citybuilder:block/fixture/vending_top'}
    if half == 'lower':
        return model(t, [box((1, 0, 4), (15, 16, 16), {'north': ('#front', (1, 0, 15, 16)), 'south': '#side',
                                                       'west': '#side', 'east': '#side', 'down': '#top'})])
    return model(t, [box((1, 0, 4), (15, 13, 16), {'north': ('#front', (1, 3, 15, 16)), 'south': '#side',
                                                   'west': '#side', 'east': '#side'}),
                     box((0.5, 13, 3.5), (15.5, 14, 16), every('#top'))])


def water_heater(half):
    t = {'particle': 'citybuilder:block/fixture/casing_white', 'front': 'citybuilder:block/fixture/heater_front',
         'casing': 'citybuilder:block/fixture/casing_white', 'cover': 'citybuilder:block/fixture/pipe_cover'}
    cover = every('#cover', ('south',))
    if half == 'lower':
        return model(t, [box((5, 0, 13), (11, 16, 16), {f: v for f, v in cover.items() if f not in ('up', 'down')})])
    return model(t, [box((5, 0, 13), (11, 3, 16), {f: v for f, v in cover.items() if f != 'up'}),
                     box((4, 3, 12), (12, 13, 16), {'north': ('#front', (4, 3, 12, 13)), 'west': '#casing',
                                                    'east': '#casing', 'up': '#casing', 'down': '#casing'})])


def postbox():
    t = {'particle': 'citybuilder:block/fixture/postbox_red', 'front': 'citybuilder:block/fixture/postbox_front',
         'red': 'citybuilder:block/fixture/postbox_red', 'cap': 'citybuilder:block/fixture/postbox_cap'}
    return model(t, [box((5, 0, 5), (11, 2, 11), every('#cap', ('up',))),
                     box((4, 2, 4), (12, 15, 12), {'north': ('#front', (4, 1, 12, 14)), 'south': '#red',
                                                   'west': '#red', 'east': '#red'}),
                     box((3.5, 15, 3.5), (12.5, 16, 12.5), every('#cap'))])


def downpipe(colour):
    t = {'particle': f'citybuilder:block/fixture/pipe_{colour}', 'pipe': f'citybuilder:block/fixture/pipe_{colour}',
         'steel': 'citybuilder:block/fixture/steel'}
    return model(t, [box((6.5, 0, 12), (9.5, 16, 15), {'north': ('#pipe', (6.5, 0, 9.5, 16)),
                                                       'south': ('#pipe', (6.5, 0, 9.5, 16)),
                                                       'west': ('#pipe', (6.5, 0, 9.5, 16)),
                                                       'east': ('#pipe', (6.5, 0, 9.5, 16)),
                                                       'up': '#pipe', 'down': '#pipe'}),
                     box((6, 10, 11.5), (10, 11, 15.5), every('#steel')),
                     box((7.5, 10, 15.5), (8.5, 11, 16), every('#steel', ('south',)))])


def solar_panel():
    t = {'particle': 'citybuilder:block/fixture/solar_top', 'top': 'citybuilder:block/fixture/solar_top',
         'side': 'citybuilder:block/fixture/solar_side'}
    return model(t, [box((0, 0, 0), (16, 8, 16), {'up': '#top', 'down': '#side', 'north': '#side', 'south': '#side',
                                                  'west': '#side', 'east': '#side'})])


def tv_antenna():
    t = {'particle': 'citybuilder:block/fixture/aluminium', 'al': 'citybuilder:block/fixture/aluminium'}
    parts = [box((7.5, 0, 7.5), (8.5, 16, 8.5), every('#al')),
             box((7.5, 12, 1), (8.5, 13, 15), every('#al')),
             box((7, 11, 7), (9, 13, 9), every('#al'))]
    # The directors, longest at the back; the dipole; the reflector's rods behind
    for z, half in ((2, 3.5), (4.5, 4), (7, 4.5), (9.5, 5), (12, 6)):
        parts.append(box((8 - half, 12.25, z), (8 + half, 12.75, z + 0.5), every('#al')))
    for y in (10, 11.5, 13, 14.5):
        parts.append(box((1.5, y, 14), (14.5, y + 0.5, 14.5), every('#al')))
    parts.append(box((7.5, 10, 14), (8.5, 15, 14.5), every('#al')))
    return model(t, parts)


def louvre():
    t = {'particle': 'citybuilder:block/fixture/louvre', 'slat': 'citybuilder:block/fixture/louvre'}
    # Slats along x (axis x), each a pixel thick and six tall, four across the block
    return model(t, [box((0, 10, z), (16, 16, z + 1), every('#slat')) for z in (1.5, 5.5, 9.5, 13.5)])


def street_light_pole():
    t = {'particle': 'citybuilder:block/fixture/galvanised', 'pole': 'citybuilder:block/fixture/galvanised'}
    return model(t, [box((6, 0, 6), (10, 16, 10), {f: ('#pole', (6, 0, 10, 16)) if f not in ('up', 'down') else '#pole'
                                                  for f in ('north', 'south', 'west', 'east', 'up', 'down')})])


def street_light_head(arms):
    # The pole's top, an arm out of it to the north (facing) rising a little and reaching a block and a half over the
    # road, the flat luminaire under its end, the LEDs on its underside; a second arm to the south for a median's light
    t = {'particle': 'citybuilder:block/fixture/galvanised', 'pole': 'citybuilder:block/fixture/galvanised',
         'housing': 'citybuilder:block/fixture/housing', 'led': 'citybuilder:block/fixture/led'}
    parts = [box((6, 0, 6), (10, 9, 10), {f: '#pole' for f in ('north', 'south', 'west', 'east', 'up', 'down')}),
             box((5.5, 9, 5.5), (10.5, 10, 10.5), every('#pole'))]
    for side in range(arms):
        def z(a, b):
            # The north arm's z range; mirrored for the south arm
            return (a, b) if side == 0 else (16 - b, 16 - a)
        z0, z1 = z(-8, 7)
        parts.append(box((7, 7, z0), (9, 9, z1), every('#pole')))
        z0, z1 = z(-16, -6)
        parts.append(box((4.5, 5, z0), (11.5, 7, z1), {'down': ('#led', (0, 0, 16, 16)), 'up': '#housing',
                                                     'north': '#housing', 'south': '#housing', 'west': '#housing',
                                                     'east': '#housing'}))
    return model(t, parts)


def street_light_lamp():
    # A short post top and a lantern on it: a frosted glass body under a dark cap, a finial
    t = {'particle': 'citybuilder:block/fixture/housing', 'pole': 'citybuilder:block/fixture/galvanised',
         'housing': 'citybuilder:block/fixture/housing', 'glass': 'citybuilder:block/fixture/lamp_glass'}
    return model(t, [box((6, 0, 6), (10, 4, 10), sides('#pole')),
                     box((5, 4, 5), (11, 5, 11), every('#housing')),
                     box((5.5, 5, 5.5), (10.5, 12, 10.5), {**sides('#glass'), 'down': '#housing'}),
                     box((4, 12, 4), (12, 13.5, 12), every('#housing')),
                     box((6, 13.5, 6), (10, 14.5, 10), every('#housing')),
                     box((7.5, 14.5, 7.5), (8.5, 16, 8.5), every('#housing'))])


# ---------------------------------------------------------------- files

def facing_states(name, extra=None):
    """Variants for every facing (and each value of one more property, {property: {value: model}})."""
    variants = {}
    if extra is None:
        extra = {None: {None: f'citybuilder:block/fixture/{name}'}}
    for prop, values in extra.items():
        for value, m in values.items():
            for f, y in FACING_Y.items():
                key = f'facing={f}' + (f',{prop}={value}' if prop else '')
                variants[key] = {'model': m, 'y': y} if y else {'model': m}
    return {'variants': variants}


def main():
    draw()
    names = {}
    pickaxe = []

    def block(name, en, zh, states, item_model):
        write(os.path.join(BLOCKSTATES, f'{name}.json'), states)
        write(os.path.join(ITEMS, f'{name}.json'), {'parent': item_model})
        names[name] = (en, zh)
        pickaxe.append(f'citybuilder:{name}')

    def fixture_model(name, m):
        write(os.path.join(MODELS, 'fixture', f'{name}.json'), m)
        return f'citybuilder:block/fixture/{name}'

    for colour, (_, en, zh) in AC.items():
        models = {mount: fixture_model(f'ac_unit_{colour}_{mount}', ac_unit(colour, mount))
                  for mount in ('floor', 'wall', 'roof')}
        block(f'ac_unit_{colour}', f'Air Conditioner Outdoor Unit ({en})', f'空调室外机（{zh}）',
              facing_states(None, {'mount': models}), models['floor'])
        loot(f'ac_unit_{colour}')
    for colour, (_, en, zh) in VENDING.items():
        models = {half: fixture_model(f'vending_{colour}_{half}', vending(colour, half)) for half in ('lower', 'upper')}
        block(f'vending_{colour}', f'Vending Machine ({en})', f'自动售货机（{zh}）',
              facing_states(None, {'half': models}), models['lower'])
        loot(f'vending_{colour}', door=True)
    models = {half: fixture_model(f'water_heater_{half}', water_heater(half)) for half in ('lower', 'upper')}
    block('water_heater', 'Water Heater', '燃气热水器', facing_states(None, {'half': models}), models['upper'])
    loot('water_heater', door=True)
    m = fixture_model('postbox', postbox())
    block('postbox', 'Post Box', '邮筒', facing_states(None, {None: {None: m}}), m)
    loot('postbox')
    for colour, (_, en, zh) in DOWNPIPES.items():
        m = fixture_model(f'downpipe_{colour}', downpipe(colour))
        block(f'downpipe_{colour}', f'Downpipe ({en})', f'落水管（{zh}）', facing_states(None, {None: {None: m}}), m)
        loot(f'downpipe_{colour}')
    m = fixture_model('solar_panel', solar_panel())
    block('solar_panel', 'Solar Panel', '太阳能板', facing_states(None, {None: {None: m}}), m)
    loot('solar_panel')
    m = fixture_model('tv_antenna', tv_antenna())
    block('tv_antenna', 'TV Antenna', '电视天线', facing_states(None, {None: {None: m}}), m)
    loot('tv_antenna')
    m = fixture_model('arcade_louvre', louvre())
    block('arcade_louvre', 'Arcade Louvre', '拱廊百叶顶', {'variants': {'axis=x': {'model': m},
                                                                     'axis=z': {'model': m, 'y': 90}}}, m)
    loot('arcade_louvre')
    m = fixture_model('street_light_pole', street_light_pole())
    block('street_light_pole', 'Street Light Pole', '路灯杆', {'variants': {'': {'model': m}}}, m)
    loot('street_light_pole')
    for name, arms, en, zh in (('street_light_head', 1, 'Street Light Head', '路灯灯头'),
                               ('street_light_head_double', 2, 'Street Light Head (Two Arms)', '路灯灯头（双臂）')):
        m = fixture_model(name, street_light_head(arms))
        block(name, en, zh, facing_states(None, {None: {None: m}}), m)
        loot(name)
    m = fixture_model('street_light_lamp', street_light_lamp())
    block('street_light_lamp', 'Lane Light', '小路灯', facing_states(None, {None: {None: m}}), m)
    loot('street_light_lamp')
    add_names(names)
    add_tag('mineable/pickaxe', pickaxe)


if __name__ == '__main__':
    main()
