#!/usr/bin/env python3
"""Checks the gesture arbiter's device side:
painting by finger (`settings.fingerPainting.set`) — a one-finger swipe changes the canvas only
when it is on, and a short tap never does; full screen (`view.fullScreen`) hides the top bar and
shows the exit button; the copy & paste menu (`view.copyPasteMenu`) opens and closes. Multi-finger
gestures cannot be driven with `adb input` and are covered by the JVM tests. The artwork is
deleted at the end and painting by finger is turned back off.

    python tools/device/check_gestures.py --device <serial>
"""

import argparse
import os
import re
import sys
import time

from kill_test import adb, composite_hash, launch, wait_for_artwork

WIDTH, HEIGHT = 1200, 800


def screen_texts(device):
    adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
    xml = adb(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml")
    return set(re.findall(r'text="([^"]+)"', xml))


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
    size = adb(device, "shell", "wm", "size")
    screen_width, screen_height = (int(value) for value in re.findall(r"(\d+)x(\d+)", size)[-1])
    if screen_width < screen_height:
        screen_width, screen_height = screen_height, screen_width
    middle_x, middle_y = screen_width // 2, screen_height // 2

    def swipe(dx, duration_millis):
        adb(device, "shell", "input", "touchscreen", "swipe", str(middle_x), str(middle_y), str(middle_x + dx), str(middle_y + 40), str(duration_millis))
        time.sleep(1.5)

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Gesture check"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("view.fit")
        channel.execute("tool.select", {"tool": "brush"})
        time.sleep(1)

        channel.execute("settings.fingerPainting.set", {"enabled": False})
        before = composite_hash(channel)
        swipe(400, 300)
        if composite_hash(channel) != before:
            failures.append("a finger swipe painted with painting by finger off")

        channel.execute("settings.fingerPainting.set", {"enabled": True})
        before = composite_hash(channel)
        adb(device, "shell", "input", "touchscreen", "tap", str(middle_x - 300), str(middle_y - 200))
        time.sleep(1.5)
        if composite_hash(channel) != before:
            failures.append("a one-finger tap left a mark")
        swipe(400, 300)
        painted = composite_hash(channel)
        if painted == before:
            failures.append("a finger swipe did not paint with painting by finger on")
        else:
            print("finger swipe painted")

        texts = screen_texts(device)
        exit_labels = {"Exit full screen", "Sair da tela cheia"}
        if texts & exit_labels:
            failures.append("exit button shown outside full screen")
        result = channel.execute("view.fullScreen")
        if result.get("enabled") is not True:
            failures.append(f"view.fullScreen answered {result}")
        time.sleep(1)
        texts = screen_texts(device)
        if not texts & exit_labels:
            failures.append("no exit button in full screen")
        if texts & {"Gallery", "Galeria"}:
            failures.append("the top bar is still shown in full screen")
        channel.execute("view.fullScreen", {"enabled": False})
        time.sleep(1)
        if not screen_texts(device) & {"Gallery", "Galeria"}:
            failures.append("the top bar did not come back")

        channel.execute("view.copyPasteMenu", {"x": middle_x, "y": middle_y})
        time.sleep(1)
        if not screen_texts(device) & {"Copy merged", "Copiar mesclado"}:
            failures.append("the copy & paste menu did not open")
        channel.execute("view.copyPasteMenu")
        time.sleep(1)
        if screen_texts(device) & {"Copy merged", "Copiar mesclado"}:
            failures.append("the copy & paste menu did not close")
    finally:
        try:
            channel.execute("settings.fingerPainting.set", {"enabled": False})
            channel.execute("view.fullScreen", {"enabled": False})
            if artwork is not None:
                channel.execute("gallery.close")
                time.sleep(3)
                channel.execute("gallery.delete", {"ids": [artwork]})
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print("gestures OK")


if __name__ == "__main__":
    main()
