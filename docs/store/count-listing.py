#!/usr/bin/env python3
"""Check the Play listing and release-note fields against Play's character limits.

Run by hand: `python docs/store/count-listing.py`. It is deliberately **not** wired into CI --
race-timer has no lint step by design, and enforcing docs-to-Play consistency is its own piece of
work (#83). An unwired check that reads as a gate is worse than an honest manual one, so this says
what it is.

`listing.md` and `release-notes.md` carry no counts in their prose on purpose: a number written
next to text that gets edited is the claim that goes stale first. Run this instead.

Exit 0 when every field fits, 1 otherwise.
"""

import re
import sys
from pathlib import Path

# Play Console limits, 2026-08. Sources are the field editors themselves; if Console ever disagrees,
# Console wins and this table is the thing that is wrong.
LIMITS = {
    "name": 30,
    "short": 80,
    "full": 4000,
    "notes": 500,
}

# Which file each field lives in. `notes` is in its own document because release notes are per
# release and the listing is not -- keeping them in one file would mean editing the listing to cut a
# release, which is the mistake that eventually edits the listing by accident.
SOURCES = {
    "name": "listing.md",
    "short": "listing.md",
    "full": "listing.md",
    "notes": "release-notes.md",
}


def extract(text, field, source):
    """Pull one delimited field out of listing.md, or raise if it is missing.

    A regex that silently matches nothing would report a zero-length field as comfortably inside its
    limit, so absence has to be an error rather than a small number.
    """
    match = re.search(
        rf"<!-- FIELD:{field} -->\n(.*?)\n<!-- /FIELD:{field} -->",
        text,
        re.DOTALL,
    )
    if match is None:
        raise SystemExit(f"{source} has no FIELD:{field} block -- cannot count what is not there")
    return match.group(1)


def main():
    # Read each source once. A missing file must raise rather than count as an empty field, for the
    # same reason extract() refuses a missing block.
    texts = {}
    for name in set(SOURCES.values()):
        path = Path(__file__).with_name(name)
        if not path.is_file():
            raise SystemExit(f"{name} is missing -- cannot count what is not there")
        texts[name] = path.read_text(encoding="utf-8")

    failed = False

    for field, limit in LIMITS.items():
        value = extract(texts[SOURCES[field]], field, SOURCES[field])
        used = len(value)
        status = "ok " if used <= limit else "OVER"
        if used > limit:
            failed = True
        print(f"{status} {field:6} {used:>4} / {limit}")

    if failed:
        print("\nAt least one field is over its Play limit.")
        return 1

    print("\nEvery field fits.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
