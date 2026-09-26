# Phone Cue Delivery — Which Conditions Reach the Officer on a Phone

The phone app declares its cue tones `USAGE_ALARM` on `STREAM_ALARM` and its cue buzzes
`VibrationAttributes.USAGE_ALARM` (`PhoneCueAudioProfile`, `PhoneHapticUsagePolicy`). Those
declarations were chosen on documentation alone for #202 and #208, and
[#210](https://github.com/SailorDave17/race-timer/issues/210) measured them on a real phone under
the conditions a race officer's phone is actually in. This file holds the baseline, how it was taken,
and when to take it again.

**The answer, in one line:** every cue is heard and felt in every condition measured **except
total-silence Do Not Disturb, where nothing gets through on either channel**. A declaration that does
get through was found, and deliberately not adopted — see [Total-silence DND](#total-silence-dnd--found-a-way-through-not-taken-it).
[#315](https://github.com/SailorDave17/race-timer/issues/315) owns that decision.

## Baseline — SM-S918U, Android 16 (API 36), 2026-09-25

Build: **`develop @ a4972fe`**, debug APK sha256 `724bead2…`, confirmed installed by hash. Device build
`BP4A.251205.006.S918USQS8FZI1`. One full US Sailing 5-4-1-Go race per row: 30 cues (19 blasts, 10
sync ticks, 1 gun), one variable changed from the normal row.

| Condition | Tones heard (mic) | Buzzes (`dumpsys vibrator_manager`) | Player (`dumpsys audio`) |
|---|---|---|---|
| Normal: ringer normal, DND off | 30 of 30 | 30 of 30 `finished` | `USAGE_ALARM`, `mutedState=none` |
| Vibrate mode | 30 of 30 | 30 of 30 | unmuted |
| Silent mode | 30 of 30 | 30 of 30 | unmuted |
| Another app's music holding `AUDIOFOCUS_GAIN` | 30 of 30 | 30 of 30 | unmuted, never ducked or faded |
| Screen off, on reported battery | 30 of 30 | 30 of 30 | unmuted |
| **Total-silence DND** (`zen_mode=2`) | **0 of 30** | **0 of 30**: 29 `ignored_app_ops`, 1 folded into its neighbour's record | `mutedState=opPlayAudio` throughout |

Two things this settles beyond the counts:

- **Ringer mode does not reach the alarm stream on this phone.** Vibrate and silent both left
  `STREAM_ALARM` unmuted. That is the opposite of the SM-R925U, whose alarm stream is aliased into
  the ringer-affected set (#95, `WearCueAudioProfile`). So the phone does not need the watch's
  reroute to media, and `PhoneCueSounder` keeps it unwired on evidence now.
- **Screen-off is safe as the app runs it.** The phone reached light doze (`mLightState=IDLE`)
  mid-race and every cue still played. The foreground service and wake lock (#203) carry it.

## Total-silence DND — found a way through, not taken it

The same build with three constants changed on a throwaway branch (tones
`USAGE_ASSISTANCE_ACCESSIBILITY` on `STREAM_ACCESSIBILITY`, cue buzzes `USAGE_TOUCH`, APK `bab76f4e…`):

| Condition | Tones heard | Buzzes | Player |
|---|---|---|---|
| Total-silence DND | **30 of 30** | **30 of 30 `finished`** as `TOUCH`, scale 1.00 | `USAGE_ASSISTANCE_ACCESSIBILITY`, `mutedState=none` |

This is the watch's #144 finding reproduced on a phone: DND restricts the accurate class and lets
through the one that is not. `STREAM_MUSIC` is muted under total silence too, so the media route is no
way out. It was **not adopted** in #210 (owner decision, 2026-09-25), because of what it costs here:

- **Touch buzzes are weaker and switchable.** This phone vibrates the alarm class at `HIGH` intensity
  and the touch class at `MEDIUM`. Touch buzzes also follow the touch-feedback setting, which a user
  can turn off entirely. That would silence every cue buzz in *every* condition.
- **Accessibility audio plays at a slider nobody set.** It was 5 of 15, and Samsung's volume panel
  may not show it. The alarm stream is the one #61's loudness was verified on.
- **It has one race behind it.** The other five conditions were not run on it.

## Why a microphone, and how it counts

The app's own frame counter cannot be the audio instrument. It reports a cue delivered at full length
while the mixer mutes it (#95; cairn `android-audiotrack-cue-playback`). `mutedState` in `dumpsys audio`
is the platform's own statement about its mixer gate, and it agreed with the mic in every row above.
The mic is still the reading, because it is the only one that stands outside the phone.

The capture and analysis scripts are in [`harness/cue-delivery/`](../harness/cue-delivery/). Per cue:

- **Its own pitch.** Each voice is read in a band around its own upper partial (`CueWaveform`): blasts
  3700/4000 Hz, sync ticks DTMF 697+1209, the gun DTMF 941+1633.
- **Heard means a line cleared.** The cue's band has to peak above that band's line within the cue's
  window. The window is mapped from the app's `ToneManager: cue` log line through the phone-to-laptop
  clock offset and the mic's start latency: 40 ms on this laptop's Shure MV7, measured on the normal
  race and pinned for the rest.
- **Each race sets its own lines, and tests them.** Phantom cues at +20 s from every real cue, where
  the sequence plays nothing, set each band's line (loudest phantom + 6 dB, never below −55 dB). A
  second, independent set at +27 s must then score **zero** false hits. Every row above passed that.
  The DND row is the other half of the proof: the same reading returns 0 of 30 when nothing plays.

Haptics are read per cue from `dumpsys vibrator_manager`, matched to the cue's own time. Two properties
of that dump matter here. It lists each vibration in up to two sections, so records are de-duplicated
on their timestamp. It also **folds an identical buzz less than 1000 ms after the previous one into a
single record**. In the DND race, one sync tick 999 ms after the last has no record of its own. It is
counted as folded, not as missing and not as delivered.

### What the instrument got wrong first, so the next run does not

1. **A blast-only band read every sync tick as missing.** They are a different pitch, not a quieter
   blast. The first race caught them only because it was loud enough to leak through.
2. **A reading relative to the window before each cue failed its own negative control.** The MV7 gates
   silence to digital zero, so any room sound stands 10 dB clear of −120.
3. **One fixed line failed in the focus race.** The other app's music raised every band and scored 25
   false hits, which is why each race now sets and tests its own lines. The real cues still stood
   5–17 dB clear in their own bands.

## The procedure

Needs the phone on adb over USB with stay-awake on, the laptop mic about 5 cm from the phone's bottom
speaker, and ffmpeg. Write the device's state down first and restore it after (cairn
`borrow-device-state-with-a-persisted-receipt`): zen mode, ringer mode, streams 1–5 and 10.

1. Build and install the commit under test; confirm it by hash (`CLAUDE.md` → *Testing*). Arm the cue
   log with `adb shell setprop log.tag.ToneManager DEBUG`, then force-stop and relaunch the app.
2. Set the condition and **read it back** rather than trusting the set:

   | Condition | Set | Read back |
   |---|---|---|
   | Normal | `cmd audio set-ringer-mode NORMAL`, `cmd notification set_dnd off` | `settings get global mode_ringer` = 2, `zen_mode` = 0 |
   | Vibrate / silent | `cmd audio set-ringer-mode VIBRATE` / `SILENT` | `mode_ringer` = 1 / 0, `zen_mode` still 0 |
   | Total-silence DND | `cmd notification set_dnd on` | `zen_mode` = 2 |
   | Focus held | start a song in a real music app | `dumpsys audio`: `requestAudioFocus() … req=1` from that app, and its player `started` |
   | Screen off | `dumpsys battery unplug`, then the race's hook sends `input keyevent 223` | `dumpsys power`: `mWakefulness=Dozing`; `dumpsys battery reset` after |

   A pushed file is hard to make hold focus. File viewers requested it and never played, and Chrome
   played without requesting it at all. A real music app with a real song is the dependable holder.
   Under total silence DND, **other apps' alarms are disabled too**, so warn the phone's owner.
3. Screenshot, find Start, then `ANDROID_SERIAL=… TAP_Y=… sh harness/cue-delivery/race.sh <dir>`.
4. `python harness/cue-delivery/analyze.py <dir> --shift-ms <latency>`. Pin the latency from a race
   that delivered; the unpinned search says when it has nothing to align to.
5. Restore the device state and disarm the log with `ASSERT`. An empty value is rejected, which leaves
   it armed.

The recordings are room audio. **Keep them out of the repo**, and the raw dumps too: they name every
other app on the phone. Record only the counts.

## When to run it again

- **A `targetSdk` bump** on the phone module, or **an Android / One UI upgrade** on the measured phone.
  The policy being measured is the device's, so it moves while the app is untouched.
- **Any change to what a cue declares.** That means `PhoneCueAudioProfile`, `PhoneHapticUsagePolicy`,
  or the attributes `ToneManager` / `HapticManager` attach. `PhoneCueDeclarationTest` pins the values,
  so a change there is a red build first.
- **#315 landing.** It must re-run all six conditions on its chosen declaration and append the result.

Append a new dated baseline section rather than editing this one, as `dnd-haptics-recheck.md` does, so
a policy that tightens slowly shows up as a trend.

## What this does not cover

- **One phone, one OS version, one race per condition.** A listing sentence is read as a claim about
  every phone the app installs on (`docs/store/listing.md`).
- **Loudness.** The mic says a cue sounded, not that it would carry across a start line. The races ran
  at alarm volume 4 of 15 (races 1–2 at 14 and 9), which was plenty for presence. #61's on-water
  loudness stands as its own evidence.
- **Priority-only DND** (`zen_mode=1`) and the phone's own "alarms only" mode were not run. Total
  silence is the strict end.
- **Deep doze.** The screen-off race reached light doze. A 5-minute race does not reach deep doze
  naturally, and the phone was physically on USB, reporting battery through `dumpsys battery unplug`.
