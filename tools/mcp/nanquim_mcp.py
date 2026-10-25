#!/usr/bin/env python3
"""MCP bridge to Nanquim.

A Model Context Protocol server on stdio that exposes every command of the app's control channel
(developer builds) as a tool with a JSON schema made from the command's parameters, plus
`nanquim_get_state` and `nanquim_screenshot`. Standard library only.

Forward the channel and start the app with a known pairing token, then point an MCP client at
this script:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN MYTOKEN
    python tools/mcp/nanquim_mcp.py --token MYTOKEN

(`--device SERIAL` runs the `adb forward` itself.) Tool names are the command ids with "." turned
into "_" and a `nanquim_` prefix: `layer.new` is `nanquim_layer_new`. A command that fails answers
its message with `isError`. Messages on stdio are JSON-RPC 2.0, one per line; nothing else is
written to stdout.
"""

import argparse
import itertools
import json
import os
import socket
import subprocess
import sys

SERVER_NAME = "nanquim"
SERVER_VERSION = "0.16.0"
PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"]
TOOL_PREFIX = "nanquim_"
STATE_TOOL = "nanquim_get_state"
SCREENSHOT_TOOL = "nanquim_screenshot"

SCHEMA_TYPES = {
    "Boolean": {"type": "boolean"},
    "Integer": {"type": "integer"},
    "Number": {"type": "number"},
    "String": {"type": "string"},
    "Enumeration": {"type": "string"},
    "Object": {"type": "object"},
    "Array": {"type": "array"},
    "Json": {},
}


class ControlChannel:
    def __init__(self, host, port, token):
        self.socket = socket.create_connection((host, port), timeout=120)
        self.reader = self.socket.makefile("r", encoding="utf-8")
        self.ids = itertools.count(1)
        self.call("authenticate", {"token": token})

    def call(self, method, params=None):
        request_id = next(self.ids)
        message = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}
        self.socket.sendall((json.dumps(message) + "\n").encode("utf-8"))
        while True:
            line = self.reader.readline()
            if not line:
                raise ConnectionError("The control channel closed")
            reply = json.loads(line)
            if reply.get("id") != request_id:
                continue
            if "error" in reply:
                raise RuntimeError(reply["error"].get("message", "Command failed"))
            return reply["result"]


def tool_name(command_id):
    return TOOL_PREFIX + command_id.replace(".", "_")


def input_schema(parameters):
    properties = {}
    required = []
    for parameter in parameters:
        schema = dict(SCHEMA_TYPES.get(parameter["type"], {}))
        if parameter.get("allowedValues"):
            schema["enum"] = parameter["allowedValues"]
        for bound in ("minimum", "maximum"):
            if parameter.get(bound) is not None:
                schema[bound] = parameter[bound]
        if parameter.get("default") is not None:
            schema["default"] = parameter["default"]
        properties[parameter["name"]] = schema
        if parameter.get("required"):
            required.append(parameter["name"])
    schema = {"type": "object", "properties": properties, "additionalProperties": False}
    if required:
        schema["required"] = required
    return schema


def command_tool(command):
    title = command.get("title") or command["id"]
    notes = [f"Nanquim command `{command['id']}` ({command['category']}"]
    notes.append(", undoable)" if command.get("undoable") else ")")
    description = f"{title}. {''.join(notes)}."
    if not command.get("available", True):
        description += " Unavailable right now (for example, no artwork is open)."
    return {"name": tool_name(command["id"]), "description": description, "inputSchema": input_schema(command["parameters"])}


class Bridge:
    def __init__(self, connect):
        self.connect = connect
        self.channel = None
        self.commands = {}

    def channel_or_connect(self):
        if self.channel is None:
            self.channel = self.connect()
        return self.channel

    def handle(self, message):
        method = message.get("method")
        request_id = message.get("id")
        if request_id is None:
            return None
        try:
            result = self.dispatch(method, message.get("params") or {})
        except LookupError as error:
            return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32601, "message": str(error)}}
        except (ConnectionError, OSError) as error:
            self.channel = None
            return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32000, "message": f"Nanquim is not reachable: {error}"}}
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def dispatch(self, method, params):
        if method == "initialize":
            requested = params.get("protocolVersion")
            return {
                "protocolVersion": requested if requested in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0],
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            }
        if method == "ping":
            return {}
        if method == "tools/list":
            return {"tools": self.list_tools()}
        if method == "tools/call":
            return self.call_tool(params.get("name", ""), params.get("arguments") or {})
        raise LookupError(f"Unknown method: {method}")

    def list_tools(self):
        commands = self.channel_or_connect().call("listCommands")
        self.commands = {tool_name(command["id"]): command["id"] for command in commands}
        tools = [
            {
                "name": STATE_TOOL,
                "description": "The app's state: the open artwork's document (layers), tool, colors, panels and more.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
            {
                "name": SCREENSHOT_TOOL,
                "description": "The open artwork's composite as a PNG image.",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            },
        ]
        return tools + [command_tool(command) for command in commands]

    def call_tool(self, name, arguments):
        channel = self.channel_or_connect()
        try:
            if name == STATE_TOOL:
                return text_result(channel.call("getState"))
            if name == SCREENSHOT_TOOL:
                shot = channel.call("screenshot")
                return {"content": [{"type": "image", "data": shot["png"], "mimeType": "image/png"}]}
            if not self.commands:
                self.list_tools()
            command_id = self.commands.get(name)
            if command_id is None:
                return error_result(f"Unknown tool: {name}")
            return text_result(channel.call("execute", {"id": command_id, "arguments": arguments}))
        except RuntimeError as error:
            return error_result(str(error))


def text_result(value):
    return {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}]}


def error_result(message):
    return {"content": [{"type": "text", "text": message}], "isError": True}


def serve(bridge, input_stream, output_stream):
    for line in input_stream:
        if not line.strip():
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON"}}
        else:
            response = bridge.handle(message)
        if response is not None:
            output_stream.write(json.dumps(response) + "\n")
            output_stream.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7765)
    parser.add_argument("--token", default=os.environ.get("NANQUIM_TOKEN"), help="The pairing token (or NANQUIM_TOKEN)")
    parser.add_argument("--device", help="Run `adb forward` for this device serial first")
    parser.add_argument("--adb", default="adb")
    options = parser.parse_args()
    if not options.token:
        parser.error("--token (or NANQUIM_TOKEN) is required")
    if options.device:
        subprocess.run(
            [options.adb, "-s", options.device, "forward", f"tcp:{options.port}", "localabstract:nanquim-control"],
            check=True,
            capture_output=True,
        )
    bridge = Bridge(lambda: ControlChannel(options.host, options.port, options.token))
    serve(bridge, sys.stdin, sys.stdout)


if __name__ == "__main__":
    main()
