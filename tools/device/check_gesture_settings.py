#!/usr/bin/env python3
"""Checks the gesture configuration on the device:
`settings.gestures.*` answer the defaults without conflicts, refuse a trigger an action cannot use,
report a conflict (and the screen shows it), keep the configuration across an app restart; the
older `settings.undoGesture.set` and `settings.fingerPainting.set` edit it; a finger drag bound to
Erase erases; a tap on the side bar's modifier button does its bound action (full screen here).
The modifier button held with a touch needs two pointers, which `adb input` cannot make. The
artwork is deleted and the defaults restored at the end.

    python tools/device/check_gesture_settings.py --device <serial>
"""

import argparse
import os
import re
import sys
import time

from kill_test import adb, composite_hash, launch, wait_for_artwork

WIDTH, HEIGHT = 1200, 800


def screen(device):
    adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
    return adb(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml")


def texts(xml):
    return set(re.findall(r'text="([^"]+)"', xml))


def center_of(xml, description):
    for node in re.findall(r"<node [^>]*>", xml):
        if re.search(f'content-desc="{description}"', node):
            left, top, right, bottom = (int(value) for value in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            return (left + right) // 2, (top + bottom) // 2
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
    size = adb(device, "shell", "wm", "size")
    screen_width, screen_height = (int(value) for value in re.findall(r"(\d+)x(\d+)", size)[-1])
    if screen_width < screen_height:
        screen_width, screen_height = screen_height, screen_width
    middle_x, middle_y = screen_width // 2, screen_height // 2

    def swipe(dy):
        adb(device, "shell", "input", "touchscreen", "swipe", str(middle_x - 300), str(middle_y + dy), str(middle_x + 300), str(middle_y + dy), "400")
        time.sleep(1.5)

    try:
        configuration = channel.execute("settings.gestures.reset")
        if configuration["conflicts"]:
            failures.append(f"defaults have conflicts: {configuration['conflicts']}")
        if configuration["triggers"]["undo"] != ["twoFingerTap"] or configuration["triggers"]["eyedropper"] != ["longPress", "stylusButton", "modifierTap"]:
            failures.append(f"unexpected defaults: {configuration['triggers']}")

        try:
            channel.execute("settings.gestures.setTriggers", {"action": "smudge", "triggers": ["twoFingerTap"]})
            failures.append("smudge took a two-finger tap")
        except RuntimeError as error:
            print(f"refused as expected: {error}")

        configuration = channel.execute("settings.gestures.setTriggers", {"action": "quickMenu", "triggers": ["twoFingerTap"]})
        expected = [{"trigger": "twoFingerTap", "actions": ["quickMenu", "undo"]}]
        if configuration["conflicts"] != expected:
            failures.append(f"conflict not reported: {configuration['conflicts']}")
        channel.execute("settings.gestures.set", {"longPressDelay": 800, "rotation": False})

        channel.execute("settings.gestures.open")
        time.sleep(1.5)
        shown = texts(screen(device))
        if not shown & {"Gesture controls", "Controles de gestos"}:
            failures.append("the gesture screen did not open")
        if not shown & {"Conflicting gestures", "Gestos em conflito"}:
            failures.append("the screen does not show the conflict")
        adb(device, "shell", "input", "keyevent", "BACK")
        time.sleep(1)

        channel.close()
        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        channel = launch(device)
        configuration = channel.execute("settings.gestures.get")
        if configuration["conflicts"] != expected or configuration["longPressDelay"] != 800 or configuration["rotation"] is not False:
            failures.append(f"not kept across a restart: {configuration}")
        else:
            print("kept across a restart")
        channel.execute("settings.gestures.reset")

        channel.execute("settings.undoGesture.set", {"fingers": 1})
        channel.execute("settings.fingerPainting.set", {"enabled": True})
        configuration = channel.execute("settings.gestures.get")
        if configuration["triggers"]["undo"] != ["oneFingerTap"] or configuration["triggers"]["paint"] != ["fingerDrag"]:
            failures.append(f"older commands did not edit the configuration: {configuration['triggers']}")
        channel.execute("settings.gestures.reset")

        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Gesture settings check"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("view.fit")
        channel.execute("tool.select", {"tool": "brush"})
        time.sleep(1)

        channel.execute("settings.gestures.setTriggers", {"action": "paint", "triggers": ["fingerDrag"]})
        blank = composite_hash(channel)
        swipe(0)
        painted = composite_hash(channel)
        if painted == blank:
            failures.append("a finger drag bound to Paint did not paint")
        channel.execute("settings.gestures.setTriggers", {"action": "paint", "triggers": []})
        channel.execute("settings.gestures.setTriggers", {"action": "erase", "triggers": ["fingerDrag"]})
        swipe(0)
        erased = composite_hash(channel)
        if erased == painted:
            failures.append("a finger drag bound to Erase did not erase")
        else:
            print("finger drag painted, then erased")
        channel.execute("settings.gestures.setTriggers", {"action": "erase", "triggers": []})

        channel.execute("settings.gestures.setTriggers", {"action": "eyedropper", "triggers": ["longPress", "stylusButton"]})
        channel.execute("settings.gestures.setTriggers", {"action": "fullScreen", "triggers": ["fourFingerTap", "modifierTap"]})
        xml = screen(device)
        button = center_of(xml, "(Modifier button|Bot.{1,3}o modificador)")
        if button is None:
            failures.append("no modifier button on screen")
        else:
            adb(device, "shell", "input", "touchscreen", "tap", str(button[0]), str(button[1]))
            time.sleep(1)
            if not texts(screen(device)) & {"Exit full screen", "Sair da tela cheia"}:
                failures.append("a modifier button tap did not toggle full screen")
            else:
                print("modifier tap toggled full screen")
            channel.execute("view.fullScreen", {"enabled": False})
    finally:
        try:
            channel.execute("settings.gestures.reset")
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
    print("gesture settings OK")


if __name__ == "__main__":
    main()
