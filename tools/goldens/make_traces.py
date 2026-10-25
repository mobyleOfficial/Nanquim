#!/usr/bin/env python3
import math
import struct
from pathlib import Path

SCENES = Path(__file__).resolve().parents[2] / "goldens" / "scenes"
TOOL_TYPE_STYLUS = 2
SAMPLE_RATE_HZ = 240


def pack(x, y, pressure, seconds):
    return struct.pack("<6fq4i", x, y, pressure, 0.0, 0.0, 0.0, round(seconds * 1e9),
                       TOOL_TYPE_STYLUS, 0, 0, 0)


def write(name, points):
    data = b"".join(pack(x, y, p, index / SAMPLE_RATE_HZ) for index, (x, y, p) in enumerate(points))
    (SCENES / name).write_bytes(data)


def s_curve(count=180):
    points = []
    for index in range(count):
        t = index / (count - 1)
        x = 40 + 432 * t
        y = 128 - 80 * math.sin(2 * math.pi * t)
        pressure = 0.15 + 0.85 * math.sin(math.pi * t)
        points.append((x, y, pressure))
    return points


def fast_diagonal(count=12):
    return [(30 + 40 * i, 230 - 18 * i, 0.8) for i in range(count)]


def eraser_line(count=60):
    return [(256 + 6 * math.sin(i / 6), 10 + 4 * i, 1.0) for i in range(count)]


if __name__ == "__main__":
    write("round_brush_s_curve.trace", s_curve())
    write("round_brush_fast_diagonal.trace", fast_diagonal())
    write("round_brush_eraser_line.trace", eraser_line())
