---
description: >-
  Privacy policy for Mad Cow Race Timer, a sailing start-sequence timer that runs standalone on
  Wear OS watches and on Android phones. The app collects no personal data, has no network
  access, and transmits nothing.
---

# Privacy Policy — Mad Cow Race Timer

**Effective date:** 17 August 2026
**Applies to:** Mad Cow Race Timer (`io.github.sailordave17.racetimer`) — both the **Wear OS watch
app** and the **Android phone app**, which ship under one Play listing. Referred to together below
as *Race Timer*, and distinguished as *the watch app* and *the phone app* wherever they differ.

## Summary

Race Timer does not collect, transmit, or share any personal data. It has no network access, no
analytics, no advertising, and no user accounts. What it stores is timing state and your own
settings, held in the app's private storage on the device you are using.

## Information we collect

**None.**

Race Timer does not collect personal information, usage analytics, crash telemetry, device
identifiers, contacts, location, or health and fitness data. There is no account to create and no
sign-in.

Neither the watch app nor the phone app requests the `INTERNET` permission, so neither is
technically capable of sending information anywhere.

## Information stored on your device

Race Timer saves a small amount of data locally so that a race in progress survives the app being
closed or the device restarting mid-sequence, and so that your choices are still there next time
you open it.

Each app keeps its own file in its own private storage, and they are not shared between devices:
`race_timer_state` on the watch, `phone_race_state` on the phone. The **Stored on** column says
which app keeps each value — the phone app does not yet offer the signal-box lead-in, and does not
adjust or restore a device volume, so it stores nothing for either.

| What | Why | Stored on |
|---|---|---|
| The identifier of the selected start sequence | So a restored race resumes at its own duration and cadence | Watch and phone |
| The scheduled gun time, as a monotonic clock reading | So the countdown resumes at the correct remaining time | Watch and phone |
| The scheduled gun time, as a wall-clock reading | Best-effort recovery after a device restart, when the monotonic reading is no longer valid | Watch and phone |
| The monotonic clock reading at the moment the race was saved | To detect a restart and fall back to the wall-clock value | Watch and phone |
| The start sequence you last chose | So the app opens on the sequence you actually use, instead of resetting each time | Watch and phone |
| The lead-in time a race was last armed with | So the same lead-in is offered next time, rather than being re-entered every race | Watch only |
| Which audio stream a running race raised the volume on | So the app knows which stream to put back when the race ends | Watch only |
| That stream's volume before the race raised it | So your original volume is restored rather than left turned up | Watch only |

This is timing state and your own settings. It contains nothing about you, and nothing that
identifies you or your device — the values above are clock readings, a sequence identifier, a
number of seconds, an audio stream index and a volume level. Neither app has any text input, so
there is nothing you could type into either one.

This data is removed when you uninstall the app, or when you clear the app's storage through the
device's system settings.

### Device backup

The **phone app** disables Android's Auto Backup, so nothing it stores is included in any backup.

The **watch app** leaves Auto Backup enabled. That means the values in the table above are eligible
to be included in the backup Android itself keeps in your Google account — so on the watch, this is
the one route by which stored data can leave the device, and it is worth stating plainly rather
than leaving to be inferred:

- The backup is made **by the operating system, not by Race Timer**. The app does not start it,
  cannot read it, and is not told when it happens.
- It is **end-to-end encrypted** with your device's PIN, pattern or password on every Android
  version this app runs on.
- Nothing in it identifies you. The values are the ones listed above and nothing else.

Auto Backup is what lets your sequence preference follow you to a replacement watch. Turning it off
for the whole device is a setting Android gives you, and Race Timer works the same either way.

## Permissions and why they are used

| Permission | Why Race Timer needs it | Requested by |
|---|---|---|
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` | To keep the start sequence running accurately while the screen is off, so the horn and vibration cues still fire at the right moment | Both apps |
| `WAKE_LOCK` | To hold the CPU awake for the duration of a running sequence, so cue timing does not drift while the device is idle. The lock is sized to the remaining race and released when the sequence ends | Both apps |
| `POST_NOTIFICATIONS` | To show the ongoing-activity notification Android requires for a running foreground service, and which lets you return to the running race | Both apps |
| `VIBRATE` | To deliver the haptic signals for each race cue | Watch app only |

Neither app requests location, microphone, camera, contacts, storage, body sensors, or any health
or fitness permission. Adding the phone app introduced **no permission the watch app did not
already request** — the phone's list is the table above without `VIBRATE`.

## Sharing

Race Timer does not share data with anyone, because it does not collect any. There are no third-party
SDKs in the app, no advertising networks, no analytics providers, and no crash-reporting services.

## Children

Race Timer is a general-purpose sports utility and is not directed at children. Because it collects
no data at all, it collects no data from children.

## Security

The data described above is held in the app's private, sandboxed storage, which the Android
operating system isolates from other apps on the device. Because the app transmits nothing, there
is no data in transit to protect; the device backup described above is encrypted by Android before
it leaves the watch.

## Changes to this policy

If Race Timer's behaviour changes in a way that affects this policy — for example if a future version
gains network access or an optional account — this policy will be updated before that version is
published, and the effective date above will change.

## Contact

Questions about this policy can be sent to **hsc.coach@gmail.com**.

---
