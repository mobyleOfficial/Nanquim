#!/usr/bin/env python3
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import nanquim_mcp  # noqa: E402

COMMANDS = [
    {
        "id": "layer.setOpacity",
        "title": "Set Layer Opacity",
        "category": "Layer",
        "undoable": True,
        "available": True,
        "parameters": [
            {"name": "layerId", "type": "Integer", "required": True},
            {"name": "opacity", "type": "Number", "required": True, "minimum": 0.0, "maximum": 1.0},
        ],
    },
    {
        "id": "tool.select",
        "title": "Select Tool",
        "category": "Brush",
        "undoable": False,
        "available": False,
        "parameters": [{"name": "tool", "type": "Enumeration", "required": True, "allowedValues": ["brush", "eraser"]}],
    },
]


class FakeChannel:
    def __init__(self):
        self.calls = []

    def call(self, method, params=None):
        self.calls.append((method, params))
        if method == "listCommands":
            return COMMANDS
        if method == "getState":
            return {"tool": "Brush"}
        if method == "screenshot":
            return {"width": 1, "height": 1, "png": "iVBORw0KGgo="}
        if method == "execute":
            if params["id"] == "tool.select":
                raise RuntimeError("No open document")
            return {"ok": True}
        raise AssertionError(method)


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.channel = FakeChannel()
        self.bridge = nanquim_mcp.Bridge(lambda: self.channel)

    def request(self, method, params=None, request_id=1):
        return self.bridge.handle({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}})

    def test_initialize_answers_the_requested_version(self):
        result = self.request("initialize", {"protocolVersion": "2024-11-05"})["result"]
        self.assertEqual("2024-11-05", result["protocolVersion"])
        self.assertIn("tools", result["capabilities"])
        self.assertEqual(nanquim_mcp.PROTOCOL_VERSIONS[0], self.request("initialize", {"protocolVersion": "1999"})["result"]["protocolVersion"])

    def test_notifications_get_no_response(self):
        self.assertIsNone(self.bridge.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))

    def test_tools_have_schemas_from_the_parameters(self):
        tools = {tool["name"]: tool for tool in self.request("tools/list")["result"]["tools"]}
        opacity = tools["nanquim_layer_setOpacity"]
        self.assertEqual(
            {
                "type": "object",
                "properties": {"layerId": {"type": "integer"}, "opacity": {"type": "number", "minimum": 0.0, "maximum": 1.0}},
                "required": ["layerId", "opacity"],
                "additionalProperties": False,
            },
            opacity["inputSchema"],
        )
        self.assertTrue(opacity["description"].startswith("Set Layer Opacity."))
        self.assertEqual(["brush", "eraser"], tools["nanquim_tool_select"]["inputSchema"]["properties"]["tool"]["enum"])
        self.assertIn("Unavailable", tools["nanquim_tool_select"]["description"])
        self.assertIn("nanquim_get_state", tools)
        self.assertIn("nanquim_screenshot", tools)

    def test_calls_run_commands_and_failures_are_tool_errors(self):
        result = self.request("tools/call", {"name": "nanquim_layer_setOpacity", "arguments": {"layerId": 2, "opacity": 0.5}})["result"]
        self.assertEqual({"ok": True}, json.loads(result["content"][0]["text"]))
        self.assertIn(("execute", {"id": "layer.setOpacity", "arguments": {"layerId": 2, "opacity": 0.5}}), self.channel.calls)
        failed = self.request("tools/call", {"name": "nanquim_tool_select", "arguments": {"tool": "brush"}})["result"]
        self.assertTrue(failed["isError"])
        self.assertEqual("No open document", failed["content"][0]["text"])
        self.assertTrue(self.request("tools/call", {"name": "nanquim_nothing"})["result"]["isError"])
        shot = self.request("tools/call", {"name": "nanquim_screenshot"})["result"]["content"][0]
        self.assertEqual(("image", "image/png"), (shot["type"], shot["mimeType"]))

    def test_unknown_methods_and_bad_json(self):
        self.assertEqual(-32601, self.request("resources/list")["error"]["code"])
        output = io.StringIO()
        nanquim_mcp.serve(self.bridge, io.StringIO('not json\n{"jsonrpc":"2.0","id":7,"method":"ping"}\n'), output)
        lines = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(-32700, lines[0]["error"]["code"])
        self.assertEqual({"jsonrpc": "2.0", "id": 7, "result": {}}, lines[1])

    def test_an_unreachable_app_is_an_error_and_retried(self):
        attempts = []

        def connect():
            attempts.append(1)
            raise ConnectionRefusedError("refused")

        bridge = nanquim_mcp.Bridge(connect)
        response = bridge.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertIn("not reachable", response["error"]["message"])
        bridge.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        self.assertEqual(2, len(attempts))


if __name__ == "__main__":
    unittest.main()
