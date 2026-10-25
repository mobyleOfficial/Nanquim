#!/usr/bin/env python3
import json
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"


def line(x0, y0, x1, y1, count=40, pressure=1.0):
    return [[round(x0 + (x1 - x0) * i / (count - 1), 3), round(y0 + (y1 - y0) * i / (count - 1), 3),
             pressure, 4.0 * i] for i in range(count)]


def stroke(layer, samples, color, size=24, tool="paint", **extra):
    action = {"type": "stroke", "tool": tool, "layer": layer, "size": size, "opacity": 1,
              "color": color, "brush": {"preset": "round", "hardness": 0.7, "spacing": 0.05},
              "samples": samples}
    action.update(extra)
    return action


scene = {
    "format": 1,
    "documentId": "golden-selection-basic",
    "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]},
    "layers": [
        {"type": "raster", "id": 1},
        {"type": "raster", "id": 2, "alphaLock": True},
        {"type": "raster", "id": 3, "mask": {"id": 4}},
    ],
    "actions": [
        {"type": "fill", "layer": 1, "rect": [20, 150, 470, 90], "color": [0.8, 0.8, 0.75, 1]},
        {"type": "fill", "layer": 2, "rect": [270, 20, 100, 100], "color": [0.2, 0.3, 0.9, 1]},
        {"type": "fill", "layer": 3, "rect": [300, 60, 180, 150], "color": [0.95, 0.55, 0.1, 0.8]},
        {"type": "fill", "selection": True, "rect": [0, 0, 256, 256], "color": [0, 0, 0, 1],
         "toColor": [1, 1, 1, 1]},
        stroke(1, line(20, 40, 490, 40), [0.85, 0.1, 0.1, 1]),
        stroke(1, line(20, 190, 490, 200), [0, 0, 0, 1], size=30, tool="erase"),
        {"type": "select", "op": "invert"},
        stroke(2, line(250, 70, 400, 70), [0.1, 0.7, 0.2, 1], size=30),
        stroke(1, line(20, 100, 490, 110), [0.9, 0.8, 0.1, 1], size=20,
               brush={"preset": "round", "hardness": 0.9, "spacing": 0.05, "blend": "multiply"}),
        {"type": "select", "op": "contents", "layer": 2},
        stroke(3, line(250, 40, 470, 200), [0, 0, 0, 1], size=40, mask=True),
        {"type": "select", "op": "all"},
        stroke(1, line(20, 228, 490, 228), [0.4, 0.2, 0.6, 1], size=10),
        {"type": "select", "op": "none"},
        stroke(1, line(20, 246, 490, 246), [0.1, 0.5, 0.6, 1], size=8),
    ],
}

with open(SCENES / "selection_basic.json", "w", encoding="utf-8", newline="\n") as file:
    json.dump(scene, file, indent=1)
    file.write("\n")


def coverage_strokes(layer, color, top=10, bottom=250, step=36, size=44):
    return [stroke(layer, line(0, y, 512, y), color, size=size) for y in range(top, bottom, step)]


shapes = {
    "format": 1,
    "documentId": "golden-selection-shapes",
    "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]},
    "layers": [{"type": "raster", "id": 1}, {"type": "raster", "id": 2}],
    "actions": [
        {"type": "select", "op": "rect", "rect": [20.5, 20.25, 200, 120]},
        {"type": "select", "op": "ellipse", "rect": [150, 60, 180, 150], "mode": "add"},
        {"type": "select", "op": "polygon", "mode": "subtract",
         "points": [[60, 40], [120, 130], [30, 100], [140, 70], [20, 60]]},
        {"type": "select", "op": "rect", "rect": [0, 0, 300, 180], "mode": "intersect"},
        *coverage_strokes(1, [0.2, 0.3, 0.85, 1]),
        {"type": "select", "op": "ellipse", "rect": [340, 40, 150, 170]},
        {"type": "select", "op": "feather", "radius": 14},
        stroke(1, line(340, 125, 490, 125), [0, 0, 0, 1], size=14, tool="erase", target="selection"),
        stroke(1, line(380, 230, 500, 180), [0, 0, 0, 1], size=24, target="selection"),
        *coverage_strokes(2, [0.9, 0.35, 0.1, 1], top=20, step=30, size=40),
    ],
}

with open(SCENES / "selection_shapes.json", "w", encoding="utf-8", newline="\n") as file:
    json.dump(shapes, file, indent=1)
    file.write("\n")


def circle(cx, cy, r, count=90):
    import math
    return [[round(cx + r * math.cos(2 * math.pi * i / (count - 1)), 3),
             round(cy + r * math.sin(2 * math.pi * i / (count - 1)), 3), 1.0, 4.0 * i] for i in range(count)]


fill = {
    "format": 1,
    "documentId": "golden-fill-threshold",
    "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]},
    "layers": [{"type": "raster", "id": 1}, {"type": "raster", "id": 2},
               {"type": "raster", "id": 3}, {"type": "raster", "id": 4}],
    "actions": [
        stroke(2, circle(70, 70, 50), [0.05, 0.05, 0.05, 1], size=6),
        stroke(2, circle(190, 70, 50), [0.05, 0.05, 0.05, 1], size=6),
        {"type": "floodFill", "layer": 1, "source": 2, "x": 70, "y": 70, "threshold": 0.1,
         "color": [0.95, 0.3, 0.2, 1]},
        {"type": "floodFill", "layer": 1, "source": 2, "x": 190, "y": 70, "threshold": 0.1,
         "expand": False, "color": [0.2, 0.6, 0.95, 1]},
        {"type": "fill", "layer": 3, "rect": [0, 140, 512, 116], "color": [0.1, 0.1, 0.1, 1],
         "toColor": [0.9, 0.9, 0.9, 1]},
        {"type": "floodFill", "layer": 4, "source": 3, "x": 2, "y": 160, "threshold": 0.05,
         "color": [0.9, 0.2, 0.6, 1]},
        {"type": "floodFill", "layer": 4, "source": 3, "x": 508, "y": 240, "threshold": 0.2,
         "color": [0.2, 0.8, 0.3, 0.8]},
        {"type": "select", "op": "auto", "layer": 2, "x": 400, "y": 40, "threshold": 0.1},
        {"type": "select", "op": "rect", "rect": [300, 0, 212, 130], "mode": "intersect"},
        {"type": "floodFill", "layer": 1, "whole": True, "color": [0.95, 0.85, 0.3, 1]},
    ],
}

with open(SCENES / "fill_threshold.json", "w", encoding="utf-8", newline="\n") as file:
    json.dump(fill, file, indent=1)
    file.write("\n")


copies = {
    "format": 1,
    "documentId": "golden-copy-layers",
    "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]},
    "layers": [{"type": "raster", "id": 1, "hidden": True},
               {"type": "raster", "id": 2, "opacity": 0.5},
               {"type": "raster", "id": 3, "blend": "multiply"}],
    "actions": [
        {"type": "fill", "layer": 1, "rect": [0, 0, 512, 256], "color": [0.9, 0.2, 0.3, 1],
         "toColor": [0.2, 0.4, 0.9, 0.6]},
        *[stroke(1, line(0, y, 512, y + 40), [0.1, 0.1, 0.1, 1], size=14) for y in range(10, 256, 48)],
        {"type": "copyLayer", "from": 1, "to": 2},
        {"type": "select", "op": "ellipse", "rect": [280, 20, 220, 220]},
        {"type": "select", "op": "feather", "radius": 8},
        {"type": "copyLayer", "from": 1, "to": 3, "masked": True},
    ],
}

with open(SCENES / "copy_layers.json", "w", encoding="utf-8", newline="\n") as file:
    json.dump(copies, file, indent=1)
    file.write("\n")


def homography(source, target):
    import numpy
    rows = []
    for (u, v), (x, y) in zip(source, target):
        rows.append([u, v, 1, 0, 0, 0, -u * x, -v * x, x])
        rows.append([0, 0, 0, u, v, 1, -u * y, -v * y, y])
    matrix = numpy.array([row[:8] for row in rows], dtype=float)
    values = numpy.array([row[8] for row in rows], dtype=float)
    h = numpy.linalg.solve(matrix, values)
    return list(h) + [1.0]


def project(h, u, v):
    x = h[0] * u + h[1] * v + h[2]
    y = h[3] * u + h[4] * v + h[5]
    w = h[6] * u + h[7] * v + h[8]
    return [round(x / w, 4), round(y / w, 4), round(w, 6), u, v]


def quad_mesh(rect, corners):
    x, y, width, height = rect
    source = [(x, y), (x + width, y), (x + width, y + height), (x, y + height)]
    h = homography(source, corners)
    a, b, c, d = (project(h, u, v) for u, v in source)
    return [a, b, c, a, c, d]


def warp_mesh(rect, displace, cells=16):
    import math
    x, y, width, height = rect
    grid = [[None] * (cells + 1) for _ in range(cells + 1)]
    for j in range(cells + 1):
        for i in range(cells + 1):
            u = x + width * i / cells
            v = y + height * j / cells
            dx, dy = displace(u, v)
            grid[j][i] = [round(u + dx, 4), round(v + dy, 4), 1.0, u, v]
    mesh = []
    for j in range(cells):
        for i in range(cells):
            a, b, c, d = grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i]
            mesh += [a, b, c, a, c, d]
    return mesh


def rotated(rect, angle, scale, center):
    import math
    x, y, width, height = rect
    cx, cy = center
    corners = []
    for u, v in [(x, y), (x + width, y), (x + width, y + height), (x, y + height)]:
        dx, dy = (u - cx) * scale, (v - cy) * scale
        corners.append((cx + dx * math.cos(angle) - dy * math.sin(angle),
                        cy + dx * math.sin(angle) + dy * math.cos(angle)))
    return corners


def checker(layer, rect, a, b, cell=8):
    x0, y0, width, height = rect
    actions = [{"type": "fill", "layer": layer, "rect": rect, "color": a}]
    for j in range(0, height, cell):
        for i in range(0, width, cell):
            if (i // cell + j // cell) % 2:
                actions.append({"type": "fill", "layer": layer, "rect": [x0 + i, y0 + j, cell, cell], "color": b})
    return actions


import math
transforms = {
    "format": 1,
    "documentId": "golden-transform-modes",
    "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]},
    "layers": [{"type": "raster", "id": layer} for layer in (1, 2, 3, 4, 5)],
    "actions": [
        *checker(1, [16, 16, 64, 48], [0.9, 0.3, 0.2, 1], [0.2, 0.2, 0.8, 1]),
        {"type": "transform", "layer": 1, "filter": "bilinear",
         "mesh": quad_mesh([16, 16, 64, 48], rotated([16, 16, 64, 48], math.radians(25), 1.4, (60, 60)))},
        *checker(2, [140, 20, 80, 60], [0.1, 0.6, 0.3, 1], [0.95, 0.9, 0.2, 1]),
        {"type": "transform", "layer": 2, "filter": "bicubic",
         "mesh": quad_mesh([140, 20, 80, 60], [(130, 30), (250, 10), (235, 120), (150, 100)])},
        *checker(3, [280, 20, 60, 40], [0.5, 0.2, 0.6, 1], [0.9, 0.9, 0.9, 0.6], cell=5),
        {"type": "transform", "layer": 3, "filter": "nearest",
         "mesh": quad_mesh([280, 20, 60, 40], [(400, 20), (280, 20), (280, 100), (400, 100)])},
        *checker(4, [30, 150, 160, 80], [0.2, 0.5, 0.9, 1], [0.95, 0.95, 0.95, 1], cell=10),
        {"type": "transform", "layer": 4, "filter": "bilinear",
         "mesh": warp_mesh([30, 150, 160, 80], lambda u, v: (6 * math.sin((v - 150) / 12), 10 * math.sin((u - 30) / 25)))},
        *checker(5, [260, 140, 230, 100], [0.95, 0.6, 0.1, 1], [0.3, 0.1, 0.1, 1], cell=12),
        {"type": "select", "op": "ellipse", "rect": [300, 150, 100, 80]},
        {"type": "transform", "layer": 5, "filter": "bilinear",
         "mesh": quad_mesh([0, 0, 512, 256], rotated([0, 0, 512, 256], math.radians(-10), 0.7, (350, 190)))},
        {"type": "select", "op": "none"},
    ],
}

with open(SCENES / "transform_modes.json", "w", encoding="utf-8", newline="\n") as file:
    json.dump(transforms, file, indent=1)
    file.write("\n")


clone = {
    "format": 1,
    "documentId": "golden-clone",
    "canvas": {"width": 512, "height": 256, "background": [0.95, 0.93, 0.88, 1]},
    "layers": [{"type": "raster", "id": 1}, {"type": "raster", "id": 2}],
    "actions": [
        *checker(1, [20, 20, 180, 200], [0.85, 0.25, 0.2, 1], [0.15, 0.35, 0.8, 0.7], cell=16),
        stroke(1, line(30, 120, 190, 60), [0.1, 0.6, 0.2, 1], size=18),
        {**stroke(2, line(260, 60, 440, 200), [0, 0, 0, 1], size=70), "clone": {"source": "composite", "offset": [-240, 0]}},
        {**stroke(2, line(300, 210, 490, 230), [0, 0, 0, 1], size=40,
                  brush={"preset": "round", "hardness": 0.3, "spacing": 0.08, "accumulation": "buildUp", "flow": 0.6}),
         "clone": {"source": "layer", "layer": 1, "offset": [-280, -120]}},
    ],
}

with open(SCENES / "clone.json", "w", encoding="utf-8", newline="\n") as file:
    json.dump(clone, file, indent=1)
    file.write("\n")
