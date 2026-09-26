# Play Store listing copy

The three text fields Play asks for, held here so the next release **edits a file rather than
retyping into a web form**. Entered in Play Console by [#79](https://github.com/SailorDave17/race-timer/issues/79);
this document is the source, the Console is the copy.

Limits are Play's: app name **30**, short description **80**, full description **4000**. The counts
below are computed from the fields in this file by `docs/store/count-listing.py`, not typed by hand —
a hand-typed count is the claim that goes stale first.

---

## App name

<!-- FIELD:name -->
Mad Cow Race Timer
<!-- /FIELD:name -->

## Short description

<!-- FIELD:short -->
Standalone sailing start-sequence timer for your watch. No phone on the water.
<!-- /FIELD:short -->

## Full description

<!-- FIELD:full -->
Mad Cow Race Timer runs the start sequence on your wrist, standalone on a Wear OS watch. No phone, no pairing, nothing else to carry on the water.

SEQUENCES SAILORS ACTUALLY USE

• US Sailing 5-4-1-Go (RRS 26) — 5:00
• Scholastic / ICSA — 3:00
• Club 3-2-1-Go — 3:00
• Custom — any whole number of minutes, from 1:00

US Sailing sounds long above the minute and short below it: a long blast is a signal the committee is sounding, a short one is your wrist counting. Every sequence shares the same final five seconds, doubled from 0:05 to 0:01, so you never have to remember which sequence is loaded to know what the last five mean.

FOR THE RACE COMMITTEE

US Sailing — Race Manager and Scholastic — Race Manager are the committee side of those sequences, not a re-skin:

• Voiced for someone sounding the signals rather than counting them
• The gun is not the end. The clock keeps running as an elapsed-time race clock, up to H:MM:SS, until you tap End Race, which freezes the finish time on screen
• A two-stage signal-box lead-in, so the watch and an external signal box start in step. Set the box's own alert window: none, 15 s, 60 s, or any value you dial from 5 s to 2:00

SYNC

Tap Sync during the countdown to snap to a whole minute. That absorbs the lag between the Race Committee's flag reaching the top of the staff and your thumb landing on the watch.

BUILT TO BE TRUSTED WITH A START

• Cues are scheduled against a monotonic clock rather than sampled by a tick loop, so they land sub-second on real hardware — measured, not estimated
• The countdown survives the screen going dark and the app going to the background, held by a foreground service
• The clock is anchored to elapsed time, so a network time correction or a time-zone change cannot move your gun
• Killed mid-sequence, the app comes back on the same clock and offers Resume or Start over rather than deciding for you

ON THE WATCH

• Large MM:SS readout, driven to maximum panel brightness while a race is on screen
• Colour states readable at a glance: navy, amber through the last minute, red through the final ten seconds, green at the gun
• A distinct vibration pattern for every signal, matched to what you hear, so a cue feels the shape it sounds
• Large Sync and Stop targets, one swipe back to the sequence picker

A NOTE ON THE RULES

Mad Cow Race Timer is a training and convenience aid. Under the Racing Rules of Sailing the Race Committee's visual signals are definitive, and sound signals are only for attention. Sail the flags, not the watch.
<!-- /FIELD:full -->

---

## The name decision

**The store name is "Mad Cow Race Timer", and `app_name` in `wear/src/main/res/values/strings.xml`
was changed to match** (owner decision, 2026-08-12, taken while writing this listing).

The name existed outside the repo before it existed inside it. The Android developer verification
row registered 2026-08-03 carries the friendly name **Mad Cow Race Timer**, while the app built as
`Race Timer` and the string "Mad Cow" appeared nowhere in the tree. A registration typed by hand into
a console is a recorded decision — it could not have appeared without someone choosing it — so the
repo was the side that was behind, and the fix runs toward the console rather than away from it.

Three surfaces now agree: the store listing, the launcher label under the watch icon, and the
verification row a Play reviewer cross-checks. Nothing is left to explain to a future reader, which
is the whole reason for choosing "everywhere" over a store-only name.

"Everywhere" was taken at its word. `notification_channel_name` and `notification_content_title`
moved with it, and so did the README's title. The one cost accepted knowingly is that
"Mad Cow Race Timer Running" is a long notification title for a watch; it was raised before the
change rather than discovered after it, and the owner chose consistency over brevity.

The name is **not** propagated into README body prose, the wiki, or Kotlin package names. The
package stays `com.racetimer.wear` — that is the `namespace`, which #169 deliberately left alone
when the `applicationId` moved, and renaming it would break the `-keep class com.racetimer.**`
proguard rule and start renaming frames out of crash reports.

## What this copy deliberately does not claim

Kept here because the next person to edit the listing will be tempted by all three, and the reasons
are not visible from the text itself.

- **No timing figure.** The README and `docs/timing-accuracy.md` carry **±13 ms**, and the #126 run
  measured median 2 ms / max 4 ms across 30 screen-off cues — but those are **median-shaped figures,
  and a store listing is read as a bound**. The same #114 run recorded one cue at `lateMs=66`, with a
  documented `queuedMs=10` ceiling on top, so the defensible bound is roughly **66 ms**, not 13. The
  listing says **sub-second**, which is true with two decimal orders of margin and survives a bad cue.
  This is the same reasoning [#82](https://github.com/SailorDave17/race-timer/issues/82) applies to
  the Play FGS justification; if that story tightens its wording, it does not follow that this one
  should.
- **No sun-legibility claim.** The listing says the app *drives the panel to maximum brightness*,
  which is a mechanism `shared/ScreenPolicy.kt` implements and a test asserts. It does not say the
  screen is readable in direct sun, because the contrast audit that would establish that is
  **unfinished** — [#121](https://github.com/SailorDave17/race-timer/issues/121) is open.
- **No promise you will always hear or feel the gun.** Under Do Not Disturb the watch currently loses
  both channels ([#144](https://github.com/SailorDave17/race-timer/issues/144)), and there is no
  pre-start warning for it ([#96](https://github.com/SailorDave17/race-timer/issues/96)). The copy
  describes the cue mechanism and stops there. Once #144 and #96 close, a line about feeling the gun
  through a sleeve becomes available and would be worth adding.

Nothing in the listing describes a feature on the roadmap rather than in the build: no Tile, no
complication, no phone companion, no named custom presets.
