#!/usr/bin/env python3
import math
import struct
import zlib
from pathlib import Path

FOLDER = Path(__file__).resolve().parents[2] / "goldens" / "palettes"


def png(width, height, pixel):
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            rows += bytes(pixel(x, y))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")


def clamp(value):
    return max(0, min(255, int(round(value))))


def bars(x, y):
    colors = [(230, 40, 30), (250, 200, 20), (40, 170, 70), (30, 90, 210), (140, 50, 160), (20, 20, 20), (240, 240, 240)]
    red, green, blue = colors[min(len(colors) - 1, x * len(colors) // 210)]
    return red, green, blue, 255


def gradient(x, y):
    t = x / 255
    return clamp(255 * t), clamp(120 + 100 * math.sin(t * math.pi)), clamp(255 * (1 - t)), 255


def blobs(x, y):
    red, green, blue = 235.0, 225.0, 200.0
    for cx, cy, radius, color in [(60, 60, 45, (200, 60, 50)), (150, 90, 55, (60, 120, 190)), (90, 150, 40, (90, 160, 80))]:
        weight = math.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * (radius / 2) ** 2))
        red, green, blue = (red * (1 - weight) + color[0] * weight, green * (1 - weight) + color[1] * weight,
                            blue * (1 - weight) + color[2] * weight)
    alpha = 0 if x < 20 and y < 20 else 255
    return clamp(red), clamp(green), clamp(blue), alpha


def noisy(x, y):
    value = (x * 73856093) ^ (y * 19349663)
    noise = (value & 0xFF) / 255 - 0.5
    sky = y < 300
    base = (110, 160, 220) if sky else (90, 120, 60)
    return clamp(base[0] + 40 * noise), clamp(base[1] + 40 * noise), clamp(base[2] + 40 * noise), 255


def main():
    FOLDER.mkdir(parents=True, exist_ok=True)
    (FOLDER / "bars.png").write_bytes(png(210, 40, bars))
    (FOLDER / "gradient.png").write_bytes(png(256, 32, gradient))
    (FOLDER / "blobs.png").write_bytes(png(200, 200, blobs))
    (FOLDER / "noisy.png").write_bytes(png(800, 500, noisy))


if __name__ == "__main__":
    main()
