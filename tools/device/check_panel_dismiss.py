#!/usr/bin/env python3
"""Checks how the canvas panels open and close: the brush library and the layers
panel take turns, the color panel stays open beside either, a tap on the canvas outside them
closes them all without painting, and Back closes them too. With --phone the screen is made a
6.1" phone (`wm size 1080x2340`, `wm density 420`, restored at the end): the panels are bottom
sheets, one at a time. Runs on a new canvas, deleted at the end.

    python tools/device/check_panel_dismiss.py --device <serial> [--phone]
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


def library_open(device):
    return re.search(r'text="(Brush library|Biblioteca de pinc)', screen(device)) is not None


def panels(channel, device):
    state = channel.call("getState")
    return {
        "library": library_open(device),
        "layers": state["layersPanelVisible"],
        "color": state["colorPanelVisible"],
    }


def screen_size(device):
    width, height = re.findall(r"(\d+)x(\d+)", adb(device, "shell", "wm", "size"))[-1]
    return int(width), int(height)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--phone", action="store_true", help="simulate a 6.1-inch phone")
    arguments = parser.parse_args()
    device = arguments.device
    phone = arguments.phone
    if phone:
        adb(device, "shell", "wm", "size", "1080x2340")
        adb(device, "shell", "wm", "density", "420")
    rotation_settings = {name: adb(device, "shell", "settings", "get", "system", name).strip()
                         for name in ("accelerometer_rotation", "user_rotation")}
    adb(device, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb(device, "shell", "settings", "put", "system", "user_rotation", "0")
    time.sleep(2)
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    adb(device, "shell", "cmd", "statusbar", "collapse")
    artwork = None
    failures = []

    def expect(label, expected):
        time.sleep(1)
        shown = panels(channel, device)
        status = "ok" if shown == expected else "FAILED"
        print(f"{label}: {shown} {status}")
        if shown != expected:
            failures.append(f"{label}: {shown}, expected {expected}")

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Panel dismiss check"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("view.fit")
        channel.execute("tool.select", {"tool": "brush"})
        time.sleep(1)

        channel.execute("brush.library.open", {"tool": "paint"})
        channel.execute("color.panel.open")
        if phone:
            expect("color replaces the library", {"library": False, "layers": False, "color": True})
            channel.execute("view.layersPanel", {"visible": True})
            expect("layers replaces color", {"library": False, "layers": True, "color": False})
            channel.execute("brush.library.open", {"tool": "paint"})
            expect("library replaces layers", {"library": True, "layers": False, "color": False})
        else:
            expect("library + color", {"library": True, "layers": False, "color": True})
            channel.execute("view.layersPanel", {"visible": True})
            expect("layers replaces the library", {"library": False, "layers": True, "color": True})
            channel.execute("brush.library.open", {"tool": "paint"})
            expect("library replaces layers", {"library": True, "layers": False, "color": True})

        width, height = screen_size(device)
        undo_before = channel.call("getState")["history"]["undo"]
        adb(device, "shell", "input", "tap", str(width // 2), str(height // 4 if phone else height - 150))
        expect("tap outside", {"library": False, "layers": False, "color": False})
        undo_after = channel.call("getState")["history"]["undo"]
        if undo_after != undo_before:
            failures.append(f"the dismissing tap painted (undo steps {undo_before} -> {undo_after})")

        channel.execute("view.layersPanel", {"visible": True})
        channel.execute("color.panel.open")
        adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
        expect("back", {"library": False, "layers": False, "color": False})
        if channel.call("getState")["document"] is None:
            failures.append("Back left the canvas instead of closing the panels")
    finally:
        try:
            if artwork is not None:
                channel.execute("gallery.close")
                time.sleep(3)
                channel.execute("gallery.delete", {"ids": [artwork]})
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
        if phone:
            adb(device, "shell", "wm", "size", "reset")
            adb(device, "shell", "wm", "density", "reset")
        for name, value in rotation_settings.items():
            if value.isdigit():
                adb(device, "shell", "settings", "put", "system", name, value)
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
