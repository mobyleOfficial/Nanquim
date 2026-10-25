# Brush tools

Tools for the default brush library and the brush tuning tool.

## Built-in library

`build_library.py` writes `assets-src/brushes/` (brush folders, procedural textures, `library.json`) and the built-in brush section of `CREDITS.md` from the recipes in the script. Edit the recipes, never the generated files:

```bash
./gradlew :engine:hostTest                # builds nanquim-cli
python3 tools/brushes/build_library.py    # or: build_library.py path/to/nanquim-cli
python3 tools/check_licenses.py
```

## Tuning a brush against a reference image

1. **Reference**: on a 2048 × 2048 white canvas, paint the test sheet items inside the regions of the template, at the brush's default size and opacity, and export a PNG. Get the template with:
   ```bash
   nanquim-cli brush-sheet-template template.png
   ```
2. **Render** the Nanquim brush on the same sheet:
   ```bash
   nanquim-cli brush-sheet assets-src/brushes/sketching/<id> nanquim.png
   ```
3. **Compare**: prints the statistics distance (match below 1.0) and the ten largest differences, and writes reference | Nanquim | difference (red: Nanquim lighter, blue: darker):
   ```bash
   nanquim-cli brush-compare reference.png nanquim.png side-by-side.png
   ```
   `nanquim-cli brush-stats sheet.png` prints all statistics as JSON.
4. **Tune** (optional): Nelder–Mead over continuous parameters; discrete choices (rendering mode, grain mode, textures) stay yours:
   ```bash
   nanquim-cli brush-tune assets-src/brushes/sketching/<id> reference.png tuned.json \
       --parameters dynamics.size.min,taper.stylus.end,grain.depth --evaluations 150 --scale 0.5
   ```
   Copy the values you accept into the recipe in `build_library.py`.
5. **Review** with two artists on the reference device.

The `hostTest` build of `nanquim-cli` is unoptimized (a full sheet takes ~20 s). For tuning, build it in release mode:

```bash
cmake -S engine/src/test/cpp -B engine/build/tools -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build engine/build/tools --target nanquim-cli
```

The sheet input is synthetic until the recorded trace exists: items, positions, speeds and pressures are in `engine/src/reference/test_sheet.cpp`.
