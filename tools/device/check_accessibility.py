#!/usr/bin/env python3
"""Accessibility audit of every screen. Each screen, menu, panel,
tool bar and dialog is opened by its command and its accessibility tree is read with uiautomator.
It fails when a tappable item has no label a screen reader can speak (no text or content
description on it or inside it) or is smaller than a 48 dp touch target in both directions, and
Color swatches in palettes and the history are dense grids reached also by the Value mode and
hex field, so they need 30 dp; items cut by the edge of a scrolling list are skipped. Runs on a new
canvas, deleted at the end.

    python tools/device/check_accessibility.py --device <serial> [--font-scale 1.3] [--out <folder>]
"""

import argparse
import os
import re
import subprocess
import sys
import time

from check_phone_layout import BOUNDS, adb_command, dump
from kill_test import adb, launch, wait_for_artwork

MINIMUM_TARGET_DP = 48
MINIMUM_SWATCH_DP = 30
SWATCH = re.compile(r"^(Amostra|Swatch|Cor usada|Used color|Espaço vazio|Empty slot) ")
PACKAGE = "com.mobyle.nanquim"


def spoken(node):
    for inner in node.iter("node"):
        if (inner.get("text") or "").strip() or (inner.get("content-desc") or "").strip():
            return True
    return False


def name_of(node):
    for inner in node.iter("node"):
        label = (inner.get("text") or "").strip() or (inner.get("content-desc") or "").strip()
        if label:
            return label
    return node.get("resource-id") or node.get("class", "?").rsplit(".", 1)[-1]


def clipped_by(viewports, bounds):
    left, top, right, bottom = bounds
    return any(
        left >= view[0] - 1 and right <= view[2] + 1 and (abs(top - view[1]) <= 1 or abs(bottom - view[3]) <= 1)
        for view in viewports
    )


def problems_of(root, pixels_per_dp):
    minimum = MINIMUM_TARGET_DP * pixels_per_dp - 1
    swatch_minimum = MINIMUM_SWATCH_DP * pixels_per_dp - 1
    viewports = [tuple(map(int, BOUNDS.match(node.get("bounds")).groups()))
                 for node in root.iter("node") if node.get("scrollable") == "true" and BOUNDS.match(node.get("bounds", ""))]
    window = [tuple(map(int, BOUNDS.match(node.get("bounds")).groups()))
              for node in root.iter("node") if node.get("package") == PACKAGE and BOUNDS.match(node.get("bounds", ""))]
    if window:
        viewports.append((min(b[0] for b in window), min(b[1] for b in window), max(b[2] for b in window), max(b[3] for b in window)))
    outside = set()
    for scroller in root.iter("node"):
        bounds = BOUNDS.match(scroller.get("bounds", "")) if scroller.get("scrollable") == "true" else None
        if not bounds:
            continue
        view = tuple(map(int, bounds.groups()))
        for inner in scroller.iter("node"):
            inner_bounds = BOUNDS.match(inner.get("bounds", ""))
            if inner_bounds:
                left, top, right, bottom = map(int, inner_bounds.groups())
                if top < view[1] - 1 or bottom > view[3] + 1 or left < view[0] - 1 or right > view[2] + 1:
                    outside.add(id(inner))
    found = []
    for node in root.iter("node"):
        if node.get("package") != PACKAGE or id(node) in outside:
            continue
        actionable = node.get("clickable") == "true" or node.get("long-clickable") == "true" or node.get("checkable") == "true"
        match = BOUNDS.match(node.get("bounds", ""))
        if not actionable or not match:
            continue
        left, top, right, bottom = map(int, match.groups())
        width, height = right - left, bottom - top
        if width <= 0 or height <= 0:
            continue
        clipped = clipped_by(viewports, (left, top, right, bottom))
        if not spoken(node) and not clipped:
            found.append(f"unlabeled {node.get('class', '?').rsplit('.', 1)[-1]} at {node.get('bounds')}")
        smallest = swatch_minimum if SWATCH.match(node.get("content-desc") or "") else minimum
        if (width < smallest or height < smallest) and not clipped:
            found.append(f"'{name_of(node)}' is {width / pixels_per_dp:.0f}x{height / pixels_per_dp:.0f} dp at {node.get('bounds')}")
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--out", help="folder for one screenshot per screen")
    parser.add_argument("--font-scale", type=float, help="run with this system font scale (restored at the end)")
    arguments = parser.parse_args()
    device = arguments.device
    if arguments.out:
        os.makedirs(arguments.out, exist_ok=True)
    density = int(re.findall(r"(\d+)", adb(device, "shell", "wm", "density"))[-1])
    pixels_per_dp = density / 160
    old_scale = adb(device, "shell", "settings", "get", "system", "font_scale").strip()
    if arguments.font_scale:
        adb(device, "shell", "settings", "put", "system", "font_scale", str(arguments.font_scale))
    adb(device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(device)
    adb(device, "shell", "cmd", "statusbar", "collapse")
    failures = []
    artwork = None

    def check(label, settle=1.5):
        time.sleep(settle)
        problems = problems_of(dump(device), pixels_per_dp)
        print(f"{label}: {'ok' if not problems else 'FAILED'}")
        for problem in problems:
            print(f"  {problem}")
            failures.append(f"{label}: {problem}")
        if arguments.out:
            png = subprocess.run(adb_command(device, "exec-out", "screencap", "-p"), capture_output=True).stdout
            with open(os.path.join(arguments.out, re.sub(r"\W+", "_", label) + ".png"), "wb") as file:
                file.write(png)

    def back(times=1):
        for _ in range(times):
            adb(device, "shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)

    try:
        time.sleep(4)
        if channel.call("getState").get("document"):
            channel.execute("gallery.close")
            time.sleep(3)
        check("gallery")
        channel.execute("gallery.newCanvas.open")
        check("new canvas")
        back()
        for command in ["settings.open", "settings.gestures.open", "settings.keyboardShortcuts.open", "settings.about.open",
                        "settings.support.open", "settings.diagnostics.open"]:
            channel.execute(command)
            check(command.removesuffix(".open"), settle=2)
        back(6)
        artwork = channel.execute("gallery.newCanvas", {"width": 1200, "height": 800, "name": "Accessibility check"})["id"]
        wait_for_artwork(channel, artwork)
        check("canvas")
        for command, command_arguments, label in [
            ("brush.library.open", {"tool": "paint"}, "brush library"),
            ("view.layersPanel", {"visible": True}, "layers"),
        ]:
            channel.execute(command, command_arguments)
            check(label)
            back()
        channel.execute("color.panel.open")
        for mode in ["disc", "classic", "harmony", "value", "palettes"]:
            channel.execute("color.panel.setMode", {"mode": mode})
            check(f"color {mode}", settle=1)
        channel.execute("color.panel.close")
        for tool in ["selection", "transform", "crop", "guide", "text", "clone"]:
            try:
                channel.execute("tool.select", {"tool": tool})
            except RuntimeError as error:
                print(f"tool {tool}: {error}")
                continue
            check(f"tool {tool}")
            channel.execute("tool.select", {"tool": "brush"})
            time.sleep(0.5)
        channel.execute("brush.editor.open", {"brushId": channel.call("getState")["brushes"]["paint"]})
        check("brush editor")
        channel.execute("brush.editor.cancel")
        time.sleep(1.5)
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
        if arguments.font_scale:
            adb(device, "shell", "settings", "put", "system", "font_scale", old_scale if old_scale != "null" else "1.0")
    if failures:
        print(f"FAILED: {len(failures)} problems")
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
