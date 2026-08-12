---
description: >-
  Privacy policy for Mad Cow Race Timer, a standalone Wear OS sailing start-sequence timer.
  The app collects no personal data, has no network access, and stores nothing off the device.
---

# Privacy Policy — Mad Cow Race Timer

**Effective date:** 12 August 2026
**Applies to:** Mad Cow Race Timer for Wear OS (`io.github.sailordave17.racetimer`), referred to
below as *Race Timer*

## Summary

Race Timer does not collect, transmit, or share any personal data. It has no network access, no
analytics, no advertising, and no user accounts. Everything the app stores stays on your watch.

## Information we collect

**None.**

Race Timer does not collect personal information, usage analytics, crash telemetry, device
identifiers, contacts, location, or health and fitness data. There is no account to create and no
sign-in.

The app does not request the `INTERNET` permission, so it is not technically capable of sending
information anywhere.

## Information stored on your device

Race Timer saves a small amount of data locally so that a race in progress survives the app being
closed or the watch restarting mid-sequence, and so that your choices are still there next time you
open it. This data never leaves the watch.

Stored in the app's private storage (`race_timer_state`):

| What | Why |
|---|---|
| The identifier of the selected start sequence | So a restored race resumes at its own duration and cadence |
| The scheduled gun time, as a monotonic clock reading | So the countdown resumes at the correct remaining time |
| The scheduled gun time, as a wall-clock reading | Best-effort recovery after a device restart, when the monotonic reading is no longer valid |
| The monotonic clock reading at the moment the race was saved | To detect a restart and fall back to the wall-clock value |
| The start sequence you last chose | So the app opens on the sequence you actually use, instead of resetting each time |
| The lead-in time a race was last armed with | So the same lead-in is offered next time, rather than being re-entered every race |
| Which audio stream a running race raised the volume on | So the app knows which stream to put back when the race ends |
| That stream's volume before the race raised it | So your original volume is restored rather than left turned up |

This is timing state and your own settings. It contains nothing about you, and nothing that
identifies you or your watch — the values above are clock readings, a sequence identifier, a number
of seconds, an audio stream index and a volume level.

This data is removed when you uninstall the app, or when you clear the app's storage through the
watch's system settings.

## Permissions and why they are used

| Permission | Why Race Timer needs it |
|---|---|
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` | To keep the start sequence running accurately while the screen is off, so the horn and vibration cues still fire at the right moment |
| `WAKE_LOCK` | To hold the CPU awake for the duration of a running sequence, so cue timing does not drift while the watch is idle. The lock is sized to the remaining race and released when the sequence ends |
| `POST_NOTIFICATIONS` | To show the ongoing-activity notification that Wear OS requires for a running foreground service, and which lets you return to the running race |
| `VIBRATE` | To deliver the haptic signals for each race cue |

Race Timer does not request location, microphone, camera, contacts, storage, body sensors, or any
health or fitness permission.

## Sharing

Race Timer does not share data with anyone, because it does not collect any. There are no third-party
SDKs in the app, no advertising networks, no analytics providers, and no crash-reporting services.

## Children

Race Timer is a general-purpose sports utility and is not directed at children. Because it collects
no data at all, it collects no data from children.

## Security

The data described above is held in the app's private, sandboxed storage, which the Android operating
system isolates from other apps on the device. Because nothing is transmitted, there is no data in
transit to protect.

## Changes to this policy

If Race Timer's behaviour changes in a way that affects this policy — for example if a future version
gains network access or an optional account — this policy will be updated before that version is
published, and the effective date above will change.

## Contact

Questions about this policy can be sent to **hsc.coach@gmail.com**.

---
