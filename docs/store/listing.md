# Play Store listing copy

The three text fields Play asks for, held here so the next release **edits a file rather than
retyping into a web form**. Entered in Play Console by [#79](https://github.com/SailorDave17/race-timer/issues/79);
this document is the source, the Console is the copy.

**This file now holds two revisions, and only one of them is in Console.**

| Section | Status |
|---|---|
| *App name*, *Short description*, *Full description* | **Live in Console.** Watch-only, correct for the build on the internal track |
| *Draft — the two-form-factor revision* | **Not in Console.** Written by [#212](https://github.com/SailorDave17/race-timer/issues/212) for the phone upload ([#214](https://github.com/SailorDave17/race-timer/issues/214)) to paste |

The live fields are deliberately **not** overwritten: the watch app is on the internal track now
and its listing describes it correctly, so replacing the copy before the phone artifact exists
would make this document disagree with Console for as long as the upload takes. The draft replaces
them in one action at #214, and this table is what says which is which.

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

# Draft — the two-form-factor revision

**Not in Console.** Paste at [#214](https://github.com/SailorDave17/race-timer/issues/214), after
the *Before pasting* checks below.

**App name is unchanged** — *Mad Cow Race Timer*. Only the two descriptions move, so there is no
draft name field and nothing to re-check against the developer-verification row.

## What this draft leads with, and what it deliberately does not

It leads with **the two form factors** — one app that runs standalone on a watch *and* standalone
on a phone — with the watch-standalone claim kept as the secondary truth it has always been.

It says **nothing about the two devices talking to each other**. That is the epic's signature
moment and it is [#219](https://github.com/SailorDave17/race-timer/issues/219)–[#223](https://github.com/SailorDave17/race-timer/issues/223),
none of which is built; the two apps do not exchange anything today. Putting it in the listing
would break this document's own standing rule — *nothing in the listing describes a feature on the
roadmap rather than in the build* — which is the same rule that keeps the sun-legibility and
timing-figure claims out. When the link ships, the listing gets a third revision and that is where
the pair belongs. *(Owner decision, 2026-08-17, taken against the alternative reading of #212's
AC 4.)*

## Before pasting — three claims that are contingent

This draft is written for the state the epic is being held for: a phone app coherent enough to
ship. Three of its lines are **not true of the phone as of 2026-08-17** and become true only when
their stories land. Check each against the build being uploaded, and cut the line if it has not.

| Line in the copy | True only after |
|---|---|
| *FOR THE RACE COMMITTEE* naming the phone at all — count-up past the gun, End Race | [#206](https://github.com/SailorDave17/race-timer/issues/206) |
| the two-stage signal-box lead-in on the phone | [#207](https://github.com/SailorDave17/race-timer/issues/207) |
| *a distinct vibration for every signal* being said of **both** devices | [#208](https://github.com/SailorDave17/race-timer/issues/208) |

Everything else in the draft is true of the phone today: the countdown, the cue audio, cueing with
the screen off, Sync, Custom, restore-after-kill and the officer's screen choice have all landed.

## Short description (draft)

<!-- FIELD:short-draft -->
Sailing start-sequence timer. Runs standalone on your watch and on your phone.
<!-- /FIELD:short-draft -->

## Full description (draft)

<!-- FIELD:full-draft -->
Mad Cow Race Timer runs the sailing start sequence on your wrist and on your phone. Two standalone apps, one download, the same clock. Wear it on the water, prop it on the committee boat, or both — neither device needs the other, and neither needs a signal.

ON THE WATCH

Standalone on Wear OS. No phone, no pairing, nothing else to carry on the water.

ON THE PHONE

The same countdown at committee-boat size, propped on the console where everyone aboard can read it. A sailor with no watch gets the complete timer, not a cut-down version of one.

SEQUENCES SAILORS ACTUALLY USE

• US Sailing 5-4-1-Go (RRS 26) — 5:00
• Scholastic / ICSA — 3:00
• Club 3-2-1-Go — 3:00
• Custom — any whole number of minutes, from 1:00

US Sailing sounds long above the minute and short below it: a long blast is a signal the committee is sounding, a short one is your wrist counting. Every sequence shares the same final five seconds, doubled from 0:05 to 0:01, so you never have to remember which sequence is loaded to know what the last five mean.

FOR THE RACE COMMITTEE

US Sailing — Race Manager and Scholastic — Race Manager are the committee side of those sequences, not a re-skin:

• Voiced for someone sounding the signals rather than counting them
• The gun is not the end. The clock keeps running as an elapsed-time race clock, up to H:MM:SS,
until you tap End Race, which freezes the finish time on screen
• A two-stage signal-box lead-in, so the timer and an external signal box start in step. Set the
box's own alert window: none, 15 s, 60 s, or any value you dial from 5 s to 2:00

SYNC

Tap Sync during the countdown to snap to a whole minute. That absorbs the lag between the Race Committee's flag reaching the top of the staff and your thumb landing on the screen.

BUILT TO BE TRUSTED WITH A START

• Cues are scheduled against a monotonic clock rather than sampled by a tick loop, so they land
sub-second on real hardware — measured, not estimated
• The countdown survives the screen going dark and the app going to the background, held by a
foreground service
• The clock is anchored to elapsed time, so a network time correction or a time-zone change cannot
move your gun
• Killed mid-sequence, the app comes back on the same clock and offers Resume or Start over rather
than deciding for you

ON SCREEN

• Large MM:SS readout, driven bright while a race is on
• Colour states readable at a glance: navy, amber through the last minute, red through the final
ten seconds, green at the gun
• A distinct vibration pattern for every signal, matched to what you hear, so a cue feels the shape
it sounds
• Large Sync and Stop targets, one swipe or tap back to the sequence picker

A NOTE ON THE RULES

Mad Cow Race Timer is a training and convenience aid. Under the Racing Rules of Sailing the Race Committee's visual signals are definitive, and sound signals are only for attention. Sail the flags, not the watch.
<!-- /FIELD:full-draft -->

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

- **No timing figure.** The figures now live in `docs/timing-accuracy.md`: measured 2026-08-18 across
  150 cues in five full sequences, dispatch is **median 2 ms, worst 61 ms**, and tone onset reaches
  **101 ms** including the deliberate 40 ms lead-in. Those are real worst cases rather than the
  median-shaped **±13 ms** this bullet used to cite — but a store listing is still read as a **bound**,
  by a sailor, on one device they have not bought yet. The listing says **sub-second**, which is true
  with an order of margin over the worst cue ever measured here and survives a bad one.

  **[#82](https://github.com/SailorDave17/race-timer/issues/82) has since tightened the Play FGS
  justification to explicit numbers (100 ms dispatch / 150 ms tone), and that does *not* propagate
  here** — settled, not still open. The two documents have different readers and different failure
  costs: a reviewer can check a declaration against a measurement, and a disappointed sailor cannot be
  argued out of a number on a store page. This bullet previously stated that independence as a
  prediction (*"if that story tightens its wording, it does not follow that this one should"*); it is
  recorded as an outcome now, because a condition that has fired stops being a guess.
- **No sun-legibility claim.** The listing says the app *drives the panel to maximum brightness*,
  which is a mechanism `shared/ScreenPolicy.kt` implements and a test asserts. It does not say the
  screen is readable in direct sun, because the contrast audit that would establish that is
  **unfinished** — [#121](https://github.com/SailorDave17/race-timer/issues/121) is open.
- **No promise you will always hear or feel the gun.** Under Do Not Disturb the tones are silent —
  `setStreamVolume` is refused, and the watch says so during the race, since
  [#96](https://github.com/SailorDave17/race-timer/issues/96) shipped the Tier 3 line *"Do Not
  Disturb — cues silent, wrist still buzzing"*. The wrist is the channel that survives: cues are
  declared `USAGE_TOUCH`, and **30 of 30 were delivered at `zen_mode=2`, the 3000 ms gun included**
  ([#144](https://github.com/SailorDave17/race-timer/issues/144) →
  [#187](https://github.com/SailorDave17/race-timer/pull/187)). The copy still describes the cue
  mechanism and stops there.

  **The trigger fired, and the line was declined** — owner decision, 2026-08-17, taken on
  [#236](https://github.com/SailorDave17/race-timer/issues/236). This bullet used to say that once
  #144 and #96 closed, *"a line about feeling the gun through a sleeve becomes available and would be
  worth adding"*. Both closed; the line is still not being added, for three reasons the reversal did
  not touch:

  - **The delivery is a dependency, not a property.** Every cue reaching the wrist under DND rests on
    this platform continuing to permit the feedback class — the declaration is a deliberate mislabel,
    and `USAGE_ALARM`, the honest class, measured **0 of 30**. Nothing CI runs can reach a `vibrate`
    call, so if the policy moves, the promise goes false with no error and no failing build. The check
    is manual: [`dnd-haptics-recheck.md`](../dnd-haptics-recheck.md)
    ([#186](https://github.com/SailorDave17/race-timer/issues/186)).
  - **One watch, one OS version.** The baseline is an SM-R925U on API 36. A listing sentence is read
    as a claim about every device it installs on — the same reason the timing figure stays out, where
    a median-shaped number would be read as a bound.
  - **The draft revision above is two-form-factor, and the phone has no haptic path at all.**
    [#208](https://github.com/SailorDave17/race-timer/issues/208) is unbuilt, so a shared line would
    over-claim on the device it was never measured on.

  What would make it available: a re-check that holds across a `targetSdk` bump and a platform
  upgrade, or arm 3 retiring the mislabel — plus the phone's haptics landing, if the line is to sit in
  copy that describes both.

Nothing in the listing describes a feature on the roadmap rather than in the build: no Tile, no
complication, no phone companion, no named custom presets.
