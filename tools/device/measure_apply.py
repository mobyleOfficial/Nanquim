#!/usr/bin/env python3
"""Measures how long applying each adjustment takes on a 4096² canvas (the
apply time is measured and recorded).

Run on the benchmark build (./gradlew :app:installProdBenchmark), with the control channel:

    adb forward tcp:7765 localabstract:nanquim-control
    adb shell am start -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.CONTROL_TOKEN killtest
    tools/device/measure_apply.py [--open-document]

Paints color bands over a new 4096² canvas (or uses the open document), then for each filter opens
it with strong parameters, records a Perfetto trace around the apply and reads the engine's
NanquimAdjustmentApply section (CPU and GPU work: the section ends after glFinish), then undoes the
apply. Liquify applies one push stroke across the canvas. Needs the perfetto Python package
(trace_processor).
"""

import argparse
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "automation"))
from nanquim_control import ControlChannel  # noqa: E402

COLORS = [(0.9, 0.2, 0.15), (0.15, 0.35, 0.85), (0.95, 0.8, 0.15), (0.2, 0.65, 0.3)]

FILTERS = [
    ("hueSaturationBrightness", {"hue": 0.3, "saturation": 0.4}),
    ("colorBalance", {"midtonesCyanRed": 0.6}),
    ("curves", {}),
    ("gradientMap", {"amount": 1}),
    ("gaussianBlur", {"radius": 1}),
    ("motionBlur", {"amount": 1, "angle": 0.3}),
    ("perspectiveBlur", {"amount": 1, "x": 0.4, "y": 0.6}),
    ("sharpen", {"amount": 1}),
    ("noise", {"amount": 1, "scale": 0.3}),
    ("bloom", {"size": 1, "transition": 0.4, "burn": 0.5}),
    ("glitch", {"amount": 0.5}),
    ("halftone", {"dotSize": 0.3}),
    ("chromaticAberration", {"amount": 1}),
    ("liquify", {}),
]

TRACE_CONFIG = """buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config { atrace_apps: "com.mobyle.nanquim" } } }
duration_ms: %d
"""


def line(x0, y0, x1, y1, count=80):
    return [[x0 + (x1 - x0) * i / (count - 1), y0 + (y1 - y0) * i / (count - 1), 1.0, 8.0 * i] for i in range(count)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--open-document", action="store_true")
    options = parser.parse_args()
    from perfetto.trace_processor import TraceProcessor

    channel = ControlChannel("127.0.0.1", 7765, "killtest")

    def run(command, arguments=None):
        return channel.call("execute", {"id": command, "arguments": arguments or {}})

    if not options.open_document:
        run("gallery.newCanvas", {"width": 4096, "height": 4096, "unit": "px", "dpi": 300,
                                  "name": "Apply timing", "background": [1, 1, 1, 1], "backgroundHidden": False})
        time.sleep(4)
        run("tool.select", {"tool": "brush"})
        run("brush.setSize", {"sizePx": 420})
        for index in range(11):
            red, green, blue = COLORS[index % len(COLORS)]
            run("color.setPrimary", {"red": red, "green": green, "blue": blue})
            x = 150 + index * 380
            run("tool.paint.strokePath", {"samples": line(x, 100, x, 4000)})
        time.sleep(8)
    run("view.fit")
    seconds_per_filter = 7
    duration = (len(FILTERS) * seconds_per_filter + 6) * 1000
    recorder = subprocess.Popen([options.adb, "shell", "perfetto", "--txt", "-c", "-", "-o",
                                 "/data/misc/perfetto-traces/apply.pftrace"],
                                stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
    recorder.stdin.write((TRACE_CONFIG % duration).encode())
    recorder.stdin.close()
    time.sleep(3)
    order = []
    for name, parameters in FILTERS:
        run(f"adjust.{name}.preview", {"mode": "layer", **parameters})
        time.sleep(2)
        if name == "liquify":
            run("liquify.strokePath", {"samples": line(300, 2000, 3800, 2100, 60)})
            time.sleep(1)
        run("adjust.commit")
        order.append(name)
        time.sleep(seconds_per_filter - 3)
        run("edit.undo")
        time.sleep(1)
    recorder.wait()
    with tempfile.TemporaryDirectory() as directory:
        local = Path(directory) / "apply.pftrace"
        subprocess.run([options.adb, "pull", "/data/misc/perfetto-traces/apply.pftrace", str(local)], check=True,
                       capture_output=True)
        processor = TraceProcessor(trace=str(local))
        rows = list(processor.query("select ts, dur from slice where name = 'NanquimAdjustmentApply' order by ts"))
        processor.close()
    if len(rows) != len(order):
        print(f"warning: {len(rows)} apply sections for {len(order)} filters")
    for name, row in zip(order, rows):
        print(f"{name}: apply {row.dur / 1e6:.0f} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
