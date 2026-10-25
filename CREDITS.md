# Credits

Every third-party library and every asset (brush, texture, icon, font, sound, image, color profile, sample artwork) used by Nanquim is listed here with its source and license. All licenses must be MIT-compatible. `tools/check_licenses.py` fails the build when an asset under `assets-src/` is not listed.

## Libraries

Machine-readable list: `tools/licenses/dependencies.json`.

| Library | Use | License | Scope |
|---|---|---|---|
| AndroidX (core, activity, Compose, input motion prediction) | UI, platform integration, stylus motion prediction | Apache-2.0 | app |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Language runtime, concurrency, JSON | Apache-2.0 | app |
| LZ4 1.10.0 with its frame format, HC and xxHash sources (`engine/third_party/lz4`, © Yann Collet) | Tile compression in memory and in `.nanquim` files | BSD-2-Clause | app |
| miniz 3.0.2 (`engine/third_party/miniz`, © Rich Geldreich, RAD Game Tools, Valve Software) | DEFLATE and ZIP in the interchange formats (PSD, TIFF, PDF, `.procreate`) | MIT | app |
| stb_image 2.30, stb_image_write 1.16 (`engine/third_party/stb`, © Sean Barrett) | PNG decoding of brush textures and JPEG encoding of PDF pages (engine); PNG read/write (reference renderer, CLI, golden tests) | MIT (or public domain) | app |
| Little CMS 2.19.1 (`engine/third_party/lcms2`, © Marti Maria Saguer) | ICC profiles and color conversion | MIT | app |
| HarfBuzz 14.6.0 (`engine/third_party/harfbuzz`, the files `src/harfbuzz.cc` builds; © Google, Red Hat, Behdad Esfahbod and contributors) | Text shaping | MIT ("Old MIT") | app |
| FreeType 2.15.0 (`engine/third_party/freetype`: `include/` and the base, sfnt, truetype, cff, psaux, pshinter, psnames and smooth modules; © The FreeType Project, www.freetype.org) | Font loading and glyph rasterization; portions of this software are copyright © 2025 The FreeType Project (www.freetype.org), all rights reserved | FreeType License (FTL, BSD-style with credit; not the GPLv2 option) | app |
| SheenBidi 3.0.0 (`engine/third_party/sheenbidi`, © Muhammad Tayyab Akram) | Unicode bidirectional algorithm and script runs | Apache-2.0 | app |
| nlohmann/json 3.12.0 (`engine/third_party/nlohmann_json`, © Niels Lohmann) | Scene files (engine test API, CLI, golden tests) | MIT | app |
| glslang 11.11.0 (the NDK's `sources/third_party/shaderc/third_party/glslang`, © 3Dlabs, LunarG, Google and contributors) | GLSL to SPIR-V for the Vulkan backend | BSD-3-Clause (two SPIR-V headers Apache-2.0) | app |
| shaderc `libshaderc_util/src/resources.cc` (the NDK's `sources/third_party/shaderc`, © The Shaderc Authors) | glslang's default resource limits | Apache-2.0 | app |
| AndroidX Room 2.8 | Brush library and gallery databases | Apache-2.0 | app |
| Firebase Crashlytics (with native crashes) | Crash reports | Apache-2.0 | app |
| Firebase Analytics (Google Play services measurement) | Usage analytics | Android Software Development Kit License (closed) | app |
| Google services and Crashlytics Gradle plugins | Firebase configuration, native symbol upload | Apache-2.0 | build |
| Kotlin Symbol Processing (KSP) | Runs the Room compiler | Apache-2.0 | build |
| AndroidX Benchmark, AndroidX Test, UI Automator | Macrobenchmarks | Apache-2.0 | test |
| JUnit 5 | JVM unit tests | EPL-2.0 | test |
| JUnit 4 (transitive, via AndroidX Test) | Instrumentation runner | EPL-1.0 | test |
| GoogleTest | C++ unit tests | BSD-3-Clause | test |
| ktlint | Kotlin style check (CI only) | MIT | build |

## Assets

Procedural textures are generated with `nanquim-cli texture <kind> <size> <seed> <parameter> <out.png>` (`engine/src/reference/procedural_texture.cpp`); the recipe column regenerates them exactly.

| Path | Author | Source | License |
|---|---|---|---|
| `assets-src/fonts/Inter-Regular.ttf`, `assets-src/fonts/Inter-Bold.ttf` | The Inter Project Authors (Rasmus Andersson) | Inter 4.1 (rsms/inter, static TTFs from the release) — bundled text font | SIL OFL 1.1 (`assets-src/fonts/licenses/OFL-Inter.txt`) |
| `assets-src/fonts/NotoSans-Regular.ttf`, `assets-src/fonts/NotoSans-Bold.ttf`, `assets-src/fonts/NotoSans-Italic.ttf`, `assets-src/fonts/NotoSerif-Regular.ttf`, `assets-src/fonts/NotoSerif-Bold.ttf`, `assets-src/fonts/NotoSansArabic-Regular.ttf`, `assets-src/fonts/NotoSansHebrew-Regular.ttf` | The Noto Project Authors | Noto Sans, Noto Serif, Noto Sans Arabic, Noto Sans Hebrew (notofonts, unhinted TTFs, unchanged) — bundled text fonts and fallbacks | SIL OFL 1.1 (`assets-src/fonts/licenses/OFL-Noto.txt`) |
| `assets-src/fonts/Caveat-Variable.ttf` | The Caveat Project Authors (Impallari Type) | Caveat (google/fonts `ofl/caveat`, variable, unchanged) — the bundled handwriting font | SIL OFL 1.1 (`assets-src/fonts/licenses/OFL-Caveat.txt`) |
| `assets-src/fonts/BebasNeue-Regular.ttf` | The Bebas Neue Project Authors (Dharma Type) | Bebas Neue (google/fonts `ofl/bebasneue`, unchanged) — the bundled display font | SIL OFL 1.1 (`assets-src/fonts/licenses/OFL-BebasNeue.txt`) |
| `assets-src/fonts/licenses/OFL-Inter.txt`, `assets-src/fonts/licenses/OFL-Noto.txt`, `assets-src/fonts/licenses/OFL-Caveat.txt`, `assets-src/fonts/licenses/OFL-BebasNeue.txt` | — | The license texts shipped with the fonts above | SIL OFL 1.1 |
| `goldens/fonts/NotoSans-Regular-subset.ttf`, `NotoSerif-Regular-subset.ttf` | The Noto Project Authors | Noto Sans / Noto Serif (notofonts), subset with fonttools to Latin-1 and punctuation (text goldens) | SIL OFL 1.1 (`goldens/fonts/OFL.txt`) |
| `goldens/fonts/NotoSansArabic-Regular-subset.ttf` | The Noto Project Authors | Noto Sans Arabic (notofonts), subset with fonttools to the Arabic blocks | SIL OFL 1.1 |
| `goldens/fonts/NotoSansHebrew-Regular-test.ttf` | The Noto Project Authors | Noto Sans Hebrew (notofonts), unchanged | SIL OFL 1.1 |
| `goldens/fonts/NotoSansCJKjp-Regular-subset.otf` | Adobe (with Reserved Font Name "Source"), The Noto Project Authors | Noto Sans CJK JP (notofonts/noto-cjk), subset with fonttools to the characters of the text goldens; the name does not use the reserved one | SIL OFL 1.1 |
| `goldens/brushes/test-pencil/shape.png` | Nanquim contributors | Procedural: `chalk 128 11 0.5` | MIT |
| `goldens/brushes/test-pencil/grain.png` | Nanquim contributors | Procedural: `paper 256 12 8` | MIT |
| `goldens/brushes/test-flat/shape.png` | Nanquim contributors | Procedural: `bristles 128 13 12` | MIT |
| `goldens/brushes/test-flat/grain.png` | Nanquim contributors | Procedural: `noise 64 14 0.6` | MIT |
| `goldens/brushes/test-stamp/shape.png` | Nanquim contributors | Procedural: `tools/goldens/make_brush_scenes.py` (arrow) | MIT |
| `goldens/brushes/test-stamp/color.png` | Nanquim contributors | Procedural: `tools/goldens/make_brush_scenes.py` (color teardrop) | MIT |
| `app/src/main/kotlin/com/mobyle/nanquim/canvas/adjust/GradientLibrary.kt` (`BUILT_INS`) | Nanquim contributors | Original gradients (color stops written for the project) | MIT |
| `app/src/main/kotlin/com/mobyle/nanquim/color/BuiltInPalettes.kt` | Nanquim contributors | Original palettes Basics, Earth, Pastel (colors chosen for the project) | MIT |
| `assets-src/profiles/sRGB-v4.icc` | Clinton Ingram (Compact ICC Profiles) | https://github.com/saucecontrol/Compact-ICC-Profiles (sRGB, V4 parametric curve); also a fuzz seed in `engine/src/fuzz/corpus/icc/` | CC0-1.0 |
| `assets-src/profiles/DisplayP3-v4.icc` | Clinton Ingram (Compact ICC Profiles) | https://github.com/saucecontrol/Compact-ICC-Profiles (Display P3, V4); also a fuzz seed in `engine/src/fuzz/corpus/icc/` | CC0-1.0 |
| `assets-src/profiles/AdobeCompat-v4.icc` | Clinton Ingram (Compact ICC Profiles) | https://github.com/saucecontrol/Compact-ICC-Profiles (Adobe RGB (1998) compatible, V4); also a fuzz seed in `engine/src/fuzz/corpus/icc/` | CC0-1.0 |
| `assets-src/samples/night-hills.nanquim`, `assets-src/samples/ink-flower.nanquim`, `assets-src/samples/bouncing-ball.nanquim` | The Nanquim project | Sample artworks painted by `tools/samples/make_samples.py` with the app's own built-in brushes and commands; installed once on the first launch | CC0-1.0 |
| `art/app-icon/ic_nanquim_app_icon.jpeg` and what `tools/icons/make_app_icon.py` makes from it (`app/src/main/res/mipmap-*/ic_launcher_foreground.png`, the gradient in `res/drawable/ic_launcher_background.xml`) | The Nanquim project (the project owner) | The app icon and splash | MIT (the project's license) |
| `assets-src/profiles/Rec2020-v4.icc` | Clinton Ingram (Compact ICC Profiles) | https://github.com/saucecontrol/Compact-ICC-Profiles (Rec. 2020, V4); also a fuzz seed in `engine/src/fuzz/corpus/icc/` | CC0-1.0 |
| `assets-src/profiles/CGATS001Compat-v2-micro.icc` | Clinton Ingram (Compact ICC Profiles) | https://github.com/saucecontrol/Compact-ICC-Profiles (CMYK from CGATS TR 001, A2B0 only (input)); also a fuzz seed in `engine/src/fuzz/corpus/icc/` | CC0-1.0 |
| `assets-src/profiles/NanquimCMYK-v4.icc` | The Nanquim project, derived from `CGATS001Compat-v2-micro.icc` (Compact ICC Profiles, CC0) | Generated by `nanquim-cli make-cmyk-profile` (`engine/src/cli/color_tools.cpp`): A2B0 resampled and B2A0 inverted with medium GCR and 300% total ink | CC0-1.0 |
<!-- built-in brushes: generated by tools/brushes/build_library.py -->
| `assets-src/brushes/library.json` | Nanquim contributors | Built-in library order | MIT |
| `assets-src/brushes/sketching/ca1e3f9b-e278-5f21-afb1-541c98909c5f/brush.json` | Nanquim contributors | Brush parameters (Peppermint) | MIT |
| `assets-src/brushes/sketching/ca1e3f9b-e278-5f21-afb1-541c98909c5f/shape.png` | Nanquim contributors | Procedural: `chalk 128 201 0.2` | MIT |
| `assets-src/brushes/sketching/ca1e3f9b-e278-5f21-afb1-541c98909c5f/grain.png` | Nanquim contributors | Procedural: `paper 512 202 24` | MIT |
| `assets-src/brushes/sketching/c169eb21-fabe-5942-abfe-d90830e49410/brush.json` | Nanquim contributors | Brush parameters (Graphite) | MIT |
| `assets-src/brushes/sketching/c169eb21-fabe-5942-abfe-d90830e49410/shape.png` | Nanquim contributors | Procedural: `chalk 128 203 0.4` | MIT |
| `assets-src/brushes/sketching/c169eb21-fabe-5942-abfe-d90830e49410/grain.png` | Nanquim contributors | Procedural: `paper 512 204 18` | MIT |
| `assets-src/brushes/sketching/9474d0fb-b194-509b-b7cc-a85cc99b2af8/brush.json` | Nanquim contributors | Brush parameters (Classic Pencil) | MIT |
| `assets-src/brushes/sketching/9474d0fb-b194-509b-b7cc-a85cc99b2af8/shape.png` | Nanquim contributors | Procedural: `chalk 128 205 0.3` | MIT |
| `assets-src/brushes/sketching/9474d0fb-b194-509b-b7cc-a85cc99b2af8/grain.png` | Nanquim contributors | Procedural: `paper 512 206 16` | MIT |
| `assets-src/brushes/sketching/aecae1ac-ab18-5bde-9acb-6c5df822f8b4/brush.json` | Nanquim contributors | Brush parameters (Technical Pencil) | MIT |
| `assets-src/brushes/sketching/aecae1ac-ab18-5bde-9acb-6c5df822f8b4/shape.png` | Nanquim contributors | Procedural: `chalk 128 207 0.1` | MIT |
| `assets-src/brushes/sketching/aecae1ac-ab18-5bde-9acb-6c5df822f8b4/grain.png` | Nanquim contributors | Procedural: `paper 512 208 20` | MIT |
| `assets-src/brushes/sketching/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a01/brush.json` | Nanquim contributors | Brush parameters (HB Pencil) | MIT |
| `assets-src/brushes/sketching/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a01/shape.png` | Nanquim contributors | Procedural: `chalk 128 101 0.3` | MIT |
| `assets-src/brushes/sketching/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a01/grain.png` | Nanquim contributors | Procedural: `paper 512 102 16` | MIT |
| `assets-src/brushes/sketching/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a02/brush.json` | Nanquim contributors | Brush parameters (6B Pencil) | MIT |
| `assets-src/brushes/sketching/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a02/shape.png` | Nanquim contributors | Procedural: `chalk 128 103 0.6` | MIT |
| `assets-src/brushes/sketching/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a02/grain.png` | Nanquim contributors | Procedural: `paper 512 104 16` | MIT |
| `assets-src/brushes/sketching/771719dd-9aa8-51a0-b673-b19eb5030ed9/brush.json` | Nanquim contributors | Brush parameters (Soft Graphite) | MIT |
| `assets-src/brushes/sketching/771719dd-9aa8-51a0-b673-b19eb5030ed9/shape.png` | Nanquim contributors | Procedural: `chalk 128 209 0.5` | MIT |
| `assets-src/brushes/sketching/771719dd-9aa8-51a0-b673-b19eb5030ed9/grain.png` | Nanquim contributors | Procedural: `paper 512 210 18` | MIT |
| `assets-src/brushes/sketching/95e57224-b539-57bd-b690-d0e3474b4506/brush.json` | Nanquim contributors | Brush parameters (Soft Pastel) | MIT |
| `assets-src/brushes/sketching/95e57224-b539-57bd-b690-d0e3474b4506/shape.png` | Nanquim contributors | Procedural: `chalk 128 211 0.7` | MIT |
| `assets-src/brushes/sketching/95e57224-b539-57bd-b690-d0e3474b4506/grain.png` | Nanquim contributors | Procedural: `paper 512 212 20` | MIT |
| `assets-src/brushes/sketching/4289f1a8-87dc-5a78-b148-5f35a1b829a0/brush.json` | Nanquim contributors | Brush parameters (Oil Pastel) | MIT |
| `assets-src/brushes/sketching/4289f1a8-87dc-5a78-b148-5f35a1b829a0/shape.png` | Nanquim contributors | Procedural: `chalk 128 213 0.5` | MIT |
| `assets-src/brushes/sketching/4289f1a8-87dc-5a78-b148-5f35a1b829a0/grain.png` | Nanquim contributors | Procedural: `paper 512 214 24` | MIT |
| `assets-src/brushes/sketching/c1d5795d-3260-5c80-b528-ca23966e42b1/brush.json` | Nanquim contributors | Brush parameters (Artist Crayon) | MIT |
| `assets-src/brushes/sketching/c1d5795d-3260-5c80-b528-ca23966e42b1/shape.png` | Nanquim contributors | Procedural: `chalk 128 215 0.6` | MIT |
| `assets-src/brushes/sketching/c1d5795d-3260-5c80-b528-ca23966e42b1/grain.png` | Nanquim contributors | Procedural: `paper 512 216 24` | MIT |
| `assets-src/brushes/inking/1d09beba-7ec8-56c4-8827-8ab14ef98579/brush.json` | Nanquim contributors | Brush parameters (Mercury) | MIT |
| `assets-src/brushes/inking/db2c6c8d-9354-5934-bfd4-2c16d517c47b/brush.json` | Nanquim contributors | Brush parameters (Baskerville) | MIT |
| `assets-src/brushes/inking/db2c6c8d-9354-5934-bfd4-2c16d517c47b/grain.png` | Nanquim contributors | Procedural: `noise 128 301 0.7` | MIT |
| `assets-src/brushes/inking/dfcd48f6-be36-5c31-8324-5b046e6edd09/brush.json` | Nanquim contributors | Brush parameters (Ink Flow) | MIT |
| `assets-src/brushes/inking/d45257a4-77c7-582f-bacd-e904a4f8bca0/brush.json` | Nanquim contributors | Brush parameters (Pandani) | MIT |
| `assets-src/brushes/inking/d45257a4-77c7-582f-bacd-e904a4f8bca0/shape.png` | Nanquim contributors | Procedural: `bristles 128 303 10` | MIT |
| `assets-src/brushes/inking/d45257a4-77c7-582f-bacd-e904a4f8bca0/grain.png` | Nanquim contributors | Procedural: `paper 256 304 12` | MIT |
| `assets-src/brushes/inking/6ea5e57c-8c8b-58b8-8587-818234adb757/brush.json` | Nanquim contributors | Brush parameters (Tinderbox) | MIT |
| `assets-src/brushes/inking/6ea5e57c-8c8b-58b8-8587-818234adb757/shape.png` | Nanquim contributors | Procedural: `chalk 128 305 0.8` | MIT |
| `assets-src/brushes/inking/56014860-49f3-52b5-874b-c1220d122b8d/brush.json` | Nanquim contributors | Brush parameters (Syrup) | MIT |
| `assets-src/brushes/inking/03d4342c-23c3-560e-9c29-88761b3ce587/brush.json` | Nanquim contributors | Brush parameters (Thylacine) | MIT |
| `assets-src/brushes/inking/03d4342c-23c3-560e-9c29-88761b3ce587/shape.png` | Nanquim contributors | Procedural: `bristles 128 307 16` | MIT |
| `assets-src/brushes/inking/03d4342c-23c3-560e-9c29-88761b3ce587/grain.png` | Nanquim contributors | Procedural: `noise 64 308 0.6` | MIT |
| `assets-src/brushes/inking/bd3dfff4-db97-5a0e-9d3d-6ccbdf8a0e62/brush.json` | Nanquim contributors | Brush parameters (Fine Tip) | MIT |
| `assets-src/brushes/inking/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a11/brush.json` | Nanquim contributors | Brush parameters (Technical Pen) | MIT |
| `assets-src/brushes/inking/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a12/brush.json` | Nanquim contributors | Brush parameters (Gel Pen) | MIT |
| `assets-src/brushes/inking/a6685b2c-ec13-5d96-a403-9993c0de2af0/brush.json` | Nanquim contributors | Brush parameters (Ink Bleed) | MIT |
| `assets-src/brushes/inking/4125df6c-df5c-5043-8bb5-d46865d137aa/brush.json` | Nanquim contributors | Brush parameters (Studio Pen) | MIT |
| `assets-src/brushes/inking/aaaba672-6015-512b-98d8-721ed0b10a0d/brush.json` | Nanquim contributors | Brush parameters (Dry Ink) | MIT |
| `assets-src/brushes/inking/aaaba672-6015-512b-98d8-721ed0b10a0d/shape.png` | Nanquim contributors | Procedural: `chalk 128 309 0.5` | MIT |
| `assets-src/brushes/inking/aaaba672-6015-512b-98d8-721ed0b10a0d/grain.png` | Nanquim contributors | Procedural: `paper 256 310 14` | MIT |
| `assets-src/brushes/inking/7978022a-44a6-5e13-b1cc-e5ec8245475a/brush.json` | Nanquim contributors | Brush parameters (Bold Ink) | MIT |
| `assets-src/brushes/inking/75db0b8a-6726-564d-9e58-c7dbc5b72a4c/brush.json` | Nanquim contributors | Brush parameters (Ink Roll) | MIT |
| `assets-src/brushes/inking/12db4221-8db6-51ba-bd7b-c9c29e784f53/brush.json` | Nanquim contributors | Brush parameters (Marker Roll) | MIT |
| `assets-src/brushes/inking/471f9f59-7711-5671-842c-99a6eef4aab7/brush.json` | Nanquim contributors | Brush parameters (Marker) | MIT |
| `assets-src/brushes/drawing/2666a8b1-695e-53f7-b915-bc004e772cf8/brush.json` | Nanquim contributors | Brush parameters (Little Pine) | MIT |
| `assets-src/brushes/drawing/2666a8b1-695e-53f7-b915-bc004e772cf8/shape.png` | Nanquim contributors | Procedural: `chalk 128 501 0.3` | MIT |
| `assets-src/brushes/drawing/2666a8b1-695e-53f7-b915-bc004e772cf8/grain.png` | Nanquim contributors | Procedural: `paper 512 502 20` | MIT |
| `assets-src/brushes/drawing/f678927f-ed13-5ba9-8b5e-e2c1b39adaeb/brush.json` | Nanquim contributors | Brush parameters (Gloaming) | MIT |
| `assets-src/brushes/drawing/f678927f-ed13-5ba9-8b5e-e2c1b39adaeb/shape.png` | Nanquim contributors | Procedural: `chalk 128 503 0.6` | MIT |
| `assets-src/brushes/drawing/f678927f-ed13-5ba9-8b5e-e2c1b39adaeb/grain.png` | Nanquim contributors | Procedural: `paper 512 504 14` | MIT |
| `assets-src/brushes/drawing/445e62b1-3ec3-5c1c-9c43-469c97290ebd/brush.json` | Nanquim contributors | Brush parameters (Freycinet) | MIT |
| `assets-src/brushes/drawing/445e62b1-3ec3-5c1c-9c43-469c97290ebd/shape.png` | Nanquim contributors | Procedural: `bristles 128 505 14` | MIT |
| `assets-src/brushes/drawing/445e62b1-3ec3-5c1c-9c43-469c97290ebd/grain.png` | Nanquim contributors | Procedural: `noise 128 506 0.6` | MIT |
| `assets-src/brushes/drawing/03decc80-b84b-5802-ba22-7f8f174aeed3/brush.json` | Nanquim contributors | Brush parameters (Blackburn) | MIT |
| `assets-src/brushes/drawing/03decc80-b84b-5802-ba22-7f8f174aeed3/shape.png` | Nanquim contributors | Procedural: `chalk 128 507 0.15` | MIT |
| `assets-src/brushes/drawing/57c183fa-6f4e-584a-8cc8-adde11b5f557/brush.json` | Nanquim contributors | Brush parameters (Evolve) | MIT |
| `assets-src/brushes/drawing/57c183fa-6f4e-584a-8cc8-adde11b5f557/shape.png` | Nanquim contributors | Procedural: `chalk 128 509 0.4` | MIT |
| `assets-src/brushes/drawing/57c183fa-6f4e-584a-8cc8-adde11b5f557/grain.png` | Nanquim contributors | Procedural: `paper 512 510 18` | MIT |
| `assets-src/brushes/drawing/3c0c837a-ff33-5110-b7b3-112c6e488542/brush.json` | Nanquim contributors | Brush parameters (Eaglehawk) | MIT |
| `assets-src/brushes/drawing/7ab84417-3fee-56b4-a914-86de0c731042/brush.json` | Nanquim contributors | Brush parameters (Oberon) | MIT |
| `assets-src/brushes/drawing/7ab84417-3fee-56b4-a914-86de0c731042/shape.png` | Nanquim contributors | Procedural: `bristles 128 511 10` | MIT |
| `assets-src/brushes/drawing/7ab84417-3fee-56b4-a914-86de0c731042/grain.png` | Nanquim contributors | Procedural: `paper 256 512 16` | MIT |
| `assets-src/brushes/drawing/e4cd054b-43da-52d9-98c0-797dd17f23b3/brush.json` | Nanquim contributors | Brush parameters (Styx) | MIT |
| `assets-src/brushes/drawing/e4cd054b-43da-52d9-98c0-797dd17f23b3/grain.png` | Nanquim contributors | Procedural: `paper 256 513 14` | MIT |
| `assets-src/brushes/drawing/7be048d3-1e6d-5f52-b31c-ee79358b6ce4/brush.json` | Nanquim contributors | Brush parameters (Vineyard) | MIT |
| `assets-src/brushes/drawing/7be048d3-1e6d-5f52-b31c-ee79358b6ce4/shape.png` | Nanquim contributors | Procedural: `chalk 128 515 0.5` | MIT |
| `assets-src/brushes/drawing/7be048d3-1e6d-5f52-b31c-ee79358b6ce4/grain.png` | Nanquim contributors | Procedural: `paper 512 516 14` | MIT |
| `assets-src/brushes/drawing/33d388ec-c655-59a3-87c7-0b92fbe61aec/brush.json` | Nanquim contributors | Brush parameters (Copperhead) | MIT |
| `assets-src/brushes/drawing/33d388ec-c655-59a3-87c7-0b92fbe61aec/shape.png` | Nanquim contributors | Procedural: `chalk 128 517 0.3` | MIT |
| `assets-src/brushes/drawing/33d388ec-c655-59a3-87c7-0b92fbe61aec/grain.png` | Nanquim contributors | Procedural: `paper 256 518 12` | MIT |
| `assets-src/brushes/painting/f56bea62-444e-56cd-8b15-cd474fc3fc5f/brush.json` | Nanquim contributors | Brush parameters (Round Brush) | MIT |
| `assets-src/brushes/painting/f56bea62-444e-56cd-8b15-cd474fc3fc5f/shape.png` | Nanquim contributors | Procedural: `bristles 128 601 24` | MIT |
| `assets-src/brushes/painting/b88b7b80-e839-5e3e-95e0-6441865509d8/brush.json` | Nanquim contributors | Brush parameters (Flat Brush) | MIT |
| `assets-src/brushes/painting/b88b7b80-e839-5e3e-95e0-6441865509d8/shape.png` | Nanquim contributors | Procedural: `bristles 128 603 40` | MIT |
| `assets-src/brushes/painting/c0ccfa20-d8cd-523c-a7c0-e77b7932ecfe/brush.json` | Nanquim contributors | Brush parameters (Acrylic) | MIT |
| `assets-src/brushes/painting/c0ccfa20-d8cd-523c-a7c0-e77b7932ecfe/shape.png` | Nanquim contributors | Procedural: `bristles 128 605 30` | MIT |
| `assets-src/brushes/painting/c0ccfa20-d8cd-523c-a7c0-e77b7932ecfe/grain.png` | Nanquim contributors | Procedural: `paper 512 606 8` | MIT |
| `assets-src/brushes/painting/eb70c39a-daaf-5846-a95b-ab074f74caf7/brush.json` | Nanquim contributors | Brush parameters (Wet Acrylic) | MIT |
| `assets-src/brushes/painting/eb70c39a-daaf-5846-a95b-ab074f74caf7/shape.png` | Nanquim contributors | Procedural: `bristles 256 607 30` | MIT |
| `assets-src/brushes/painting/dc3e4339-7f88-5b60-a589-6116bd67ce8f/brush.json` | Nanquim contributors | Brush parameters (Jagged Brush) | MIT |
| `assets-src/brushes/painting/dc3e4339-7f88-5b60-a589-6116bd67ce8f/shape.png` | Nanquim contributors | Procedural: `chalk 128 609 0.9` | MIT |
| `assets-src/brushes/painting/ec6e56ba-6993-5695-8926-0f30f6d9f029/brush.json` | Nanquim contributors | Brush parameters (Salamanca) | MIT |
| `assets-src/brushes/painting/ec6e56ba-6993-5695-8926-0f30f6d9f029/shape.png` | Nanquim contributors | Procedural: `bristles 128 611 20` | MIT |
| `assets-src/brushes/painting/ec6e56ba-6993-5695-8926-0f30f6d9f029/grain.png` | Nanquim contributors | Procedural: `paper 512 612 10` | MIT |
| `assets-src/brushes/painting/a92c888b-dbf5-58f4-842c-7d4cfb07b12e/brush.json` | Nanquim contributors | Brush parameters (Bristle Roll) | MIT |
| `assets-src/brushes/painting/a92c888b-dbf5-58f4-842c-7d4cfb07b12e/shape.png` | Nanquim contributors | Procedural: `bristles 128 613 36` | MIT |
| `assets-src/brushes/painting/7785c2e2-21ba-534c-a441-6bafb03719a1/brush.json` | Nanquim contributors | Brush parameters (Spectra) | MIT |
| `assets-src/brushes/painting/7785c2e2-21ba-534c-a441-6bafb03719a1/shape.png` | Nanquim contributors | Procedural: `bristles 128 615 24` | MIT |
| `assets-src/brushes/painting/f0cdbd39-5801-5f17-925c-2422dead7482/brush.json` | Nanquim contributors | Brush parameters (Tamar) | MIT |
| `assets-src/brushes/painting/f0cdbd39-5801-5f17-925c-2422dead7482/shape.png` | Nanquim contributors | Procedural: `bristles 256 617 20` | MIT |
| `assets-src/brushes/painting/5a2fe4bf-334d-541a-9bc2-ea617a6d1438/brush.json` | Nanquim contributors | Brush parameters (Old Brush) | MIT |
| `assets-src/brushes/painting/5a2fe4bf-334d-541a-9bc2-ea617a6d1438/shape.png` | Nanquim contributors | Procedural: `bristles 128 619 12` | MIT |
| `assets-src/brushes/painting/5a2fe4bf-334d-541a-9bc2-ea617a6d1438/grain.png` | Nanquim contributors | Procedural: `noise 64 620 0.55` | MIT |
| `assets-src/brushes/painting/03b2541d-88b7-549e-a40d-d84249eb842d/brush.json` | Nanquim contributors | Brush parameters (Dry Brush) | MIT |
| `assets-src/brushes/painting/03b2541d-88b7-549e-a40d-d84249eb842d/shape.png` | Nanquim contributors | Procedural: `bristles 128 621 18` | MIT |
| `assets-src/brushes/painting/03b2541d-88b7-549e-a40d-d84249eb842d/grain.png` | Nanquim contributors | Procedural: `paper 512 622 18` | MIT |
| `assets-src/brushes/painting/8627ddce-02a1-5d0f-9067-d23b6c1f14b7/brush.json` | Nanquim contributors | Brush parameters (Stucco) | MIT |
| `assets-src/brushes/painting/8627ddce-02a1-5d0f-9067-d23b6c1f14b7/shape.png` | Nanquim contributors | Procedural: `sponge 256 623 10` | MIT |
| `assets-src/brushes/painting/458c284f-12fa-570c-8bf1-4f8c9c046520/brush.json` | Nanquim contributors | Brush parameters (Oil Paint) | MIT |
| `assets-src/brushes/painting/458c284f-12fa-570c-8bf1-4f8c9c046520/shape.png` | Nanquim contributors | Procedural: `bristles 256 625 28` | MIT |
| `assets-src/brushes/painting/baea0855-a342-5d4b-a260-7c1193e3a9fc/brush.json` | Nanquim contributors | Brush parameters (Turpentine) | MIT |
| `assets-src/brushes/painting/e8f4e036-c716-5ea5-a31b-1327968ee84f/brush.json` | Nanquim contributors | Brush parameters (Gouache) | MIT |
| `assets-src/brushes/painting/e8f4e036-c716-5ea5-a31b-1327968ee84f/shape.png` | Nanquim contributors | Procedural: `bristles 128 629 32` | MIT |
| `assets-src/brushes/painting/e8f4e036-c716-5ea5-a31b-1327968ee84f/grain.png` | Nanquim contributors | Procedural: `paper 512 630 12` | MIT |
| `assets-src/brushes/painting/91a25f3c-d874-599e-a1d1-73ba8aa27437/brush.json` | Nanquim contributors | Brush parameters (Fresco) | MIT |
| `assets-src/brushes/painting/91a25f3c-d874-599e-a1d1-73ba8aa27437/grain.png` | Nanquim contributors | Procedural: `paper 512 632 6` | MIT |
| `assets-src/brushes/painting/352e8a06-2dfc-53cc-a17f-e7fe72dc956e/brush.json` | Nanquim contributors | Brush parameters (Watercolor) | MIT |
| `assets-src/brushes/painting/352e8a06-2dfc-53cc-a17f-e7fe72dc956e/shape.png` | Nanquim contributors | Procedural: `blotch 256 633 0.5` | MIT |
| `assets-src/brushes/artistic/c8a0a557-120b-51ec-8328-22ec798def0f/brush.json` | Nanquim contributors | Brush parameters (Wild Light) | MIT |
| `assets-src/brushes/artistic/cd6aa3ed-8839-59ad-9692-0eab39586c94/brush.json` | Nanquim contributors | Brush parameters (Aurora) | MIT |
| `assets-src/brushes/artistic/7c7efa2a-6628-5285-8eb3-40513a88882e/brush.json` | Nanquim contributors | Brush parameters (Leatherwood) | MIT |
| `assets-src/brushes/artistic/7c7efa2a-6628-5285-8eb3-40513a88882e/shape.png` | Nanquim contributors | Procedural: `bristles 128 701 16` | MIT |
| `assets-src/brushes/artistic/7c7efa2a-6628-5285-8eb3-40513a88882e/grain.png` | Nanquim contributors | Procedural: `paper 512 702 14` | MIT |
| `assets-src/brushes/artistic/e0f729d8-04c6-5cb1-ad00-e34329483927/brush.json` | Nanquim contributors | Brush parameters (Hartz) | MIT |
| `assets-src/brushes/artistic/e0f729d8-04c6-5cb1-ad00-e34329483927/shape.png` | Nanquim contributors | Procedural: `chalk 128 703 0.7` | MIT |
| `assets-src/brushes/artistic/e0f729d8-04c6-5cb1-ad00-e34329483927/grain.png` | Nanquim contributors | Procedural: `paper 512 704 10` | MIT |
| `assets-src/brushes/artistic/49f348ed-cd1c-52b9-ad53-dfbc921cf00b/brush.json` | Nanquim contributors | Brush parameters (Tarraleah) | MIT |
| `assets-src/brushes/artistic/79667abf-4570-5d00-a609-42a6cb8dc02e/brush.json` | Nanquim contributors | Brush parameters (Plimsoll) | MIT |
| `assets-src/brushes/artistic/fee0f42b-271a-5d1c-a79e-cc2fa4aff0cd/brush.json` | Nanquim contributors | Brush parameters (Old Beach) | MIT |
| `assets-src/brushes/artistic/fee0f42b-271a-5d1c-a79e-cc2fa4aff0cd/shape.png` | Nanquim contributors | Procedural: `sponge 256 707 10` | MIT |
| `assets-src/brushes/artistic/b944e1f9-e37b-5bdc-9c42-7124ba2125bd/brush.json` | Nanquim contributors | Brush parameters (Coastline) | MIT |
| `assets-src/brushes/artistic/b944e1f9-e37b-5bdc-9c42-7124ba2125bd/shape.png` | Nanquim contributors | Procedural: `spatter 256 709 40` | MIT |
| `assets-src/brushes/artistic/58c40100-ba47-58eb-9f02-cf2334c62438/brush.json` | Nanquim contributors | Brush parameters (Sassafras) | MIT |
| `assets-src/brushes/artistic/58c40100-ba47-58eb-9f02-cf2334c62438/shape.png` | Nanquim contributors | Procedural: `bristles 128 711 20` | MIT |
| `assets-src/brushes/artistic/c0844fe8-b89e-53fa-9e1a-28e9831301c3/brush.json` | Nanquim contributors | Brush parameters (Quoll) | MIT |
| `assets-src/brushes/artistic/c0844fe8-b89e-53fa-9e1a-28e9831301c3/shape.png` | Nanquim contributors | Procedural: `spatter 256 713 25` | MIT |
| `assets-src/brushes/calligraphy/0fd977eb-8ccf-5c07-aea8-aba41a7c9add/brush.json` | Nanquim contributors | Brush parameters (Summit Script) | MIT |
| `assets-src/brushes/calligraphy/87e5aa75-91e5-512b-bce6-4fc1a35c998e/brush.json` | Nanquim contributors | Brush parameters (Marquee) | MIT |
| `assets-src/brushes/calligraphy/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a21/brush.json` | Nanquim contributors | Brush parameters (Monoline) | MIT |
| `assets-src/brushes/calligraphy/1cb45e54-c421-5985-8f14-53f9e8f9cdf3/brush.json` | Nanquim contributors | Brush parameters (Chalk) | MIT |
| `assets-src/brushes/calligraphy/1cb45e54-c421-5985-8f14-53f9e8f9cdf3/shape.png` | Nanquim contributors | Procedural: `chalk 128 401 0.6` | MIT |
| `assets-src/brushes/calligraphy/1cb45e54-c421-5985-8f14-53f9e8f9cdf3/grain.png` | Nanquim contributors | Procedural: `paper 512 402 10` | MIT |
| `assets-src/brushes/calligraphy/3285c039-5f17-5c54-bd26-d47852b735a5/brush.json` | Nanquim contributors | Brush parameters (Blotchy) | MIT |
| `assets-src/brushes/calligraphy/3285c039-5f17-5c54-bd26-d47852b735a5/shape.png` | Nanquim contributors | Procedural: `chalk 128 403 1.0` | MIT |
| `assets-src/brushes/calligraphy/3285c039-5f17-5c54-bd26-d47852b735a5/grain.png` | Nanquim contributors | Procedural: `noise 64 404 0.5` | MIT |
| `assets-src/brushes/calligraphy/55d89f13-9efc-5916-b8b4-855de2f78ca3/brush.json` | Nanquim contributors | Brush parameters (Streaks) | MIT |
| `assets-src/brushes/calligraphy/55d89f13-9efc-5916-b8b4-855de2f78ca3/shape.png` | Nanquim contributors | Procedural: `bristles 128 405 8` | MIT |
| `assets-src/brushes/calligraphy/8fef6107-2aec-50cd-9715-5cc3ba259261/brush.json` | Nanquim contributors | Brush parameters (Water Pen) | MIT |
| `assets-src/brushes/calligraphy/f5882d62-bc65-5b65-a1bf-3ae3559de525/brush.json` | Nanquim contributors | Brush parameters (Shale Brush) | MIT |
| `assets-src/brushes/calligraphy/f5882d62-bc65-5b65-a1bf-3ae3559de525/shape.png` | Nanquim contributors | Procedural: `chalk 128 407 0.4` | MIT |
| `assets-src/brushes/calligraphy/f5882d62-bc65-5b65-a1bf-3ae3559de525/grain.png` | Nanquim contributors | Procedural: `paper 256 408 12` | MIT |
| `assets-src/brushes/calligraphy/d1c53d39-9d36-51cd-97aa-1fc5f03af57b/brush.json` | Nanquim contributors | Brush parameters (Brush Pen) | MIT |
| `assets-src/brushes/calligraphy/e50689f1-c1c7-55c9-848e-7413a5c84d58/brush.json` | Nanquim contributors | Brush parameters (Script) | MIT |
| `assets-src/brushes/airbrushing/0d227fa5-f082-58a2-874e-17dcb1b4dece/brush.json` | Nanquim contributors | Brush parameters (Soft Brush) | MIT |
| `assets-src/brushes/airbrushing/b31ff27d-4cb9-58e2-a944-1035ca3a96ac/brush.json` | Nanquim contributors | Brush parameters (Medium Brush) | MIT |
| `assets-src/brushes/airbrushing/9ca84ac8-f441-5916-a73b-f64af6bbe7ba/brush.json` | Nanquim contributors | Brush parameters (Medium Hard Brush) | MIT |
| `assets-src/brushes/airbrushing/7aa95fc4-74ed-5b42-885c-1d10a7444c90/brush.json` | Nanquim contributors | Brush parameters (Hard Brush) | MIT |
| `assets-src/brushes/airbrushing/1a252e5f-736e-5ee0-97d4-ad1fcfb8ca02/brush.json` | Nanquim contributors | Brush parameters (Soft Blend) | MIT |
| `assets-src/brushes/airbrushing/9ad28243-7b61-5b61-a920-2ff0399f37b4/brush.json` | Nanquim contributors | Brush parameters (Medium Blend) | MIT |
| `assets-src/brushes/airbrushing/fc938400-2dbb-5006-91a6-f4b6b405abf8/brush.json` | Nanquim contributors | Brush parameters (Medium Hard Blend) | MIT |
| `assets-src/brushes/airbrushing/bfad0ed9-3068-5528-bf13-a8eb5ee01d5f/brush.json` | Nanquim contributors | Brush parameters (Hard Blend) | MIT |
| `assets-src/brushes/airbrushing/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a31/brush.json` | Nanquim contributors | Brush parameters (Soft Airbrush) | MIT |
| `assets-src/brushes/airbrushing/63263fb5-32a7-55f6-a6f7-a465c6051ae5/brush.json` | Nanquim contributors | Brush parameters (Medium Airbrush) | MIT |
| `assets-src/brushes/airbrushing/dc6fcfa6-6be2-5858-b7b4-cf755aac0a9d/brush.json` | Nanquim contributors | Brush parameters (Medium Hard Airbrush) | MIT |
| `assets-src/brushes/airbrushing/2c1f5a0e-6b1d-4f63-9a0e-3f7c2d1b8a32/brush.json` | Nanquim contributors | Brush parameters (Hard Airbrush) | MIT |
| `assets-src/brushes/textures/fac4fd72-67c8-50fd-8a29-95f408630d8f/brush.json` | Nanquim contributors | Brush parameters (Tessellated) | MIT |
| `assets-src/brushes/textures/fac4fd72-67c8-50fd-8a29-95f408630d8f/grain.png` | Nanquim contributors | Procedural: `triangles 512 801 6` | MIT |
| `assets-src/brushes/textures/21a0b4d9-e5aa-54d1-be4c-1ad74c8fe0b3/brush.json` | Nanquim contributors | Brush parameters (Tarkine) | MIT |
| `assets-src/brushes/textures/21a0b4d9-e5aa-54d1-be4c-1ad74c8fe0b3/grain.png` | Nanquim contributors | Procedural: `cells 512 802 10` | MIT |
| `assets-src/brushes/textures/3da223ba-6aa9-59c1-a583-6cf73092256d/brush.json` | Nanquim contributors | Brush parameters (Currawong) | MIT |
| `assets-src/brushes/textures/3da223ba-6aa9-59c1-a583-6cf73092256d/grain.png` | Nanquim contributors | Procedural: `paper 512 803 24` | MIT |
| `assets-src/brushes/textures/df5cb595-95a0-5ac6-ab96-56eb1db90d8a/brush.json` | Nanquim contributors | Brush parameters (Cygnet) | MIT |
| `assets-src/brushes/textures/df5cb595-95a0-5ac6-ab96-56eb1db90d8a/grain.png` | Nanquim contributors | Procedural: `cells 512 804 6` | MIT |
| `assets-src/brushes/textures/f074fb2b-5991-580a-a531-9fd34ea6e545/brush.json` | Nanquim contributors | Brush parameters (Melaleuca) | MIT |
| `assets-src/brushes/textures/f074fb2b-5991-580a-a531-9fd34ea6e545/grain.png` | Nanquim contributors | Procedural: `wood 512 805 12` | MIT |
| `assets-src/brushes/textures/3754193f-9d19-54dc-883d-cebc1e89bbb6/brush.json` | Nanquim contributors | Brush parameters (Dove Lake) | MIT |
| `assets-src/brushes/textures/3754193f-9d19-54dc-883d-cebc1e89bbb6/grain.png` | Nanquim contributors | Procedural: `waves 512 806 8` | MIT |
| `assets-src/brushes/textures/02164ac1-b015-5366-a2ce-3dc6472437d6/brush.json` | Nanquim contributors | Brush parameters (Rectangles) | MIT |
| `assets-src/brushes/textures/02164ac1-b015-5366-a2ce-3dc6472437d6/grain.png` | Nanquim contributors | Procedural: `bricks 512 807 8` | MIT |
| `assets-src/brushes/textures/8842c352-50ec-5caf-ac15-71e0ab7ed58a/brush.json` | Nanquim contributors | Brush parameters (Grit Roll) | MIT |
| `assets-src/brushes/textures/8842c352-50ec-5caf-ac15-71e0ab7ed58a/grain.png` | Nanquim contributors | Procedural: `noise 512 808 0.4` | MIT |
| `assets-src/brushes/textures/3a9e5e3f-bd18-5ee7-b03c-bf8ed7cfcd00/brush.json` | Nanquim contributors | Brush parameters (Grid) | MIT |
| `assets-src/brushes/textures/3a9e5e3f-bd18-5ee7-b03c-bf8ed7cfcd00/grain.png` | Nanquim contributors | Procedural: `grid 512 809 8` | MIT |
| `assets-src/brushes/textures/86302acb-32c1-5a00-b1b9-2563193679cc/brush.json` | Nanquim contributors | Brush parameters (Decimals) | MIT |
| `assets-src/brushes/textures/86302acb-32c1-5a00-b1b9-2563193679cc/grain.png` | Nanquim contributors | Procedural: `dots 512 810 16` | MIT |
| `assets-src/brushes/textures/711155db-555a-583a-bff7-f1e85e5f89ab/brush.json` | Nanquim contributors | Brush parameters (Diagonal) | MIT |
| `assets-src/brushes/textures/711155db-555a-583a-bff7-f1e85e5f89ab/grain.png` | Nanquim contributors | Procedural: `stripes 512 811 12` | MIT |
| `assets-src/brushes/textures/1994e09e-79a4-5aa5-b0b9-c0b480f7c220/brush.json` | Nanquim contributors | Brush parameters (Victorian) | MIT |
| `assets-src/brushes/textures/1994e09e-79a4-5aa5-b0b9-c0b480f7c220/grain.png` | Nanquim contributors | Procedural: `ornament 512 812 4` | MIT |
| `assets-src/brushes/textures/4088689a-aef0-5189-b5d4-660e75ed5bba/brush.json` | Nanquim contributors | Brush parameters (Wood) | MIT |
| `assets-src/brushes/textures/4088689a-aef0-5189-b5d4-660e75ed5bba/grain.png` | Nanquim contributors | Procedural: `wood 512 813 6` | MIT |
| `assets-src/brushes/textures/a9b9dd63-b59c-5fca-b9b1-4b0bffeb5f20/brush.json` | Nanquim contributors | Brush parameters (Cubes) | MIT |
| `assets-src/brushes/textures/a9b9dd63-b59c-5fca-b9b1-4b0bffeb5f20/grain.png` | Nanquim contributors | Procedural: `cubes 512 814 6` | MIT |
| `assets-src/brushes/textures/656a5fd5-268f-5006-9a36-761dee672bad/brush.json` | Nanquim contributors | Brush parameters (Rosette) | MIT |
| `assets-src/brushes/textures/656a5fd5-268f-5006-9a36-761dee672bad/grain.png` | Nanquim contributors | Procedural: `rosette 512 815 4` | MIT |
| `assets-src/brushes/textures/9296a057-ba97-58bd-97ad-ac0ddcad12ca/brush.json` | Nanquim contributors | Brush parameters (Grunge) | MIT |
| `assets-src/brushes/textures/9296a057-ba97-58bd-97ad-ac0ddcad12ca/grain.png` | Nanquim contributors | Procedural: `paper 512 816 40` | MIT |
| `assets-src/brushes/abstract/bb90220b-cb5b-5bf0-8472-fcdc04664961/brush.json` | Nanquim contributors | Brush parameters (Stickman) | MIT |
| `assets-src/brushes/abstract/bb90220b-cb5b-5bf0-8472-fcdc04664961/shape.png` | Nanquim contributors | Procedural: `stick 256 901 0.4` | MIT |
| `assets-src/brushes/abstract/869802f0-3dc4-5d71-ad9e-e82d22331a28/brush.json` | Nanquim contributors | Brush parameters (Storm Bay) | MIT |
| `assets-src/brushes/abstract/869802f0-3dc4-5d71-ad9e-e82d22331a28/shape.png` | Nanquim contributors | Procedural: `streak 256 902 0.5` | MIT |
| `assets-src/brushes/abstract/42aafe80-b5af-5071-a60e-2726f8b1e124/brush.json` | Nanquim contributors | Brush parameters (Waveform) | MIT |
| `assets-src/brushes/abstract/42aafe80-b5af-5071-a60e-2726f8b1e124/grain.png` | Nanquim contributors | Procedural: `waves 512 903 12` | MIT |
| `assets-src/brushes/abstract/4eb50487-5914-5f02-874f-0d184f2ae98d/brush.json` | Nanquim contributors | Brush parameters (Membrane) | MIT |
| `assets-src/brushes/abstract/4eb50487-5914-5f02-874f-0d184f2ae98d/grain.png` | Nanquim contributors | Procedural: `cells 512 904 8` | MIT |
| `assets-src/brushes/abstract/5294f129-a848-5af7-aac0-ee366c9882e6/brush.json` | Nanquim contributors | Brush parameters (Triangulum) | MIT |
| `assets-src/brushes/abstract/5294f129-a848-5af7-aac0-ee366c9882e6/grain.png` | Nanquim contributors | Procedural: `triangles 512 905 4` | MIT |
| `assets-src/brushes/abstract/0fddb291-ed76-589d-b6ae-6ecba4573aec/brush.json` | Nanquim contributors | Brush parameters (Hexagons) | MIT |
| `assets-src/brushes/abstract/0fddb291-ed76-589d-b6ae-6ecba4573aec/grain.png` | Nanquim contributors | Procedural: `hexagons 512 906 6` | MIT |
| `assets-src/brushes/abstract/66d97c15-f7fa-53e8-9aed-81963ff6123c/brush.json` | Nanquim contributors | Brush parameters (Marble) | MIT |
| `assets-src/brushes/abstract/66d97c15-f7fa-53e8-9aed-81963ff6123c/grain.png` | Nanquim contributors | Procedural: `marble 512 907 6` | MIT |
| `assets-src/brushes/abstract/27786684-0a72-55a2-bf84-812423322d90/brush.json` | Nanquim contributors | Brush parameters (Optical) | MIT |
| `assets-src/brushes/abstract/27786684-0a72-55a2-bf84-812423322d90/grain.png` | Nanquim contributors | Procedural: `dots 512 908 12` | MIT |
| `assets-src/brushes/abstract/a397f401-8cb7-58b3-addf-b135e98a958c/brush.json` | Nanquim contributors | Brush parameters (Spicule) | MIT |
| `assets-src/brushes/abstract/a397f401-8cb7-58b3-addf-b135e98a958c/shape.png` | Nanquim contributors | Procedural: `star 256 909 14` | MIT |
| `assets-src/brushes/abstract/a4707048-9405-5419-aeea-63fc03428f4b/brush.json` | Nanquim contributors | Brush parameters (Polygons) | MIT |
| `assets-src/brushes/abstract/a4707048-9405-5419-aeea-63fc03428f4b/grain.png` | Nanquim contributors | Procedural: `polygons 512 910 8` | MIT |
| `assets-src/brushes/charcoals/ba321868-c9bf-5380-9c1d-94b497d5d76c/brush.json` | Nanquim contributors | Brush parameters (2B Compressed) | MIT |
| `assets-src/brushes/charcoals/ba321868-c9bf-5380-9c1d-94b497d5d76c/shape.png` | Nanquim contributors | Procedural: `chalk 128 1001 0.3` | MIT |
| `assets-src/brushes/charcoals/ba321868-c9bf-5380-9c1d-94b497d5d76c/grain.png` | Nanquim contributors | Procedural: `paper 512 1002 18` | MIT |
| `assets-src/brushes/charcoals/03963252-0001-5fe3-a9fc-8126f4a64558/brush.json` | Nanquim contributors | Brush parameters (4B Compressed) | MIT |
| `assets-src/brushes/charcoals/03963252-0001-5fe3-a9fc-8126f4a64558/shape.png` | Nanquim contributors | Procedural: `chalk 128 1003 0.4` | MIT |
| `assets-src/brushes/charcoals/03963252-0001-5fe3-a9fc-8126f4a64558/grain.png` | Nanquim contributors | Procedural: `paper 512 1004 16` | MIT |
| `assets-src/brushes/charcoals/22ffdc0c-67f5-54f3-a0bf-e12bd4dbb8a2/brush.json` | Nanquim contributors | Brush parameters (6B Compressed) | MIT |
| `assets-src/brushes/charcoals/22ffdc0c-67f5-54f3-a0bf-e12bd4dbb8a2/shape.png` | Nanquim contributors | Procedural: `chalk 128 1005 0.5` | MIT |
| `assets-src/brushes/charcoals/22ffdc0c-67f5-54f3-a0bf-e12bd4dbb8a2/grain.png` | Nanquim contributors | Procedural: `paper 512 1006 14` | MIT |
| `assets-src/brushes/charcoals/cee15bc2-5439-5ece-99b7-96bda5f9e34b/brush.json` | Nanquim contributors | Brush parameters (Vine Charcoal) | MIT |
| `assets-src/brushes/charcoals/cee15bc2-5439-5ece-99b7-96bda5f9e34b/shape.png` | Nanquim contributors | Procedural: `chalk 128 1007 0.6` | MIT |
| `assets-src/brushes/charcoals/cee15bc2-5439-5ece-99b7-96bda5f9e34b/grain.png` | Nanquim contributors | Procedural: `paper 512 1008 20` | MIT |
| `assets-src/brushes/charcoals/cff135a1-5bd9-5f74-93cb-6d53112974b4/brush.json` | Nanquim contributors | Brush parameters (Willow Charcoal) | MIT |
| `assets-src/brushes/charcoals/cff135a1-5bd9-5f74-93cb-6d53112974b4/shape.png` | Nanquim contributors | Procedural: `chalk 128 1009 0.7` | MIT |
| `assets-src/brushes/charcoals/cff135a1-5bd9-5f74-93cb-6d53112974b4/grain.png` | Nanquim contributors | Procedural: `paper 512 1010 16` | MIT |
| `assets-src/brushes/charcoals/161e2d14-293d-5589-8a5d-ee2275b3763d/brush.json` | Nanquim contributors | Brush parameters (Carbon Stick) | MIT |
| `assets-src/brushes/charcoals/161e2d14-293d-5589-8a5d-ee2275b3763d/shape.png` | Nanquim contributors | Procedural: `chalk 128 1011 0.2` | MIT |
| `assets-src/brushes/charcoals/161e2d14-293d-5589-8a5d-ee2275b3763d/grain.png` | Nanquim contributors | Procedural: `paper 512 1012 22` | MIT |
| `assets-src/brushes/charcoals/bf68793d-0cc2-5269-b330-2f81dd2cb866/brush.json` | Nanquim contributors | Brush parameters (Charcoal Roll) | MIT |
| `assets-src/brushes/charcoals/bf68793d-0cc2-5269-b330-2f81dd2cb866/shape.png` | Nanquim contributors | Procedural: `chalk 128 1013 0.5` | MIT |
| `assets-src/brushes/charcoals/bf68793d-0cc2-5269-b330-2f81dd2cb866/grain.png` | Nanquim contributors | Procedural: `paper 512 1014 14` | MIT |
| `assets-src/brushes/charcoals/737090a0-b359-55c9-a06b-6be129ef7135/brush.json` | Nanquim contributors | Brush parameters (Charcoal Block) | MIT |
| `assets-src/brushes/charcoals/737090a0-b359-55c9-a06b-6be129ef7135/shape.png` | Nanquim contributors | Procedural: `chalk 128 1015 0.6` | MIT |
| `assets-src/brushes/charcoals/737090a0-b359-55c9-a06b-6be129ef7135/grain.png` | Nanquim contributors | Procedural: `paper 512 1016 10` | MIT |
| `assets-src/brushes/charcoals/fb758849-c9fc-5c98-b0d6-7d062859e471/brush.json` | Nanquim contributors | Brush parameters (Burnt Tree) | MIT |
| `assets-src/brushes/charcoals/fb758849-c9fc-5c98-b0d6-7d062859e471/shape.png` | Nanquim contributors | Procedural: `chalk 128 1017 0.9` | MIT |
| `assets-src/brushes/charcoals/fb758849-c9fc-5c98-b0d6-7d062859e471/grain.png` | Nanquim contributors | Procedural: `paper 512 1018 12` | MIT |
| `assets-src/brushes/elements/011e89d4-15f2-5efe-b464-2c73e289aa94/brush.json` | Nanquim contributors | Brush parameters (Smoke) | MIT |
| `assets-src/brushes/elements/011e89d4-15f2-5efe-b464-2c73e289aa94/shape.png` | Nanquim contributors | Procedural: `cloud 256 801 0.7` | MIT |
| `assets-src/brushes/elements/837d6a8d-85cf-55e2-b786-c22f5477e68a/brush.json` | Nanquim contributors | Brush parameters (Flames) | MIT |
| `assets-src/brushes/elements/837d6a8d-85cf-55e2-b786-c22f5477e68a/shape.png` | Nanquim contributors | Procedural: `fire 256 802 0.7` | MIT |
| `assets-src/brushes/elements/bfd27947-ee2f-539b-a7ce-c90d4258991c/brush.json` | Nanquim contributors | Brush parameters (Water) | MIT |
| `assets-src/brushes/elements/bfd27947-ee2f-539b-a7ce-c90d4258991c/shape.png` | Nanquim contributors | Procedural: `blotch 256 803 0.4` | MIT |
| `assets-src/brushes/elements/91348e34-7844-58f5-88c8-cb58d5bd3d87/brush.json` | Nanquim contributors | Brush parameters (Oceans) | MIT |
| `assets-src/brushes/elements/91348e34-7844-58f5-88c8-cb58d5bd3d87/shape.png` | Nanquim contributors | Procedural: `waves 256 804 6` | MIT |
| `assets-src/brushes/elements/958ab067-fa3e-535e-a96d-904b09f2afad/brush.json` | Nanquim contributors | Brush parameters (Driven Snow) | MIT |
| `assets-src/brushes/elements/958ab067-fa3e-535e-a96d-904b09f2afad/shape.png` | Nanquim contributors | Procedural: `spatter 256 805 300` | MIT |
| `assets-src/brushes/elements/79d6e904-8c31-55fa-baa7-ec21044ec62d/brush.json` | Nanquim contributors | Brush parameters (Hard Rain) | MIT |
| `assets-src/brushes/elements/79d6e904-8c31-55fa-baa7-ec21044ec62d/shape.png` | Nanquim contributors | Procedural: `streak 256 806 0.15` | MIT |
| `assets-src/brushes/elements/72d30a8d-033c-5504-92dd-55e4eca4bf7c/brush.json` | Nanquim contributors | Brush parameters (Crystals) | MIT |
| `assets-src/brushes/elements/72d30a8d-033c-5504-92dd-55e4eca4bf7c/shape.png` | Nanquim contributors | Procedural: `star 256 807 6` | MIT |
| `assets-src/brushes/elements/3d311ae8-00c6-53bb-becd-c38cf48be789/brush.json` | Nanquim contributors | Brush parameters (Clouds) | MIT |
| `assets-src/brushes/elements/3d311ae8-00c6-53bb-becd-c38cf48be789/shape.png` | Nanquim contributors | Procedural: `cloud 256 808 0.4` | MIT |
| `assets-src/brushes/spraypaints/0b0e2f4e-92d5-5540-a512-178930870ec1/brush.json` | Nanquim contributors | Brush parameters (Ultrafine Nozzle) | MIT |
| `assets-src/brushes/spraypaints/0b0e2f4e-92d5-5540-a512-178930870ec1/shape.png` | Nanquim contributors | Procedural: `spatter 256 1101 400` | MIT |
| `assets-src/brushes/spraypaints/1fab58be-32e1-54d1-b121-832d0192cdd6/brush.json` | Nanquim contributors | Brush parameters (Fine Nozzle) | MIT |
| `assets-src/brushes/spraypaints/1fab58be-32e1-54d1-b121-832d0192cdd6/shape.png` | Nanquim contributors | Procedural: `spatter 256 1102 300` | MIT |
| `assets-src/brushes/spraypaints/9c575fb3-e6e8-5c9f-b5e4-ab3062e22d9b/brush.json` | Nanquim contributors | Brush parameters (Medium Nozzle) | MIT |
| `assets-src/brushes/spraypaints/9c575fb3-e6e8-5c9f-b5e4-ab3062e22d9b/shape.png` | Nanquim contributors | Procedural: `spatter 256 1103 250` | MIT |
| `assets-src/brushes/spraypaints/01cde586-0f39-573c-a4db-53b1b8e00299/brush.json` | Nanquim contributors | Brush parameters (Fat Nozzle) | MIT |
| `assets-src/brushes/spraypaints/01cde586-0f39-573c-a4db-53b1b8e00299/shape.png` | Nanquim contributors | Procedural: `spatter 256 1104 200` | MIT |
| `assets-src/brushes/spraypaints/43ceb370-5159-510f-ae54-834bad1b9a95/brush.json` | Nanquim contributors | Brush parameters (Splatter) | MIT |
| `assets-src/brushes/spraypaints/43ceb370-5159-510f-ae54-834bad1b9a95/shape.png` | Nanquim contributors | Procedural: `spatter 256 1105 60` | MIT |
| `assets-src/brushes/spraypaints/5e275eda-b560-539b-941b-9ef620ba4638/brush.json` | Nanquim contributors | Brush parameters (Flicks) | MIT |
| `assets-src/brushes/spraypaints/5e275eda-b560-539b-941b-9ef620ba4638/shape.png` | Nanquim contributors | Procedural: `streak 256 1106 0.3` | MIT |
| `assets-src/brushes/spraypaints/fe00aa24-7d8f-5df8-a357-d24dfe1490d3/brush.json` | Nanquim contributors | Brush parameters (Burst) | MIT |
| `assets-src/brushes/spraypaints/fe00aa24-7d8f-5df8-a357-d24dfe1490d3/shape.png` | Nanquim contributors | Procedural: `spatter 256 1107 120` | MIT |
| `assets-src/brushes/spraypaints/1ac0e6db-e2c2-5e70-ba01-bb44ab1cb46b/brush.json` | Nanquim contributors | Brush parameters (Drip) | MIT |
| `assets-src/brushes/spraypaints/1ac0e6db-e2c2-5e70-ba01-bb44ab1cb46b/shape.png` | Nanquim contributors | Procedural: `drip 256 1108 0.4` | MIT |
| `assets-src/brushes/touchups/53258b1f-2f04-5821-a86a-929ac9312d83/brush.json` | Nanquim contributors | Brush parameters (Avalon) | MIT |
| `assets-src/brushes/touchups/53258b1f-2f04-5821-a86a-929ac9312d83/grain.png` | Nanquim contributors | Procedural: `paper 512 901 30` | MIT |
| `assets-src/brushes/touchups/c4ad647a-6104-5567-a820-d16ea60c8d3f/brush.json` | Nanquim contributors | Brush parameters (Wattlebird) | MIT |
| `assets-src/brushes/touchups/c4ad647a-6104-5567-a820-d16ea60c8d3f/grain.png` | Nanquim contributors | Procedural: `noise 512 902 0.35` | MIT |
| `assets-src/brushes/touchups/3ac70dd6-2d98-5a24-ad05-599f5e24eb49/brush.json` | Nanquim contributors | Brush parameters (Ferntree) | MIT |
| `assets-src/brushes/touchups/3ac70dd6-2d98-5a24-ad05-599f5e24eb49/shape.png` | Nanquim contributors | Procedural: `foliage 256 903 9` | MIT |
| `assets-src/brushes/touchups/c37c16c8-3f60-5bc4-b9ec-3ca265b86194/brush.json` | Nanquim contributors | Brush parameters (Sawtooth) | MIT |
| `assets-src/brushes/touchups/c37c16c8-3f60-5bc4-b9ec-3ca265b86194/shape.png` | Nanquim contributors | Procedural: `triangles 256 904 4` | MIT |
| `assets-src/brushes/touchups/a2ba5bca-ee04-511c-95c4-490b33cf4d8e/brush.json` | Nanquim contributors | Brush parameters (Blackwood) | MIT |
| `assets-src/brushes/touchups/a2ba5bca-ee04-511c-95c4-490b33cf4d8e/grain.png` | Nanquim contributors | Procedural: `wood 512 905 5` | MIT |
| `assets-src/brushes/touchups/daf55b75-fec1-5f08-b50f-22f0583fc65d/brush.json` | Nanquim contributors | Brush parameters (Tawny) | MIT |
| `assets-src/brushes/touchups/daf55b75-fec1-5f08-b50f-22f0583fc65d/grain.png` | Nanquim contributors | Procedural: `paper 512 906 40` | MIT |
| `assets-src/brushes/touchups/92bbeb29-9cce-5566-8f7d-86286da04336/brush.json` | Nanquim contributors | Brush parameters (D'Entrecasteaux) | MIT |
| `assets-src/brushes/touchups/92bbeb29-9cce-5566-8f7d-86286da04336/grain.png` | Nanquim contributors | Procedural: `cells 512 907 10` | MIT |
| `assets-src/brushes/touchups/016da99f-08c5-5fd4-856b-4839606b2195/brush.json` | Nanquim contributors | Brush parameters (Painter's Touch) | MIT |
| `assets-src/brushes/touchups/016da99f-08c5-5fd4-856b-4839606b2195/shape.png` | Nanquim contributors | Procedural: `bristles 128 908 14` | MIT |
| `assets-src/brushes/touchups/70aaa664-dff0-502f-99a8-4193c39c3a23/brush.json` | Nanquim contributors | Brush parameters (Furneaux) | MIT |
| `assets-src/brushes/touchups/70aaa664-dff0-502f-99a8-4193c39c3a23/grain.png` | Nanquim contributors | Procedural: `marble 512 909 3` | MIT |
| `assets-src/brushes/touchups/e6dda894-e3e9-593e-8f38-7c6bc0d25eb0/brush.json` | Nanquim contributors | Brush parameters (Stubble) | MIT |
| `assets-src/brushes/touchups/e6dda894-e3e9-593e-8f38-7c6bc0d25eb0/shape.png` | Nanquim contributors | Procedural: `spatter 256 910 220` | MIT |
| `assets-src/brushes/touchups/a5654b07-1032-525d-8c5a-c969a866c211/brush.json` | Nanquim contributors | Brush parameters (Noise Brush) | MIT |
| `assets-src/brushes/touchups/a5654b07-1032-525d-8c5a-c969a866c211/grain.png` | Nanquim contributors | Procedural: `noise 512 911 0.5` | MIT |
| `assets-src/brushes/touchups/ce7c664f-a7d7-5fba-bc40-8428b6cd72d7/brush.json` | Nanquim contributors | Brush parameters (Old Skin) | MIT |
| `assets-src/brushes/touchups/ce7c664f-a7d7-5fba-bc40-8428b6cd72d7/grain.png` | Nanquim contributors | Procedural: `concrete 512 912 20` | MIT |
| `assets-src/brushes/touchups/116b3c96-4c18-5ca8-9f82-90de3f9239c8/brush.json` | Nanquim contributors | Brush parameters (Rough Skin) | MIT |
| `assets-src/brushes/touchups/116b3c96-4c18-5ca8-9f82-90de3f9239c8/grain.png` | Nanquim contributors | Procedural: `cells 512 913 18` | MIT |
| `assets-src/brushes/touchups/bdee5bc3-2c80-5fe6-95d9-344828b3f8e1/brush.json` | Nanquim contributors | Brush parameters (Zombie Skin) | MIT |
| `assets-src/brushes/touchups/bdee5bc3-2c80-5fe6-95d9-344828b3f8e1/grain.png` | Nanquim contributors | Procedural: `minerals 512 914 9` | MIT |
| `assets-src/brushes/touchups/93973066-27e7-5eda-a833-b6da09d88a1f/brush.json` | Nanquim contributors | Brush parameters (Fine Hair) | MIT |
| `assets-src/brushes/touchups/93973066-27e7-5eda-a833-b6da09d88a1f/shape.png` | Nanquim contributors | Procedural: `bristles 128 915 30` | MIT |
| `assets-src/brushes/touchups/286e2f3e-c81a-5b93-bc00-6e14cb591cf8/brush.json` | Nanquim contributors | Brush parameters (Flowing Hair) | MIT |
| `assets-src/brushes/touchups/286e2f3e-c81a-5b93-bc00-6e14cb591cf8/shape.png` | Nanquim contributors | Procedural: `bristles 128 916 18` | MIT |
| `assets-src/brushes/touchups/95e2c72e-724f-56e7-9b3f-82be47704662/brush.json` | Nanquim contributors | Brush parameters (Short Hair) | MIT |
| `assets-src/brushes/touchups/95e2c72e-724f-56e7-9b3f-82be47704662/shape.png` | Nanquim contributors | Procedural: `bristles 128 917 22` | MIT |
| `assets-src/brushes/retro/7e0f2c2f-8629-5e51-b542-93fb5d7c88de/brush.json` | Nanquim contributors | Brush parameters (Myrtle) | MIT |
| `assets-src/brushes/retro/7e0f2c2f-8629-5e51-b542-93fb5d7c88de/shape.png` | Nanquim contributors | Procedural: `rosette 256 1001 1` | MIT |
| `assets-src/brushes/retro/a68fc300-5810-5875-b0e8-e46da7fa81f0/brush.json` | Nanquim contributors | Brush parameters (Honeyeater) | MIT |
| `assets-src/brushes/retro/a68fc300-5810-5875-b0e8-e46da7fa81f0/grain.png` | Nanquim contributors | Procedural: `hexagons 512 1002 6` | MIT |
| `assets-src/brushes/retro/0b3cd91c-87e9-56b6-931b-718d557948a9/brush.json` | Nanquim contributors | Brush parameters (Galaxias) | MIT |
| `assets-src/brushes/retro/0b3cd91c-87e9-56b6-931b-718d557948a9/shape.png` | Nanquim contributors | Procedural: `star 256 1003 10` | MIT |
| `assets-src/brushes/retro/236e76e7-5e2a-5b8a-a327-ac481a78c70e/brush.json` | Nanquim contributors | Brush parameters (Piazza) | MIT |
| `assets-src/brushes/retro/236e76e7-5e2a-5b8a-a327-ac481a78c70e/grain.png` | Nanquim contributors | Procedural: `ornament 512 1004 4` | MIT |
| `assets-src/brushes/retro/415a690d-3d0b-5477-bc58-3159eac71fe3/brush.json` | Nanquim contributors | Brush parameters (Wedge Tail) | MIT |
| `assets-src/brushes/retro/415a690d-3d0b-5477-bc58-3159eac71fe3/shape.png` | Nanquim contributors | Procedural: `triangles 256 1005 2` | MIT |
| `assets-src/brushes/retro/83257959-990f-5324-83da-4c2a84734ae2/brush.json` | Nanquim contributors | Brush parameters (Groovy) | MIT |
| `assets-src/brushes/retro/83257959-990f-5324-83da-4c2a84734ae2/shape.png` | Nanquim contributors | Procedural: `waves 256 1006 3` | MIT |
| `assets-src/brushes/retro/7d84068e-d3fc-5531-95ae-733765cf405d/brush.json` | Nanquim contributors | Brush parameters (Bolt) | MIT |
| `assets-src/brushes/retro/7d84068e-d3fc-5531-95ae-733765cf405d/shape.png` | Nanquim contributors | Procedural: `star 256 1007 3` | MIT |
| `assets-src/brushes/retro/ed2d2ec0-9c4b-576f-90b9-896b1a2e4bd0/brush.json` | Nanquim contributors | Brush parameters (Fever) | MIT |
| `assets-src/brushes/retro/ed2d2ec0-9c4b-576f-90b9-896b1a2e4bd0/shape.png` | Nanquim contributors | Procedural: `dots 256 1008 3` | MIT |
| `assets-src/brushes/retro/734cb9c4-b8e7-5bfe-b599-9611fe75b584/brush.json` | Nanquim contributors | Brush parameters (Newsprint) | MIT |
| `assets-src/brushes/retro/734cb9c4-b8e7-5bfe-b599-9611fe75b584/grain.png` | Nanquim contributors | Procedural: `dots 512 1009 16` | MIT |
| `assets-src/brushes/retro/b1d8d484-d2b5-5af2-b1d0-9c837a647c15/brush.json` | Nanquim contributors | Brush parameters (Buzz) | MIT |
| `assets-src/brushes/retro/b1d8d484-d2b5-5af2-b1d0-9c837a647c15/grain.png` | Nanquim contributors | Procedural: `stripes 512 1010 12` | MIT |
| `assets-src/brushes/retro/c285d912-0707-54b8-afba-bdfe8d2ad259/brush.json` | Nanquim contributors | Brush parameters (Disco) | MIT |
| `assets-src/brushes/retro/c285d912-0707-54b8-afba-bdfe8d2ad259/shape.png` | Nanquim contributors | Procedural: `polygons 256 1011 4` | MIT |
| `assets-src/brushes/retro/8f1e02ce-6f77-5f5a-bf40-8307469a4fa5/brush.json` | Nanquim contributors | Brush parameters (Rad) | MIT |
| `assets-src/brushes/retro/8f1e02ce-6f77-5f5a-bf40-8307469a4fa5/shape.png` | Nanquim contributors | Procedural: `spatter 256 1012 40` | MIT |
| `assets-src/brushes/retro/5dafb276-b824-54dd-89d0-8f084174dfec/brush.json` | Nanquim contributors | Brush parameters (Flower Power) | MIT |
| `assets-src/brushes/retro/5dafb276-b824-54dd-89d0-8f084174dfec/shape.png` | Nanquim contributors | Procedural: `rosette 256 1013 1` | MIT |
| `assets-src/brushes/luminance/8802fa61-d71b-56ce-8a55-93d6eda512a5/brush.json` | Nanquim contributors | Brush parameters (Flare) | MIT |
| `assets-src/brushes/luminance/8802fa61-d71b-56ce-8a55-93d6eda512a5/shape.png` | Nanquim contributors | Procedural: `star 256 1101 16` | MIT |
| `assets-src/brushes/luminance/966ab0fc-265c-563a-9b63-a836b6a3e101/brush.json` | Nanquim contributors | Brush parameters (Lightpen) | MIT |
| `assets-src/brushes/luminance/966ab0fc-265c-563a-9b63-a836b6a3e101/shape.png` | Nanquim contributors | Procedural: `disc 256 1102 0.6` | MIT |
| `assets-src/brushes/luminance/1653096b-cebc-54d4-9fdc-6c858a65ed37/brush.json` | Nanquim contributors | Brush parameters (Lightbrush) | MIT |
| `assets-src/brushes/luminance/1653096b-cebc-54d4-9fdc-6c858a65ed37/shape.png` | Nanquim contributors | Procedural: `bristles 256 1103 12` | MIT |
| `assets-src/brushes/luminance/074f9055-e5a5-5308-a93f-218a707dcf60/brush.json` | Nanquim contributors | Brush parameters (Pulse) | MIT |
| `assets-src/brushes/luminance/074f9055-e5a5-5308-a93f-218a707dcf60/shape.png` | Nanquim contributors | Procedural: `disc 256 1104 0.3` | MIT |
| `assets-src/brushes/luminance/23147a7f-4e81-590c-bc78-9f88cf6a81c4/brush.json` | Nanquim contributors | Brush parameters (Bokeh Lights) | MIT |
| `assets-src/brushes/luminance/23147a7f-4e81-590c-bc78-9f88cf6a81c4/shape.png` | Nanquim contributors | Procedural: `bokeh 256 1105 0.8` | MIT |
| `assets-src/brushes/luminance/e4988ba3-86ea-51e0-9b47-7ff8977b4e9f/brush.json` | Nanquim contributors | Brush parameters (Lightleak) | MIT |
| `assets-src/brushes/luminance/e4988ba3-86ea-51e0-9b47-7ff8977b4e9f/shape.png` | Nanquim contributors | Procedural: `cloud 256 1106 0.3` | MIT |
| `assets-src/brushes/luminance/c7affa05-57d2-5b0d-a7b3-7a154592837c/brush.json` | Nanquim contributors | Brush parameters (Glimmer) | MIT |
| `assets-src/brushes/luminance/c7affa05-57d2-5b0d-a7b3-7a154592837c/shape.png` | Nanquim contributors | Procedural: `star 256 1107 6` | MIT |
| `assets-src/brushes/luminance/c4f0cebb-7911-57aa-a30d-db6fd1cd26b6/brush.json` | Nanquim contributors | Brush parameters (Nebula) | MIT |
| `assets-src/brushes/luminance/c4f0cebb-7911-57aa-a30d-db6fd1cd26b6/shape.png` | Nanquim contributors | Procedural: `cloud 256 1108 0.8` | MIT |
| `assets-src/brushes/industrial/299b4e11-3741-561a-8ddb-36f439ffa5f3/brush.json` | Nanquim contributors | Brush parameters (Caged) | MIT |
| `assets-src/brushes/industrial/299b4e11-3741-561a-8ddb-36f439ffa5f3/grain.png` | Nanquim contributors | Procedural: `grid 512 1201 6` | MIT |
| `assets-src/brushes/industrial/74ef963e-8ea7-5ace-8706-a2391b6bc43e/brush.json` | Nanquim contributors | Brush parameters (Heavy Metal) | MIT |
| `assets-src/brushes/industrial/74ef963e-8ea7-5ace-8706-a2391b6bc43e/grain.png` | Nanquim contributors | Procedural: `metal 512 1202 400` | MIT |
| `assets-src/brushes/industrial/db457e4e-4441-502a-b0b9-880ca5e8045a/brush.json` | Nanquim contributors | Brush parameters (Wasteland) | MIT |
| `assets-src/brushes/industrial/db457e4e-4441-502a-b0b9-880ca5e8045a/grain.png` | Nanquim contributors | Procedural: `concrete 512 1203 8` | MIT |
| `assets-src/brushes/industrial/3e35eb7b-030b-5bbf-998d-f236daddf101/brush.json` | Nanquim contributors | Brush parameters (Twisted Tree) | MIT |
| `assets-src/brushes/industrial/3e35eb7b-030b-5bbf-998d-f236daddf101/shape.png` | Nanquim contributors | Procedural: `fiber 256 1204 40` | MIT |
| `assets-src/brushes/industrial/fa1fb70b-a3db-5c7c-9858-d328be5761b2/brush.json` | Nanquim contributors | Brush parameters (Stone Wall) | MIT |
| `assets-src/brushes/industrial/fa1fb70b-a3db-5c7c-9858-d328be5761b2/grain.png` | Nanquim contributors | Procedural: `bricks 512 1205 5` | MIT |
| `assets-src/brushes/industrial/85f7bf4d-49df-56f9-87d5-7ef9fe18eb52/brush.json` | Nanquim contributors | Brush parameters (Rusted Decay) | MIT |
| `assets-src/brushes/industrial/85f7bf4d-49df-56f9-87d5-7ef9fe18eb52/grain.png` | Nanquim contributors | Procedural: `concrete 512 1206 30` | MIT |
| `assets-src/brushes/industrial/da7a0aba-bf83-51ae-bb34-c684473fa5d0/brush.json` | Nanquim contributors | Brush parameters (Concrete Block) | MIT |
| `assets-src/brushes/industrial/da7a0aba-bf83-51ae-bb34-c684473fa5d0/grain.png` | Nanquim contributors | Procedural: `concrete 512 1207 14` | MIT |
| `assets-src/brushes/industrial/f1eaf862-6b78-53d7-a2ff-b8ae670f6062/brush.json` | Nanquim contributors | Brush parameters (Corrugated Iron) | MIT |
| `assets-src/brushes/industrial/f1eaf862-6b78-53d7-a2ff-b8ae670f6062/grain.png` | Nanquim contributors | Procedural: `stripes 512 1208 10` | MIT |
| `assets-src/brushes/organic/67b7cb4a-50dc-5cdd-ac00-61d5a6344ce7/brush.json` | Nanquim contributors | Brush parameters (Spires) | MIT |
| `assets-src/brushes/organic/67b7cb4a-50dc-5cdd-ac00-61d5a6344ce7/shape.png` | Nanquim contributors | Procedural: `streak 256 1301 0.3` | MIT |
| `assets-src/brushes/organic/99f4af92-f758-5a73-b411-e093defeba6b/brush.json` | Nanquim contributors | Brush parameters (Rainforest) | MIT |
| `assets-src/brushes/organic/99f4af92-f758-5a73-b411-e093defeba6b/shape.png` | Nanquim contributors | Procedural: `foliage 256 1302 12` | MIT |
| `assets-src/brushes/organic/2d321f93-d80c-55c0-bd1d-fd2b021780d2/brush.json` | Nanquim contributors | Brush parameters (Snow Gum) | MIT |
| `assets-src/brushes/organic/2d321f93-d80c-55c0-bd1d-fd2b021780d2/shape.png` | Nanquim contributors | Procedural: `foliage 256 1303 5` | MIT |
| `assets-src/brushes/organic/41e01c8e-64f4-5440-8975-12861f88eb1c/brush.json` | Nanquim contributors | Brush parameters (Mountain Ash) | MIT |
| `assets-src/brushes/organic/41e01c8e-64f4-5440-8975-12861f88eb1c/shape.png` | Nanquim contributors | Procedural: `foliage 256 1304 3` | MIT |
| `assets-src/brushes/organic/96dbeaf4-e8d3-5931-8139-1baedaea97c9/brush.json` | Nanquim contributors | Brush parameters (Paper Daisy) | MIT |
| `assets-src/brushes/organic/96dbeaf4-e8d3-5931-8139-1baedaea97c9/shape.png` | Nanquim contributors | Procedural: `rosette 256 1305 1` | MIT |
| `assets-src/brushes/organic/2cc9a94e-7b6e-59c9-8dfc-ab30927587c2/brush.json` | Nanquim contributors | Brush parameters (Swordgrass) | MIT |
| `assets-src/brushes/organic/2cc9a94e-7b6e-59c9-8dfc-ab30927587c2/shape.png` | Nanquim contributors | Procedural: `streak 256 1306 0.2` | MIT |
| `assets-src/brushes/organic/1f491c7e-fd81-51fc-a566-e67e78f1cfa5/brush.json` | Nanquim contributors | Brush parameters (Wildgrass) | MIT |
| `assets-src/brushes/organic/1f491c7e-fd81-51fc-a566-e67e78f1cfa5/shape.png` | Nanquim contributors | Procedural: `streak 256 1307 0.12` | MIT |
| `assets-src/brushes/organic/faff9901-8013-5f03-b68c-e435ef39023e/brush.json` | Nanquim contributors | Brush parameters (Twig) | MIT |
| `assets-src/brushes/organic/faff9901-8013-5f03-b68c-e435ef39023e/shape.png` | Nanquim contributors | Procedural: `fiber 256 1308 12` | MIT |
| `assets-src/brushes/organic/66f05605-9652-5903-b50d-8b33e0865066/brush.json` | Nanquim contributors | Brush parameters (Reed) | MIT |
| `assets-src/brushes/organic/66f05605-9652-5903-b50d-8b33e0865066/shape.png` | Nanquim contributors | Procedural: `bristles 128 1309 6` | MIT |
| `assets-src/brushes/organic/1126d9d0-d485-59d2-8aaa-cd7f0ddb821f/brush.json` | Nanquim contributors | Brush parameters (Bamboo) | MIT |
| `assets-src/brushes/organic/1126d9d0-d485-59d2-8aaa-cd7f0ddb821f/grain.png` | Nanquim contributors | Procedural: `stripes 512 1310 4` | MIT |
| `assets-src/brushes/organic/e926de59-19cd-52e7-add2-8441335ece16/brush.json` | Nanquim contributors | Brush parameters (Sable) | MIT |
| `assets-src/brushes/organic/e926de59-19cd-52e7-add2-8441335ece16/shape.png` | Nanquim contributors | Procedural: `bristles 128 1311 24` | MIT |
| `assets-src/brushes/organic/eebcc319-4c1e-52ff-ab67-94add41faa1a/brush.json` | Nanquim contributors | Brush parameters (Hemp) | MIT |
| `assets-src/brushes/organic/eebcc319-4c1e-52ff-ab67-94add41faa1a/grain.png` | Nanquim contributors | Procedural: `fiber 512 1312 200` | MIT |
| `assets-src/brushes/organic/41dde7ba-d8dc-5b2e-91ec-c2ca6c600351/brush.json` | Nanquim contributors | Brush parameters (Clay) | MIT |
| `assets-src/brushes/organic/41dde7ba-d8dc-5b2e-91ec-c2ca6c600351/grain.png` | Nanquim contributors | Procedural: `concrete 512 1313 40` | MIT |
| `assets-src/brushes/organic/19e6a45f-93ee-5b54-9e13-70fb62768c0a/brush.json` | Nanquim contributors | Brush parameters (Cotton) | MIT |
| `assets-src/brushes/organic/19e6a45f-93ee-5b54-9e13-70fb62768c0a/grain.png` | Nanquim contributors | Procedural: `fiber 512 1314 400` | MIT |
| `assets-src/brushes/organic/c10c8ee7-3607-573f-8173-5ab9268fc4df/brush.json` | Nanquim contributors | Brush parameters (Hessian) | MIT |
| `assets-src/brushes/organic/c10c8ee7-3607-573f-8173-5ab9268fc4df/grain.png` | Nanquim contributors | Procedural: `grid 512 1315 24` | MIT |
| `assets-src/brushes/water/a0abe7c5-7e77-588f-b1dc-951068b9270a/brush.json` | Nanquim contributors | Brush parameters (Water Bleed) | MIT |
| `assets-src/brushes/water/40c45020-7509-5895-b95d-22ef5cef6c4e/brush.json` | Nanquim contributors | Brush parameters (Wet Sponge) | MIT |
| `assets-src/brushes/water/40c45020-7509-5895-b95d-22ef5cef6c4e/shape.png` | Nanquim contributors | Procedural: `sponge 256 1202 10` | MIT |
| `assets-src/brushes/water/9600ad51-67bc-5537-846f-a4db564bd4c3/brush.json` | Nanquim contributors | Brush parameters (Wet Glaze) | MIT |
| `assets-src/brushes/water/dd51d0f1-fb40-5154-b2c1-9fb724ad7b3c/brush.json` | Nanquim contributors | Brush parameters (Wash) | MIT |
| `assets-src/brushes/water/743a7573-d2f5-5b11-8b9c-95d3187716e2/brush.json` | Nanquim contributors | Brush parameters (Mad Splashes) | MIT |
| `assets-src/brushes/water/743a7573-d2f5-5b11-8b9c-95d3187716e2/shape.png` | Nanquim contributors | Procedural: `spatter 256 1205 50` | MIT |
| `assets-src/brushes/water/bbea614f-d83d-5419-8bf0-53a790597fbd/brush.json` | Nanquim contributors | Brush parameters (Water Flicks) | MIT |
| `assets-src/brushes/water/bbea614f-d83d-5419-8bf0-53a790597fbd/shape.png` | Nanquim contributors | Procedural: `streak 256 1206 0.4` | MIT |
| `assets-src/brushes/water/e72e3503-1c56-5192-9bc2-c88f78a0a0d2/brush.json` | Nanquim contributors | Brush parameters (Blotch) | MIT |
| `assets-src/brushes/water/e72e3503-1c56-5192-9bc2-c88f78a0a0d2/shape.png` | Nanquim contributors | Procedural: `blotch 256 1207 0.7` | MIT |
| `assets-src/brushes/water/e97536fb-4688-5410-b5c7-80db32b32cc2/brush.json` | Nanquim contributors | Brush parameters (Water Drip) | MIT |
| `assets-src/brushes/water/e97536fb-4688-5410-b5c7-80db32b32cc2/shape.png` | Nanquim contributors | Procedural: `drip 256 1208 0.5` | MIT |
| `assets-src/brushes/earth/4b0719aa-38c0-5a49-9b28-2fee760a6bb5/brush.json` | Nanquim contributors | Brush parameters (Wheatgrass) | MIT |
| `assets-src/brushes/earth/4b0719aa-38c0-5a49-9b28-2fee760a6bb5/shape.png` | Nanquim contributors | Procedural: `streak 256 1401 0.18` | MIT |
| `assets-src/brushes/earth/e4b10028-f612-5612-9ed1-c13e59648462/brush.json` | Nanquim contributors | Brush parameters (Limestone) | MIT |
| `assets-src/brushes/earth/e4b10028-f612-5612-9ed1-c13e59648462/grain.png` | Nanquim contributors | Procedural: `concrete 512 1402 24` | MIT |
| `assets-src/brushes/earth/63fe5d4e-0a91-5b67-8a88-6a9447870204/brush.json` | Nanquim contributors | Brush parameters (Smoky Quartz) | MIT |
| `assets-src/brushes/earth/63fe5d4e-0a91-5b67-8a88-6a9447870204/grain.png` | Nanquim contributors | Procedural: `minerals 512 1403 7` | MIT |
| `assets-src/brushes/earth/2adcabd8-9162-5cfd-8de8-8e0868a2614b/brush.json` | Nanquim contributors | Brush parameters (Palm Husk) | MIT |
| `assets-src/brushes/earth/2adcabd8-9162-5cfd-8de8-8e0868a2614b/grain.png` | Nanquim contributors | Procedural: `fiber 512 1404 80` | MIT |
| `assets-src/brushes/earth/e7c9c751-8f2f-5037-b3b8-e060ec6f0f51/brush.json` | Nanquim contributors | Brush parameters (Flourish) | MIT |
| `assets-src/brushes/earth/e7c9c751-8f2f-5037-b3b8-e060ec6f0f51/shape.png` | Nanquim contributors | Procedural: `foliage 256 1405 4` | MIT |
| `assets-src/brushes/earth/2ff2be91-8fa2-57bb-8b00-4bc68d64cae1/brush.json` | Nanquim contributors | Brush parameters (Sandstone) | MIT |
| `assets-src/brushes/earth/2ff2be91-8fa2-57bb-8b00-4bc68d64cae1/grain.png` | Nanquim contributors | Procedural: `paper 512 1406 60` | MIT |
| `assets-src/brushes/earth/9d194704-2817-541b-85fe-a612855b4e92/brush.json` | Nanquim contributors | Brush parameters (River Silt) | MIT |
| `assets-src/brushes/earth/9d194704-2817-541b-85fe-a612855b4e92/grain.png` | Nanquim contributors | Procedural: `marble 512 1407 4` | MIT |
| `assets-src/brushes/earth/5c5ff64f-9a27-568c-b525-f043268b1d8a/brush.json` | Nanquim contributors | Brush parameters (Volcanic Ash) | MIT |
| `assets-src/brushes/earth/5c5ff64f-9a27-568c-b525-f043268b1d8a/grain.png` | Nanquim contributors | Procedural: `concrete 512 1408 50` | MIT |
<!-- end of built-in brushes -->
