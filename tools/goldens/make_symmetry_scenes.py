#!/usr/bin/env python3
import json
import math
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"
SIZE = 256
CENTER = (SIZE / 2, SIZE / 2)


def rotation(angle, cx, cy):
    cos, sin = math.cos(angle), math.sin(angle)
    return (cos, -sin, cx - cos * cx + sin * cy, sin, cos, cy - sin * cx - cos * cy)


def reflection(angle, cx, cy):
    cos, sin = math.cos(2 * angle), math.sin(2 * angle)
    return (cos, sin, cx - cos * cx - sin * cy, sin, -cos, cy - sin * cx + cos * cy)


IDENTITY = (1.0, 0.0, 0.0, 0.0, 1.0, 0.0)


def transforms(kind, segments=8, rotational=False, rotation_angle=0.0, center=CENTER):
    cx, cy = center
    up = rotation_angle - math.pi / 2

    def turns(count):
        return [IDENTITY if k == 0 else rotation(2 * math.pi * k / count, cx, cy) for k in range(count)]

    if kind in ("vertical", "horizontal"):
        if rotational:
            return turns(2)
        axis = up if kind == "vertical" else rotation_angle
        return [IDENTITY, reflection(axis, cx, cy)]
    if kind == "quadrant":
        if rotational:
            return turns(4)
        return [IDENTITY, reflection(up, cx, cy), reflection(rotation_angle, cx, cy),
                rotation(math.pi, cx, cy)]
    count = segments if rotational or segments % 2 == 0 else segments + 1
    if rotational:
        return turns(count)
    wedge = 2 * math.pi / count
    return [IDENTITY if k == 0 else rotation(k * wedge, cx, cy) if k % 2 == 0
            else reflection(up + (k + 1) * wedge / 2, cx, cy) for k in range(count)]


def engine_copies(copies):
    return [[round(v, 9) for v in (t[0], t[3], t[1], t[4], t[2], t[5])] for t in copies]


def curve(x0, y0, x1, y1, bend, count=48, pressure=(0.4, 1.0)):
    samples = []
    for i in range(count):
        t = i / (count - 1)
        nx, ny = -(y1 - y0), x1 - x0
        length = math.hypot(nx, ny)
        offset = bend * math.sin(math.pi * t)
        x = x0 + (x1 - x0) * t + nx / length * offset
        y = y0 + (y1 - y0) * t + ny / length * offset
        p = pressure[0] + (pressure[1] - pressure[0]) * math.sin(math.pi * t)
        samples.append([round(x, 3), round(y, 3), round(p, 3), 4.0 * i])
    return samples


ROUND = {"preset": "round", "hardness": 0.8, "spacing": 0.05, "sizeMinimum": 0.3}


def stroke(samples, color, copies, size=14, tool="paint", **extra):
    action = {"type": "stroke", "tool": tool, "layer": 1, "size": size, "opacity": 1,
              "color": color, "brush": ROUND, "samples": samples,
              "symmetry": engine_copies(copies)}
    action.update(extra)
    if "brushPackage" in extra:
        del action["brush"]
    return action


def scene(name, actions, layers=1):
    return {"format": 1, "documentId": f"golden-{name.replace('_', '-')}",
            "canvas": {"width": SIZE, "height": SIZE, "background": [1, 1, 1, 1]},
            "layers": layers, "actions": actions}


def strokes(copies):
    return [
        stroke(curve(150, 30, 220, 120, 25), [0.85, 0.15, 0.1, 1], copies),
        stroke(curve(140, 150, 230, 200, -20, pressure=(0.6, 1.0)), [0.1, 0.3, 0.8, 1], copies,
               size=26, brushPackage="../brushes/test-flat"),
    ]


def main():
    vertical = transforms("vertical")
    scenes = {
        "symmetry_vertical": strokes(vertical) + [
            stroke(curve(135, 60, 235, 170, 0, count=30, pressure=(1, 1)), [0, 0, 0, 1], vertical,
                   size=6, tool="erase"),
        ],
        "symmetry_horizontal": strokes(transforms("horizontal", rotation_angle=0.3)),
        "symmetry_quadrant": strokes(transforms("quadrant")),
        "symmetry_radial8": strokes(transforms("radial", segments=8)),
        "symmetry_rotational": strokes(transforms("radial", segments=5, rotational=True)),
        "symmetry_selection": [
            stroke(curve(150, 40, 215, 110, 18, pressure=(1, 1)), [0, 0, 0, 1],
                   transforms("quadrant"), size=30, target="selection"),
            {"type": "stroke", "tool": "paint", "layer": 1, "size": 120, "opacity": 1,
             "color": [0.2, 0.6, 0.3, 1], "brush": ROUND,
             "samples": curve(10, 128, 246, 128, 0, count=40, pressure=(1, 1))},
            {"type": "stroke", "tool": "paint", "layer": 1, "size": 120, "opacity": 1,
             "color": [0.9, 0.7, 0.1, 1], "brush": ROUND,
             "samples": curve(128, 10, 128, 246, 0, count=40, pressure=(1, 1))},
        ],
    }
    for name, actions in scenes.items():
        path = SCENES / f"{name}.json"
        path.write_text(json.dumps(scene(name, actions), indent=1) + "\n", encoding="utf-8")
        print(f"wrote {path.name}")


if __name__ == "__main__":
    main()
