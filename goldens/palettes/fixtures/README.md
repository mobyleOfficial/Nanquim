# Palette fixtures from Adobe apps

Nanquim reads ACO and ASE palettes from their public descriptions. Two readings could not be
checked without files written by the Adobe apps themselves:

- ACO grayscale: read as **ink** (0 = white, 10000 = black).
- ASE `Gray`: read as **light** (0 = black, 1 = white).

Files we write ourselves would only repeat those guesses, so these must be exported by a person
from Photoshop and Illustrator. Put them in this folder with exactly these names; the host test
`PaletteFixtures.*` (`./gradlew :engine:hostTest`) then checks them against `expected.json` and is
skipped while the folder has none.

| File | App | How |
|---|---|---|
| `photoshop.aco` | Photoshop | Swatches panel menu → Save Swatches… (writes versions 1 and 2) |
| `photoshop.ase` | Photoshop | Swatches panel menu → Save Swatches for Exchange… / Export Selected Swatches |
| `illustrator.ase` | Illustrator | Swatches panel menu → Save Swatch Library as ASE… |

## The swatches

Start from an empty swatch set (Photoshop: a new swatch group with only these, exported with
Export Selected Swatches; Illustrator: a new document with every other swatch deleted). Add these
seven, **in this order**, named exactly so. Pick each color in the mode given — the gray ones
must be made with the **Grayscale** slider (Color panel → panel menu → Grayscale Slider), so the
file stores them as grayscale, not RGB.

| # | Name | Mode in the Color panel | Value |
|---|---|---|---|
| 1 | Red | RGB | 255, 0, 0 |
| 2 | Gray 25 | Grayscale | K 25 % |
| 3 | Black K | Grayscale | K 100 % |
| 4 | White K | Grayscale | K 0 % |
| 5 | Cyan ink | CMYK | 100, 0, 0, 0 |
| 6 | Lab mid | Lab | L 50, a 0, b 0 (Illustrator: Lab swatch, or skip and say so) |
| 7 | Green | HSB (Photoshop) / RGB (Illustrator) | 120°, 100 %, 100 % / 0, 255, 0 |

Photoshop may convert a swatch to the document's mode when it is added from another mode; work
in an RGB document and check, after saving, that the Gray swatches still show "K" values when
hovered or edited. The test fails with "stored as … not gray" when a gray swatch was converted.

## What the test checks

For each file present: the swatches in order, their names when the format keeps names (ACO
version 2, ASE), each color within the tolerance of `expected.json`, and — for the gray rows —
that the file really stores them as grayscale (ACO color space 8, ASE model `Gray`). The gray
tolerance is wide on purpose: it tells "25 % ink" (light) from "25 % light" (dark) without
depending on Photoshop's dot-gain gray profile.

The files are made by the team for this test and may be committed (no third-party content).
