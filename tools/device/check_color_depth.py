import argparse
import os
import time

from kill_test import PACKAGE, adb, launch, wait_for_artwork


def pick(channel, x, y):
    color = channel.execute("color.eyedropper", {"x": x, "y": y})
    return tuple(round(color[name] * 255) for name in ("red", "green", "blue"))


def close_enough(a, b, tolerance):
    return all(abs(x - y) <= tolerance for x, y in zip(a, b))


def line(x0, y0, x1, y1, steps=40):
    return [[x0 + (x1 - x0) * step / steps, y0 + (y1 - y0) * step / steps, 1.0, step * 8] for step in range(steps + 1)]


def edge(channel, x, y, direction):
    middle = pick(channel, x, y)
    for offset in range(2, 120, 2):
        color = pick(channel, x, y + direction * offset)
        if not close_enough(color, middle, 5) and not close_enough(color, (255, 255, 255), 5):
            return (x, y + direction * offset)
    return (x, y + direction * 120)


def state(channel):
    current = channel.call("getState")
    return current["document"]["bitsPerChannel"], current["history"]["undo"]


def convert(channel, artwork_id, bits):
    start = time.time()
    channel.execute("canvas.colorDepth", {"bitsPerChannel": bits})
    seconds = time.time() - start
    wait_for_artwork(channel, artwork_id)
    return seconds


def reopen(channel, artwork_id):
    channel.execute("gallery.close")
    time.sleep(3)
    channel.execute("gallery.open", {"id": artwork_id})
    wait_for_artwork(channel, artwork_id)
    time.sleep(0.5)


def check_colors(device, size):
    channel = launch(device)
    channel.execute("gallery.close")
    time.sleep(3)
    artwork_id = channel.execute("gallery.newCanvas", {"width": size, "height": size, "name": "Color depth"})["id"]
    wait_for_artwork(channel, artwork_id)
    channel.execute("tool.select", {"tool": "brush"})
    channel.execute("brush.setSize", {"sizePx": 120})
    channel.execute("color.setPrimary", {"hex": "C83C1E"})
    channel.execute("tool.paint.strokePath", {"samples": line(size * 0.2, size * 0.4, size * 0.8, size * 0.4)})
    channel.execute("layer.new")
    channel.execute("color.setPrimary", {"hex": "1E64B4"})
    channel.execute("tool.paint.strokePath", {"samples": line(size * 0.2, size * 0.6, size * 0.8, size * 0.6)})
    wait_for_artwork(channel, artwork_id)
    points = [(size / 2, size * 0.4), edge(channel, size / 2, size * 0.4, 1), (size / 2, size * 0.6), edge(channel, size / 2, size * 0.6, -1)]
    before = [pick(channel, x, y) for x, y in points]
    results = {}
    for bits in (16, 8):
        seconds = convert(channel, artwork_id, bits)
        converted = [pick(channel, x, y) for x, y in points]
        depth, undo = state(channel)
        reopen(channel, artwork_id)
        reopened = [pick(channel, x, y) for x, y in points]
        reopened_depth, _ = state(channel)
        print(f"to {bits} bits in {seconds:.2f} s: {converted}; reopened {reopened} ({reopened_depth} bits)")
        results[bits] = (converted, depth, undo, reopened, reopened_depth)
    channel.close()
    print("before", before)
    points_count = len(before)
    checks = {}
    for bits, (converted, depth, undo, reopened, reopened_depth) in results.items():
        checks[f"{bits} bits: the depth changes"] = depth == bits
        checks[f"{bits} bits: the history starts empty"] = undo == 0
        compared = [0, 2] if bits == 16 else range(points_count)
        checks[f"{bits} bits: the colors survive the conversion"] = all(close_enough(before[i], converted[i], 1) for i in compared)
        checks[f"{bits} bits: reopening keeps the depth"] = reopened_depth == bits
        checks[f"{bits} bits: reopening keeps the colors"] = converted == reopened
    white = (255, 255, 255)
    checks["the strokes are there"] = not close_enough(before[0], white, 20) and not close_enough(before[2], white, 20)
    checks["the edges are partial"] = all(not close_enough(before[i], white, 5) and not close_enough(before[i], before[i - 1], 5) for i in (1, 3))
    return checks


def time_conversion(device):
    channel = launch(device)
    channel.execute("gallery.close")
    time.sleep(3)
    artwork_id = channel.execute("gallery.newCanvas", {"width": 4096, "height": 4096, "name": "Color depth timing"})["id"]
    wait_for_artwork(channel, artwork_id)
    for _ in range(4):
        channel.execute("layer.new")
    channel.execute("debug.layer.fillRandomTiles", {"layerId": 0, "count": -1, "seed": 7})
    channel.execute("document.save")
    wait_for_artwork(channel, artwork_id)
    to_deep = convert(channel, artwork_id, 16)
    to_eight = convert(channel, artwork_id, 8)
    channel.close()
    print(f"4096x4096, 5 full layers: 8 to 16 bits {to_deep:.2f} s, 16 to 8 bits {to_eight:.2f} s")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--size", type=int, default=2048)
    parser.add_argument("--skip-timing", action="store_true")
    arguments = parser.parse_args()
    adb(arguments.device, "shell", "am", "force-stop", PACKAGE)
    checks = check_colors(arguments.device, arguments.size)
    if not arguments.skip_timing:
        time_conversion(arguments.device)
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
