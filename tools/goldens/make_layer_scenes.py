#!/usr/bin/env python3
import json
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"

LAYER_MODES = [
    "normal", "multiply", "screen", "overlay", "darken", "lighten", "colorDodge", "colorBurn",
    "hardLight", "softLight", "difference", "exclusion", "add", "subtract", "divide",
    "linearBurn", "vividLight", "linearLight", "pinLight", "hardMix", "hue", "saturation",
    "color", "luminosity", "darkerColor", "lighterColor",
]

CELL = 64
COLUMNS = 7


def write(name, scene):
    path = SCENES / f"{name}.json"
    path.write_text(json.dumps(scene, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {path}")


def blend_modes():
    rows = (len(LAYER_MODES) + COLUMNS - 1) // COLUMNS
    layers, actions = [], []
    next_id = 1
    for index, mode in enumerate(LAYER_MODES):
        x, y = (index % COLUMNS) * CELL, (index // COLUMNS) * CELL
        group_id, backdrop_id, source_id = next_id, next_id + 1, next_id + 2
        next_id += 3
        layers.append({
            "type": "group", "id": group_id, "blend": "normal",
            "children": [
                {"type": "raster", "id": backdrop_id},
                {"type": "raster", "id": source_id, "blend": mode},
            ],
        })
        rect = [x, y, CELL, CELL]
        actions.append({"type": "fill", "layer": backdrop_id, "rect": rect,
                        "color": [0.9, 0.15, 0.2, 1], "toColor": [0.1, 0.75, 0.95, 0.4],
                        "direction": "horizontal"})
        actions.append({"type": "fill", "layer": source_id, "rect": rect,
                        "color": [1.0, 0.85, 0.1, 0.3], "toColor": [0.25, 0.2, 0.7, 1],
                        "direction": "vertical"})
    write("blend_modes", {
        "format": 1,
        "documentId": "golden-blend-modes",
        "canvas": {"width": COLUMNS * CELL, "height": rows * CELL, "background": [1, 1, 1, 1]},
        "layers": layers,
        "actions": actions,
    })


def line(x0, y0, x1, y1, pressure=1.0, count=40):
    return [[round(x0 + (x1 - x0) * t / (count - 1), 3), round(y0 + (y1 - y0) * t / (count - 1), 3),
             pressure, t * 4.0] for t in range(count)]


def stroke(layer, samples, color, size=24, tool="paint", mask=False, blend=None, opacity=1):
    brush = {"preset": "round", "hardness": 0.8, "spacing": 0.05}
    if blend:
        brush["blend"] = blend
    action = {"type": "stroke", "tool": tool, "layer": layer, "size": size, "opacity": opacity,
              "color": color, "brush": brush, "samples": samples}
    if mask:
        action["mask"] = True
    return action


def layer_tree():
    layers = [
        {"type": "group", "id": 10, "opacity": 0.5, "children": [
            {"type": "raster", "id": 11},
            {"type": "raster", "id": 12, "blend": "multiply"},
        ]},
        {"type": "group", "id": 20, "blend": "multiply", "opacity": 0.8, "children": [
            {"type": "raster", "id": 21},
            {"type": "raster", "id": 22, "clipping": True},
            {"type": "raster", "id": 23, "clipping": True, "blend": "multiply", "opacity": 0.7},
        ]},
        {"type": "raster", "id": 31, "mask": {"id": 32}},
        {"type": "raster", "id": 33, "mask": {"id": 34, "inverted": True}},
        {"type": "raster", "id": 35, "opacity": 0.6, "mask": {"id": 36, "enabled": False}},
        {"type": "raster", "id": 41, "alphaLock": True},
        {"type": "raster", "id": 42},
        {"type": "raster", "id": 43, "hidden": True},
        {"type": "raster", "id": 44, "blend": "overlay"},
    ]
    actions = [
        {"type": "fill", "layer": 11, "rect": [8, 16, 80, 224], "color": [0.1, 0.4, 0.9, 1]},
        {"type": "fill", "layer": 12, "rect": [40, 16, 80, 224], "color": [0.95, 0.6, 0.1, 1],
         "toColor": [0.95, 0.6, 0.1, 0.2], "direction": "vertical"},
        {"type": "fill", "layer": 21, "rect": [144, 48, 96, 160], "color": [0.5, 0.1, 0.4, 1],
         "toColor": [0.5, 0.1, 0.4, 0.3], "direction": "horizontal"},
        stroke(22, line(130, 30, 250, 230), [0.2, 0.9, 0.3, 1], size=40),
        {"type": "fill", "layer": 23, "rect": [128, 100, 128, 40], "color": [0.3, 0.3, 0.9, 1]},
        {"type": "fill", "layer": 31, "rect": [264, 8, 112, 112], "color": [0.85, 0.2, 0.2, 1]},
        {"type": "fill", "layer": 31, "mask": True, "rect": [264, 8, 112, 112],
         "color": [1, 1, 1, 1], "toColor": [0, 0, 0, 1], "direction": "horizontal"},
        stroke(31, line(270, 100, 370, 20), [0, 0, 0, 1], size=14, mask=True),
        {"type": "fill", "layer": 33, "rect": [264, 136, 112, 56], "color": [0.1, 0.6, 0.3, 1]},
        {"type": "fill", "layer": 33, "mask": True, "rect": [264, 136, 56, 56],
         "color": [0.25, 0.25, 0.25, 1]},
        {"type": "fill", "layer": 35, "rect": [264, 200, 112, 48], "color": [0.9, 0.8, 0.2, 1]},
        {"type": "fill", "layer": 35, "mask": True, "rect": [264, 200, 112, 48],
         "color": [0, 0, 0, 1]},
        {"type": "fill", "layer": 41, "rect": [400, 16, 96, 72], "color": [0.95, 0.9, 0.3, 1],
         "toColor": [0.95, 0.9, 0.3, 0.4], "direction": "vertical"},
        stroke(41, line(390, 52, 506, 52), [0.8, 0.1, 0.5, 1], size=40),
        stroke(41, line(390, 30, 506, 80), [0, 0, 0, 1], size=20, tool="erase"),
        {"type": "fill", "layer": 42, "rect": [400, 104, 96, 64], "color": [0.2, 0.5, 0.9, 1]},
        stroke(42, line(390, 136, 506, 136), [0.95, 0.4, 0.1, 1], size=48, blend="behind"),
        stroke(42, line(448, 96, 448, 176), [0.6, 0.9, 0.6, 1], size=30, blend="multiply"),
        {"type": "fill", "layer": 43, "rect": [400, 180, 96, 70], "color": [1, 0, 0, 1]},
        stroke(44, line(392, 214, 508, 214), [1, 1, 1, 1], size=36, opacity=0.8),
    ]
    write("layer_tree", {
        "format": 1,
        "documentId": "golden-layer-tree",
        "canvas": {"width": 512, "height": 256, "background": [0.92, 0.9, 0.86, 1]},
        "layers": layers,
        "actions": actions,
    })


if __name__ == "__main__":
    blend_modes()
    layer_tree()
