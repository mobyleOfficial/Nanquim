# Device measurement scripts

PowerShell scripts (Windows PowerShell 5.1+, saved as UTF-8 with BOM) that drive the app on a
device through adb and uiautomator, for the app's performance targets.

- `device_ui.ps1`: helpers (tap a text, tap the switch left of a label, screenshot, back).
- `measure_phase1.ps1`: layer toggles and the 10 s camera tour on a test document.
- `measure_stroke.ps1`: frame timing of a 5 s stroke (statistics reset just before it).
- `kill_test.py`: kill test — kills the app around its autosave 100 times and checks
  that the artwork reopens exactly as before or after the last stroke (Python 3 + Pillow).
- `measure_save.py`: incremental save time of a 4096² × 20-layer artwork after one stroke.
- `measure_adjust.py`: frame times of an adjustment's live preview on a 4096² canvas fit to the
  screen while a finger drags its main parameter.
- `measure_symmetry.py`: frame times of a 5 s stylus stroke alone and with the radial symmetry's 8
  copies on a 4096² canvas.
- `measure_shape_edit.py`: frame times while a corner of a QuickShape rectangle is dragged ("Edit
  shape").
- `check_deep_color.py`: 16-bit documents: new canvas, paint, undo, redo, save, reopen,
  and the painted color read back.
- `measure_deep_color.py`: a 4096² canvas in 8 and 16 bits: camera tour, 5 s strokes and flood fill;
  `--bits 16` measures one depth.
- `check_color_depth.py`: Canvas › Color depth: an 8-bit artwork
  converted to 16 bits and back keeps its colors and depth across reopens, with an empty history;
  then times both conversions of a 4096² artwork with 5 full layers.
- `check_color_profile.py`: color profiles: convert sRGB → Display P3,
  reopen, assign, and a 16-bit conversion to Adobe RGB and back; values read with the eyedropper.
- `check_display_color.py`: the display follows the document profile: screen colors (screencap) and the SurfaceView's
  dataspace across convert/assign.
- `measure_display_color.py`: camera tour, screen-pass GPU time and strokes on a 4096² canvas in
  sRGB and converted to Display P3.
- `measure_renderers.py`: camera tour, screen-pass GPU time and strokes on one 4096² canvas with
  GL ES and Vulkan alternately (developer switch `debug.nanquim.renderer`),
  strokes front- and double-buffered with the engine's latencies.
- `measure_text.py`: typing into a text layer (a page of text in a fixed box on 4096²; at its end or
  `--at-start`): the engine's CPU time per rasterization and the frames while typing.
- `check_missing_fonts.py`: the missing-font flow (save a text layer with an unknown font; reopen; the
  Missing fonts list, editing with a substitute).
- `check_profile_export.py`: exports and imports with the document profile, 16-bit and CMYK files;
  needs the debug build and the synthetic CMYK profile
  the host tests write.
- `verify_profile_exports.py`: the files `check_profile_export.py` leaves in its folder read by color
  management that is not Nanquim's: Windows Color System through
  WIC, Pillow + ImageCms, ffmpeg with the Display P3 matrix, Poppler, tifffile for the CMYK TIFF
  (host only, Windows; needs ffmpeg, Poppler's `pdfimages`, `pip install tifffile`).
- `measure_reference.py`: frame times of a 3 s stroke with the reference window hidden and shown in
  Canvas mode.

GPU time per part of the engine's work (stroke dabs, provisional stroke, composition, screen pass):
`adb shell setprop debug.nanquim.gpuprofile 1`, then read
`adb logcat -s NanquimEngine | grep "GPU profile"` (one line every 120 frames); `setprop … 0` turns
it off. It needs `GL_EXT_disjoint_timer_query`. Perfetto (`atrace_apps: "com.mobyle.nanquim"`) shows the
CPU side; on the reference tablet pass the config through stdin
(`cat cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/x.pftrace`).

The Python scripts drive the app through the automation control channel:
`python tools/device/kill_test.py --device <serial>`.

Usage (from the repository root):

```powershell
$env:NANQUIM_DEVICE = "<adb serial>"   # from: adb devices
./gradlew :app:installProdBenchmark   # optimized engine; never measure the debug APK
tools/device/measure_stroke.ps1 -Spec 4096x4096x30 -Prefix s30
tools/device/measure_phase1.ps1 -Spec 4096x4096x20 -Prefix p20 -SettleSeconds 40 -ToggleSeconds 8
```

Results go to `build/device-measurements/` (screenshots and HUD crops). The scripts tap UI texts,
so they match the device language (the labels in the scripts are the Brazilian Portuguese ones).
Test documents open through the intent extras `com.mobyle.nanquim.extra.TEST_DOCUMENT`
(`<width>x<height>x<layers>`) and `com.mobyle.nanquim.extra.TEST_FILL` (`-1` = every tile).

ktlint runs in CI from its release jar; locally, download the same version (`KTLINT_VERSION` in
`.github/workflows/ci.yml`) and run
`java -jar ktlint.jar "**/src/**/*.kt" "**/*.gradle.kts" "*.gradle.kts"`.
- `check_animation.py`: animation: five frames, onion skin colors and range in the document composite, background frame, hold, reorder, save/reopen, playback and undo.
- `measure_playback.py`: animation playback of 100 frames at 1920 × 1080: frames shown against frames due at each fps, engine frame intervals, CPU and incomplete frames; `--tiles` sets how full each frame is.
- `measure_timelapse.py`: timelapse recording cost on the render thread: Perfetto trace of injected pen strokes with recording on and off; capture work per frame drawn, longest capture section, mean frame times; `--size`, `--resolution`.
- `check_animation_export.py`: animation exports: GIF, APNG, MP4 (H.264, HEVC), WebM and PNG sequence written by `export.image` and decoded back on the device (`debug.animation.decode`): sizes, frame counts, durations and each frame's colors.
- `check_video_layer.py`: video layers: an MP4 exported by the app imported as a video layer, frames shown at two instants, paint refused, rasterize/undo, reopen.
- `measure_animation_export.py`: exports: 100 frames at 1920 × 1080 exported in every format, decoded back, with the time each took.
- `check_private_layers.py`: private layers and layer select by touch.
- `check_timelapse.py`: timelapse recording: frames, private layers left out, segments per session, a force-stop losing only the unfinished segment.
- `check_timelapse_export.py`: timelapse export (full and 30 s with cards, a WorkManager job) decoded back, and the replay.
- `check_gestures.py`: gesture arbiter on the device: painting by finger only when on (a tap never paints), full screen, the copy & paste menu (it collapses the notification shade first: an open one takes the swipes).
- `check_gesture_settings.py`: gesture configuration: `settings.gestures.*` defaults, a refused trigger, a conflict shown on the screen, the configuration kept across a restart, the older settings commands, a finger drag bound to Paint then Erase, a modifier button tap bound to full screen.
- `check_quick_menu.py`: quick menu and shortcut brush: slots on screen, a slot runs and closes the menu, a tap outside closes it, presets, a one-finger tap bound to the menu, the shortcut brush toggle. It restores the presets, gestures and brushes it changed.
- `check_keyboard_mouse.py`: keyboard shortcuts, mouse and projected canvas: default and edited shortcuts through `input keyevent/keycombination`, a mouse drag paints and with Space held (a background `keycombination -t`) moves the view without painting, the projected canvas on a developer overlay display (`overlay_display_devices`, removed at the end).
- `check_canvas_info.py`: canvas information: `canvas.info.get` values, stroke count and tracked time, the dialog, a signature swiped in its box and a typed artist name kept after reopening.
- `check_macros_mcp.py`: macros and the MCP bridge: a recording around a query keeps only the edits, replay, kept across a restart, the dialog; `tools/mcp/nanquim_mcp.py` driven as an MCP client would (initialize, tools/list, a tool call, state, screenshot, a failing call).
- `check_cmyk_canvas.py`: CMYK canvas: new CMYK canvas, colors brought into the gamut, inks round trip, soft proof on the screen, TIFF/PSD in CMYK by default (inks compared with Pillow's Little CMS), RGB export still possible, the profile kept after reopening, a CMYK TIFF imported as a CMYK canvas. Debug build.
- `check_accessibility.py`: accessibility audit: every screen, panel and tool bar read with uiautomator; unlabeled tappable items and touch targets under 48 dp (30 dp for color swatches); `--font-scale 1.3`.
- `check_lifecycle.py`: turning the tablet, a pop-up window and the system killing the process in the background keep the artwork.
- `measure_first_stroke.py`: first-stroke latency after an idle pause, cold and with the GPU kept awake as a hovering pen does. Benchmark build.
