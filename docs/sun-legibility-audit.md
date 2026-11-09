# Sun-Legibility Audit — Does Each Colour State Read on the Wrist in Direct Sun

The countdown changes background as the gun approaches: navy, then amber inside the last minute,
then a flashing red for the final ten seconds, then green at the gun. Every contrast figure in the
repo was computed indoors (`MessageContrastTest`) or modelled for sunlight (#12, #277). None of them
says whether a sailor can read the watch at a glance in real sun. Only a wrist outdoors can.
[#121](https://github.com/SailorDave17/race-timer/issues/121) asks for that check, with a photograph
of each state, and [#296](https://github.com/SailorDave17/race-timer/issues/296) asks whether #277's
darker amber still reads as amber once the sun washes out the hue.

**The answer so far, in one line:** on the SM-L715U in full early-afternoon sun, navy, the dark
amber, the final-ten red and green all read at a glance without shading, with the palette as it
stands. The amber read clearly as amber, not as a second navy. Green was seen but not photographed;
[#363](https://github.com/SailorDave17/race-timer/issues/363) owns that photograph.

## Session 1 — 2026-11-08

| | |
|---|---|
| Watch | SM-L715U, Android 17 / SDK 37 |
| Build | #328's branch release build, signed with the upload key (`CN=Race Timer Upload`). `versionCode` 3, `versionName` 1.1. APK sha256 `1b0f567c79ec569689a927cc87db977ab789d8d6d6783c51c79e1b6855dbd6f7`. Installed 2026-11-06 23:51:34 EST, built on `f1380bf` before #328's commit `3e99463` (23:59:41) |
| How the build was read | Off the watch over adb on 2026-11-08 at 21:17 EST, after the outing. `lastUpdateTime` was still 2026-11-06 23:51:34, earlier than the photos and unchanged since, so this is the build that was photographed. The commit comes from #328's record ("SM-S948U + SM-L715U release builds", stop test at 23:53), not from the APK |
| Sequence | Scholastic – Race Manager, which counts up after the gun |
| Time | 13:00:32 to 13:03:29 EST, from the photos' own timestamps |
| Light | Outdoors in full sun, hard-edged shadows in every frame. The exposures were 1/1300 to 1/1900 s at f/1.9, ISO 32, which works out to roughly EV 14 at ISO 100: bright daylight. That figure is computed from the camera's metadata, not read from a light meter |
| Crystal | Visibly dusty in every frame |
| Observer | The owner, one person, a wrist-glance judgement without shading the screen |

The build carries everything the audit is about: #277's amber (`#553000`, commit `27a7601`), #12's
final-ten flash on the background rather than the digits (`#2B0000` trough, `65afb23`), and #351's
light-sensor band (`efdc1ea`). Of the two commits on `develop` after `f1380bf` that touch the app,
#328's changes no display file and #302's (`e960cc5`) moves only banner text-fit constants. No banner
was on screen in any frame. So what was photographed is what `develop` draws at `381cc4e`. That is
judged from the diffs, not tested on the device.

### The photographs

All seven are in [`sun-legibility-audit/`](sun-legibility-audit/), in the order taken. They are
resized copies converted to sRGB. The location, the camera and its colour profile were removed
before they were committed, because this repo is public.

| # | File | Taken | Readout | State | Background | What the frame shows |
|---|---|---|---|---|---|---|
| 1 | [`01-navy-2m54.jpg`](sun-legibility-audit/01-navy-2m54.jpg) | 13:00:32 | 2:54 | `RUNNING` | navy `#1A1A2E` | Face fully in sun, no shadow on it. Digits, sequence name, time of day, Sync and Stop all legible through the dust |
| 2 | [`02-navy-2m49.jpg`](sun-legibility-audit/02-navy-2m49.jpg) | 13:00:37 | 2:49 | `RUNNING` | navy | A shadow band across Sync and Stop. Digits in sun |
| 3 | [`03-navy-1m26.jpg`](sun-legibility-audit/03-navy-1m26.jpg) | 13:02:00 | 1:26 | `RUNNING` | navy | Close-up. A thin shadow line across Sync. Digits in sun. The navy control for frame 4 |
| 4 | [`04-amber-0m24.jpg`](sun-legibility-audit/04-amber-0m24.jpg) | 13:03:02 | 0:24 | `RUNNING`, last minute | amber `#553000` | Reads brown-amber, plainly different from frame 3's navy 62 s earlier in the same light. Shadow lines cross the label and the buttons, not the digits |
| 5 | [`05-final-ten-0m10.jpg`](sun-legibility-audit/05-final-ten-0m10.jpg) | 13:03:17 | 0:10 | `RUNNING`, final ten | red `#7B0000`, flashing | Railing shadows cross the face in bands, one across the top of the digits. The digits read in both the sunlit and the shaded parts |
| 6 | [`06-final-ten-flash-0m03.jpg`](sun-legibility-audit/06-final-ten-flash-0m03.jpg) | 13:03:24 | 0:03 | `RUNNING`, final ten | red, toward the `#2B0000` trough | Darker red than frame 5, which fits the pulse caught toward its trough. A photo cannot say where in the 400 ms pulse it landed. A shadow band crosses the digits, and they stay full white and legible |
| 7 | [`07-count-up-navy-0m02.jpg`](sun-legibility-audit/07-count-up-navy-0m02.jpg) | 13:03:29 | 0:02 | `COUNTING_UP` | navy | Two seconds after the gun. This sequence counts up on navy with End Race, so it never shows green at the gun |

### What the owner observed

Reported to the session on 2026-11-08, after the outing:

- **Every state read at a glance without shading the screen**: navy, amber, the final-ten red and
  green. Green was seen, not photographed.
- **The one-minute amber read clearly as amber**, "inside a minute", not as a second navy (#296's
  first criterion).
- **The digits on the amber were at least as legible as on navy** at the same glance (#296's second
  criterion). The model on #296 predicted that: white on the new amber 1.70 : 1 in sun, against
  navy's 1.74.

This is a report, not an instrumented run: one observer, one watch, one day. "Can a sailor read it
at a glance" has no objective proxy, so a subjective judgement is the right instrument. The
photographs show what the observer was looking at, not what they saw.

## What session 1 settles

- **#121: the palette needs no further change for sun.** Its last criterion asked for `Theme.kt`
  to be adjusted and re-tested, or for the audit to record explicitly that every state passed
  unchanged. Both halves happened, on different states. **Amber was adjusted** (#277, `#A0660A` to
  `#553000`) and is re-tested here in sun. **Navy, red and green passed unchanged**: their values
  are the MVP's (`436fba6`), moved but never altered. The final-ten flash was moved off the digits
  in #12 (PR #278), and frames 5 and 6 show the digits holding full white through it.
- **The backgrounds were never in `Theme.kt`.** At the MVP they were private values in
  `wear/…/ui/TimerScreen.kt`, and since #123 they live in `shared/MessageContrast.kt`. #121 was
  filed on the measurement that `Theme.kt` had not changed since the MVP. That was true, and it said
  nothing about the backgrounds either way.
- **#296: the darker amber keeps its meaning in sun**, and its digits read at least as well as
  navy's. No retune is owed. The brighter `#6B3A00` costed on #277 is not needed.
- **The dusty crystal.** #277's lever table ranks a clean crystal as the biggest gain available
  (2.46×). This crystal was visibly dusty in every frame and every state still read. So the
  palette passed with that lever in its unfavourable position, not its favourable one.

## What it does not settle

- **A photograph of green.** [#363](https://github.com/SailorDave17/race-timer/issues/363). End
  Race on a Race Manager sequence holds `RACE_ENDED`'s green until Stop, which is the easy frame.
- **Which brightness arm was on.** In direct sun the light sensor almost certainly read above
  #351's 3800 lux release, so automatic brightness was most likely driving the panel, not the
  forced 600 nits. Nothing read `dumpsys display` during the outing, so that is reasoned. Comparing
  the two arms in sun is [#349](https://github.com/SailorDave17/race-timer/issues/349).
- **The SM-R925U.** Only the SM-L715U was photographed. The two panels' brightness curves differ
  (`ScreenPolicy.kt`), so a pass on one watch is not a pass on the other.
- **The Tier 3 re-sync prompt.** A comment on #121 suggested photographing it in sun, since it is
  the one message that can be on screen during a running race. It was not on screen here. Its
  contrast is computed (8.53 : 1 on every reachable background, #123 and PR #146, in
  [`message-surface.md`](message-surface.md)) and has not been seen in sun.
- **Phone screens.** The phone's committee-boat record is
  [`committee-boat-observation.md`](committee-boat-observation.md).

## When a session stops counting

A session is evidence about the build on the wrist, not about the app. When a later change touches
what a session observed, mark it invalidated here, naming the change, and add a new session rather
than carrying the old one forward. The surfaces:

| Axis | Surfaces |
|---|---|
| Colour and digits | `shared/src/main/kotlin/com/racetimer/shared/MessageContrast.kt` (every background, and which state picks which); `wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt` (the flash, the digits) and `ui/Theme.kt` |
| Brightness | `shared/src/main/kotlin/com/racetimer/shared/ScreenPolicy.kt` (the override and its light-sensor band) |

An OS upgrade on the watch, or a different watch, also invalidates a session, because the panel is
the watch's, not the app's.

To check session 1:

```
git log --oneline 381cc4e..origin/develop -- shared/src/main/kotlin/com/racetimer/shared/MessageContrast.kt shared/src/main/kotlin/com/racetimer/shared/ScreenPolicy.kt wear/src/main/kotlin/com/racetimer/wear/ui/TimerScreen.kt wear/src/main/kotlin/com/racetimer/wear/ui/Theme.kt
```

Each commit it prints is a candidate. A change to a colour, the digits' size or weight, the flash,
or the brightness rule invalidates the session. A change that provably draws the same does not.
Nothing enforces this. No check reads this file when a surface changes.

## How to take a session

1. **Before going out, put the watch on adb and read its build.** `adb shell dumpsys package
   io.github.sailordave17.racetimer` for `versionCode` and `lastUpdateTime`, then pull the APK
   (`adb shell pm path …`) and hash it. A worktree build names no commit, so take the commit from
   the session that installed it.
2. **Pick a sequence that reaches every state you need.** A Race Manager sequence counts up on
   navy after the gun, so green needs End Race. A sequence that does not count up shows green only
   for the few seconds of the GO! linger.
3. **Photograph each state as it arrives, digits in direct sun.** Note any shadow across the face.
4. **Glance, unshaded, at each state, and record what read and what did not.**
5. **If adb is on during the sequence, read `dumpsys display`** (`lux`, `nits`, the override) so
   the session says which brightness arm was on. #349 has the commands.
6. **Strip the photos before committing them.** This repo is public, and phone photos carry GPS.
   Resize, re-encode from the pixels alone, convert to sRGB, and check the result carries no EXIF,
   XMP or colour profile.
