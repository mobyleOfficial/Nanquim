#!/usr/bin/env python3
"""Paints the store showcase artworks with the app's own brushes and commands, through the control
channel of a developer build on a device (same approach as tools/samples/make_samples.py).
Every stroke is computed here from fixed seeds, so the artworks are the project's own work.

    python tools/store/paint_showcase.py --device <serial> [--only key]

The artworks stay in the device's gallery so the store screenshots can be taken from them.
"""

import argparse
import math
import os
import random
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "device"))
import kill_test  # noqa: E402

PACKAGE = os.environ.get("NANQUIM_PACKAGE", "com.mobyle.nanquim.dev")
kill_test.PACKAGE = PACKAGE
kill_test.ACTIVITY = f"{PACKAGE}/com.mobyle.nanquim.MainActivity"
from kill_test import adb, launch, wait_for_artwork  # noqa: E402

B = {
    "hard": "7aa95fc4-74ed-5b42-885c-1d10a7444c90",
    "soft_air": "2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a31",
    "hard_air": "2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a32",
    "soft_blend": "1a252e5f-736e-5ee0-97d4-ad1fcfb8ca02",
    "watercolor": "352e8a06-2dfc-53cc-a17f-e7fe72dc956e",
    "gouache": "e8f4e036-c716-5ea5-a31b-1327968ee84f",
    "oil": "458c284f-12fa-570c-8bf1-4f8c9c046520",
    "studio_pen": "4125df6c-df5c-5043-8bb5-d46865d137aa",
    "ink_bleed": "a6685b2c-ec13-5d96-a403-9993c0de2af0",
    "dry_ink": "aaaba672-6015-512b-98d8-721ed0b10a0d",
    "bold_ink": "7978022a-44a6-5e13-b1cc-e5ec8245475a",
    "tech_pen": "2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a11",
    "pencil_6b": "2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a02",
    "clouds": "3d311ae8-00c6-53bb-becd-c38cf48be789",
    "nebula": "c4f0cebb-7911-57aa-a30d-db6fd1cd26b6",
    "bokeh": "23147a7f-4e81-590c-bc78-9f88cf6a81c4",
    "glimmer": "c7affa05-57d2-5b0d-a7b3-7a154592837c",
    "lightbrush": "1653096b-cebc-54d4-9fdc-6c858a65ed37",
    "lightpen": "966ab0fc-265c-563a-9b63-a836b6a3e101",
    "flare": "8802fa61-d71b-56ce-8a55-93d6eda512a5",
    "wildgrass": "1f491c7e-fd81-51fc-a566-e67e78f1cfa5",
}


class Painter:
    def __init__(self, channel):
        self.channel = channel

    def run(self, command, arguments=None):
        return self.channel.execute(command, arguments or {})

    def brush(self, key, size, color, opacity=1.0):
        self.run("brush.select", {"brushId": B[key]})
        self.run("brush.setSize", {"sizePx": size})
        self.run("brush.setOpacity", {"opacity": opacity})
        self.color(color)

    def color(self, color):
        red, green, blue = color
        self.run("color.setPrimary", {"red": red, "green": green, "blue": blue})

    def stroke(self, points, pressure=1.0):
        samples = []
        for index, (x, y) in enumerate(points):
            value = pressure[index] if isinstance(pressure, list) else pressure
            samples.append([round(x, 2), round(y, 2), max(0.02, min(1.0, value)), index * 8])
        if len(samples) == 1:
            samples.append([samples[0][0] + 0.5, samples[0][1], samples[0][2], 8])
        self.run("tool.paint.strokePath", {"samples": samples})

    def dab(self, x, y, coats=1, pressure=1.0):
        for _ in range(coats):
            self.stroke([(x, y)], pressure)

    def layer(self, name, blend=None, opacity=None):
        layer_id = self.run("layer.new")["layerId"]
        self.run("layer.rename", {"layerId": layer_id, "name": name})
        if blend:
            self.run("layer.setBlend", {"layerId": layer_id, "blend": blend})
        if opacity is not None:
            self.run("layer.setOpacity", {"layerId": layer_id, "opacity": opacity})
        return layer_id


def line(start, end, steps):
    (x0, y0), (x1, y1) = start, end
    return [(x0 + (x1 - x0) * t / steps, y0 + (y1 - y0) * t / steps) for t in range(steps + 1)]


def circle(cx, cy, r, steps=40):
    return [(cx + r * math.cos(a / steps * 2 * math.pi), cy + r * math.sin(a / steps * 2 * math.pi)) for a in range(steps + 1)]


def taper(n, start=0.35, peak=1.0, end=0.15):
    out = []
    for i in range(n):
        t = i / max(1, n - 1)
        rise = min(1.0, t / 0.15)
        fall = 1.0 - t
        out.append(start + (peak - start) * rise if t < 0.15 else end + (peak - end) * fall ** 0.6)
    return out


def fill_under(painter, width, height, curve, step=24, bottom=None):
    bottom = height + 40 if bottom is None else bottom
    for x in range(-step, width + step, step):
        painter.stroke(line((x, curve(x)), (x, bottom), 12))


def ridge(seed, base, amp):
    rnd = random.Random(seed)
    waves = [(rnd.uniform(90, 420), rnd.uniform(0, 6.28), rnd.uniform(0.3, 1.0)) for _ in range(5)]
    norm = sum(w[2] for w in waves)

    def f(x):
        v = 0.0
        for period, phase, weight in waves:
            v += weight * (1 - abs(math.sin(x / period + phase)))
        return base - amp * v / norm
    return f


# ---------------------------------------------------------------- 1. Sumi-e cherry blossom
def solid_under(p, w, h, curve, size=90, bottom=None):
    """Fills under a curve with horizontal hard strokes (no vertical banding)."""
    bottom = h + 60 if bottom is None else bottom
    top = min(curve(x) for x in range(0, w + 1, 10))
    y = top
    while y < bottom:
        xs = [x for x in range(-60, w + 61, 12) if curve(x) <= y]
        if xs:
            runs, run = [], [xs[0]]
            for x in xs[1:]:
                if x - run[-1] > 12:
                    runs.append(run)
                    run = [x]
                else:
                    run.append(x)
            runs.append(run)
            for r in runs:
                p.stroke(line((r[0], y), (r[-1], y), max(2, (r[-1] - r[0]) // 60)))
        y += size * 0.45


def cherry(p, w, h):
    rnd = random.Random(7)
    p.run("layer.rename", {"layerId": 1, "name": "Papel"})
    p.run("layer.fill", {"layerId": 1, "color": [0.965, 0.935, 0.875, 1.0]})
    p.run("layer.select", {"layerId": 1})
    p.brush("soft_air", 1000, (0.88, 0.80, 0.68), opacity=0.30)
    for corner in ((0, 0), (w, 0), (0, h), (w, h)):
        p.dab(*corner)

    sun = (w * 0.68, h * 0.36)
    p.layer("Halo", opacity=0.55)
    p.brush("soft_air", 900, (0.98, 0.70, 0.55), opacity=0.6)
    p.dab(*sun)
    p.layer("Sol")
    p.brush("hard", 500, (0.84, 0.22, 0.17))
    p.dab(*sun, coats=2)

    for i, (base, amp, tone, op) in enumerate(((h * 0.80, 330, 0.72, 0.35), (h * 0.92, 240, 0.55, 0.45))):
        p.layer(f"Montanha {i + 1}", blend="multiply", opacity=op)
        p.brush("hard", 60, (tone, tone + 0.03, tone + 0.08))
        fill_under(p, w, h, ridge(30 + i, base, amp))
        p.brush("soft_air", 700, (0.965, 0.935, 0.875), opacity=0.8)
        p.stroke(line((-300, base + 40), (w + 300, base + 40), 30))

    p.layer("Galhos")
    tips = []
    tree = random.Random(8)

    def branch(x, y, a, length, size, depth, target):
        pts = [(x, y)]
        steps = max(8, int(length / 18))
        for _ in range(steps):
            a += 0.12 * (target - a) + tree.uniform(-0.10, 0.10)
            x += math.cos(a) * length / steps
            y += math.sin(a) * length / steps
            pts.append((x, y))
        p.brush("studio_pen", size, (0.08, 0.06, 0.07))
        p.stroke(pts, [1.0 - 0.42 * i / (len(pts) - 1) for i in range(len(pts))])
        if size > 30:
            p.brush("dry_ink", size * 0.45, (0.25, 0.20, 0.19), opacity=0.6)
            p.stroke([(px, py - size * 0.15) for px, py in pts], 0.8)
        if depth == 0 or size < 5:
            tips.append((x, y))
            return
        n = tree.choice((2, 2, 3))
        for c in range(n):
            na = a + (c - (n - 1) / 2) * tree.uniform(0.5, 0.8)
            branch(x, y, na, length * tree.uniform(0.62, 0.8), size * 0.58, depth - 1, max(-2.6, min(0.4, na)))
        if size > 12 and tree.random() < 0.7:
            k = tree.randint(len(pts) // 3, 2 * len(pts) // 3)
            mx, my = pts[k]
            na = a + tree.choice((-1, 1)) * tree.uniform(0.7, 1.1)
            branch(mx, my, na, length * 0.5, size * 0.45, depth - 2, max(-2.6, min(0.5, na)))

    branch(-40, h * 1.02, -1.05, 560, 120, 6, -0.75)

    p.layer("Brilho das flores", opacity=0.5)
    for tx, ty in tips:
        p.brush("soft_air", rnd.uniform(140, 220), (0.98, 0.70, 0.78), opacity=0.35)
        p.dab(tx, ty)
    p.layer("Flores")
    pinks = [(0.98, 0.66, 0.74), (0.95, 0.55, 0.66), (1.0, 0.80, 0.85), (0.92, 0.45, 0.58), (1.0, 0.90, 0.92)]
    centers = []
    for tx, ty in tips:
        for _ in range(rnd.randint(1, 3)):
            cx, cy = tx + rnd.gauss(0, 30), ty + rnd.gauss(0, 26)
            r = rnd.uniform(11, 19)
            color = rnd.choice(pinks)
            p.brush("hard", r * 1.25, color, opacity=0.95)
            rot = rnd.uniform(0, 6.28)
            for k in range(5):
                ang = rot + k * 2 * math.pi / 5
                p.dab(cx + math.cos(ang) * r * 0.75, cy + math.sin(ang) * r * 0.75)
            centers.append((cx, cy, r))
    p.layer("Miolos")
    for cx, cy, r in centers:
        p.brush("hard", r * 0.55, (0.98, 0.88, 0.55))
        p.dab(cx, cy)
        p.brush("hard", 3.5, (0.62, 0.12, 0.22))
        for k in range(4):
            ang = k * 1.57 + 0.4
            p.dab(cx + math.cos(ang) * r * 0.45, cy + math.sin(ang) * r * 0.45)

    p.layer("Pétalas ao vento")
    for _ in range(45):
        x, y = rnd.uniform(w * 0.25, w * 0.98), rnd.uniform(h * 0.15, h * 0.95)
        p.brush("hard", rnd.uniform(9, 15), rnd.choice(pinks), opacity=0.9)
        ang = rnd.uniform(0, 6.28)
        p.stroke([(x, y), (x + 10 * math.cos(ang), y + 10 * math.sin(ang))], [0.5, 1.0])

    p.layer("Pássaros")
    p.brush("studio_pen", 7, (0.1, 0.09, 0.1))
    for bx, by, s in ((w * 0.47, h * 0.16, 1.0), (w * 0.52, h * 0.11, 0.8), (w * 0.44, h * 0.09, 0.65)):
        p.stroke([(bx - 34 * s, by - 6 * s), (bx - 16 * s, by - 14 * s), (bx, by)], [0.2, 0.9, 0.6])
        p.stroke([(bx, by), (bx + 18 * s, by - 16 * s), (bx + 38 * s, by - 10 * s)], [0.6, 0.9, 0.2])

    p.layer("Selo")
    seal = (w * 0.91, h * 0.78)
    p.brush("hard", 22, (0.76, 0.14, 0.13))
    for dy in range(-38, 42, 8):
        p.stroke(line((seal[0] - 38, seal[1] + dy), (seal[0] + 38, seal[1] + dy), 8))
    p.brush("studio_pen", 8, (0.97, 0.92, 0.85))
    p.stroke(line((seal[0] - 20, seal[1] - 20), (seal[0] + 20, seal[1] - 20), 6))
    p.stroke(line((seal[0], seal[1] - 20), (seal[0] - 6, seal[1] + 26), 6))
    p.stroke(line((seal[0] - 20, seal[1] + 2), (seal[0] + 20, seal[1] + 2), 6))


# ---------------------------------------------------------------- 2. Sunset mountains
def sunset(p, w, h):
    rnd = random.Random(11)
    horizon = h * 0.66
    p.run("layer.rename", {"layerId": 1, "name": "Céu"})
    p.run("layer.fill", {"layerId": 1, "color": [0.17, 0.10, 0.32, 1.0]})
    p.run("layer.select", {"layerId": 1})
    for y, size, color, op in ((0.35, 1100, (0.55, 0.20, 0.52), 0.7), (0.52, 900, (0.93, 0.36, 0.40), 0.7),
                               (0.62, 700, (0.99, 0.62, 0.30), 0.8), (0.68, 420, (1.0, 0.85, 0.50), 0.8)):
        p.brush("soft_air", size, color, opacity=op)
        p.stroke(line((-300, h * y), (w + 300, h * y), 40))

    sun = (w * 0.58, horizon - 230)
    p.layer("Brilho do sol", blend="screen")
    p.brush("soft_air", 900, (1.0, 0.62, 0.30), opacity=0.8)
    p.dab(*sun)
    p.brush("hard", 230, (1.0, 0.93, 0.74))
    p.dab(*sun, coats=3)

    p.layer("Nuvens", opacity=0.85)
    for _ in range(9):
        y = rnd.uniform(h * 0.12, h * 0.45)
        x = rnd.uniform(-100, w)
        p.brush("clouds", rnd.uniform(160, 260), rnd.choice(((1.0, 0.70, 0.62), (0.98, 0.55, 0.62), (1.0, 0.85, 0.62))), opacity=0.9)
        p.stroke(line((x, y), (x + rnd.uniform(300, 700), y + rnd.uniform(-30, 30)), 14), 0.8)

    ranges = [((0.55, 0.30, 0.52), horizon - 30, 330), ((0.40, 0.22, 0.45), horizon + 10, 250),
              ((0.26, 0.15, 0.35), horizon + 40, 170), ((0.14, 0.08, 0.22), horizon + 70, 110)]
    for i, (color, base, amp) in enumerate(ranges):
        p.layer(f"Montanhas {i + 1}")
        p.brush("hard", 60, color)
        fill_under(p, w, h, ridge(100 + i, base, amp), bottom=horizon + 120)
        p.layer(f"Névoa {i + 1}", blend="screen", opacity=0.45)
        p.brush("soft_air", 380, (0.95, 0.55, 0.45), opacity=0.5)
        p.stroke(line((-200, base + 60), (w + 200, base + 60), 30))

    p.layer("Lago")
    p.brush("hard", 70, (0.20, 0.10, 0.28))
    fill_under(p, w, h, lambda x: horizon + 110, step=40)
    p.layer("Reflexo", blend="screen")
    for i in range(26):
        y = horizon + 130 + i * 18
        half = 230 - i * 6
        for _ in range(3):
            a = sun[0] + rnd.uniform(-half, half * 0.6)
            p.brush("hard_air", rnd.uniform(5, 11), (1.0, 0.78, 0.50), opacity=0.85 - i * 0.025)
            p.stroke(line((a, y + rnd.uniform(-4, 4)), (a + rnd.uniform(40, half), y), 8), 0.8)

    p.layer("Pinheiros")
    p.brush("hard", 10, (0.05, 0.03, 0.08))
    for k in range(18):
        x = (k / 17) ** 1.4 * w * 0.42 if k < 12 else w * 0.78 + (k - 12) * 90
        base = horizon + 125 + rnd.uniform(-8, 8)
        tall = rnd.uniform(160, 300) if k < 12 else rnd.uniform(110, 200)
        p.brush("hard", 10, (0.05, 0.03, 0.08))
        p.stroke(line((x, base), (x, base - tall), 10))
        for j in range(16):
            t = j / 16
            yy = base - tall * (0.15 + 0.85 * t)
            span = (1 - t) * tall * 0.28 + 6
            p.brush("hard", 9 + (1 - t) * 6, (0.05, 0.03, 0.08))
            p.stroke([(x - span, yy + span * 0.35), (x, yy), (x + span, yy + span * 0.35)], 1.0)

    p.layer("Pássaros")
    p.brush("studio_pen", 5, (0.10, 0.05, 0.12))
    for _ in range(7):
        bx, by, s = rnd.uniform(w * 0.25, w * 0.5), rnd.uniform(h * 0.2, h * 0.35), rnd.uniform(0.5, 1.0)
        p.stroke([(bx - 26 * s, by - 6 * s), (bx - 12 * s, by - 10 * s), (bx, by)], [0.3, 0.9, 0.5])
        p.stroke([(bx, by), (bx + 13 * s, by - 12 * s), (bx + 28 * s, by - 8 * s)], [0.5, 0.9, 0.3])


# ---------------------------------------------------------------- 3. Aurora night
def aurora(p, w, h):
    rnd = random.Random(23)
    p.run("layer.rename", {"layerId": 1, "name": "Céu noturno"})
    p.run("layer.fill", {"layerId": 1, "color": [0.03, 0.05, 0.13, 1.0]})
    p.run("layer.select", {"layerId": 1})
    p.brush("soft_air", 1300, (0.06, 0.20, 0.32), opacity=0.8)
    p.stroke(line((-300, h * 0.78), (w + 300, h * 0.78), 40))
    p.brush("soft_air", 900, (0.22, 0.10, 0.35), opacity=0.6)
    p.stroke(line((-300, h * 0.05), (w + 300, h * 0.10), 40))

    p.layer("Estrelas")
    for _ in range(320):
        p.brush("hard_air", rnd.uniform(2.0, 5.5), (1.0, 0.97, 0.92), opacity=rnd.uniform(0.35, 1.0))
        p.dab(rnd.uniform(0, w), rnd.uniform(0, h * 0.75))
    p.layer("Estrelas brilhantes", blend="screen")
    for _ in range(14):
        x, y = rnd.uniform(0, w), rnd.uniform(0, h * 0.5)
        p.brush("soft_air", 40, (0.8, 0.85, 1.0), opacity=0.7)
        p.dab(x, y)
        p.brush("hard_air", 7, (1.0, 1.0, 1.0))
        p.dab(x, y)

    p.layer("Aurora", blend="screen")
    for band, (base, color, height) in enumerate(((0.42, (0.20, 0.95, 0.60), 520), (0.34, (0.25, 0.80, 0.90), 420), (0.50, (0.55, 0.95, 0.45), 300))):
        phase = rnd.uniform(0, 6)
        for x in range(-100, w + 100, 22):
            y = h * base + 140 * math.sin(x / 420 + phase) + 45 * math.sin(x / 150 + phase * 2)
            tall = height * (0.6 + 0.4 * math.sin(x / 230 + phase) ** 2) * rnd.uniform(0.8, 1.1)
            p.brush("soft_air", 60, color, opacity=0.22)
            p.stroke(line((x, y), (x + 20, y - tall), 12), [1.0 - 0.95 * i / 12 for i in range(13)])
        p.brush("soft_air", 40, (0.75, 1.0, 0.85), opacity=0.35)
        p.stroke([(x, h * base + 140 * math.sin(x / 420 + phase) + 45 * math.sin(x / 150 + phase * 2)) for x in range(-100, w + 100, 30)], 0.8)
    p.layer("Rosa da aurora", blend="screen", opacity=0.6)
    p.brush("soft_air", 380, (0.70, 0.25, 0.75), opacity=0.4)
    p.stroke(line((-200, h * 0.10), (w + 200, h * 0.16), 30))

    f = ridge(301, h * 0.80, 420)
    p.layer("Montanhas")
    p.brush("hard", 60, (0.02, 0.04, 0.09))
    fill_under(p, w, h, f)
    p.layer("Luz nas encostas", blend="screen", opacity=0.5)
    p.brush("soft_air", 160, (0.15, 0.45, 0.40), opacity=0.35)
    p.stroke([(x, f(x) + 40) for x in range(0, w + 1, 40)], 0.8)
    p.layer("Pinheiros")
    for k in range(40):
        x = rnd.uniform(0, w)
        base = h * 0.93 + rnd.uniform(-10, 10)
        tall = rnd.uniform(80, 200)
        for j in range(12):
            t = j / 12
            yy = base - tall * (0.1 + 0.9 * t)
            span = (1 - t) * tall * 0.26 + 4
            p.brush("hard", 8 + (1 - t) * 5, (0.01, 0.02, 0.04))
            p.stroke([(x - span, yy + span * 0.4), (x, yy), (x + span, yy + span * 0.4)], 1.0)
        p.stroke(line((x, base + 30), (x, base - tall), 6))
    p.brush("hard", 60, (0.01, 0.02, 0.04))
    fill_under(p, w, h, lambda x: h * 0.94 + 10 * math.sin(x / 90), step=30)


# ---------------------------------------------------------------- 4. Paper plane animation
def plane(p, w, h):
    rnd = random.Random(5)
    p.run("animation.setEnabled", {"enabled": True})
    p.run("animation.setSettings", {"playback": "loop", "framesPerSecond": 12, "firstFrameIsBackground": True, "onionEnabled": True})
    p.run("layer.rename", {"layerId": 1, "name": "Fundo"})
    p.run("layer.select", {"layerId": 1})
    p.run("layer.fill", {"layerId": 1, "color": [0.56, 0.78, 0.95, 1.0]})
    p.brush("soft_air", 900, (0.95, 0.88, 0.80), opacity=0.6)
    p.stroke(line((-200, h * 0.95), (w + 200, h * 0.95), 30))
    for _ in range(6):
        x, y = rnd.uniform(0, w), rnd.uniform(h * 0.1, h * 0.8)
        p.brush("clouds", rnd.uniform(160, 240), (1.0, 1.0, 1.0), opacity=0.9)
        p.stroke(line((x, y), (x + rnd.uniform(250, 500), y + rnd.uniform(-20, 20)), 12), 0.9)

    frames = 10
    for frame in range(frames):
        p.run("animation.addFrame")
        state = p.channel.call("getState")["document"]
        p.run("layer.rename", {"layerId": state["activeLayerId"], "name": f"Avião {frame + 1}"})
        t = frame / (frames - 1)
        x = w * (0.10 + 0.78 * t)
        y = h * (0.62 - 0.30 * math.sin(math.pi * t * 1.1)) + 30 * math.sin(t * 9)
        ang = math.atan2(-0.30 * h * math.pi * 1.1 * math.cos(math.pi * t * 1.1), 0.78 * w) - 0.1
        s = 1.6

        def pt(dx, dy):
            return (x + (dx * math.cos(ang) - dy * math.sin(ang)) * s, y + (dx * math.sin(ang) + dy * math.cos(ang)) * s)
        nose, tail_top, tail_low, fold = pt(90, 0), pt(-70, -42), pt(-70, 34), pt(-52, 6)
        p.brush("hard", 26, (1.0, 1.0, 1.0))
        for k in range(26):
            a = k / 25
            p.stroke(line((nose[0] + (tail_top[0] - nose[0]) * a, nose[1] + (tail_top[1] - nose[1]) * a), fold, 8))
        p.brush("hard", 26, (0.80, 0.85, 0.93))
        for k in range(26):
            a = k / 25
            p.stroke(line((nose[0] + (tail_low[0] - nose[0]) * a, nose[1] + (tail_low[1] - nose[1]) * a), fold, 8))
        p.brush("studio_pen", 6, (0.15, 0.2, 0.3))
        p.stroke([nose, tail_top, fold, nose], 0.9)
        p.stroke([nose, tail_low, fold], 0.9)
        trail = [pt(-80 - 30 * k, 4 + 6 * math.sin(k)) for k in range(6)]
        p.brush("hard_air", 6, (1.0, 1.0, 1.0), opacity=0.7)
        p.stroke(trail, 0.5)


# ---------------------------------------------------------------- 5. Symmetry mandala
def mandala(p, w, h):
    rnd = random.Random(31)
    cx, cy = w / 2, h / 2
    p.run("layer.rename", {"layerId": 1, "name": "Fundo"})
    p.run("layer.fill", {"layerId": 1, "color": [0.07, 0.05, 0.12, 1.0]})
    p.run("layer.select", {"layerId": 1})
    p.brush("soft_air", 1400, (0.28, 0.12, 0.38), opacity=0.7)
    p.dab(cx, cy)
    p.run("guide.set", {"type": "symmetry", "visible": True, "symmetry": "radial", "segments": 12, "rotational": True,
                        "centerX": cx, "centerY": cy, "hue": 190, "opacity": 0.6})
    layer = p.layer("Mandala")
    p.run("layer.setAssisted", {"layerId": layer, "enabled": True})

    def polar(r, a):
        return (cx + r * math.cos(a), cy + r * math.sin(a))
    seg = 2 * math.pi / 12
    palette = [(1.0, 0.75, 0.35), (0.95, 0.40, 0.55), (0.40, 0.85, 0.95), (0.70, 0.55, 1.0), (1.0, 0.95, 0.80)]
    # petals: arcs within one sector, mirrored by the guide
    for ring, (r0, r1, col, size) in enumerate(((60, 200, palette[0], 16), (200, 360, palette[1], 14), (340, 520, palette[2], 12), (500, 660, palette[3], 10))):
        pts = [polar(r0 + (r1 - r0) * t, -seg * 0.45 * math.sin(math.pi * t)) for t in [i / 20 for i in range(21)]]
        p.brush("lightpen" if "lightpen" in B else "studio_pen", size, col)
        p.stroke(pts, [0.4 + 0.6 * math.sin(math.pi * i / 20) for i in range(21)])
        pts = [polar(r0 + (r1 - r0) * t, seg * 0.45 * math.sin(math.pi * t)) for t in [i / 20 for i in range(21)]]
        p.stroke(pts, [0.4 + 0.6 * math.sin(math.pi * i / 20) for i in range(21)])
        p.brush("hard", size * 1.8, palette[4])
        p.dab(*polar((r0 + r1) / 2, 0))
        p.brush("hard", size * 0.9, col)
        p.dab(*polar(r1 + 14, seg / 2))
    p.brush("studio_pen", 6, palette[4])
    p.stroke([polar(700, -seg / 2 + seg * i / 20) for i in range(21)], 0.8)
    p.stroke([polar(140, -seg / 2 + seg * i / 20) for i in range(21)], 0.8)
    p.brush("hard", 40, palette[0])
    p.dab(cx, cy)
    p.layer("Brilho", blend="add", opacity=0.6)
    p.brush("soft_air", 900, (0.40, 0.25, 0.55), opacity=0.5)
    p.dab(cx, cy)


ARTWORKS = [
    ("cherry", "Cerejeira a nanquim", 2400, 1500, cherry),
    ("sunset", "Pôr do sol nas montanhas", 2400, 1500, sunset),
    ("aurora", "Aurora boreal", 2400, 1500, aurora),
    ("plane", "Aviãozinho (animação)", 1920, 1200, plane),
    ("mandala", "Mandala", 2400, 1500, mandala),
    ("cherry_p", "Cerejeira (retrato)", 1500, 2400, cherry),
    ("sunset_p", "Pôr do sol (retrato)", 1500, 2400, sunset),
    ("aurora_p", "Aurora (retrato)", 1500, 2400, aurora),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--only")
    parser.add_argument("--keep-open", action="store_true", help="leave the last artwork open")
    arguments = parser.parse_args()
    adb(arguments.device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(arguments.device)
    painter = Painter(channel)
    for key, name, width, height, paint in ARTWORKS:
        if arguments.only and key not in arguments.only.split(","):
            continue
        for _ in range(20):
            if channel.call("getState")["artwork"] is None:
                break
            channel.execute("gallery.close")
            time.sleep(1)
        started = time.time()
        artwork = channel.execute("gallery.newCanvas", {"width": width, "height": height, "name": name})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("view.fit")
        paint(painter, width, height)
        channel.execute("document.save")
        print(f"{key}: {artwork} in {time.time() - started:.0f}s", flush=True)
    if not arguments.keep_open:
        channel.execute("gallery.close")


if __name__ == "__main__":
    main()
