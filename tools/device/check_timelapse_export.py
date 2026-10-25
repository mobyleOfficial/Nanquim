#!/usr/bin/env python3
"""Checks timelapse replay and export on the device: two editing sessions of
fills make two segments; `timelapse.export` (a WorkManager
foreground job) writes the full length by copying the segments (frames = both segments'), and
the 30-second version with title cards by re-encoding (2 s card, every frame — fewer than 27 s of
them —, 3 s of the final artwork, 2 s card); both are decoded back. `timelapse.replay` opens the
replay over the canvas (the screen turns dark) and closes it. The artwork is deleted at the end.

    python tools/device/check_timelapse_export.py --device <serial>
"""

import argparse
import io
import os
import subprocess
import sys
import time

from PIL import Image

from kill_test import ADB, adb, launch, wait_for_artwork

AUTHORITY = "content://com.mobyle.nanquim.files/exports/"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    channel.socket.settimeout(600)
    artwork = None
    failures = []

    def session(count, offset):
        for index in range(count):
            channel.execute("selection.rect", {"x": 40 + (index % 10) * 110, "y": 100 + (index // 10) * 110 + offset, "width": 90, "height": 90})
            channel.execute("layer.fill", {"color": [index / count, 0.4, 1 - index / count, 1]})
            channel.execute("selection.clear")
            time.sleep(0.35)

    def segment_frames(segment):
        return channel.execute("debug.animation.decode", {"timelapseSegment": segment, "frames": [], "points": []})["frames"]

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": 1200, "height": 900, "name": "Timelapse export check"})["id"]
        wait_for_artwork(channel, artwork)
        session(12, 0)
        channel.execute("gallery.close")
        time.sleep(4)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        session(12, 300)
        time.sleep(1)
        started = time.time()
        channel.execute("timelapse.export", {"length": "full", "uri": AUTHORITY + "timelapse-full.mp4"})
        print(f"full export: {time.time() - started:.1f} s")
        frames = [segment_frames(1), segment_frames(2)]
        full = channel.execute("debug.animation.decode", {"name": "timelapse-full.mp4", "frames": [0], "points": [[960, 540]]})
        print("segments", frames, "full:", {key: full[key] for key in ("width", "height", "frames", "durationMilliseconds")})
        if full["frames"] != sum(frames) or (full["width"], full["height"]) != (1920, 1080):
            failures.append(f"full export: {full['frames']} frames of {full['width']}×{full['height']}, segments {frames}")
        started = time.time()
        channel.execute("timelapse.export", {"length": "30s", "cards": True, "author": "Tester", "uri": AUTHORITY + "timelapse-30s.mp4"})
        print(f"30 s export: {time.time() - started:.1f} s")
        short = channel.execute("debug.animation.decode", {"name": "timelapse-30s.mp4", "frames": [0], "points": [[960, 540]]})
        expected = 60 + sum(frames) + 90 + 60
        print("30 s:", {key: short[key] for key in ("width", "height", "frames", "durationMilliseconds")})
        if abs(short["frames"] - expected) > 2:
            failures.append(f"30 s export: {short['frames']} frames, expected about {expected}")
        card = short["colors"][0][0]
        if not all(20 <= value <= 50 for value in card[:3]):
            failures.append(f"the title card's background is {card}")
        channel.execute("timelapse.replay", {"open": True})
        time.sleep(2)
        png = subprocess.run([ADB, "-s", device, "exec-out", "screencap", "-p"], check=True, capture_output=True).stdout
        corner = Image.open(io.BytesIO(png)).convert("RGB").getpixel((40, 400))
        channel.execute("timelapse.replay", {"open": False})
        if max(corner) > 60:
            failures.append(f"the replay does not cover the canvas: {corner}")
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Timelapse replay and export passed.")


if __name__ == "__main__":
    main()
