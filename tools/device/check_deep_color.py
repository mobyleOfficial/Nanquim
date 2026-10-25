import argparse
import os
import time

from kill_test import PACKAGE, adb, launch, wait_for_artwork


def pick(channel, x, y):
    color = channel.execute("color.eyedropper", {"x": x, "y": y})
    return tuple(round(color[channel_name] * 255) for channel_name in ("red", "green", "blue"))


def close_enough(a, b, tolerance=2):
    return all(abs(x - y) <= tolerance for x, y in zip(a, b))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--size", type=int, default=2048)
    parser.add_argument("--bits", type=int, default=16)
    arguments = parser.parse_args()
    size = arguments.size
    middle = size / 2

    adb(arguments.device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(arguments.device)
    channel.execute("gallery.close")
    artwork_id = channel.execute(
        "gallery.newCanvas", {"width": size, "height": size, "name": f"Deep color {arguments.bits}", "bitsPerChannel": arguments.bits}
    )["id"]
    wait_for_artwork(channel, artwork_id)
    white = pick(channel, middle, middle)
    painted_color = (0xC8, 0x3C, 0x1E)
    channel.execute("color.setPrimary", {"hex": "C83C1E"})
    channel.execute("brush.setSize", {"sizePx": 120})
    samples = [[size * 0.2 + step * size * 0.6 / 40, middle, 1.0, step * 8] for step in range(41)]
    channel.execute("tool.paint.strokePath", {"samples": samples})
    time.sleep(0.5)
    painted = pick(channel, middle, middle)
    channel.execute("edit.undo")
    time.sleep(0.5)
    undone = pick(channel, middle, middle)
    channel.execute("edit.redo")
    time.sleep(0.5)
    redone = pick(channel, middle, middle)
    channel.execute("document.save")
    wait_for_artwork(channel, artwork_id)
    channel.execute("gallery.close")
    time.sleep(1)
    channel.execute("gallery.open", {"id": artwork_id})
    wait_for_artwork(channel, artwork_id)
    time.sleep(0.5)
    reopened = pick(channel, middle, middle)
    bits = channel.call("getState")["document"]["bitsPerChannel"]
    channel.close()

    manifest = adb(
        arguments.device, "shell", "run-as", PACKAGE, "sh", "-c", f"find files -path '*{artwork_id}*' -name manifest.json -exec cat {{}} +", check=False
    )
    print(f"white {white} painted {painted} undone {undone} redone {redone} reopened {reopened}")
    print("manifest pixelFormat:", "rgba16f_premul_linear" if "rgba16f_premul_linear" in manifest else "rgba8" if manifest else "not readable (non-debuggable build)")
    print("reopened with", bits, "bits per channel")
    checks = {
        "reopens with its color depth": bits == arguments.bits,
        "undo restores white": close_enough(undone, white),
        "redo restores the stroke": close_enough(redone, painted),
        "reopen keeps the stroke": close_enough(reopened, painted),
        "the stroke changed the canvas": not close_enough(painted, white, 20),
        "the painted color reads back": close_enough(painted, painted_color),
    }
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
