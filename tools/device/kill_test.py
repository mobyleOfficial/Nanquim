import argparse
import base64
import hashlib
import io
import json
import os
import random
import socket
import subprocess
import sys
import time

PACKAGE = "com.mobyle.nanquim"
ACTIVITY = "com.mobyle.nanquim/com.mobyle.nanquim.MainActivity"
TOKEN = "killtest"
PORT = 7765


class Channel:
    def __init__(self):
        self.socket = socket.create_connection(("127.0.0.1", PORT), timeout=60)
        self.file = self.socket.makefile("rw", encoding="utf-8")
        self.next_id = 1
        self.events = []
        self.call("authenticate", {"token": TOKEN})

    def call(self, method, params=None):
        request_id = self.next_id
        self.next_id += 1
        self.file.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}) + "\n")
        self.file.flush()
        while True:
            line = self.file.readline()
            if not line:
                raise ConnectionError("channel closed")
            message = json.loads(line)
            if message.get("method") == "event":
                self.events.append(message["params"])
            if message.get("id") == request_id:
                if "error" in message:
                    raise RuntimeError(f"{method}: {message['error']}")
                return message["result"]

    def execute(self, command, arguments=None):
        return self.call("execute", {"id": command, "arguments": arguments or {}})

    def close(self):
        try:
            self.socket.close()
        except OSError:
            pass


def adb(device, *args, check=True):
    return subprocess.run([ADB, "-s", device, *args], check=check, capture_output=True, text=True).stdout


def launch(device):
    adb(device, "shell", "am", "start", "-W", "-n", ACTIVITY, "--es", "com.mobyle.nanquim.extra.CONTROL_TOKEN", TOKEN)
    adb(device, "forward", f"tcp:{PORT}", "localabstract:nanquim-control")
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            return Channel()
        except (OSError, ConnectionError, RuntimeError):
            time.sleep(0.3)
    raise TimeoutError("control channel did not come up")


def composite_hash(channel):
    shot = channel.call("screenshot")
    from PIL import Image
    image = Image.open(io.BytesIO(base64.b64decode(shot["png"]))).convert("RGBA")
    return hashlib.sha256(image.tobytes()).hexdigest()


def wait_for_artwork(channel, artwork_id, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        state = channel.call("getState")
        artwork = state.get("artwork")
        if artwork and artwork["id"] == artwork_id and state.get("document") and artwork["saveStatus"] == "Saved":
            return
        time.sleep(0.25)
    raise TimeoutError(f"artwork {artwork_id} did not reopen saved")


def stroke_samples(size, generator):
    x, y = generator.uniform(0.1, 0.9) * size, generator.uniform(0.1, 0.9) * size
    samples = []
    for step in range(40):
        x = min(max(x + generator.uniform(-40, 40), 0), size - 1)
        y = min(max(y + generator.uniform(-40, 40), 0), size - 1)
        samples.append([x, y, 0.5 + 0.5 * generator.random(), step * 8])
    return samples


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--cycles", type=int, default=100)
    parser.add_argument("--size", type=int, default=1024)
    parser.add_argument("--seed", type=int, default=20261014)
    arguments = parser.parse_args()
    generator = random.Random(arguments.seed)

    adb(arguments.device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(arguments.device)
    channel.execute("gallery.close")
    artwork_id = channel.execute("gallery.newCanvas", {"width": arguments.size, "height": arguments.size, "name": "Kill test"})["id"]
    channel.execute("brush.setSize", {"sizePx": 60})
    wait_for_artwork(channel, artwork_id)

    failures = 0
    outcomes = {"before": 0, "after": 0}
    for cycle in range(1, arguments.cycles + 1):
        wait_for_artwork(channel, artwork_id)
        before = composite_hash(channel)
        channel.execute("tool.paint.strokePath", {"samples": stroke_samples(arguments.size, generator)})
        stroked = time.time()
        after = composite_hash(channel)
        delay = generator.uniform(1.5, 2.8)
        time.sleep(max(0.0, delay - (time.time() - stroked)))
        adb(arguments.device, "shell", "am", "force-stop", PACKAGE)
        delay = time.time() - stroked
        channel.close()
        channel = launch(arguments.device)
        try:
            wait_for_artwork(channel, artwork_id)
            reopened = composite_hash(channel)
        except (TimeoutError, RuntimeError) as error:
            failures += 1
            print(f"cycle {cycle}: FAIL ({error})", flush=True)
            continue
        if reopened == before:
            outcomes["before"] += 1
            verdict = "old state"
        elif reopened == after:
            outcomes["after"] += 1
            verdict = "new state"
        else:
            failures += 1
            verdict = "FAIL (neither state)"
        print(f"cycle {cycle}: killed after {delay:.2f} s -> {verdict}", flush=True)

    passed = arguments.cycles - failures
    print(f"RESULT {passed}/{arguments.cycles} passed (old {outcomes['before']}, new {outcomes['after']})", flush=True)
    channel.close()
    sys.exit(0 if failures == 0 else 1)


ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"

if __name__ == "__main__":
    main()
