#!/usr/bin/env python3
"""Checks that every screen is reachable and whole on a 6.1" phone. The tablet is
made a phone (`wm size 1080x2340`, `wm density 420`, restored at
the end; --landscape also turns it). Each screen, menu, panel, tool bar and dialog is opened by its
command and its accessibility tree is read with uiautomator; it fails when a tappable item lies
outside the screen, is smaller than a 32 dp touch target, or shows text squeezed into a column
narrower than its height (a word broken letter by letter), or when two large items of one
layout overlap (a layout that ran out of room); undo must stay on screen beside every tool's bar. Screenshots go to --out. Runs on a new
canvas, deleted at the end.

    python tools/device/check_phone_layout.py --device <serial> [--landscape] [--out <folder>]
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree

from kill_test import adb, launch, wait_for_artwork

PHONE_SIZE = "1080x2340"
PHONE_DENSITY = 420
MINIMUM_TARGET_DP = 32
OVERLAP_MINIMUM_DP = 64
UNDO = r"\|(Desfazer|Undo)$"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def dump(device):
    adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
    xml = subprocess.run(
        adb_command(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml"), capture_output=True
    ).stdout.decode("utf-8", errors="replace")
    return ElementTree.fromstring(xml[xml.index("<"):])


def adb_command(device, *arguments):
    executable = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if not os.path.exists(executable):
        executable = "adb"
    return [executable] + (["-s", device] if device else []) + list(arguments)


def screen_size(device):
    width, height = re.findall(r"(\d+)x(\d+)", adb(device, "shell", "wm", "size"))[-1]
    return int(width), int(height)


def problems_of(root, width, height):
    pixels_per_dp = PHONE_DENSITY / 160
    minimum = MINIMUM_TARGET_DP * pixels_per_dp
    found = []
    for node in root.iter("node"):
        if node.get("package") != "com.mobyle.nanquim":
            continue
        match = BOUNDS.match(node.get("bounds", ""))
        if not match:
            continue
        left, top, right, bottom = map(int, match.groups())
        node_width, node_height = right - left, bottom - top
        name = label_of(node)
        if node.get("clickable") == "true" and node_width > 0 and node_height > 0:
            if left < -1 or top < -1 or right > width + 1 or bottom > height + 1:
                found.append(f"'{name}' outside the screen at {node.get('bounds')}")
            if node_width < minimum and node_height < minimum:
                found.append(f"'{name}' smaller than {MINIMUM_TARGET_DP} dp: {node_width}x{node_height} px")
        text = node.get("text") or ""
        if len(text) >= 4 and node_height > 3 * node_width and node_width < 40 * pixels_per_dp:
            found.append(f"text '{text}' squeezed into {node_width}x{node_height} px")
    for parent in root.iter("node"):
        children = []
        for child in parent.findall("node"):
            match = BOUNDS.match(child.get("bounds", ""))
            if child.get("package") == "com.mobyle.nanquim" and match:
                left, top, right, bottom = map(int, match.groups())
                if right > left and bottom > top:
                    children.append((child, (left, top, right, bottom)))
        for index, (first, a) in enumerate(children):
            for second, b in children[index + 1:]:
                overlap_width = min(a[2], b[2]) - max(a[0], b[0])
                overlap_height = min(a[3], b[3]) - max(a[1], b[1])
                if overlap_width <= 0 or overlap_height <= 0 or contains(a, b) or contains(b, a):
                    continue
                smaller = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
                if smaller >= (OVERLAP_MINIMUM_DP * pixels_per_dp) ** 2 and overlap_width * overlap_height > 0.4 * smaller:
                    found.append(f"'{label_of(first)}' and '{label_of(second)}' overlap at {first.get('bounds')} / {second.get('bounds')}")
    return found


def contains(outer, inner, tolerance=16):
    return (inner[0] >= outer[0] - tolerance and inner[1] >= outer[1] - tolerance
            and inner[2] <= outer[2] + tolerance and inner[3] <= outer[3] + tolerance)


def label_of(node):
    return node.get("text") or node.get("content-desc") or node.get("class", "?").rsplit(".", 1)[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--landscape", action="store_true", help="the phone turned sideways")
    parser.add_argument("--out", help="folder for one screenshot per screen")
    arguments = parser.parse_args()
    device = arguments.device
    if arguments.out:
        os.makedirs(arguments.out, exist_ok=True)
    adb(device, "shell", "wm", "size", PHONE_SIZE)
    adb(device, "shell", "wm", "density", str(PHONE_DENSITY))
    rotation_settings = {name: adb(device, "shell", "settings", "get", "system", name).strip()
                         for name in ("accelerometer_rotation", "user_rotation")}
    adb(device, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb(device, "shell", "settings", "put", "system", "user_rotation", "1" if arguments.landscape else "0")
    time.sleep(2)
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    adb(device, "shell", "cmd", "statusbar", "collapse")
    width, height = screen_size(device)
    if arguments.landscape:
        width, height = max(width, height), min(width, height)
    failures = []
    artwork = None

    def check(label, settle=1.5, must_show=None):
        time.sleep(settle)
        root = dump(device)
        problems = problems_of(root, width, height)
        if must_show and not any(
            re.search(must_show, (node.get("text") or "") + "|" + (node.get("content-desc") or "")) for node in root.iter("node")
        ):
            problems.append(f"nothing matching {must_show} on screen")
        print(f"{label}: {'ok' if not problems else 'FAILED'}")
        for problem in problems:
            print(f"  {problem}")
            failures.append(f"{label}: {problem}")
        if arguments.out:
            png = subprocess.run(adb_command(device, "exec-out", "screencap", "-p"), capture_output=True).stdout
            with open(os.path.join(arguments.out, re.sub(r"\W+", "_", label) + ".png"), "wb") as file:
                file.write(png)
        return root

    def tap_node(root, pattern, required=True):
        for node in root.iter("node"):
            label = (node.get("text") or "") + "|" + (node.get("content-desc") or "")
            match = BOUNDS.match(node.get("bounds", ""))
            if match and re.search(pattern, label):
                left, top, right, bottom = map(int, match.groups())
                adb(device, "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                return True
        if required:
            failures.append(f"no item matching {pattern}")
        return False

    def back(times=1):
        for _ in range(times):
            adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)

    try:
        time.sleep(5)
        if channel.call("getState").get("document"):
            channel.execute("gallery.close")
            time.sleep(3)
        root = check("gallery")
        if tap_node(root, r"^(Mais|More)\|", required=False):
            check("gallery menu", settle=1)
            back()
        channel.execute("gallery.newCanvas.open")
        check("new canvas")
        back()
        for command in ["settings.open", "settings.gestures.open", "settings.keyboardShortcuts.open", "settings.about.open", "settings.support.open",
                        "settings.diagnostics.open"]:
            channel.execute(command)
            check(command.removesuffix(".open"), settle=2)
        back(6)

        artwork = channel.execute("gallery.newCanvas", {"width": 1200, "height": 800, "name": "Phone layout check"})["id"]
        wait_for_artwork(channel, artwork)
        root = check("canvas", must_show=UNDO)
        if tap_node(root, r"\|(Actions|A.{1,4}es)$"):
            check("actions menu", settle=1)
            back()
        for command, command_arguments, label in [
            ("brush.library.open", {"tool": "paint"}, "brush library"),
            ("view.layersPanel", {"visible": True}, "layers"),
            ("color.panel.open", {}, "color"),
        ]:
            channel.execute(command, command_arguments)
            check(label)
            back()
        for tool in ["selection", "transform", "crop", "guide", "text", "clone"]:
            try:
                channel.execute("tool.select", {"tool": tool})
            except RuntimeError as error:
                print(f"tool {tool}: {error}")
                continue
            check(f"tool {tool}", must_show=UNDO)
            channel.execute("tool.select", {"tool": "brush"})
            time.sleep(0.5)
        channel.execute("brush.editor.open", {"brushId": channel.call("getState")["brushes"]["paint"]})
        check("brush editor")
        channel.execute("brush.editor.cancel")
        time.sleep(1.5)
        if channel.call("getState").get("document") is None:
            failures.append("leaving the brush editor closed the canvas")
        channel.execute("view.exportSheet")
        check("export")
        back()
        channel.execute("canvas.info.show")
        check("canvas information")
        back()
    finally:
        try:
            if artwork is not None:
                channel.execute("gallery.close")
                time.sleep(3)
                channel.execute("gallery.delete", {"ids": [artwork]})
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
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
