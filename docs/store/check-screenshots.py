#!/usr/bin/env python3
"""Check the store screenshots against Play's asset rules, one rule set per form factor.

Run by hand: `python docs/store/check-screenshots.py`. Deliberately **not** wired into CI, for the
same reason `count-listing.py` is not -- race-timer has no lint step by design (#83). An unwired
check that reads as a gate is worse than an honest manual one, so this says what it is.

Two sets, two rule sets, and the directory says which applies:

- `screenshots/*.png`        -- **Wear OS**. Play's Wear rules are the strict ones, quoted in
  `cairn/memory/reference/google-play-wear-os-release-requirements-2026-08-01.md`: 1:1 aspect
  ratio, minimum 384 x 384, PNG or JPEG, no transparency.
- `screenshots/phone/*.png`  -- **Phone** (#213). Play's phone rules, read from
  https://support.google.com/googleplay/android-developer/answer/9866151 on 2026-09-05 rather than
  copied from the Wear spec, because the two differ in exactly the dimension that matters: JPEG or
  24-bit PNG (no alpha); each side between 320 px and 3840 px; and *"the maximum dimension of your
  screenshot can't be more than twice as long as the minimum dimension"*. That last rule is why a
  raw framebuffer from a modern phone **fails** -- a 1080 x 2424 Pixel 9 or a 1440 x 3088 S23 Ultra
  is taller than 2:1 -- and why the phone set is captured on a 1080 x 1920 display instead
  (`screenshots/phone/README.md`).

The same page recommends 16:9 landscape (at least 1920 x 1080) or 9:16 portrait (at least
1080 x 1920) for eligibility in Play's large-format promotional placements. That is a
recommendation, not a rule, so it is **reported** per phone image and never fails the run.

`no transparency` is checked as **no alpha channel at all**, which is stricter than Play's wording
for Wear and exactly Play's wording ("24-bit PNG (no alpha)") for phone. `adb exec-out screencap -p`
emits RGBA with every alpha byte at 255 -- no actual transparency, but an alpha channel present.
Stripping it to 24-bit RGB removes the argument rather than winning it, and this check is what stops
a future capture landing back in RGBA unnoticed.

The rules a script cannot see -- no device frame, and no added text or composed background -- are
properties of how the file was made, not of its bytes. They are discharged by the capture method
(`screencap` reads the framebuffer, so there is nothing to compose) and recorded in each set's
README. This docstring says so rather than letting a green run imply the whole AC.

Both directories must exist and hold at least one image: an empty or missing set would otherwise
pass every rule vacuously and print a reassuring "all pass", which is the failure this whole file
exists to avoid.

Exit 0 when every image in both sets passes, 1 otherwise.
"""

import sys
from pathlib import Path

from PIL import Image

SHOTS = Path(__file__).with_name("screenshots")
PHONE_SHOTS = SHOTS / "phone"

WEAR_MIN_EDGE = 384

PHONE_MIN_EDGE = 320
PHONE_MAX_EDGE = 3840
PHONE_MAX_RATIO = 2          # long side may be at most this many times the short side
PROMO_MIN_SHORT = 1080       # 9:16 needs 1080 x 1920; 16:9 needs 1920 x 1080
PROMO_MIN_LONG = 1920


def _alpha(im, problems):
    if "A" in im.getbands():
        problems.append(f"has an alpha channel (mode {im.mode})")


def check_wear(path):
    """Return a list of failure strings for one Wear image; empty means it passes."""
    problems = []
    with Image.open(path) as im:
        width, height = im.size
        if im.format != "PNG":
            problems.append(f"format is {im.format}, expected PNG")
        if width != height:
            problems.append(f"{width}x{height} is not 1:1")
        if min(width, height) < WEAR_MIN_EDGE:
            problems.append(f"{width}x{height} is below the {WEAR_MIN_EDGE}x{WEAR_MIN_EDGE} minimum")
        _alpha(im, problems)
    return problems


def check_phone(path):
    """Return a list of failure strings for one phone image; empty means it passes."""
    problems = []
    with Image.open(path) as im:
        width, height = im.size
        if im.format not in ("PNG", "JPEG"):
            problems.append(f"format is {im.format}, expected PNG or JPEG")
        short, long = min(width, height), max(width, height)
        if short < PHONE_MIN_EDGE:
            problems.append(f"{width}x{height}: a side is below the {PHONE_MIN_EDGE} px minimum")
        if long > PHONE_MAX_EDGE:
            problems.append(f"{width}x{height}: a side is above the {PHONE_MAX_EDGE} px maximum")
        if long > PHONE_MAX_RATIO * short:
            problems.append(
                f"{width}x{height}: the long side is more than {PHONE_MAX_RATIO}x the short side"
            )
        _alpha(im, problems)
    return problems


def promo_eligibility(width, height):
    """Play's large-format placement recommendation, reported rather than enforced."""
    short, long = min(width, height), max(width, height)
    if long * 9 == short * 16 and short >= PROMO_MIN_SHORT and long >= PROMO_MIN_LONG:
        return "9:16 promo-eligible" if height > width else "16:9 promo-eligible"
    return "not promo-eligible"


def run_set(label, directory, patterns, check, note=None):
    """Check every image in one set. Returns (image count, failed?)."""
    if not directory.is_dir():
        raise SystemExit(f"{directory} does not exist -- nothing to check")

    images = sorted(p for pattern in patterns for p in directory.glob(pattern))
    if not images:
        raise SystemExit(f"{directory} contains no images -- refusing to report a vacuous pass")

    print(f"[{label}] {directory.relative_to(SHOTS.parent)}")
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
                extra = f"  {note(*im.size)}" if note else ""
                print(f"ok   {path.name:38} {im.size[0]}x{im.size[1]} {im.mode}{extra}")
    print()
    return len(images), failed


def main():
    wear_count, wear_failed = run_set("wear", SHOTS, ("*.png",), check_wear)
    phone_count, phone_failed = run_set(
        "phone", PHONE_SHOTS, ("*.png", "*.jpg", "*.jpeg"), check_phone, note=promo_eligibility
    )

    print(f"{wear_count} wear image(s) and {phone_count} phone image(s) checked.")
    if wear_failed or phone_failed:
        print("At least one image breaks a Play asset rule.")
        return 1
    print(
        "Every wear image is 1:1, at least 384x384, PNG, and carries no alpha channel; "
        "every phone image is PNG or JPEG, 320-3840 px a side, at most 2:1, and carries no alpha channel."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
