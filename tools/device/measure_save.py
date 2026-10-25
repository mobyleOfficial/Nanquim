import argparse
import json
import os
import random
import statistics
import time

from kill_test import ADB, PACKAGE, adb, launch, stroke_samples, wait_for_artwork


def wait_for_save(channel, timeout):
    for index, event in enumerate(channel.events):
        if event.get("type") == "documentSaved":
            del channel.events[: index + 1]
            return event
    channel.events.clear()
    deadline = time.time() + timeout
    channel.socket.settimeout(timeout)
    while time.time() < deadline:
        line = channel.file.readline()
        if not line:
            raise ConnectionError("channel closed")
        message = json.loads(line)
        if message.get("method") == "event" and message["params"].get("type") == "documentSaved":
            return message["params"]
    raise TimeoutError("no save")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--size", type=int, default=4096)
    parser.add_argument("--layers", type=int, default=20)
    parser.add_argument("--strokes", type=int, default=10)
    arguments = parser.parse_args()
    generator = random.Random(7)

    adb(arguments.device, "shell", "am", "force-stop", PACKAGE)
    channel = launch(arguments.device)
    channel.execute("gallery.close")
    artwork_id = channel.execute("gallery.newCanvas", {"width": arguments.size, "height": arguments.size, "name": "Save timing"})["id"]
    wait_for_artwork(channel, artwork_id)
    for _ in range(arguments.layers - 1):
        channel.execute("layer.new")
    channel.execute("debug.layer.fillRandomTiles", {"layerId": 0, "count": -1})
    channel.execute("brush.setSize", {"sizePx": 40})
    channel.call("subscribeEvents")
    total_tiles = 0
    total_milliseconds = 0.0
    start = time.time()
    while True:
        channel.execute("document.save")
        full = wait_for_save(channel, 600)
        total_tiles += full["tilesWritten"]
        total_milliseconds += full["milliseconds"]
        if full["tilesWritten"] == 0 and total_tiles > 0:
            break
        time.sleep(1)
    print(f"full save: {total_tiles} tiles in {total_milliseconds:.0f} ms of saves (wall {time.time() - start:.1f} s)", flush=True)

    durations = []
    for index in range(arguments.strokes):
        channel.execute("tool.paint.strokePath", {"samples": stroke_samples(arguments.size, generator)})
        saved = wait_for_save(channel, 30)
        durations.append(saved["milliseconds"])
        print(f"stroke {index + 1}: {saved['tilesWritten']} tiles, {saved['milliseconds']:.0f} ms, ok={saved['ok']}", flush=True)
    print(f"RESULT incremental save median {statistics.median(durations):.0f} ms, max {max(durations):.0f} ms over {len(durations)} strokes", flush=True)
    channel.close()


if __name__ == "__main__":
    main()
