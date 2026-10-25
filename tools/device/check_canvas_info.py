#!/usr/bin/env python3
"""Checks canvas information on the device:
`canvas.info.get` answers the dimensions, layers and their maximum, profile and depth, timelapse
settings and statistics; strokes count up and tracked time grows between edits; the dialog
opens and shows its sections; a signature drawn with a finger in the dialog and the artist name
are saved and survive closing and reopening the artwork. The artwork is deleted at the end.

    python tools/device/check_canvas_info.py --device <serial>
"""

import argparse
import os
import re
import sys
import time

from kill_test import adb, launch, wait_for_artwork

WIDTH, HEIGHT = 1200, 800


def screen(device):
    adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
    return adb(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml")


def bounds_of(xml, pattern, attribute="text|content-desc"):
    for node in re.findall(r"<node [^>]*>", xml):
        if re.search(f'({attribute})="({pattern})"', node):
            return tuple(int(value) for value in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    adb(device, "shell", "cmd", "statusbar", "collapse")
    artwork = None
    failures = []

    def stroke(y):
        points = [[200 + step * 40, y, 0.6, step * 16] for step in range(15)]
        channel.execute("tool.paint.strokePath", {"samples": points})

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Info check"})["id"]
        wait_for_artwork(channel, artwork)
        info = channel.execute("canvas.info.get")
        print(info)
        if (info["width"], info["height"]) != (WIDTH, HEIGHT) or info["layers"] < 1 or info["maximumLayers"] < info["layers"]:
            failures.append(f"unexpected dimensions or layers: {info}")
        if info["colorProfile"] != "sRGB" or info["bitsPerChannel"] != 8 or info["strokes"] != 0:
            failures.append(f"unexpected color or statistics: {info}")

        stroke(200)
        time.sleep(2.5)
        stroke(300)
        time.sleep(2.5)
        stroke(400)
        info = channel.execute("canvas.info.get")
        if info["strokes"] != 3:
            failures.append(f"strokes counted {info['strokes']}, expected 3")
        if not 3 <= info["timeSpentSeconds"] <= 8:
            failures.append(f"tracked time {info['timeSpentSeconds']} s after two 2.5 s gaps")
        else:
            print(f"{info['strokes']} strokes, {info['timeSpentSeconds']} s tracked")

        channel.execute("canvas.info.show", {"visible": True})
        time.sleep(1.5)
        xml = screen(device)
        shown = set(re.findall(r'text="([^"]+)"', xml))
        if not any(re.fullmatch(r"Canvas information|Informa.{1,4}es da tela", text) for text in shown):
            failures.append("the dialog did not show its sections")
        pad = bounds_of(xml, "Signature|Assinatura", attribute="content-desc")
        name_field = bounds_of(xml, "Artist name|Nome do artista")
        if pad is None or name_field is None:
            failures.append("no signature box or name field on screen (it may need scrolling)")
        else:
            left, top, right, bottom = pad
            adb(device, "shell", "input", "touchscreen", "swipe", str(left + 30), str((top + bottom) // 2), str(right - 30), str(top + 20), "400")
            time.sleep(0.5)
            adb(device, "shell", "input", "touchscreen", "tap", str((name_field[0] + name_field[2]) // 2), str((name_field[1] + name_field[3]) // 2))
            time.sleep(0.5)
            adb(device, "shell", "input", "text", "Ana")
            time.sleep(0.5)
            adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)
            done = bounds_of(screen(device), "Done|Concluir")
            if done is None:
                failures.append("no Done button")
            else:
                adb(device, "shell", "input", "touchscreen", "tap", str((done[0] + done[2]) // 2), str((done[1] + done[3]) // 2))
                time.sleep(1)
            info = channel.execute("canvas.info.get")
            if info["artist"] != "Ana" or info["signatureStrokes"] != 1:
                failures.append(f"author not saved from the dialog: {info['artist']!r}, {info['signatureStrokes']} strokes")

        channel.execute("gallery.close")
        time.sleep(3)
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)
        info = channel.execute("canvas.info.get")
        if info["artist"] != "Ana" or info["signatureStrokes"] != 1 or info["strokes"] != 3 or info["timeSpentSeconds"] < 3:
            failures.append(f"not kept after reopening: {info}")
        else:
            print("author, signature and statistics kept after reopening")
    finally:
        try:
            channel.execute("canvas.info.show", {"visible": False})
            if artwork is not None:
                channel.execute("gallery.close")
                time.sleep(3)
                channel.execute("gallery.delete", {"ids": [artwork]})
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print("canvas information OK")


if __name__ == "__main__":
    main()
