#!/usr/bin/env python3
import json
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"

WIDTH = 1024
HEIGHT = 16
STRIP = 5
STEPS = 200
ALPHA = 0.03


def scene(pixel_format):
    actions = [{"type": "fill", "layer": 1, "color": [1, 1, 1, 1]}]
    for step in range(1, STEPS + 1):
        left = step * STRIP
        actions.append({"type": "select", "op": "rect", "rect": [left, 0, WIDTH - left, HEIGHT]})
        actions.append({"type": "floodFill", "layer": 1, "whole": True, "color": [0, 0, 0, ALPHA]})
    canvas = {"width": WIDTH, "height": HEIGHT, "background": [1, 1, 1, 1]}
    if pixel_format:
        canvas["pixelFormat"] = pixel_format
    return {"format": 1, "documentId": "golden-banding", "canvas": canvas, "layers": 1,
            "actions": actions}


def main():
    for name, pixel_format in (("banding", None), ("deep_banding", "rgba16f_premul_linear")):
        output = SCENES / f"{name}.json"
        output.write_text(json.dumps(scene(pixel_format), indent=1) + "\n", encoding="utf-8")
        print(f"wrote {output.name}")


if __name__ == "__main__":
    main()
