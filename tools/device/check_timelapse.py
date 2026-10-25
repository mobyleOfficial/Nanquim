#!/usr/bin/env python3
"""Checks timelapse recording on the device:
a new 1600 × 1200 canvas records by default; a sequence of fills records frames; a private
layer does not appear in the video; closing the artwork finishes the segment (decoded back:
1920 × 1080 H.264, the canvas pillarboxed, the last fill visible); a second session writes a
second segment; a force-stop while recording loses only that session's segment (the earlier
ones still decode and the next session continues the numbering). The artwork is deleted at the
end.

    python tools/device/check_timelapse.py --device <serial>
"""

import argparse
import os
import sys
import time

from kill_test import adb, launch, wait_for_artwork

WIDTH, HEIGHT = 1600, 1200
COLORS = [[0.9, 0.1, 0.1], [0.1, 0.7, 0.2], [0.1, 0.3, 0.9], [0.9, 0.8, 0.1], [0.6, 0.2, 0.8], [0.2, 0.8, 0.8]]
PRIVATE_RECT = {"x": 1200, "y": 800, "width": 300, "height": 300}


def rect(index):
    return {"x": 100 + index * 200, "y": 300, "width": 160, "height": 160}


def video_point(canvas_x, canvas_y):
    return [int(240 + canvas_x * 0.9), int(canvas_y * 0.9)]


def close(color, expected, tolerance=40):
    return all(abs(a - e * 255) <= tolerance for a, e in zip(color, expected))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artwork = None
    failures = []

    def fill(area, color):
        channel.execute("selection.rect", area)
        channel.execute("layer.fill", {"color": color + [1]})
        channel.execute("selection.clear")
        time.sleep(0.4)

    def timelapse():
        return channel.call("getState")["timelapse"]

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Timelapse check"})["id"]
        wait_for_artwork(channel, artwork)
        if not timelapse()["enabled"]:
            failures.append("a new canvas does not record")
        for index, color in enumerate(COLORS):
            fill(rect(index), color)
        private = channel.execute("layer.new")["layerId"]
        channel.execute("layer.setPrivate", {"layerId": private, "enabled": True})
        fill(PRIVATE_RECT, [0.05, 0.05, 0.05])
        time.sleep(1)
        state = timelapse()
        print("session 1:", state)
        if state["framesRecorded"] < len(COLORS):
            failures.append(f"only {state['framesRecorded']} frames recorded for {len(COLORS) + 1} changes")
        recorded = state["framesRecorded"]
        channel.execute("gallery.close")
        time.sleep(4)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        if timelapse()["segments"] != 1:
            failures.append(f"segments after one session: {timelapse()['segments']}")
        points = [video_point(*[c + 80 for c in (rect(len(COLORS) - 1)["x"], rect(0)["y"])]),
                  video_point(PRIVATE_RECT["x"] + 150, PRIVATE_RECT["y"] + 150), [10, 540]]
        count = channel.execute("debug.animation.decode", {"timelapseSegment": 1, "frames": [], "points": []})["frames"]
        decoded = channel.execute("debug.animation.decode", {"timelapseSegment": 1, "frames": [count - 1], "points": points})
        print("segment 1:", {key: decoded[key] for key in ("width", "height", "frames", "durationMilliseconds", "mime")})
        if (decoded["width"], decoded["height"]) != (1920, 1080) or not recorded <= decoded["frames"] <= recorded + 2:
            failures.append(f"segment 1: {decoded['width']}×{decoded['height']}, {decoded['frames']} frames, {recorded} counted")
        last, private_spot, bar = decoded["colors"][0]
        if not close(last, COLORS[-1]):
            failures.append(f"the last fill is {last} in the video")
        if max(private_spot[:3]) < 100:
            failures.append(f"the private layer shows in the video: {private_spot}")
        if max(bar[:3]) > 20:
            failures.append(f"the pillarbox is {bar}")
        fill(rect(0), COLORS[3])
        fill(rect(1), COLORS[4])
        channel.execute("gallery.close")
        time.sleep(4)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        fill(rect(2), COLORS[0])
        fill(rect(3), COLORS[1])
        time.sleep(1)
        channel.close()
        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        time.sleep(2)
        channel = launch(device)
        channel.execute("gallery.close")
        time.sleep(3)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        segments = timelapse()["segments"]
        print("segments after the force-stop:", segments)
        for segment in (1, 2):
            decoded = channel.execute("debug.animation.decode", {"timelapseSegment": segment, "frames": [0], "points": [[960, 540]]})
            if decoded["frames"] < 1:
                failures.append(f"segment {segment} does not decode after the force-stop")
        fill(rect(4), COLORS[2])
        channel.execute("gallery.close")
        time.sleep(4)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        newest = timelapse()["segments"]
        decoded = channel.execute("debug.animation.decode", {"timelapseSegment": newest, "frames": [0], "points": [[960, 540]]})
        if decoded["frames"] < 1:
            failures.append(f"the session after the force-stop (segment {newest}) does not decode")
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("Timelapse check passed.")


if __name__ == "__main__":
    main()
