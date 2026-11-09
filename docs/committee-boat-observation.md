# Committee-Boat Observation — Does the Console Clock Read and Sound on a Real Boat

The phone app's bar ([epic #196](https://github.com/SailorDave17/race-timer/issues/196)) claims a
clock *readable across a committee boat at arm's length* and *audible over an outboard*. Every test in
the repo checks a mechanism behind those claims: the window flags (#199), the launch choice (#225),
the scheduled cues (#202), and delivery under the phone's ringer and Do Not Disturb states (#210,
[`phone-cue-delivery.md`](phone-cue-delivery.md)). None of them can check the claims themselves. Only
someone standing on a committee boat can.
[#215](https://github.com/SailorDave17/race-timer/issues/215) is where that observation started. This
file holds each session, what it was taken against, and when a session stops counting.

**The answer so far, in one line:** the countdown read from farther than arm's length in daylight
**with full brightness off**. The cues were heard, **but only with the engine off or idling**, so the
bar's audibility claim is still unobserved.
[#361](https://github.com/SailorDave17/race-timer/issues/361) owns the rest.

## Observations

One row per session. Append a row. Never edit one, except to mark it invalidated
([below](#when-a-row-stops-counting)).

| # | Reported | Device | Build | Launch choices | Conditions | Display | Audio |
|---|---|---|---|---|---|---|---|
| 1 | 2026-11-08 | SM-S948U, Android 17 | **Not read.** The repo's device notes record an upload-key-signed v1.1 on it | screen-on **on**, full brightness **off**: the launch defaults (#225) | Daylight, sun or overcast not recorded. On the committee-boat console, read from farther than arm's length. Engine off or idling | "Very visible" from farther than arm's length | Cues heard. **Engine off or idling**, so not the bar's condition |

Row 1 is the owner's report to the session, not an instrumented run: no light reading, no distance in
metres, no cue volume. The day of the session was not stated beyond its being reported on 2026-11-08.

### Row 1's build, and why its surfaces still match `develop`

The phone was not on adb, so its build was not read. That was the owner's choice at #215's pickup.
What bounds it is reasoned from the record, not read off the device:

- The last install the issue record names on the SM-S948U is #328's branch release build, on
  2026-11-06: #328's stop test ran "on SM-S948U + SM-L715U release builds". #304 installed a release
  build on it earlier the same day.
- No commit on `develop` after #328's (`3e99463`) touches any surface in the table
  [below](#when-a-row-stops-counting). On 2026-11-08, `git log 3e99463..origin/develop -- <surfaces>`
  printed nothing for the display, brightness or audio surfaces. The last display change was #304's
  time-of-day line (`de39c94`).

So if the phone still carried either of those builds, what was seen is what `develop` draws at
`ee4df0a`. That is reasoned. Whether anything else was installed in between is not established.

## What row 1 settles, and what it does not

**It settles** that at the launch defaults the system's own brightness, with no override, read from
beyond arm's length in daylight on the SM-S948U. That discharges
[#197](https://github.com/SailorDave17/race-timer/issues/197)'s committee-boat criterion, which asks
for exactly that and names no brightness.

**It is a finding for [#216](https://github.com/SailorDave17/race-timer/issues/216).** In that light
the full-brightness override was not needed to read the clock. That is one session in unrecorded
light, so it does not show the override is never needed: direct sun is the case that would. But it is
the first evidence that an officer could run a day at the defaults, and #216 prices the override
against a full battery day. #215's own discussion named this outcome in advance as a real finding.

**It does not settle:**

- **Cues over a running outboard.** Heard with the engine off or idling is not the bar's condition.
- **Full brightness on the water**, #199's last criterion. It was off.
- **Which reads better**, the override or the system's own brightness. That needs both in the same
  light; see step 5 of the method.
- **The build.** Row 1 cannot be tied to a commit.

## How to take an observation

1. **Before leaving the dock, put the phone on adb and read its build.**
   `adb shell dumpsys package io.github.sailordave17.racetimer` gives `versionCode` and
   `lastUpdateTime`. Then `adb shell pm path io.github.sailordave17.racetimer`, `adb pull` that path,
   and take the APK's sha256. A build made in a worktree carries no commit:
   `META-INF/version-control-info.textproto` reads `NO_VALID_GIT_FOUND`. So name the commit from the
   session that installed it, and record the hash beside it.
2. **Record both launch choices** (#225). They are presented on every cold launch and persist
   nowhere, so the choice in force is whatever was tapped that morning.
3. **Record the conditions:** sun or overcast, the distance read from, the engine (off, idling, or
   running in gear), and the alarm-stream volume the cues play on (#210).
4. **Watch and listen through a full sequence.**
5. **Take the free control arm when you can.** Run a sequence with full brightness off, close the
   app, relaunch with it on, and run it again: the same panel, light and boat, a minute apart. The
   launch surface must reappear on the relaunch. If it does not, the process survived and the choice
   is unchanged. The shipped app is its own A/B rig, which is how the watch's sun question was
   settled on 2026-08-03.

## When a row stops counting

A row is evidence about the build that was in front of the observer, not about the app. **When a
later change touches a surface a row observed, mark the row invalidated, naming the change, rather
than carrying it forward** (#215). Do not delete it. What was seen stays visible, and a new row
replaces it.

| Axis | Surfaces |
|---|---|
| Display | `phone/src/main/kotlin/com/racetimer/phone/ui/TimerScreen.kt`, `PhoneReadout.kt`, `PhoneTheme.kt` and `TimeOfDayText.kt` in the same directory; `shared/src/main/kotlin/com/racetimer/shared/MessageContrast.kt` and `CountdownFormat.kt` |
| Brightness | `phone/src/main/kotlin/com/racetimer/phone/DisplayChoice.kt`, `PhoneDisplay.kt`, and `ui/DisplayChoiceScreen.kt` |
| Audio | `phone/src/main/kotlin/com/racetimer/phone/PhoneCueAudioProfile.kt` and `PhoneCueSounder.kt`; `shared-android/src/main/kotlin/com/racetimer/android/ToneManager.kt`; `shared/src/main/kotlin/com/racetimer/shared/CueWaveform.kt` |

`MessageContrast.kt` is on the list because it holds every colour the countdown draws, the per-state
backgrounds included, and they are the watch's colours. So a colour change made for the watch
invalidates a phone row too, as #277's darker amber would have.

On every axis, **an OS upgrade on the observed device, or a different device**, also invalidates the
row: the panel and the speaker are the phone's, not the app's.

To check a row that has a build commit:

```
git log --oneline <the row's commit>..origin/develop -- <that axis's surfaces>
```

Each commit it prints is a candidate. A change to what is drawn or sounded (size, layout, colour,
brightness, waveform, volume or audio attributes) invalidates the row on that axis. A change that
provably draws and sounds the same, such as a rename or a test-only edit, does not. Say in the row
which commits were judged, either way.

A row without a commit, like row 1, cannot be checked that way. Read it against `ee4df0a` as its
build section says, and treat any change to its surfaces merged after 2026-11-08 as invalidating it.

## What this does not cover

- **One phone so far.** A row is about its device, and a listing sentence is read as a claim about
  every phone (`docs/store/listing.md`).
- **Loudness as a number.** A row records whether the cues were heard, at a stated volume. #61 is the
  watch's on-water loudness. Nothing has measured the phone's.
- **Battery.** That is #216's, in [`start-day-battery.md`](start-day-battery.md).
- **Nothing enforces the invalidation rule.** No check reads this file when a surface changes. The
  rule holds only if the story that changes a surface reads this section.
