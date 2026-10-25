#!/usr/bin/env python3
"""Lifecycle and multi-window check: an artwork with a stroke
survives turning the tablet, a pop-up (freeform) window and back, and the system killing the
process in the background; the history survives everything but the process death. Abrupt
force-stops are covered by kill_test.py.

    python tools/device/check_lifecycle.py --device <serial>
"""

import argparse
import os
import time

from kill_test import ACTIVITY, PACKAGE, TOKEN, adb, launch, wait_for_artwork

SIZE = 1024


def pick(channel):
    color = channel.execute("color.eyedropper", {"x": SIZE / 2, "y": SIZE / 2})
    return tuple(round(color[name] * 255) for name in ("red", "green", "blue"))


def state(channel):
    current = channel.call("getState")
    artwork = current.get("artwork") or {}
    return artwork.get("id"), bool(current.get("document")), current.get("history", {})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    device = parser.parse_args().device
    rotation = {name: adb(device, "shell", "settings", "get", "system", name).strip()
                for name in ("accelerometer_rotation", "user_rotation")}
    adb(device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(device)
    channel.execute("gallery.close")
    time.sleep(2)
    artwork = channel.execute("gallery.newCanvas", {"width": SIZE, "height": SIZE, "name": "Lifecycle"})["id"]
    wait_for_artwork(channel, artwork)
    channel.execute("color.setPrimary", {"hex": "C03020"})
    channel.execute("brush.setSize", {"sizePx": 120})
    channel.execute("tool.paint.strokePath", {"samples": [[SIZE * 0.3, SIZE / 2, 1.0, 0], [SIZE * 0.7, SIZE / 2, 1.0, 200]]})
    wait_for_artwork(channel, artwork)
    painted = pick(channel)
    first = state(channel)
    results = {}
    try:
        adb(device, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
        for turn in ("1", "0"):
            adb(device, "shell", "settings", "put", "system", "user_rotation", turn)
            time.sleep(3)
        results["turned"] = (state(channel), pick(channel))

        adb(device, "shell", "am", "start", "--windowingMode", "5", "-n", ACTIVITY)
        time.sleep(3)
        stacks = adb(device, "shell", "dumpsys", "activity", "activities")
        freeform = any("mode=freeform" in line or "windowingMode=5" in line for line in stacks.splitlines() if PACKAGE in line or "mode=" in line)
        results["pop-up window"] = (freeform, state(channel), pick(channel))
        adb(device, "shell", "am", "start", "--windowingMode", "1", "-n", ACTIVITY)
        time.sleep(3)
        results["back to full screen"] = (state(channel), pick(channel))

        adb(device, "shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(3)
        channel.close()
        adb(device, "shell", "am", "kill", PACKAGE)
        time.sleep(2)
        alive = adb(device, "shell", "pidof", PACKAGE, check=False).strip()
        try:
            channel = launch(device)
        except TimeoutError:
            channel = launch(device)
        wait_for_artwork(channel, artwork)
        results["after the system killed it"] = (alive == "", state(channel), pick(channel))
    finally:
        for name, value in rotation.items():
            if value.isdigit():
                adb(device, "shell", "settings", "put", "system", name, value)
    channel.execute("gallery.close")
    time.sleep(2)
    channel.execute("gallery.delete", {"ids": [artwork]})
    channel.close()

    for name, value in results.items():
        print(name, value)
    same = lambda entry: entry[0] == artwork and entry[1]  # noqa: E731
    checks = {
        "the stroke is painted": painted != (255, 255, 255),
        "turning keeps the artwork, the pixels and the history": same(results["turned"][0])
        and results["turned"][1] == painted and results["turned"][0][2] == first[2],
        "a pop-up window keeps the artwork and the pixels": results["pop-up window"][0]
        and same(results["pop-up window"][1]) and results["pop-up window"][2] == painted,
        "full screen again keeps the artwork": same(results["back to full screen"][0]) and results["back to full screen"][1] == painted,
        "after the system kills the process in the background the artwork reopens with the stroke":
        results["after the system killed it"][0] and same(results["after the system killed it"][1])
        and results["after the system killed it"][2] == painted,
    }
    for name, passed in checks.items():
        print(("PASS " if passed else "FAIL ") + name)
    print("RESULT", "ok" if all(checks.values()) else "failed")


if __name__ == "__main__":
    main()
