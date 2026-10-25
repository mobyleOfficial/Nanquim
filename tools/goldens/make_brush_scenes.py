#!/usr/bin/env python3
import json
import math
import struct
import zlib
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"
BRUSHES = Path(__file__).resolve().parents[2] / "goldens" / "brushes"


def write(name, scene):
    path = SCENES / f"{name}.json"
    path.write_text(json.dumps(scene, indent=1) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {path}")


def wave(x0, x1, y, pressure=lambda t: 1.0, tilt=None, count=60, amplitude=10):
    samples = []
    for index in range(count):
        t = index / (count - 1)
        sample = [round(x0 + (x1 - x0) * t, 3), round(y + amplitude * math.sin(t * 2 * math.pi), 3),
                  round(pressure(t), 4), index * 4.0]
        if tilt is not None:
            sample += [0, round(tilt(t), 4)]
        samples.append(sample)
    return samples


def hairpin(x0, x1, y, count=120):
    samples = []
    for index in range(count):
        t = index / (count - 1)
        along = 2 * t if t < 0.5 else 2 - 2 * t
        samples.append([round(x0 + (x1 - x0) * along, 3),
                        round(y - 8 + 16 * t + 4 * math.sin(along * 3 * math.pi), 3),
                        round(0.2 + 0.8 * t, 4), index * 4.0])
    return samples


def round_brush(**sections):
    brush = {"format": 1, "shape": {"texture": None, "hardness": 0.8}, "path": {"spacing": 0.08},
             "render": {"accumulation": "buildUp"}}
    brush.update(sections)
    return brush


def stroke(samples, color, brush, size=28, **extra):
    action = {"type": "stroke", "tool": "paint", "layer": 1, "size": size, "opacity": 1,
              "color": color, "brush": brush, "samples": samples}
    action.update(extra)
    return action


def color_dynamics():
    ramp = lambda t: 0.1 + 0.9 * t  # noqa: E731
    actions = [
        stroke(wave(30, 480, 40), [0.85, 0.2, 0.15, 1],
               round_brush(path={"spacing": 0.6},
                           colorDynamics={"hueJitter": 0.4, "saturationJitter": 0.3,
                                          "brightnessJitter": 0.3,
                                          "secondaryColorJitter": 0.6})),
        *[stroke(wave(30 + 150 * index, 160 + 150 * index, 100, amplitude=6), [0.2, 0.5, 0.9, 1],
                 round_brush(path={"spacing": 0.3},
                             colorDynamics={"darknessJitter": 0.6, "strokeHueJitter": 1,
                                            "strokeDarknessJitter": 0.5}),
                 size=22)
          for index in range(3)],
        stroke(wave(30, 480, 160, pressure=ramp, tilt=lambda t: 1.2 * t), [0.9, 0.1, 0.1, 1],
               round_brush(colorDynamics={"pressureHue": 0.6, "tiltSaturation": -0.8}), size=30),
        stroke(wave(30, 480, 220, pressure=ramp), [0.1, 0.2, 0.9, 1],
               round_brush(colorDynamics={"secondaryColorByPressure": 1,
                                          "pressureColorCurve": "curve:soft"}),
               size=30, secondaryColor=[1, 0.85, 0.1, 1]),
    ]
    write("brush_color_dynamics", {
        "format": 1,
        "documentId": "golden-brush-color-dynamics",
        "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


def glaze():
    modes = ["glazeLight", "glazeUniform", "glazeIntense", "glazeHeavy", "blendUniform",
             "blendIntense"]
    actions = [{"type": "fill", "layer": 1, "rect": [40, 5 * 64 + 8, 220, 48],
                "color": [1, 0.85, 0.1, 1]}]
    for row, mode in enumerate(modes):
        y = row * 64 + 32
        brush = round_brush(shape={"texture": None, "hardness": 0.3}, path={"spacing": 0.05},
                            dynamics={"opacity": {"min": 0.2, "pressure": "curve:linear"}},
                            render={"accumulation": mode, "flow": 0.5})
        brush["colorDynamics"] = {"secondaryColorByPressure": 1}
        actions.append(stroke(hairpin(20, 490, y), [0.9, 0.15, 0.1, 1], brush, size=34,
                              secondaryColor=[0.1, 0.25, 0.9, 1]))
    write("brush_glaze", {
        "format": 1,
        "documentId": "golden-brush-glaze",
        "canvas": {"width": 512, "height": 384, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


def edges():
    def brush(**render):
        settings = {"accumulation": "buildUp"}
        settings.update(render)
        return round_brush(shape={"texture": None, "hardness": 0.9}, path={"spacing": 0.05},
                           render=settings)

    jitter = {"hueJitter": 1, "saturationJitter": 0.2}
    actions = [
        {"type": "fill", "layer": 1, "rect": [0, 256, 512, 64], "color": [0.08, 0.12, 0.35, 1]},
        stroke(hairpin(30, 480, 32), [0.15, 0.35, 0.85, 1], brush(wetEdges=1), size=40),
        stroke(hairpin(30, 480, 96), [0.95, 0.55, 0.15, 1], brush(burntEdges=1), size=40),
        stroke(hairpin(30, 480, 160), [0.2, 0.7, 0.35, 1],
               brush(wetEdges=0.7, burntEdges=0.6, burntEdgesBlend="linearBurn"), size=40),
        stroke(wave(30, 240, 224, amplitude=6), [0.9, 0.2, 0.2, 1],
               round_brush(path={"spacing": 0.25}, colorDynamics=jitter), size=36),
        stroke(wave(270, 480, 224, amplitude=6), [0.9, 0.2, 0.2, 1],
               round_brush(path={"spacing": 0.25}, colorDynamics=jitter,
                           render={"accumulation": "buildUp", "gammaCorrect": True}), size=36),
        stroke(wave(30, 480, 288, amplitude=10), [1, 0.85, 0.2, 1],
               brush(blend="add", luminanceBlending=True), size=30, opacity=0.8),
    ]
    write("brush_edges", {
        "format": 1,
        "documentId": "golden-brush-edges",
        "canvas": {"width": 512, "height": 320, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


def bands(top, height):
    colors = [[0.9, 0.2, 0.15, 1], [0.15, 0.35, 0.85, 1], [0.95, 0.8, 0.15, 1],
              [0.2, 0.65, 0.3, 1]]
    return [{"type": "fill", "layer": 1, "rect": [40 + 100 * index, top, 60, height],
             "color": color} for index, color in enumerate(colors)]


def mixing_scene(name, document_id, strokes):
    actions = []
    for row in range(len(strokes)):
        actions += bands(row * 64 + 4, 56)
    actions += strokes
    write(name, {
        "format": 1,
        "documentId": document_id,
        "canvas": {"width": 512, "height": 64 * len(strokes), "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


def smudge():
    def brush(**smudge_settings):
        return round_brush(shape={"texture": None, "hardness": 0.4}, path={"spacing": 0.06},
                           smudge=smudge_settings)

    def smudge_stroke(row, settings, color=(0, 0, 0, 1), size=36):
        return stroke(wave(20, 500, row * 64 + 32, amplitude=6), list(color), brush(**settings),
                      size=size, tool="smudge")

    mixing_scene("brush_smudge", "golden-brush-smudge", [
        smudge_stroke(0, {"strength": 0.9, "pickUp": 0.2, "length": 0.8}),
        smudge_stroke(1, {"strength": 0.9, "pickUp": 0.8, "length": 0.8}),
        smudge_stroke(2, {"strength": 0.9, "pickUp": 0.2, "length": 0}),
        smudge_stroke(3, {"strength": 0.7, "pickUp": 0.3, "length": 0.8, "useBrushColor": 0.4},
                      color=(0.6, 0.2, 0.7, 1)),
    ])


def wet_mix():
    def brush(**wet):
        settings = {"enabled": True}
        settings.update(wet)
        return round_brush(shape={"texture": None, "hardness": 0.6}, path={"spacing": 0.06},
                           wetMix=settings)

    def wet_stroke(row, settings):
        return stroke(wave(20, 500, row * 64 + 32, pressure=lambda t: 0.4 + 0.6 * t, amplitude=6),
                      [0.85, 0.4, 0.75, 1], brush(**settings), size=32)

    mixing_scene("brush_wet_mix", "golden-brush-wet-mix", [
        wet_stroke(0, {"pull": 0.5, "attack": 0.6}),
        wet_stroke(1, {"pull": 0.2, "dilution": 0.6, "wetnessJitter": 0.4}),
        wet_stroke(2, {"pull": 0.3, "charge": 0.3}),
        wet_stroke(3, {"pull": 0.4, "blur": 0.5, "blurJitter": 0.3, "grade": 0.8}),
    ])


def dual():
    secondary = {"format": 1, "shape": {"texture": None, "hardness": 0.9, "scatter": 1.2, "count": 3},
                 "path": {"spacing": 0.8}, "limits": {"maxSize": 14}, "render": {"accumulation": "buildUp"}}
    actions = []
    for row, blend in enumerate(["multiply", "subtract", "darken", "lighten", "overlay", "height"]):
        primary = round_brush(shape={"texture": None, "hardness": 0.2}, path={"spacing": 0.05},
                              dual={"enabled": True, "brushId": "test-secondary", "blend": blend})
        actions.append(stroke(wave(30, 480, row * 48 + 24, pressure=lambda t: 0.3 + 0.7 * t, amplitude=5),
                              [0.15, 0.3, 0.75, 1], primary, size=36, dualBrush=secondary))
    write("brush_dual", {
        "format": 1,
        "documentId": "golden-brush-dual",
        "canvas": {"width": 512, "height": 288, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


def particles():
    def brush(**settings):
        values = {"rate": 6, "lifetimeMs": 500, "initialSpeed": 120, "sizeStart": 0.35,
                  "sizeEnd": 0.05, "opacityStart": 0.9, "opacityEnd": 0.2}
        values.update(settings)
        return round_brush(shape={"texture": None, "hardness": 0.8}, path={"spacing": 0.1},
                           particles=values)

    rows = [
        ({"direction": "random", "gravity": 400, "speedJitter": 0.6}, [0.85, 0.35, 0.1, 1]),
        ({"direction": "outward", "drag": 0.3}, [0.1, 0.45, 0.8, 1]),
        ({"direction": "followStroke", "drag": 0.6, "initialSpeed": 300}, [0.2, 0.6, 0.3, 1]),
        ({"direction": "random", "useShape": False, "rotationSpeed": 6}, [0.6, 0.2, 0.6, 1]),
    ]
    actions = []
    for row, (settings, color) in enumerate(rows):
        actions.append(stroke(wave(40, 470, row * 72 + 30, amplitude=6, count=40), color,
                              brush(**settings), size=18))
    write("brush_particles", {
        "format": 1,
        "documentId": "golden-brush-particles",
        "canvas": {"width": 512, "height": 288, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


def write_png(path, width, height, channels, pixels):
    color_type = {1: 0, 4: 6}[channels]
    rows = b"".join(b"\x00" + bytes(pixels[y * width * channels:(y + 1) * width * channels])
                    for y in range(height))

    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data +
                struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n" +
           chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    path.write_bytes(png)
    print(f"wrote {path}")


def supersampled(size, inside, samples=4):
    coverage = []
    for y in range(size):
        for x in range(size):
            hits = 0
            for sy in range(samples):
                for sx in range(samples):
                    u = ((x + (sx + 0.5) / samples) / size) * 2 - 1
                    v = ((y + (sy + 0.5) / samples) / size) * 2 - 1
                    hits += inside(u, v)
            coverage.append(hits / (samples * samples))
    return coverage


def arrow(u, v):
    if (u + 0.35) ** 2 + v ** 2 < 0.12 ** 2:
        return False
    shaft = -0.85 <= u <= 0.2 and abs(v) <= 0.22
    head = 0.2 <= u <= 0.9 and abs(v) <= 0.65 * (0.9 - u) / 0.7
    return shaft or head


def teardrop(u, v):
    if u < -0.2:
        return (u + 0.2) ** 2 + v ** 2 <= 0.65 ** 2
    return u <= 0.9 and abs(v) <= 0.65 * (1 - ((u + 0.2) / 1.1) ** 2) ** 0.5 * (0.9 - u) / 0.9 * 1.4


def stamp_textures():
    size = 128
    folder = BRUSHES / "test-stamp"
    folder.mkdir(parents=True, exist_ok=True)
    gray = [round(255 * c) for c in supersampled(size, arrow)]
    write_png(folder / "shape.png", size, size, 1, gray)
    alpha = supersampled(size, teardrop)
    eye = supersampled(size, lambda u, v: (u + 0.25) ** 2 + (v + 0.15) ** 2 < 0.13 ** 2)
    rgba = []
    for index, (a, e) in enumerate(zip(alpha, eye)):
        t = (index % size) / (size - 1)
        red, green, blue = 0.9, 0.15 + 0.75 * t, 0.1
        red, green, blue = (red * (1 - e) + 0.1 * e, green * (1 - e) + 0.3 * e,
                            blue * (1 - e) + 0.95 * e)
        rgba += [round(255 * red), round(255 * green), round(255 * blue), round(255 * a)]
    write_png(folder / "color.png", size, size, 4, rgba)


def stamp():
    stamp_textures()

    def brush(texture="shape.png", stamp_settings=None, shape=None, render=None):
        shape_settings = {"texture": f"../brushes/test-stamp/{texture}", "rotationMode": "fixed"}
        shape_settings.update(shape or {})
        settings = {"enabled": True}
        settings.update(stamp_settings or {})
        return {"format": 1, "shape": shape_settings, "stamp": settings,
                "render": render or {"accumulation": "buildUp"}}

    def tap(x, y):
        return [[x, y, 1.0, 0.0], [x, y, 1.0, 16.0]]

    def drag(x, y, dx, dy, steps=10):
        return [[round(x + dx * i / steps, 3), round(y + dy * i / steps, 3), 1.0, i * 8.0]
                for i in range(steps + 1)]

    color = [0.15, 0.25, 0.7, 1]
    actions = [
        stroke(tap(56, 48), color, brush(), size=72),
        stroke(tap(152, 48), color, brush(shape={"rotation": 0.8}), size=72),
        stroke(tap(248, 48), color, brush(shape={"roundness": 0.5}), size=72),
        stroke(tap(344, 48), color, brush(shape={"invert": True}), size=72),
        stroke(drag(440, 48, 60, 30), color, brush(), size=72),
    ]
    for index, (dx, dy) in enumerate([(40, 0), (0, 40), (-40, 0), (-30, -30)]):
        actions.append(stroke(drag(56 + 96 * index, 136, dx, dy), [0.75, 0.2, 0.2, 1],
                              brush(stamp_settings={"placeOnRelease": True}), size=72))
    green = [0.1, 0.8, 0.2, 1]
    color_stamp = {"keepColors": True}
    actions += [
        stroke(tap(56, 224), green, brush("color.png", color_stamp), size=80),
        stroke(tap(152, 224), green, brush("color.png", color_stamp, shape={"rotation": -1.2}),
               size=80),
        stroke(tap(248, 224), green,
               brush("color.png", color_stamp, render={"accumulation": "glazeLight"}),
               size=80, opacity=0.5),
        stroke(tap(344, 224), green,
               brush("color.png", color_stamp,
                     render={"accumulation": "buildUp", "gammaCorrect": True}), size=80),
        stroke(drag(440, 224, 0, 40), green,
               brush("color.png", {"keepColors": True, "placeOnRelease": True}), size=80),
    ]
    write("brush_stamp", {
        "format": 1,
        "documentId": "golden-brush-stamp",
        "canvas": {"width": 512, "height": 272, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


def stylus():
    tapered = round_brush(shape={"texture": None, "hardness": 0.9}, path={"spacing": 0.05},
                          taper={"stylus": {"start": 120, "minSize": 0.1, "tipAnimation": True}})

    def held(x0, y, hold_ms):
        samples = [[x0, y, 1.0, t * 1.0] for t in range(0, int(hold_ms) + 1, 16)]
        start = samples[-1][3]
        samples += [[round(x0 + 8 * i, 3), y, 1.0, start + 8.0 * i] for i in range(1, 40)]
        return samples

    actions = [stroke(held(40, 32 + 48 * row, hold), [0.15, 0.2, 0.6, 1], tapered, size=30)
               for row, hold in enumerate([0, 120, 240, 480])]
    for index, hold in enumerate([60, 200, 480]):
        actions.append(stroke([[400 + 40 * index, 220, 1.0, t * 1.0] for t in range(0, hold + 1, 16)],
                              [0.6, 0.15, 0.2, 1], tapered, size=30))
    flat = round_brush(shape={"texture": None, "hardness": 0.95, "rotationMode": "fixed", "roundness": 0.2},
                       path={"spacing": 0.03}, roll={"enabled": True, "amount": 1})

    def rolling(y):
        samples = []
        for i in range(60):
            t = i / 59
            samples.append([round(30 + 450 * t, 3), y, 1.0, i * 6.0, 0, 0.0, round(-1.5 + 3.0 * t, 4)])
        return samples

    actions.append(stroke(rolling(258), [0.1, 0.5, 0.3, 1], flat, size=28, rollFromOrientation=True))
    actions.append(stroke(rolling(292), [0.1, 0.5, 0.3, 1], flat, size=28))
    write("brush_stylus", {
        "format": 1,
        "documentId": "golden-brush-stylus",
        "canvas": {"width": 512, "height": 312, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": actions,
    })


if __name__ == "__main__":
    color_dynamics()
    glaze()
    edges()
    smudge()
    wet_mix()
    dual()
    particles()
    stamp()
    stylus()
