# Play release notes

The "What's new" text Play asks for on every release, held here so the next one **edits a file
rather than retyping into a web form** — the same arrangement as [`listing.md`](listing.md), and for
the same reason.

Play's limit is **500 characters per language**. The count is computed by
`docs/store/count-listing.py`, not typed here: a number written next to text that gets edited is the
claim that goes stale first.

Notes are per `versionCode`. Keep the section for each past upload rather than overwriting it — the
release log in [`../releases.md`](../releases.md) says *which commit* shipped, and this file says
*what testers were told about it*. Neither answers the other's question.

---

## versionCode 1 — versionName 1.0 — first internal build

<!-- FIELD:notes -->
First internal build. Mad Cow Race Timer runs a sailing start sequence standalone on your watch.

Worth trying:
- Every sequence end to end — US Sailing 5-4-1-Go, Scholastic, Club 3-2-1, Custom.
- Cues on your wrist with the screen off, which is how it is actually used.
- Sync mid-sequence to the committee's signal; check the clock follows.
- Let a race run past the gun into count-up.

Please report any cue that fires late or not at all, and anything unreadable in sun.
<!-- /FIELD:notes -->

### Why this text

Internal testers get the build within minutes and no review reads this, so it is written for the
**dozen people who will actually sail with it** rather than for a store page. It names the four
things most likely to be wrong on hardware and asks for the two failure modes this project has
already had (a late cue, and sunlight legibility), because "it seems fine" is the default report and
it carries no information.
