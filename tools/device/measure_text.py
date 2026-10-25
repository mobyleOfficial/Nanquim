#!/usr/bin/env python3
"""Measures text layer editing: on a 4096² canvas
a fixed text box with --characters characters of Latin text at 36 pt (about a page) is placed,
then --keys characters are typed one at a time at its end (or start), each after the 50 ms
debounce, as a person types.
Reports the engine's CPU time per rasterization of the layer (layout, coverage of every tile under
the text, uploads queued) and the frame statistics while typing. The artwork is deleted at the end.

Run on the benchmark build (./gradlew :app:installProdBenchmark):

    python tools/device/measure_text.py --device <serial> [--characters 2000] [--keys 30]
"""

import argparse
import os
import time

from kill_test import adb, launch, wait_for_artwork
from measure_deep_color import frames_line

WORDS = ("the quick brown fox jumps over a lazy dog while Nanquim draws every glyph again "
         "Lorem ipsum dolor sit amet consectetur adipiscing elit ").split()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", default=os.environ.get("NANQUIM_DEVICE"))
    parser.add_argument("--characters", type=int, default=2000)
    parser.add_argument("--keys", type=int, default=30)
    parser.add_argument("--at-start", action="store_true",
                        help="type at the start of the text (the whole paragraph reflows each time)")
    arguments = parser.parse_args()
    device = arguments.device
    adb(device, "shell", "am", "force-stop", "com.mobyle.nanquim")
    channel = launch(device)
    artwork = None
    try:
        channel.execute("gallery.close")
        time.sleep(3)
        artwork = channel.execute("gallery.newCanvas", {"width": 4096, "height": 4096, "name": "Text timing"})["id"]
        wait_for_artwork(channel, artwork)
        channel.execute("view.fit")
        channel.execute("tool.select", {"tool": "text"})
        channel.execute("text.place", {"x": 200, "y": 200})
        channel.execute("text.setBox", {"mode": "fixed", "width": 3600, "height": 3600})
        channel.execute("text.setStyle", {"sizePt": 36})
        words, text = 0, ""
        while len(text) < arguments.characters:
            text += WORDS[words % len(WORDS)] + " "
            words += 1
        channel.execute("text.insert", {"text": text[: arguments.characters]})
        time.sleep(3)
        if arguments.at_start:
            channel.execute("text.select", {"start": 0, "end": 0})
        channel.execute("debug.stats.resetFrames")
        for index in range(arguments.keys):
            channel.execute("text.insert", {"text": "abcdefghij"[index % 10]})
            time.sleep(0.15)
        time.sleep(1)
        frames = channel.execute("debug.stats.readFrames")
        stats = frames["text"]
        where = "at the start" if arguments.at_start else "at the end"
        print(f"typing {where}, {arguments.characters} characters, 36 pt, 3600 px box on 4096²: {stats['rasterCount']} rasterizations, "
              f"median {stats['rasterMedianMilliseconds']:.1f} ms, max {stats['rasterMaxMilliseconds']:.1f} ms")
        print(f"frames while typing: {frames_line(frames)}")
        channel.execute("text.done")
    finally:
        if artwork is not None:
            channel.execute("gallery.close")
            time.sleep(3)
            channel.execute("gallery.delete", {"ids": [artwork]})
        channel.close()


if __name__ == "__main__":
    main()
