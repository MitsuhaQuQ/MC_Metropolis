"""Draws the 16x16 textures of City Builder's landmark blocks: the all-weather track, pitch turf (two mowing shades),
the dark infield soil of Japanese ballparks, white lines painted on each of them (at the same sixteen angles and eight
offsets as the road lines, so lane lines and pitch circles run on smoothly), stadium seats in six colours, a Fresnel
lens for lighthouses and an LED panel for scoreboards. Run from the repository root:
python tools/textures/landmark_textures.py

The textures are made by this script for this mod (no outside sources), so they carry the mod's own licence.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from road_textures import ANGLES, OFFSETS, OUT, clamp, noise, png  # noqa: E402


def track(x, y):
    """Red-brown polyurethane with rubber granules: fine grain, a few lighter and darker specks."""
    n = noise(x, y, 11) * 8 + noise(x // 2, y // 2, 12) * 4
    s = noise(x, y, 13)
    if s > 0.82:
        n += 20
    elif s < -0.82:
        n -= 18
    return clamp(166 + n), clamp(70 + n * 0.6), clamp(52 + n * 0.5)


def turf(dark):
    """Mown grass: blades as short streaks along the mowing direction (texture v), the darker stripe mown the other
    way."""
    base = (66, 122, 40) if dark else (92, 152, 54)

    def f(x, y):
        n = noise(x, y, 21) * 7 + noise(x, y // 3, 22) * 6
        if noise(x, y, 23) > 0.9:
            n += 14
        return clamp(base[0] + n * 0.6), clamp(base[1] + n), clamp(base[2] + n * 0.5)
    return f


def soil(x, y):
    """The dark, fine infield soil (黒土) with a little sand and pebbles."""
    n = noise(x, y, 31) * 7 + noise(x // 2, y // 2, 32) * 5
    s = noise(x, y, 33)
    if s > 0.86:
        return clamp(150 + n), clamp(132 + n), clamp(108 + n)
    return clamp(84 + n), clamp(62 + n * 0.9), clamp(46 + n * 0.8)


def turf_side(x, y):
    """A sod's edge: grass along the top, soil below."""
    if y < 3 or y == 3 and noise(x, 0, 41) > 0:
        return turf(False)(x, y)
    return soil(x, y)


def marking(base, colour, angle, offset, half):
    """A base texture with a line across it, as core ChunkLayer.mark places it: the line at angle * 180 / ANGLES
    degrees from +x towards +z (u along +x, v along +z), at one of OFFSETS positions measured along its normal
    (-sin, cos) across the block's span that way; {@code half} is half the line's width in pixels."""
    theta = math.pi * angle / ANGLES
    nx, nz = -math.sin(theta), math.cos(theta)
    span = 0.5 * (abs(nx) + abs(nz))
    centre = -span + (offset + 0.5) * 2 * span / OFFSETS
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            px = (x + 0.5) / 16 - 0.5
            pz = (y + 0.5) / 16 - 0.5
            if abs(px * nx + pz * nz - centre) <= half / 16:
                n = noise(x, y, 3) * 5
                row.append(tuple(clamp(c + n) for c in colour))
            else:
                row.append(base(x, y))
        rows.append(row)
    return rows


SEATS = {'blue': (36, 74, 168), 'light_blue': (84, 156, 214), 'red': (184, 40, 42), 'green': (46, 132, 68),
         'orange': (226, 118, 30), 'white': (222, 222, 216)}


def seat(colour):
    """Moulded plastic: lit from above, a darker rim."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            k = 1.12 - 0.022 * y
            if x in (0, 15) or y in (0, 15):
                k -= 0.18
            n = noise(x, y, 51) * 3
            row.append(tuple(clamp(c * k + n) for c in colour))
        rows.append(row)
    return rows


def lens(x, y):
    """A Fresnel lens's glass prisms: concentric rings, pale and bright, round the lamp, in a brass frame."""
    if x in (0, 15) or y in (0, 15):
        n = noise(x, y, 61) * 6
        return clamp(156 + n), clamp(122 + n), clamp(58 + n)
    r = math.hypot(x - 7.5, y - 7.5)
    band = int(r * 1.5) % 2
    glow = max(0.0, 1 - r / 9)
    if band:
        return clamp(214 + 30 * glow), clamp(206 + 30 * glow), clamp(150 + 40 * glow)
    return clamp(250), clamp(246), clamp(206 + 40 * glow)


def led(x, y):
    """A scoreboard's dot matrix: black, a grid of dark diodes, some lit amber."""
    if x % 2 or y % 2:
        return 14, 14, 16
    if noise(x // 2, y // 2, 71) > 0.35:
        return 255, 176, 48
    return 44, 44, 48


def main():
    os.makedirs(os.path.join(OUT, 'line'), exist_ok=True)
    full = lambda f: [[f(x, y) for x in range(16)] for y in range(16)]  # noqa: E731
    png(os.path.join(OUT, 'track.png'), full(track))
    png(os.path.join(OUT, 'turf.png'), full(turf(False)))
    png(os.path.join(OUT, 'turf_dark.png'), full(turf(True)))
    png(os.path.join(OUT, 'turf_side.png'), full(turf_side))
    png(os.path.join(OUT, 'soil.png'), full(soil))
    # Lines: 2 pixels (12.5 cm) wide, as on a track or pitch.
    for name, base in (('track', track), ('turf', turf(False)), ('turf_dark', turf(True)), ('soil', soil)):
        for angle in range(ANGLES):
            for offset in range(OFFSETS):
                png(os.path.join(OUT, 'line', f'{name}_white_{angle}_{offset}.png'),
                    marking(base, (236, 236, 230), angle, offset, 1.0))
    for name, colour in SEATS.items():
        png(os.path.join(OUT, f'seat_{name}.png'), seat(colour))
    png(os.path.join(OUT, 'fresnel_lens.png'), full(lens))
    png(os.path.join(OUT, 'led_panel.png'), full(led))


if __name__ == '__main__':
    main()
