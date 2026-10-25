#!/usr/bin/env python3
"""Makes the sample artworks with the app's own
brushes and commands, through the control channel of a developer build on a device, and writes
them to assets-src/samples/<key>.nanquim. Everything is computed here from fixed seeds (shapes,
colors, strokes), so the samples are the project's own work, released as CC0 (CREDITS.md).

    python tools/samples/make_samples.py --device <serial>

The samples:
- night-hills: layers and blend modes (Screen glow, Soft Light mist) over a filled sky;
- ink-flower: a pencil sketch, watercolor under Multiply and a pen outline;
- bouncing-ball: a 12-frame animation over a background frame.
"""

import argparse
import math
import os
import random
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "device"))
from kill_test import adb, launch, wait_for_artwork  # noqa: E402

REPOSITORY = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUTPUT = os.path.join(REPOSITORY, "assets-src", "samples")
EXPORTS = "content://com.mobyle.nanquim.files/exports"

HARD_BRUSH = "7aa95fc4-74ed-5b42-885c-1d10a7444c90"
SOFT_AIRBRUSH = "2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a31"
HARD_AIRBRUSH = "2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a32"
PENCIL_6B = "2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a02"
WATERCOLOR = "352e8a06-2dfc-53cc-a17f-e7fe72dc956e"
STUDIO_PEN = "4125df6c-df5c-5043-8bb5-d46865d137aa"


class Painter:
    def __init__(self, channel):
        self.channel = channel

    def run(self, command, arguments=None):
        return self.channel.execute(command, arguments or {})

    def brush(self, brush_id, size, color, opacity=1.0):
        self.run("brush.select", {"brushId": brush_id})
        self.run("brush.setSize", {"sizePx": size})
        self.run("brush.setOpacity", {"opacity": opacity})
        red, green, blue = color
        self.run("color.setPrimary", {"red": red, "green": green, "blue": blue})

    def stroke(self, points, pressure=1.0):
        samples = []
        for index, (x, y) in enumerate(points):
            value = pressure[index] if isinstance(pressure, list) else pressure
            samples.append([round(x, 2), round(y, 2), value, index * 8])
        if len(samples) == 1:
            samples.append([samples[0][0] + 0.5, samples[0][1], samples[0][2], 8])
        self.run("tool.paint.strokePath", {"samples": samples})

    def dab(self, x, y, coats=1):
        for _ in range(coats):
            self.stroke([(x, y)])

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


def fill_under(painter, width, height, curve, step=24):
    for x in range(-step, width + step, step):
        painter.stroke(line((x, curve(x)), (x, height + 40), 12))


def night_hills(painter, width, height):
    random_source = random.Random(17)
    painter.run("layer.rename", {"layerId": 1, "name": "Sky"})
    painter.run("layer.fill", {"layerId": 1, "color": [0.07, 0.09, 0.24, 1.0]})
    painter.run("layer.select", {"layerId": 1})
    painter.brush(SOFT_AIRBRUSH, 900, (0.42, 0.24, 0.52), opacity=0.55)
    painter.stroke(line((-200, height * 0.72), (width + 200, height * 0.72), 40))
    painter.brush(SOFT_AIRBRUSH, 600, (0.85, 0.45, 0.40), opacity=0.35)
    painter.stroke(line((-200, height * 0.78), (width + 200, height * 0.78), 40))

    painter.layer("Stars")
    for _ in range(140):
        painter.brush(HARD_AIRBRUSH, random_source.uniform(2.5, 7.0), (1.0, 0.97, 0.9), opacity=random_source.uniform(0.5, 1.0))
        painter.dab(random_source.uniform(0, width), random_source.uniform(0, height * 0.62))

    moon = (width * 0.72, height * 0.27)
    painter.layer("Moon glow", blend="screen", opacity=0.7)
    painter.brush(SOFT_AIRBRUSH, 760, (0.75, 0.62, 0.40))
    painter.dab(*moon)
    painter.layer("Moon")
    painter.brush(HARD_BRUSH, 210, (0.98, 0.94, 0.80))
    painter.dab(*moon, coats=3)
    painter.brush(HARD_BRUSH, 46, (0.90, 0.85, 0.70))
    painter.dab(moon[0] - 40, moon[1] - 25)
    painter.brush(HARD_BRUSH, 30, (0.90, 0.85, 0.70))
    painter.dab(moon[0] + 35, moon[1] + 30)

    painter.layer("Far hills")
    painter.brush(HARD_BRUSH, 60, (0.20, 0.17, 0.38))
    fill_under(painter, width, height, lambda x: height * 0.64 + 55 * math.sin(x / 170.0) + 28 * math.sin(x / 63.0 + 1.3))

    painter.layer("Mist", blend="softLight", opacity=0.8)
    painter.brush(SOFT_AIRBRUSH, 420, (0.72, 0.70, 0.88), opacity=0.6)
    painter.stroke(line((-200, height * 0.76), (width + 200, height * 0.76), 40))

    painter.layer("Near hills")
    painter.brush(HARD_BRUSH, 60, (0.06, 0.06, 0.14))
    fill_under(painter, width, height, lambda x: height * 0.80 + 70 * math.sin(x / 260.0 + 2.0) + 22 * math.sin(x / 90.0))


def ink_flower(painter, width, height):
    center = (width * 0.5, height * 0.40)
    petals = 6

    def petal(angle, length, spread, steps=36):
        points = []
        for index in range(steps + 1):
            t = index / steps
            distance = length * (0.5 - 0.5 * math.cos(2 * math.pi * t))
            side = spread * math.sin(2 * math.pi * t)
            x = center[0] + math.cos(angle) * distance - math.sin(angle) * side
            y = center[1] + math.sin(angle) * distance + math.cos(angle) * side
            points.append((x, y))
        return points

    stem = [(center[0] + 40 * math.sin(t / 30.0 * math.pi) * (t / 30.0), center[1] + 60 + t * 17) for t in range(31)]
    leaf_base = stem[16]

    def leaf(direction):
        points = []
        for index in range(31):
            t = index / 30
            distance = 220 * (0.5 - 0.5 * math.cos(2 * math.pi * t))
            side = 55 * math.sin(2 * math.pi * t)
            angle = math.radians(-35 if direction > 0 else 215)
            points.append((leaf_base[0] + math.cos(angle) * distance - math.sin(angle) * side,
                           leaf_base[1] + math.sin(angle) * distance + math.cos(angle) * side))
        return points

    painter.run("layer.rename", {"layerId": 1, "name": "Sketch"})
    painter.run("layer.setOpacity", {"layerId": 1, "opacity": 0.45})
    painter.run("layer.select", {"layerId": 1})
    painter.brush(PENCIL_6B, 6, (0.35, 0.33, 0.32), opacity=0.8)
    for radius in (90, 260):
        painter.stroke([(center[0] + radius * math.cos(a / 40 * 2 * math.pi), center[1] + radius * math.sin(a / 40 * 2 * math.pi)) for a in range(41)], 0.5)
    painter.stroke(stem, 0.5)

    painter.layer("Watercolor", blend="multiply")
    for index in range(petals):
        angle = index * 2 * math.pi / petals - math.pi / 2
        painter.brush(WATERCOLOR, 70, (0.93, 0.48 + 0.04 * (index % 2), 0.58), opacity=0.8)
        outline = petal(angle, 250, 70)
        painter.stroke(outline, 0.9)
        painter.stroke(petal(angle, 190, 35), 0.9)
    painter.brush(WATERCOLOR, 80, (0.98, 0.80, 0.30), opacity=0.9)
    painter.stroke([(center[0] + 30 * math.cos(a / 12 * 2 * math.pi), center[1] + 30 * math.sin(a / 12 * 2 * math.pi)) for a in range(13)], 1.0)
    painter.brush(WATERCOLOR, 60, (0.45, 0.70, 0.40), opacity=0.8)
    painter.stroke(leaf(1), 0.9)
    painter.stroke(leaf(-1), 0.9)
    painter.brush(WATERCOLOR, 22, (0.40, 0.62, 0.35), opacity=0.9)
    painter.stroke(stem, 1.0)

    painter.layer("Ink")
    painter.brush(STUDIO_PEN, 7, (0.08, 0.07, 0.09))
    for index in range(petals):
        angle = index * 2 * math.pi / petals - math.pi / 2
        outline = petal(angle, 255, 72)
        painter.stroke(outline, [0.45 + 0.55 * math.sin(math.pi * i / (len(outline) - 1)) for i in range(len(outline))])
    painter.stroke([(center[0] + 34 * math.cos(a / 16 * 2 * math.pi), center[1] + 34 * math.sin(a / 16 * 2 * math.pi)) for a in range(17)], 0.8)
    painter.stroke(stem, [0.9 - 0.4 * i / 30 for i in range(31)])
    for direction in (1, -1):
        outline = leaf(direction)
        painter.stroke(outline, [0.4 + 0.6 * math.sin(math.pi * i / 30) for i in range(31)])
        tip = outline[15]
        painter.stroke(line(leaf_base, tip, 10), 0.4)


def bouncing_ball(painter, width, height):
    ground = height * 0.82
    painter.run("animation.setEnabled", {"enabled": True})
    painter.run("animation.setSettings", {"playback": "loop", "framesPerSecond": 12, "firstFrameIsBackground": True, "onionEnabled": False})
    painter.run("layer.rename", {"layerId": 1, "name": "Background"})
    painter.run("layer.select", {"layerId": 1})
    painter.brush(SOFT_AIRBRUSH, 900, (0.80, 0.88, 0.97), opacity=0.8)
    painter.stroke(line((-200, height * 0.25), (width + 200, height * 0.25), 30))
    painter.brush(HARD_BRUSH, 60, (0.55, 0.75, 0.45))
    fill_under(painter, width, height, lambda x: ground + 6 * math.sin(x / 50.0))
    painter.brush(STUDIO_PEN, 8, (0.15, 0.20, 0.12))
    painter.stroke([(x, ground + 6 * math.sin(x / 50.0) - 2) for x in range(0, width + 1, 20)], 0.9)

    frames = 12
    radius = 70
    for frame in range(frames):
        painter.run("animation.addFrame")
        state = painter.channel.call("getState")["document"]
        layer_id = state["activeLayerId"]
        painter.run("layer.rename", {"layerId": layer_id, "name": f"Ball {frame + 1}"})
        t = frame / frames
        x = width * 0.18 + width * 0.64 * t
        lift = abs(math.sin(2 * math.pi * t)) * height * 0.48
        y = ground - radius - lift
        shadow = 0.25 + 0.75 * (1 - lift / (height * 0.48))
        painter.brush(SOFT_AIRBRUSH, 2 * radius * (0.6 + 0.6 * shadow), (0.20, 0.30, 0.18), opacity=0.5 * shadow)
        painter.dab(x, ground + 4)
        painter.brush(HARD_BRUSH, 2 * radius, (0.90, 0.33, 0.22))
        painter.dab(x, y, coats=3)
        painter.brush(HARD_BRUSH, radius * 0.55, (1.0, 0.70, 0.55))
        painter.dab(x - radius * 0.32, y - radius * 0.32, coats=2)


SAMPLES = [
    ("night-hills", "Night Hills", 1600, 1200, night_hills),
    ("ink-flower", "Ink Flower", 1600, 1200, ink_flower),
    ("bouncing-ball", "Bouncing Ball", 1200, 900, bouncing_ball),
]


def closed(channel):
    for _ in range(20):
        channel.execute("gallery.close")
        time.sleep(1)
        if channel.call("getState")["artwork"] is None:
            return
    raise RuntimeError("the canvas did not close")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--only", help="one sample key")
    arguments = parser.parse_args()
    device = arguments.device
    os.makedirs(OUTPUT, exist_ok=True)
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    painter = Painter(channel)
    closed(channel)
    channel.execute("settings.timelapse.newCanvases", {"enabled": False})
    adb(device, "shell", "run-as", "com.mobyle.nanquim", "mkdir", "-p", "cache/exports")
    try:
        for key, name, width, height, paint in SAMPLES:
            if arguments.only and key != arguments.only:
                continue
            artwork = channel.execute("gallery.newCanvas", {"width": width, "height": height, "name": name})["id"]
            wait_for_artwork(channel, artwork)
            channel.execute("view.fit")
            paint(painter, width, height)
            if key == "bouncing-ball":
                channel.execute("animation.goToFrame", {"index": 1})
            closed(channel)
            channel.execute("export.document", {"uri": f"{EXPORTS}/sample-{key}.nanquim", "id": artwork})
            data = subprocess.run(
                [os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"), "-s", device,
                 "exec-out", "run-as", "com.mobyle.nanquim", "cat", f"cache/exports/sample-{key}.nanquim"],
                capture_output=True, check=True,
            ).stdout
            path = os.path.join(OUTPUT, f"{key}.nanquim")
            with open(path, "wb") as file:
                file.write(data)
            print(f"{path}: {len(data)} bytes")
            channel.execute("gallery.delete", {"ids": [artwork]})
    finally:
        channel.execute("settings.timelapse.newCanvases", {"enabled": True})


if __name__ == "__main__":
    main()
