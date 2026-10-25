#!/usr/bin/env python3
"""Client of Nanquim's automation control channel.

The channel runs in developer builds on a local socket. Forward it and read the pairing token in
the app's debug panel (or choose it at launch with the CONTROL_TOKEN extra):

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN MYTOKEN

Examples:

    tools/automation/nanquim_control.py --token MYTOKEN list
    tools/automation/nanquim_control.py --token MYTOKEN state
    tools/automation/nanquim_control.py --token MYTOKEN run layer.new
    tools/automation/nanquim_control.py --token MYTOKEN run layer.setBlend '{"layerId": 2, "blend": "multiply"}'
    tools/automation/nanquim_control.py --token MYTOKEN screenshot out.png
    tools/automation/nanquim_control.py --token MYTOKEN events

Messages are JSON-RPC 2.0, one per line.
"""

import argparse
import base64
import itertools
import json
import socket
import sys


class ControlChannel:
    def __init__(self, host, port, token):
        self.socket = socket.create_connection((host, port))
        self.reader = self.socket.makefile("r", encoding="utf-8")
        self.ids = itertools.count(1)
        self.call("authenticate", {"token": token})

    def call(self, method, params=None):
        request_id = next(self.ids)
        message = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}
        self.socket.sendall((json.dumps(message) + "\n").encode("utf-8"))
        while True:
            reply = json.loads(self.reader.readline())
            if reply.get("id") != request_id:
                continue
            if "error" in reply:
                raise RuntimeError(f"{method}: {reply['error']['message']}")
            return reply["result"]

    def events(self):
        self.call("subscribeEvents")
        for line in self.reader:
            message = json.loads(line)
            if message.get("method") == "event":
                yield message["params"]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7765)
    parser.add_argument("--token", required=True)
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("list")
    commands.add_parser("state")
    run = commands.add_parser("run")
    run.add_argument("id")
    run.add_argument("arguments", nargs="?", default="{}")
    shot = commands.add_parser("screenshot")
    shot.add_argument("output")
    commands.add_parser("events")
    options = parser.parse_args()

    channel = ControlChannel(options.host, options.port, options.token)
    if options.action == "list":
        for command in channel.call("listCommands"):
            names = ", ".join(p["name"] for p in command["parameters"])
            print(f"{command['id']}({names})" + ("" if command["available"] else "  [unavailable]"))
    elif options.action == "state":
        print(json.dumps(channel.call("getState"), indent=2))
    elif options.action == "run":
        result = channel.call("execute", {"id": options.id, "arguments": json.loads(options.arguments)})
        print(json.dumps(result, indent=2))
    elif options.action == "screenshot":
        result = channel.call("screenshot")
        with open(options.output, "wb") as file:
            file.write(base64.b64decode(result["png"]))
        print(f"{result['width']}x{result['height']} -> {options.output}")
    elif options.action == "events":
        try:
            for event in channel.events():
                print(json.dumps(event))
        except KeyboardInterrupt:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
