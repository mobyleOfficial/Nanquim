#!/usr/bin/env python3
"""Checks macros and the MCP bridge on the device:
a recording keeps the edits run meanwhile (not queries), `macro.run` replays them, a macro is
kept across an app restart, the macros dialog opens from its command; then
`tools/mcp/nanquim_mcp.py` is started as an MCP server on stdio and driven like a client would:
initialize, tools/list (a tool per command, with titles), a tool call that adds a layer, state
and screenshot. The artwork and the macro are deleted at the end.

    python tools/device/check_macros_mcp.py --device <serial>
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time

from kill_test import TOKEN, adb, launch, wait_for_artwork

WIDTH, HEIGHT = 1000, 800
BRIDGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mcp", "nanquim_mcp.py")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artwork = None
    macro = None
    failures = []

    def layers():
        return len(channel.call("getState")["document"]["layers"])

    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": WIDTH, "height": HEIGHT, "name": "Macro check"})["id"]
        wait_for_artwork(channel, artwork)

        channel.execute("macro.record.start", {"name": "Two layers"})
        channel.execute("layer.new")
        channel.execute("canvas.info.get")
        channel.execute("layer.new")
        listed = channel.execute("macro.list")
        if listed["recording"] != "Two layers" or listed["recordedSteps"] != 2:
            failures.append(f"recording state {listed}")
        macro = channel.execute("macro.record.stop")["id"]
        if macro is None:
            failures.append("the recording saved no macro")
        else:
            before = layers()
            channel.execute("macro.run", {"id": macro})
            if layers() != before + 2:
                failures.append(f"macro.run went from {before} to {layers()} layers")
            else:
                print("macro recorded two steps and replayed them")

        channel.execute("macro.panel.show", {"visible": True})
        time.sleep(1)
        adb(device, "shell", "uiautomator", "dump", "/sdcard/nanquim-ui.xml")
        shown = set(re.findall(r'text="([^"]+)"', adb(device, "exec-out", "cat", "/sdcard/nanquim-ui.xml")))
        if "Two layers" not in shown:
            failures.append("the macros dialog does not list the macro")
        channel.execute("macro.panel.show", {"visible": False})

        channel.close()
        adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
        channel = launch(device)
        names = [entry["name"] for entry in channel.execute("macro.list")["macros"]]
        if "Two layers" not in names:
            failures.append(f"the macro was not kept: {names}")
        channel.execute("gallery.open", {"id": artwork})
        wait_for_artwork(channel, artwork)

        bridge = subprocess.Popen(
            [sys.executable, BRIDGE, "--token", TOKEN],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )

        def request(request_id, method, params=None):
            bridge.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}) + "\n")
            bridge.stdin.flush()
            return json.loads(bridge.stdout.readline())

        try:
            init = request(1, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "check", "version": "1"}})
            bridge.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
            bridge.stdin.flush()
            if init["result"]["serverInfo"]["name"] != "nanquim":
                failures.append(f"initialize answered {init}")
            tools = {tool["name"]: tool for tool in request(2, "tools/list")["result"]["tools"]}
            layer_new = tools.get("nanquim_layer_new")
            if layer_new is None or not layer_new["description"].startswith("New Layer") and "layer.new" not in layer_new["description"]:
                failures.append(f"tools/list has no proper layer.new tool: {layer_new}")
            print(f"{len(tools)} MCP tools")
            before = layers()
            called = request(3, "tools/call", {"name": "nanquim_layer_new", "arguments": {}})["result"]
            if called.get("isError") or layers() != before + 1:
                failures.append(f"the layer.new tool call did not add a layer: {called}")
            state = json.loads(request(4, "tools/call", {"name": "nanquim_get_state", "arguments": {}})["result"]["content"][0]["text"])
            if state["artwork"]["id"] != artwork:
                failures.append("the state tool shows another artwork")
            shot = request(5, "tools/call", {"name": "nanquim_screenshot", "arguments": {}})["result"]["content"][0]
            if shot["type"] != "image" or len(shot["data"]) < 100:
                failures.append("the screenshot tool returned no image")
            wrong = request(6, "tools/call", {"name": "nanquim_layer_setOpacity", "arguments": {"layerId": 9999, "opacity": 0.5}})["result"]
            if not wrong.get("isError"):
                failures.append("a failing command was not a tool error")
            else:
                print("MCP initialize, tools/list, calls, state, screenshot and errors work")
        finally:
            bridge.stdin.close()
            bridge.wait(timeout=10)
    finally:
        try:
            if macro is not None:
                channel.execute("macro.delete", {"id": macro})
            channel.execute("macro.panel.show", {"visible": False})
            if artwork is not None:
                channel.execute("gallery.close")
                time.sleep(3)
                channel.execute("gallery.delete", {"ids": [artwork]})
        except Exception as error:  # noqa: BLE001 — cleanup is best effort
            print(f"cleanup: {error}")
    if failures:
        print("FAILED:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print("macros and MCP OK")


if __name__ == "__main__":
    main()
