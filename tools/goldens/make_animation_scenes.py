#!/usr/bin/env python3
import json
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"

ONION_OPACITY = 0.5
ONION_FRAMES = 2
PREVIOUS = [1.0, 0.2, 0.2, 1.0]
NEXT = [0.2, 0.6, 1.0, 1.0]


def onion_opacity(distance):
    return ONION_OPACITY * (1 - distance / (ONION_FRAMES + 1))


def ball(layer, frame, color):
    x = 80 + frame * 88
    y = 200 - [0, 90, 130, 90, 0][frame]
    samples = [[x + dx, y + dy, 1.0, t * 4.0] for t, (dx, dy) in enumerate(
        [(-14, 0), (-10, -10), (0, -14), (10, -10), (14, 0), (10, 10), (0, 14), (-10, 10), (-14, 0)])]
    brush = {"preset": "round", "hardness": 0.8, "spacing": 0.05}
    return {"type": "stroke", "tool": "paint", "layer": layer, "size": 28, "opacity": 1,
            "color": color, "brush": brush, "samples": samples}


def animation_onion():
    layers = [
        {"type": "raster", "id": 1},
        {"type": "raster", "id": 11, "opacity": onion_opacity(2), "tint": PREVIOUS},
        {"type": "raster", "id": 12, "opacity": onion_opacity(1), "tint": PREVIOUS},
        {"type": "raster", "id": 13},
        {"type": "group", "id": 14, "blend": "normal", "opacity": onion_opacity(1), "children": [
            {"type": "raster", "id": 141, "tint": NEXT},
            {"type": "raster", "id": 142, "blend": "multiply", "tint": NEXT},
        ]},
        {"type": "raster", "id": 15, "opacity": onion_opacity(2), "tint": NEXT},
        {"type": "raster", "id": 99, "hidden": True},
    ]
    actions = [
        {"type": "fill", "layer": 1, "rect": [0, 0, 512, 216], "color": [0.75, 0.88, 0.97, 1]},
        {"type": "fill", "layer": 1, "rect": [0, 216, 512, 40], "color": [0.35, 0.6, 0.3, 1]},
        ball(11, 0, [0.1, 0.1, 0.1, 1]),
        ball(12, 1, [0.1, 0.1, 0.1, 1]),
        ball(13, 2, [0.9, 0.5, 0.1, 1]),
        ball(141, 3, [0.1, 0.1, 0.1, 1]),
        {"type": "fill", "layer": 142, "rect": [330, 40, 60, 60], "color": [0.8, 0.8, 0.2, 1]},
        {"type": "fill", "layer": 15, "rect": [404, 170, 60, 30], "color": [0.1, 0.1, 0.1, 0.5]},
        ball(15, 4, [0.1, 0.1, 0.1, 1]),
        {"type": "fill", "layer": 99, "rect": [0, 0, 512, 256], "color": [1, 0, 0, 1]},
    ]
    return {
        "format": 1,
        "documentId": "golden-animation-onion",
        "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]},
        "layers": layers,
        "actions": actions,
    }


def main():
    path = SCENES / "animation_onion.json"
    path.write_text(json.dumps(animation_onion(), indent=1) + "\n", encoding="utf-8")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
