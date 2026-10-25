#!/usr/bin/env python3
import json
import math
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"
FONTS = {
    "NotoSans-Regular": "../fonts/NotoSans-Regular-subset.ttf",
    "NotoSerif-Regular": "../fonts/NotoSerif-Regular-subset.ttf",
    "NotoSansArabic-Regular": "../fonts/NotoSansArabic-Regular-subset.ttf",
    "NotoSansHebrew-Regular": "../fonts/NotoSansHebrew-Regular-test.ttf",
    "NotoSansCJKjp-Regular": "../fonts/NotoSansCJKjp-Regular-subset.otf",
}
FALLBACKS = ["NotoSans-Regular", "NotoSansArabic-Regular", "NotoSansHebrew-Regular", "NotoSansCJKjp-Regular"]


def style(font, size, color, **extra):
    result = {"fontId": font, "sizePt": size, "color": color}
    result.update(extra)
    return result


def rotation(degrees, scale, tx, ty):
    angle = math.radians(degrees)
    cos, sin = math.cos(angle) * scale, math.sin(angle) * scale
    return [round(cos, 6), round(sin, 6), round(-sin, 6), round(cos, 6), tx, ty]


def scene(name, width, height, model, pixel_format=None):
    document = {
        "format": 1,
        "documentId": f"golden-{name.replace('_', '-')}",
        "canvas": {"width": width, "height": height, "background": [1, 1, 1, 1]},
        "layers": 1,
        "actions": [{"type": "text", "layer": 1, "dpi": 72, "fonts": FONTS, "fallbacks": FALLBACKS, "model": model}],
    }
    if pixel_format:
        document["canvas"]["pixelFormat"] = pixel_format
    (SCENES / f"{name}.json").write_text(json.dumps(document, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")


def main():
    latin = {
        "format": 1,
        "content": [
            {"text": "Nanquim AVAWA ffi fl\n", "style": "s0"},
            {"text": "outlined ", "style": "s1"},
            {"text": "Serif", "style": "s2"},
            {"text": " shifted", "style": "s3"},
        ],
        "styles": {
            "s0": style("NotoSans-Regular", 40, [0.1, 0.1, 0.12, 1], underline=True),
            "s1": style("NotoSans-Regular", 36, [1, 0.85, 0.2, 1], outline={"width": 2, "color": [0.15, 0.2, 0.6, 1]}),
            "s2": style("NotoSerif-Regular", 44, [0.7, 0.1, 0.15, 0.8], strikethrough=True, tracking=80),
            "s3": style("NotoSans-Regular", 24, [0.1, 0.5, 0.2, 1], baselineShift=10, allCaps=True),
        },
        "paragraph": {"alignment": "left", "lineHeight": 1.25, "vertical": False, "direction": "auto"},
        "box": {"mode": "auto", "width": 0, "height": 0},
        "transform": rotation(-6, 1.1, 24, 40),
    }
    scene("text_latin", 560, 200, latin)
    scene("text_latin_deep", 560, 200, latin, "rgba16f_premul_linear")
    scene("text_arabic", 520, 200, {
        "format": 1,
        "content": [{"text": "مرحبا بالعالم 123\nسلام عليكم", "style": "s0"}],
        "styles": {"s0": style("NotoSansArabic-Regular", 40, [0.05, 0.25, 0.45, 1])},
        "paragraph": {"alignment": "right", "lineHeight": 1.4, "vertical": False, "direction": "auto"},
        "box": {"mode": "fixed", "width": 480, "height": 160},
        "transform": [1, 0, 0, 1, 20, 16],
    })
    scene("text_hebrew", 520, 140, {
        "format": 1,
        "content": [{"text": "שלום עולם (abc) 2026!", "style": "s0"}],
        "styles": {"s0": style("NotoSansHebrew-Regular", 40, [0.35, 0.1, 0.4, 1])},
        "paragraph": {"alignment": "right", "lineHeight": 1.2, "vertical": False, "direction": "auto"},
        "box": {"mode": "fixed", "width": 480, "height": 100},
        "transform": [1, 0, 0, 1, 20, 20],
    })
    scene("text_cjk_vertical", 360, 420, {
        "format": 1,
        "content": [{"text": "吾輩は猫である。名前はまだ無い。縦書き「テスト」ABC123", "style": "s0"}],
        "styles": {"s0": style("NotoSansCJKjp-Regular", 36, [0.1, 0.1, 0.1, 1])},
        "paragraph": {"alignment": "left", "lineHeight": 1.3, "vertical": True, "direction": "auto"},
        "box": {"mode": "fixed", "width": 320, "height": 380},
        "transform": [1, 0, 0, 1, 20, 20],
    })


if __name__ == "__main__":
    main()
