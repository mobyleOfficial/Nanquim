#!/usr/bin/env python3
import json
import math
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"


def wobbly(points, count, wobble, pressure=(0.4, 1.0), duration=600.0):
    lengths = [0.0]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        lengths.append(lengths[-1] + math.hypot(x1 - x0, y1 - y0))
    total = lengths[-1]
    samples = []
    for i in range(count):
        t = i / (count - 1)
        x, y = along(points, lengths, t * total)
        x += wobble * math.sin(9.1 * t + 0.4) + 0.6 * wobble * math.sin(23.7 * t)
        y += wobble * math.cos(7.3 * t) - 0.5 * wobble * math.sin(17.9 * t + 1)
        p = pressure[0] + (pressure[1] - pressure[0]) * math.sin(math.pi * t)
        samples.append([round(x, 3), round(y, 3), round(p, 3), round(duration * t, 3)])
    return samples


def along(points, lengths, distance):
    for index in range(1, len(points)):
        if distance <= lengths[index] or index == len(points) - 1:
            span = lengths[index] - lengths[index - 1]
            f = 0.0 if span == 0 else (distance - lengths[index - 1]) / span
            (x0, y0), (x1, y1) = points[index - 1], points[index]
            return x0 + (x1 - x0) * f, y0 + (y1 - y0) * f
    return points[-1]


def outline(points, step=1.0):
    result = [points[0]]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        pieces = max(1, math.ceil(math.hypot(x1 - x0, y1 - y0) / step))
        result += [(x0 + (x1 - x0) * k / pieces, y0 + (y1 - y0) * k / pieces)
                   for k in range(1, pieces + 1)]
    return result


def ellipse(cx, cy, rx, ry, start=0.0):
    count = max(8, math.ceil(2 * math.pi * max(rx, ry)))
    return [(cx + rx * math.cos(start + 2 * math.pi * k / count),
             cy + ry * math.sin(start + 2 * math.pi * k / count)) for k in range(count + 1)]


def replacement(samples, shape):
    lengths = [0.0]
    for a, b in zip(samples, samples[1:]):
        lengths.append(lengths[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    points = outline(shape)
    shape_lengths = [0.0]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        shape_lengths.append(shape_lengths[-1] + math.hypot(x1 - x0, y1 - y0))
    result = []
    index = 1
    for (x, y), length in zip(points, shape_lengths):
        target = length / shape_lengths[-1] * lengths[-1]
        while index < len(lengths) - 1 and lengths[index] < target:
            index += 1
        span = lengths[index] - lengths[index - 1]
        f = 0.0 if span == 0 else min(1.0, max(0.0, (target - lengths[index - 1]) / span))
        a, b = samples[index - 1], samples[index]
        result.append([round(x, 3), round(y, 3), round(a[2] + (b[2] - a[2]) * f, 4),
                       round(a[3] + (b[3] - a[3]) * f, 3)])
    return result


def stroke(brush, samples, shape, color, size, tool="paint", **extra):
    action = {"type": "stroke", "tool": tool, "layer": 1, "size": size, "opacity": 1,
              "color": color, "brush": brush, "samples": samples,
              "replaceWith": replacement(samples, shape)}
    action.update(extra)
    return action


def scene(name, width, height, actions):
    return {"format": 1, "documentId": f"golden-{name.replace('_', '-')}",
            "canvas": {"width": width, "height": height, "background": [1, 1, 1, 1]},
            "layers": 1, "actions": actions}


SMOOTHING = {"preset": "round", "hardness": 0.8, "spacing": 0.05, "sizeMinimum": 0.3,
             "streamline": 0.7, "stabilization": 0.4, "jitterFilter": 0.5}


def shapes():
    rectangle = [(290, 40), (470, 40), (470, 130), (290, 130), (290, 40)]
    triangle = [(300, 170), (480, 200), (360, 270), (300, 170)]
    return [
        stroke(SMOOTHING, wobbly([(30, 40), (230, 110)], 50, 6), [(30, 40), (230, 110)],
               [0.85, 0.15, 0.1, 1], 10),
        stroke(SMOOTHING, wobbly(ellipse(130, 205, 70, 62, -1.2), 80, 5),
               ellipse(130, 205, 66, 66, -1.2), [0.1, 0.3, 0.8, 1], 12),
        stroke(SMOOTHING, wobbly(rectangle, 90, 4), rectangle, [0.1, 0.55, 0.25, 1], 8),
        stroke(SMOOTHING, wobbly(triangle, 80, 4), triangle, [0.5, 0.2, 0.6, 1], 8),
    ]


def first_stroke(name):
    action = next(a for a in json.loads((SCENES / f"{name}.json").read_text(encoding="utf-8"))
                  ["actions"] if a["type"] == "stroke")
    return {key: action[key] for key in ("tool", "size", "color", "brush", "dualBrush")
            if key in action}


def swatches():
    colors = [[0.9, 0.2, 0.15, 1], [0.15, 0.35, 0.85, 1], [0.95, 0.8, 0.15, 1]]
    return [{"type": "fill", "layer": 1, "rect": [300 + 70 * (k % 3), 10 + 140 * (k // 3), 60, 130],
             "color": colors[(k + k // 3) % 3]} for k in range(6)]


def effects():
    actions = swatches()
    rows = [("brush_dual", 60), ("brush_particles", 150), ("brush_edges", 235)]
    for name, y in rows:
        source = first_stroke(name)
        drawn = wobbly([(20, y - 20), (130, y + 15), (250, y - 10)], 60, 5)
        line = [(30, y), (250, y)]
        actions.append(stroke(source["brush"], drawn, line, source["color"],
                              min(source["size"], 30), **({"dualBrush": source["dualBrush"]}
                                                          if "dualBrush" in source else {})))
    for name, y in (("brush_smudge", 70), ("brush_wet_mix", 210)):
        source = first_stroke(name)
        drawn = wobbly([(290, y - 30), (400, y + 20), (500, y - 25)], 60, 4)
        line = [(290, y), (500, y)]
        actions.append(stroke(source["brush"], drawn, line, source["color"], source["size"],
                              tool=source["tool"]))
    return actions


def symmetry():
    cx, cy = 256, 144
    mirror_x = [-1, 0, 0, 1, 2 * cx, 0]
    mirror_y = [1, 0, 0, -1, 0, 2 * cy]
    half_turn = [-1, 0, 0, -1, 2 * cx, 2 * cy]
    identity = [1, 0, 0, 1, 0, 0]
    quadrant = [identity, mirror_x, mirror_y, half_turn]
    rectangle = [(150, 20), (230, 20), (230, 70), (150, 70), (150, 20)]
    return [
        stroke(SMOOTHING, wobbly(ellipse(70, 70, 50, 34, 0.5), 70, 4), ellipse(70, 70, 48, 32, 0.5),
               [0.1, 0.3, 0.8, 1], 8, symmetry=quadrant),
        stroke(SMOOTHING, wobbly(rectangle, 80, 3), rectangle, [0.85, 0.15, 0.1, 1], 6,
               symmetry=quadrant),
        stroke(SMOOTHING, wobbly([(150, 120), (240, 135)], 40, 4), [(150, 120), (240, 135)],
               [0.1, 0.55, 0.25, 1], 8, symmetry=[identity, mirror_x]),
    ]


def main():
    scenes = {"quickshape_shapes": shapes(), "quickshape_effects": effects(),
              "quickshape_symmetry": symmetry()}
    for name, actions in scenes.items():
        path = SCENES / f"{name}.json"
        path.write_text(json.dumps(scene(name, 512, 288, actions), indent=1) + "\n",
                        encoding="utf-8")
        print(f"wrote {path.name}")


if __name__ == "__main__":
    main()
