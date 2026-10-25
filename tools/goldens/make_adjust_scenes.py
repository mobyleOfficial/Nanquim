#!/usr/bin/env python3
import json
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"


def bands():
    actions = []
    for layer in range(1, 5):
        y = (layer - 1) * 64
        actions.append({"type": "fill", "layer": layer, "rect": [0, y, 256, 64], "color": [0.9, 0.1, 0.1, 1],
                        "toColor": [0.2, 0.8, 0.3, 0.6], "direction": "horizontal"})
        actions.append({"type": "fill", "layer": layer, "rect": [256, y, 256, 64], "color": [0.1, 0.3, 0.9, 0.8],
                        "toColor": [0.95, 0.85, 0.2, 1], "direction": "horizontal"})
        actions.append({"type": "fill", "layer": layer, "rect": [0, y + 48, 512, 16], "color": [0, 0, 0, 1],
                        "toColor": [1, 1, 1, 1], "direction": "horizontal"})
    return actions


def scene(name, filter_name, settings):
    actions = bands()
    for index, fields in enumerate(settings):
        if index == 2:
            actions.append({"type": "select", "op": "ellipse", "rect": [96, 176, 320, 96]})
            actions.append({"type": "select", "op": "feather", "radius": 12})
        actions.append({"type": "adjust", "layer": index + 2, "filter": filter_name, **fields})
    document = {"format": 1, "documentId": f"golden-{name.replace('_', '-')}",
                "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]}, "layers": 4, "actions": actions}
    with open(SCENES / f"{name}.json", "w", encoding="utf-8", newline="\n") as file:
        json.dump(document, file, indent=1)
        file.write("\n")


def line(x0, y0, x1, y1, count=40, pressure=(1.0, 1.0)):
    return [[round(x0 + (x1 - x0) * i / (count - 1), 3), round(y0 + (y1 - y0) * i / (count - 1), 3),
             round(pressure[0] + (pressure[1] - pressure[0]) * i / (count - 1), 3), 4.0 * i] for i in range(count)]


def mask_stroke(samples, tool="paint", size=36, opacity=1.0):
    return {"type": "stroke", "tool": tool, "target": "adjustmentMask",
            "brush": {"preset": "round", "hardness": 0.5, "spacing": 0.05}, "size": size, "opacity": opacity,
            "color": [1, 1, 1, 1], "samples": samples}


def pencil_scene():
    actions = bands()
    actions.append(mask_stroke(line(20, 70, 490, 120, pressure=(0.3, 1.0))))
    actions.append({"type": "adjust", "layer": 2, "filter": "hueSaturationBrightness", "pencil": True,
                    "parameters": {"hue": 0.6}})
    actions.append(mask_stroke(line(30, 160, 480, 160), size=48, opacity=0.5))
    actions.append(mask_stroke(line(200, 130, 260, 190), tool="erase", size=30))
    actions.append({"type": "adjust", "layer": 3, "filter": "curves", "pencil": True,
                    "curves": {"master": [[0, 1], [1, 0]]}})
    actions.append({"type": "select", "op": "rect", "rect": [128, 192, 256, 64]})
    actions.append(mask_stroke(line(60, 196, 450, 250, pressure=(1.0, 0.5)), size=22))
    actions.append({"type": "adjust", "layer": 4, "filter": "gradientMap", "pencil": True,
                    "parameters": {"amount": 1}, "gradient": [[0, 0.1, 0.0, 0.3], [1, 1, 0.9, 0.2]]})
    document = {"format": 1, "documentId": "golden-adjust-pencil",
                "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]}, "layers": 4, "actions": actions}
    with open(SCENES / "adjust_pencil.json", "w", encoding="utf-8", newline="\n") as file:
        json.dump(document, file, indent=1)
        file.write("\n")


def liquify_stroke(mode, samples, size=0.3, pressure=1.0, distortion=0.5, momentum=0.0):
    return {"mode": mode, "size": size, "pressure": pressure, "distortion": distortion, "momentum": momentum,
            "samples": samples}


def held(x, y, count=12):
    return [[x, y, 1.0, 4.0 * i] for i in range(count)]


def liquify_scene():
    actions = bands()
    actions.append({"type": "liquify", "layer": 2, "strokes": [
        liquify_stroke("push", line(40, 100, 200, 80, count=30), size=0.4),
        liquify_stroke("push", line(260, 70, 330, 110, count=12), size=0.4, momentum=1),
        liquify_stroke("twirlRight", held(256, 100, 24), size=0.5),
        liquify_stroke("twirlLeft", held(430, 114, 24), size=0.4),
    ]})
    actions.append({"type": "liquify", "layer": 3, "adjust": 0.8, "strokes": [
        liquify_stroke("pinch", held(256, 160, 30), size=0.5),
        liquify_stroke("expand", held(100, 178, 30), size=0.5),
        liquify_stroke("crystals", line(250, 150, 330, 170, count=10), size=0.4, distortion=1),
        liquify_stroke("edge", held(420, 178, 30), size=0.5, distortion=0.8),
    ]})
    actions.append({"type": "select", "op": "ellipse", "rect": [96, 176, 320, 96]})
    actions.append({"type": "liquify", "layer": 4, "strokes": [
        liquify_stroke("push", line(100, 230, 400, 210, count=40), size=0.5, pressure=0.8),
        liquify_stroke("reconstruct", line(200, 220, 300, 220, count=20), size=0.4),
    ]})
    document = {"format": 1, "documentId": "golden-liquify-modes",
                "canvas": {"width": 512, "height": 256, "background": [1, 1, 1, 1]}, "layers": 4, "actions": actions}
    with open(SCENES / "liquify_modes.json", "w", encoding="utf-8", newline="\n") as file:
        json.dump(document, file, indent=1)
        file.write("\n")


def main():
    scene("adjust_hsb", "hueSaturationBrightness", [
        {"parameters": {"hue": 0.5}},
        {"parameters": {"hue": -0.25, "saturation": 0.6, "brightness": -0.4}},
        {"parameters": {"saturation": -0.8, "brightness": 0.5}, "amount": 0.75},
    ])
    scene("adjust_color_balance", "colorBalance", [
        {"parameters": {"shadowsCyanRed": -0.8, "shadowsYellowBlue": 0.8}},
        {"parameters": {"midtonesCyanRed": 0.7, "midtonesMagentaGreen": -0.5, "midtonesYellowBlue": -0.4}},
        {"parameters": {"highlightsMagentaGreen": -0.9, "highlightsYellowBlue": -0.6, "shadowsMagentaGreen": 0.5}},
    ])
    scene("adjust_curves", "curves", [
        {"curves": {"master": [[0, 0], [0.25, 0.1], [0.75, 0.9], [1, 1]]}},
        {"curves": {"red": [[0, 0.2], [0.5, 0.8], [1, 1]], "blue": [[0, 0], [0.6, 0.3], [1, 0.7]],
                    "master": [[0, 1], [1, 0]]}},
        {"curves": {"green": [[0, 0], [0.3, 0.6], [1, 1]], "alpha": [[0, 0], [0.7, 0.2], [1, 1]]}},
    ])
    scene("adjust_gradient_map", "gradientMap", [
        {"parameters": {"amount": 1}, "gradient": [[0, 0.1, 0.0, 0.3], [0.5, 0.9, 0.2, 0.4], [1, 1, 0.95, 0.6]]},
        {"parameters": {"amount": 1}, "gradient": [[0, 0, 0.2, 0.2], [0.3, 0, 0.2, 0.2], [0.7, 0.9, 0.9, 0.7],
                                                   [1, 1, 1, 1]]},
        {"parameters": {"amount": 0.6}, "gradient": [[0, 0.9, 0.3, 0.1], [1, 0.1, 0.4, 0.9]]},
    ])
    scene("adjust_gaussian_blur", "gaussianBlur", [
        {"parameters": {"radius": 0.15}},
        {"parameters": {"radius": 0.5}},
        {"parameters": {"radius": 1}, "amount": 0.75},
    ])
    scene("adjust_motion_blur", "motionBlur", [
        {"parameters": {"amount": 0.6}},
        {"parameters": {"amount": 1, "angle": 0.25}},
        {"parameters": {"amount": 0.8, "angle": -0.6}},
    ])
    scene("adjust_perspective_blur", "perspectiveBlur", [
        {"parameters": {"amount": 0.5, "x": 0.3, "y": 0.4}},
        {"parameters": {"amount": 0.6, "x": 0.7, "y": 0.5, "directional": 1, "angle": 0.1}},
        {"parameters": {"amount": 1, "x": 0.5, "y": 0.8}},
    ])
    scene("adjust_sharpen", "sharpen", [
        {"parameters": {"amount": 0.3}},
        {"parameters": {"amount": 0.7}},
        {"parameters": {"amount": 1}},
    ])
    scene("adjust_noise", "noise", [
        {"parameters": {"amount": 0.6, "scale": 0.2, "octaves": 0.43}},
        {"parameters": {"amount": 0.8, "scale": 0.5, "octaves": 0, "turbulence": 0.6, "type": 1}},
        {"parameters": {"amount": 1, "scale": 0.3, "octaves": 1, "type": 2}, "amount": 0.75},
    ])
    scene("adjust_bloom", "bloom", [
        {"parameters": {"size": 0.3, "transition": 0.5, "burn": 0.3}},
        {"parameters": {"size": 0.6, "transition": 0.3, "burn": 1}},
        {"parameters": {"size": 1, "transition": 0.7, "burn": 0.5}},
    ])
    scene("adjust_glitch", "glitch", [
        {"parameters": {"amount": 0.4, "blockSize": 0.3, "zoom": 0.5, "frequency": 0.5}},
        {"parameters": {"amount": 0.3, "blockSize": 0.5, "zoom": 0.5, "frequency": 0.5, "type": 1}},
        {"parameters": {"amount": 0.3, "blockSize": 0.4, "zoom": 0.6, "frequency": 0.6, "type": 2}},
    ])
    scene("adjust_halftone", "halftone", [
        {"parameters": {"dotSize": 0.2}},
        {"parameters": {"dotSize": 0.4, "style": 1}},
        {"parameters": {"dotSize": 0.3, "style": 2}},
    ])
    scene("adjust_chromatic_aberration", "chromaticAberration", [
        {"parameters": {"amount": 1, "x": 0.5, "y": 0.5, "falloff": 0.5}},
        {"parameters": {"amount": 0.7, "x": 0.5, "y": 0.5, "displace": 1, "angle": 0.25, "falloff": 0}},
        {"parameters": {"amount": 0.6, "x": 0.3, "y": 0.7, "falloff": 0.5, "transparency": 1}},
    ])
    pencil_scene()
    liquify_scene()


if __name__ == "__main__":
    main()
