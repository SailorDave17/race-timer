#!/usr/bin/env python3
"""Check the store screenshots in `screenshots/` against Play's Wear OS asset rules.

Run by hand: `python docs/store/check-screenshots.py`. Deliberately **not** wired into CI, for the
same reason `count-listing.py` is not -- race-timer has no lint step by design (#83). An unwired
check that reads as a gate is worse than an honest manual one, so this says what it is.

The rules it enforces are Play's, quoted in
`cairn/memory/reference/google-play-wear-os-release-requirements-2026-08-01.md`:

- 1:1 aspect ratio, minimum 384 x 384
- PNG or JPEG
- no transparency

`no transparency` is checked as **no alpha channel at all**, which is stricter than Play's wording.
`adb exec-out screencap -p` emits RGBA with every alpha byte at 255 -- no actual transparency, but an
alpha channel present. Stripping it to 24-bit RGB removes the argument rather than winning it, and
this check is what stops a future capture landing back in RGBA unnoticed.

The two rules a script cannot see -- no device frame, and no added text or composed background -- are
properties of how the file was made, not of its bytes. They are discharged by the capture method
(`screencap` reads the framebuffer, so there is nothing to compose) and recorded in
`screenshots/README.md`. This docstring says so rather than letting a green run imply the whole AC.

Exit 0 when every image passes, 1 otherwise.
"""

import sys
from pathlib import Path

from PIL import Image

MIN_EDGE = 384
SHOTS = Path(__file__).with_name("screenshots")


def check(path):
    """Return a list of failure strings for one image; empty means it passes."""
    problems = []
    with Image.open(path) as im:
        width, height = im.size
        if im.format != "PNG":
            problems.append(f"format is {im.format}, expected PNG")
        if width != height:
            problems.append(f"{width}x{height} is not 1:1")
        if min(width, height) < MIN_EDGE:
            problems.append(f"{width}x{height} is below the {MIN_EDGE}x{MIN_EDGE} minimum")
        if "A" in im.getbands():
            problems.append(f"has an alpha channel (mode {im.mode})")
    return problems


def main():
    if not SHOTS.is_dir():
        raise SystemExit(f"{SHOTS} does not exist -- nothing to check")

    images = sorted(SHOTS.glob("*.png"))
    if not images:
        # An empty directory would otherwise pass every rule vacuously and print a reassuring
        # "all pass", which is the failure this whole file exists to avoid.
        raise SystemExit(f"{SHOTS} contains no .png files -- refusing to report a vacuous pass")

    failed = False
    for path in images:
        problems = check(path)
        if problems:
            failed = True
            print(f"FAIL {path.name}")
            for problem in problems:
                print(f"       {problem}")
        else:
            with Image.open(path) as im:
                print(f"ok   {path.name:38} {im.size[0]}x{im.size[1]} {im.mode}")

    print(f"\n{len(images)} image(s) checked.")
    if failed:
        print("At least one image breaks a Play asset rule.")
        return 1
    print("Every image is 1:1, at least 384x384, PNG, and carries no alpha channel.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
