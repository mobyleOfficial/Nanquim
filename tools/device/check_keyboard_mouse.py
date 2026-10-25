#!/usr/bin/env python3
"""Checks keyboard, mouse and external display on the device: the default
shortcuts (B/E tools, L layers panel, C color panel,
Ctrl+N new layer, Ctrl+Z / Ctrl+Shift+Z undo and redo, Tab full screen, Ctrl+E export sheet,
Ctrl+/ shortcut screen), an edited shortcut, Space + mouse drag moves the view without painting
while a plain mouse drag paints, and the projected canvas on a simulated display (developer
"overlay display"). The mouse wheel cannot be driven with `adb input`. Everything it changes is
undone at the end.

    python tools/device/check_keyboard_mouse.py --device <serial>
"""

import argparse
import os
import re
import subprocess
import sys
import time

from kill_test import ADB, adb, composite_hash, launch, wait_for_artwork

WIDTH, HEIGHT = 1200, 800


def texts(device):
    adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
    return set(re.findall(r'text="([^"]+)"', adb(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml")))


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

    def state():
        return channel.call("getState")

    def key(*names):
        if len(names) == 1:
            adb(device, "shell", "input", "keyevent", names[0])
        else:
            adb(device, "shell", "input", "keycombination", *names)
        time.sleep(0.8)

    def layers():
        return len(state()["document"]["layers"])

    size = adb(device, "shell", "wm", "size")
    width, height = (int(value) for value in re.findall(r"(\d+)x(\d+)", size)[-1])
    try:
        channel.execute("settings.keyboardShortcuts.reset")
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Keyboard check"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("view.fit")
        time.sleep(1)

        key("KEYCODE_E")
        if state()["tool"] != "Eraser":
            failures.append(f"E selected {state()['tool']}")
        key("KEYCODE_B")
        if state()["tool"] != "Brush":
            failures.append(f"B selected {state()['tool']}")
        key("KEYCODE_L")
        if not state()["layersPanelVisible"]:
            failures.append("L did not show the layers panel")
        key("KEYCODE_L")
        key("KEYCODE_C")
        if not state()["colorPanelVisible"]:
            failures.append("C did not show the color panel")
        key("KEYCODE_C")

        before = layers()
        key("KEYCODE_CTRL_LEFT", "KEYCODE_N")
        if layers() != before + 1:
            failures.append("Ctrl+N did not add a layer")
        key("KEYCODE_CTRL_LEFT", "KEYCODE_Z")
        if layers() != before:
            failures.append("Ctrl+Z did not undo")
        key("KEYCODE_CTRL_LEFT", "KEYCODE_SHIFT_LEFT", "KEYCODE_Z")
        if layers() != before + 1:
            failures.append("Ctrl+Shift+Z did not redo")
        else:
            print("tools, panels, new layer, undo and redo by keyboard")

        key("KEYCODE_TAB")
        if not texts(device) & {"Exit full screen", "Sair da tela cheia"}:
            failures.append("Tab did not enter full screen")
        key("KEYCODE_TAB")

        key("KEYCODE_CTRL_LEFT", "KEYCODE_SLASH")
        if not texts(device) & {"Keyboard shortcuts", "Atalhos de teclado"}:
            failures.append("Ctrl+/ did not open the shortcut screen")
        adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)

        key("KEYCODE_CTRL_LEFT", "KEYCODE_E")
        shown = texts(device)
        adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)
        if not any(re.search(r"PNG|PSD|Export|Exportar", text) for text in shown):
            failures.append("Ctrl+E did not show the export sheet")

        replaced = channel.execute("settings.keyboardShortcuts.set", {"shortcut": "G", "command": "layer.new"})
        before = layers()
        key("KEYCODE_G")
        if layers() != before + 1 or replaced.get("replaced") is not None:
            failures.append(f"the edited shortcut G did not add a layer ({replaced.get('replaced')})")
        else:
            print("edited shortcut works")

        before = composite_hash(channel)
        adb(device, "shell", "input", "mouse", "swipe", str(width // 2 - 200), str(height // 2), str(width // 2 + 200), str(height // 2 + 50), "400")
        time.sleep(1.5)
        painted = composite_hash(channel)
        if painted == before:
            failures.append("a mouse drag did not paint")
        hold = subprocess.Popen(
            [ADB, "-s", device, "shell", "input", "keycombination", "-t", "4000", "KEYCODE_SHIFT_LEFT", "KEYCODE_SPACE"],
        )
        time.sleep(1.5)
        adb(device, "shell", "input", "mouse", "swipe", str(width // 2), str(height // 2 + 150), str(width // 2 + 200), str(height // 2 + 250), "600")
        hold.wait()
        time.sleep(1.5)
        if composite_hash(channel) != painted:
            failures.append("Space + mouse drag painted")
        else:
            print("Space + drag painted nothing")

        channel.execute("settings.projectedCanvas.set", {"enabled": True})
        adb(device, "shell", "settings", "put", "global", "overlay_display_devices", "1280x720/160")
        time.sleep(3)
        shown_on = state()["projectedDisplay"]
        if not shown_on:
            failures.append("no projected canvas on the simulated display")
        else:
            print(f"projected on {shown_on}")
        channel.execute("settings.projectedCanvas.set", {"enabled": False})
        time.sleep(1)
        if state()["projectedDisplay"]:
            failures.append("the projected canvas stayed after turning it off")
        channel.execute("settings.projectedCanvas.set", {"enabled": True})
        adb(device, "shell", "settings", "delete", "global", "overlay_display_devices")
        time.sleep(3)
        if state()["projectedDisplay"]:
            failures.append("the projected canvas stayed after the display left")
    finally:
        try:
            adb(device, "shell", "settings", "delete", "global", "overlay_display_devices", check=False)
            channel.execute("settings.keyboardShortcuts.reset")
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
    print("keyboard, mouse and display OK")


if __name__ == "__main__":
    main()
