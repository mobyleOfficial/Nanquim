#!/usr/bin/env python3
import os

from PIL import Image

REPOSITORY = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SOURCE = os.path.join(REPOSITORY, "art", "app-icon", "ic_nanquim_app_icon.jpeg")
RESOURCES = os.path.join(REPOSITORY, "app", "src", "main", "res")
SCALE = 0.70
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LAYER_DP = 108


def glyph(source):
    width, height = source.size
    pixels = source.load()
    row_saturation = []
    for y in range(height):
        row_saturation.append(max(max(pixels[x, y]) - min(pixels[x, y]) for x in range(width)))
    out = Image.new("RGBA", source.size)
    result = out.load()
    for y in range(height):
        background = pixels[2, y]
        full = max(40, row_saturation[y])
        for x in range(width):
            red, green, blue = pixels[x, y]
            saturation = max(red, green, blue) - min(red, green, blue)
            alpha = min(1.0, max(0.0, (saturation - 4) / (full - 4)))
            if alpha <= 0.0:
                result[x, y] = (0, 0, 0, 0)
                continue
            color = tuple(
                int(round(min(255.0, max(0.0, (channel - (1 - alpha) * base) / alpha))))
                for channel, base in zip((red, green, blue), background)
            )
            result[x, y] = color + (int(round(alpha * 255)),)
    return out


def main():
    cut = glyph(Image.open(SOURCE).convert("RGB"))
    for density, factor in DENSITIES.items():
        side = int(round(LAYER_DP * factor))
        inner = int(round(side * SCALE))
        layer = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        offset = (side - inner) // 2
        layer.alpha_composite(cut.resize((inner, inner), Image.LANCZOS), (offset, offset))
        folder = os.path.join(RESOURCES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, "ic_launcher_foreground.png")
        layer.save(path, optimize=True)
        print(f"{path}: {side} px")


if __name__ == "__main__":
    main()
