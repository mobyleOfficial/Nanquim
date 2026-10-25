#!/usr/bin/env python3
"""Checks the welcome cards and the tool tips.

1. A real first launch: the help preferences are removed (`run-as`, debug build), the app is
   started without the scripts' token, and the cards must cover the gallery; Skip closes them and
   a relaunch does not show them again.
2. With the control channel: every card in turn (screenshots), each fully on the screen; Done
   closes them.
3. On a new canvas (deleted at the end), with the tips allowed: the eraser's tip appears the first
   time the eraser is chosen, OK closes it, and choosing the eraser again does not bring it back.

With --phone the screen is a 6.1" phone (`wm size 1080x2340`, `wm density 420`, restored after).

    python tools/device/check_help.py --device <serial> [--phone] [--out <folder>]
"""

import argparse
import os
import re
import subprocess
import sys
import time

from kill_test import ACTIVITY, adb, launch, wait_for_artwork

BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
SKIP = r'text="(Skip|Pular)"'


def adb_bytes(device, *arguments):
    executable = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if not os.path.exists(executable):
        executable = "adb"
    return subprocess.run([executable, "-s", device, *arguments], capture_output=True, check=True).stdout


def screen(device):
    adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
    return adb_bytes(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml").decode("utf-8", errors="replace")


def screenshot(device, folder, name):
    with open(os.path.join(folder, name), "wb") as file:
        file.write(adb_bytes(device, "exec-out", "screencap", "-p"))


def tap_text(device, pattern):
    for node in re.finditer(r"<node [^>]*>", screen(device)):
        if re.search(pattern, node.group(0)):
            left, top, right, bottom = map(int, BOUNDS.search(node.group(0)).groups())
            adb(device, "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            return True
    return False


def screen_size(device):
    width, height = re.findall(r"(\d+)x(\d+)", adb(device, "shell", "wm", "size"))[-1]
    return int(width), int(height)


def outside(device):
    width, height = screen_size(device)
    found = []
    for node in re.finditer(r"<node [^>]*>", screen(device)):
        text = re.search(r'text="([^"]*)"', node.group(0))
        if 'package="com.mobyle.nanquim"' not in node.group(0) or not text or not text.group(1):
            continue
        left, top, right, bottom = map(int, BOUNDS.search(node.group(0)).groups())
        if left < 0 or top < 0 or right > width or bottom > height:
            found.append(text.group(1))
    return found


def delete_artworks(channel, ids):
    for _ in range(20):
        channel.execute("gallery.close")
        time.sleep(1)
        if channel.call("getState")["artwork"] is None:
            break
    channel.execute("gallery.delete", {"ids": ids})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--phone", action="store_true")
    parser.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "nanquim-help"))
    arguments = parser.parse_args()
    device = arguments.device
    os.makedirs(arguments.out, exist_ok=True)
    failures = []
    artwork = None
    if arguments.phone:
        adb(device, "shell", "wm", "size", "1080x2340")
        adb(device, "shell", "wm", "density", "420")
    try:
        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        adb(device, "shell", "run-as", "com.mobyle.nanquim", "rm", "-f", "shared_prefs/help.xml")
        adb(device, "shell", "am", "start", "-W", "-n", ACTIVITY)
        time.sleep(3)
        shown = screen(device)
        if not re.search(SKIP, shown):
            failures.append("the first launch did not show the welcome cards")
        screenshot(device, arguments.out, "first-launch.png")
        if not tap_text(device, SKIP):
            failures.append("no Skip on the first card")
        time.sleep(1)
        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        adb(device, "shell", "am", "start", "-W", "-n", ACTIVITY)
        time.sleep(3)
        if re.search(SKIP, screen(device)):
            failures.append("the welcome cards came back after Skip")

        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        channel = launch(device)
        state = channel.call("getState")["help"]
        if state["welcomeVisible"] or not state["welcomeSeen"]:
            failures.append(f"after Skip: {state}")
        channel.execute("gallery.close")
        time.sleep(2)
        channel.execute("help.welcome.show")
        for index in range(5):
            channel.execute("help.welcome.card", {"index": index})
            time.sleep(1.2)
            screenshot(device, arguments.out, f"card-{index + 1}.png")
            cut = outside(device)
            if cut:
                failures.append(f"card {index + 1} does not fit: {cut}")
        if not tap_text(device, r'text="(Start drawing|Começar a desenhar)"'):
            failures.append("no Done on the last card")
        time.sleep(1)
        if channel.call("getState")["help"]["welcomeVisible"]:
            failures.append("Done did not close the cards")

        artwork = channel.execute("gallery.newCanvas", {"width": 1200, "height": 900, "name": "Help check"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("help.automatic.set", {"enabled": True})
        channel.execute("help.tips.reset")
        channel.execute("tool.select", {"tool": "eraser"})
        time.sleep(1.5)
        if channel.call("getState")["help"]["tip"] != "Eraser":
            failures.append("the eraser's tip did not appear")
        screenshot(device, arguments.out, "tip-eraser.png")
        if not tap_text(device, r'text="OK"'):
            failures.append("no OK on the tip")
        time.sleep(1)
        if channel.call("getState")["help"]["tip"] is not None:
            failures.append("OK did not close the tip")
        channel.execute("tool.select", {"tool": "brush"})
        channel.execute("tool.select", {"tool": "eraser"})
        time.sleep(1)
        if channel.call("getState")["help"]["tip"] is not None:
            failures.append("the eraser's tip came back")
        channel.execute("help.automatic.set", {"enabled": False})
        channel.execute("tool.select", {"tool": "brush"})
    finally:
        try:
            if artwork is not None:
                delete_artworks(channel, [artwork])
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
        if arguments.phone:
            adb(device, "shell", "wm", "size", "reset")
            adb(device, "shell", "wm", "density", "reset")
    print(f"screenshots: {arguments.out}")
    if failures:
        print("FAILED:\n" + "\n".join(failures))
        sys.exit(1)
    print("help ok")


if __name__ == "__main__":
    main()
