#!/usr/bin/env python3
"""Checks the quick menu and the shortcut brush on the device: the default
preset's six slots; `quickMenu.show` opens the radial menu (its
slots and the preset name on screen), a tap on the "New layer" slot adds a layer and closes it,
a tap elsewhere closes it; a slot refuses a quick-menu command; presets are added, renamed,
shown and deleted; a one-finger tap bound to the quick menu opens it under the finger; the
shortcut brush toggle switches to a duplicated brush and back. Everything it changes is undone
at the end (artwork, presets, slots, gestures, the duplicate).

    python tools/device/check_quick_menu.py --device <serial>
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


def center_of(xml, pattern):
    for node in re.findall(r"<node [^>]*>", xml):
        if re.search(f'(text|content-desc)="({pattern})"', node):
            left, top, right, bottom = (int(value) for value in re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            return (left + right) // 2, (top + bottom) // 2
    return None


def layer_count(channel):
    return len(channel.call("getState")["document"]["layers"])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    adb(device, "shell", "cmd", "statusbar", "collapse")
    artwork = None
    duplicate = None
    failures = []
    original = channel.execute("quickMenu.get")
    try:
        slots = [slot and slot["command"] for slot in original["presets"][original["active"]]["slots"]]
        print(f"slots: {slots}")

        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Quick menu check"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("view.fit")
        channel.execute("tool.select", {"tool": "brush"})
        time.sleep(1)

        channel.execute("quickMenu.setSlot", {"slot": 0, "command": "layer.new"})
        try:
            channel.execute("quickMenu.setSlot", {"slot": 1, "command": "quickMenu.show"})
            failures.append("a slot took a quick-menu command")
        except RuntimeError:
            pass

        channel.execute("quickMenu.show")
        time.sleep(1)
        xml = screen(device)
        new_layer = center_of(xml, "New layer|Nova camada")
        if new_layer is None:
            failures.append("the menu's New layer slot is not on screen")
        else:
            layers = layer_count(channel)
            adb(device, "shell", "input", "touchscreen", "tap", str(new_layer[0]), str(new_layer[1]))
            time.sleep(1.5)
            if layer_count(channel) != layers + 1:
                failures.append("tapping the New layer slot did not add a layer")
            if channel.call("getState")["quickMenuOpen"]:
                failures.append("the menu stayed open after running a slot")
            else:
                print("slot ran and the menu closed")

        channel.execute("quickMenu.show", {"x": 300, "y": 300})
        time.sleep(1)
        size = adb(device, "shell", "wm", "size")
        width, height = (int(value) for value in re.findall(r"(\d+)x(\d+)", size)[-1])
        adb(device, "shell", "input", "touchscreen", "tap", str(width - 60), str(height - 200))
        time.sleep(1)
        if channel.call("getState")["quickMenuOpen"]:
            failures.append("a tap outside did not close the menu")

        added = channel.execute("quickMenu.addPreset", {"name": "Check preset"})
        index = added["active"]
        channel.execute("quickMenu.renamePreset", {"preset": index, "name": "Renamed preset"})
        state = channel.execute("quickMenu.selectPreset", {"preset": index})
        if state["presets"][index]["name"] != "Renamed preset" or state["active"] != index:
            failures.append(f"preset not added, renamed and shown: {state}")
        channel.execute("quickMenu.show")
        time.sleep(1)
        if center_of(screen(device), "Renamed preset") is None:
            failures.append("the menu does not show the preset's name")
        channel.execute("quickMenu.hide")
        state = channel.execute("quickMenu.deletePreset", {"preset": index})
        if len(state["presets"]) != len(original["presets"]):
            failures.append("the preset was not deleted")
        channel.execute("quickMenu.selectPreset", {"preset": original["active"]})

        channel.execute("settings.gestures.setTriggers", {"action": "quickMenu", "triggers": ["oneFingerTap"]})
        adb(device, "shell", "input", "touchscreen", "tap", str(width // 2), str(height // 2))
        time.sleep(1)
        if not channel.call("getState")["quickMenuOpen"]:
            failures.append("a one-finger tap bound to the quick menu did not open it")
        else:
            print("one-finger tap opened the menu")
        channel.execute("quickMenu.hide")
        channel.execute("settings.gestures.reset")

        brush = channel.call("getState")["brushes"]["paint"]
        duplicate = channel.execute("brush.duplicate", {"brushId": brush})["brushId"]
        channel.execute("brush.shortcut.set", {"brushId": duplicate})
        channel.execute("brush.shortcut.toggle")
        first = channel.call("getState")["brushes"]["paint"]
        channel.execute("brush.shortcut.toggle")
        second = channel.call("getState")["brushes"]["paint"]
        if (first, second) != (duplicate, brush):
            failures.append(f"shortcut toggle went {brush} -> {first} -> {second}, expected {duplicate} then back")
        else:
            print("shortcut brush toggled and back")
    finally:
        try:
            channel.execute("quickMenu.hide")
            channel.execute("settings.gestures.reset")
            channel.execute("brush.shortcut.set", {"clear": True})
            for preset_index, preset in enumerate(original["presets"]):
                for slot_index, slot in enumerate(preset["slots"]):
                    restored = {"slot": slot_index, "preset": preset_index}
                    if slot:
                        restored.update({"command": slot["command"], "arguments": slot["arguments"]})
                        if "label" in slot:
                            restored["label"] = slot["label"]
                    channel.execute("quickMenu.setSlot", restored)
            channel.execute("quickMenu.selectPreset", {"preset": original["active"]})
            if duplicate is not None:
                channel.execute("brush.delete", {"brushId": duplicate})
            if artwork is not None:
                channel.execute("gallery.close")
                time.sleep(3)
                channel.execute("gallery.delete", {"ids": [artwork]})
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print("quick menu OK")


if __name__ == "__main__":
    main()
