#!/usr/bin/env python3
import json
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"

SOURCES = [
    "brush_flat", "brush_glaze", "brush_smudge", "brush_wet_mix", "brush_stamp", "brush_dual",
    "brush_edges", "fill_threshold", "blend_modes", "layer_tree", "adjust_hsb", "adjust_curves",
    "adjust_gaussian_blur", "liquify_modes", "selection_basic", "clone",
    "transform_modes", "animation_onion",
]


def main():
    for name in SOURCES:
        scene = json.loads((SCENES / f"{name}.json").read_text(encoding="utf-8"))
        scene["canvas"]["pixelFormat"] = "rgba16f_premul_linear"
        if name == "fill_threshold":
            scene["actions"] = [
                action for action in scene["actions"]
                if not (action["type"] == "floodFill" and action.get("source") == 3)
            ]
        output = SCENES / f"deep_{name}.json"
        output.write_text(json.dumps(scene, indent=1) + "\n", encoding="utf-8")
        print(f"wrote {output.name}")


if __name__ == "__main__":
    main()
