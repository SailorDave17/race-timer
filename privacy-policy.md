---
description: >-
  Privacy policy for Mad Cow Race Timer, a sailing start-sequence timer that runs standalone on
  Wear OS watches and on Android phones. The app collects no personal data, has no network
  access, and transmits nothing.
---

# Privacy Policy — Mad Cow Race Timer

**Effective date:** 29 September 2026
**Applies to:** Mad Cow Race Timer (`io.github.sailordave17.racetimer`) — both the **Wear OS watch
app** and the **Android phone app**, which ship under one Play listing. Referred to together below
as *Race Timer*, and distinguished as *the watch app* and *the phone app* wherever they differ.

## Summary

Race Timer does not collect or share any personal data. It has no analytics, no advertising, and no
user accounts, and it does not request network access. What it stores is timing state and your own
settings, held in the app's private storage on the device you are using.

If you run it on a Wear OS watch and an Android phone that are paired with each other, the two apps
exchange clock readings, tell each other when a race is started, synced or ended, and share what
their start screens are set to, over the direct connection between your two devices, so that both
run the same race to the same gun. That is the only thing either app sends anywhere, and it goes only
to your own other device. It is described in full under *Clock readings and race controls exchanged
with your paired device*.

## Information we collect

**None.**

Race Timer does not collect personal information, usage analytics, crash telemetry, device
identifiers, contacts, location, or health and fitness data. There is no account to create and no
sign-in.

Neither the watch app nor the phone app requests the `INTERNET` permission, and neither sends
anything to the developer or to any server. The one exchange between the two apps is described
under *Clock readings and race controls exchanged with your paired device*, and it passes only between
your own devices.

## Information stored on your device

Race Timer saves a small amount of data locally so that a race in progress survives the app being
closed or the device restarting mid-sequence, and so that your choices are still there next time
you open it.

Each app keeps its own file in its own private storage, and they are not shared between devices:
`race_timer_state` on the watch, `phone_race_state` on the phone. The **Stored on** column says
which app keeps each value — the phone app does not adjust or restore a device volume, so it stores
nothing for that.

| What | Why | Stored on |
|---|---|---|
| The identifier of the selected start sequence | So a restored race resumes at its own duration and cadence | Watch and phone |
| The scheduled gun time, as a monotonic clock reading | So the countdown resumes at the correct remaining time | Watch and phone |
| The scheduled gun time, as a wall-clock reading | Best-effort recovery after a device restart, when the monotonic reading is no longer valid | Watch and phone |
| The monotonic clock reading at the moment the race was saved | To detect a restart and fall back to the wall-clock value | Watch and phone |
| The start sequence you last chose | So the app opens on the sequence you actually use, instead of resetting each time | Watch and phone |
| The lead-in time a race was last armed with | So the same lead-in is offered next time, rather than being re-entered every race | Watch and phone |
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

## Clock readings and race controls exchanged with your paired device

When the watch app and the phone app are both running, on a watch and a phone that are paired with
each other, each app asks the other what its clock reads, several times a minute while the app is on
screen and from the moment a race is started until its gun, and answers the other's questions
whenever it is running. Comparing the answers is how the two devices agree on when the gun is, to
within a fraction of a second.

When you start, sync or end a race on either device, it tells the other one, so that both run the
same race to the same gun and end it at the same time. When the two devices come back within reach
of each other during a race, each tells the other which race it is running, so that a start or an
end one of them missed while they were apart is caught up and the two guns are brought back
together. And when you choose a start sequence, a custom length or a lead-in alert on either
device's start screen, the other one's start screen follows.

This is everything that is exchanged:

| What | Why |
|---|---|
| Readings of each device's own clock: how long that device has been running since it last started, in milliseconds | To work out how far apart the two clocks are, so that both count down to the same gun |
| A random number pairing each question with its answer | So that an answer is matched to the question it belongs to, and a stale one is ignored |
| When a race is started: which start sequence it runs (for example *US Sailing 5-4-1-Go*, or a custom length) | So the other device runs the same race |
| When a race is started: when its gun is, and when Start was tapped, as readings of the devices' clocks | To place the gun on the other device's clock, and to decide which start counts when both devices are started at nearly the same moment |
| When a race is started: a random number identifying that race | So a race sent twice is recognised as the same race, and so a later sync or end is applied only to the race it belongs to |
| When a race is synced: the race's new gun, and when Sync was tapped, as readings of the devices' clocks, with a random number | To move the other device's gun to the same moment, and to decide which sync counts when both devices are synced at nearly the same moment |
| When a race is ended: how long it ran, in milliseconds | So both devices show the same final race time |
| When a start screen is set: which start sequence it will run and which lead-in alert it offers, when that choice was made as a reading of the devices' clocks, and a random number | So the other device's start screen shows the same choice, and to decide which choice counts when the two differ |
| When the two devices come back within reach during a race: its start sequence, its gun, and when its Start and its latest Sync were tapped, as readings of the devices' clocks, with their random numbers, and whether that gun was set on the device sending it | So a start or an end one device missed while they were apart is caught up, and so the two guns can be compared and brought back together |

- **It goes only to your other device.** It is never sent to the developer or to anyone else.
- **It travels only over the direct connection between your watch and your phone.** The apps use the
  Wearable Data Layer, the channel Google Play services provides between a paired watch and phone.
  While the two devices can reach each other only through the internet rather than directly, Race
  Timer sends nothing.
- **It is not stored, beyond the race you are running and the start screen's choice.** Each app
  holds the readings, and what it was told about a race, in memory while it runs, and they are gone
  when it stops. A race started on your other device — or synced there, or matched to it when the
  two come back within reach — is then saved on this one exactly as a race you started here is: the
  sequence and the gun time, listed under *Information
  stored on your device*, so it survives the app being closed. A start screen's choice made on your
  other device is saved on this one exactly as a choice you made here is: the start sequence you last
  chose and the lead-in time, in the same table.
- **Nothing in it identifies you.** A clock reading says how long a device has been switched on; a
  race start, sync or end says which sequence you ran, when, and for how long; a start screen's
  choice says which sequence and lead-in you picked; and nothing more.
- **One device on its own exchanges nothing.** A phone with no paired watch running Race Timer, or a
  watch whose phone does not run it, sends nothing at all.

## Permissions and why they are used

| Permission | Why Race Timer needs it | Requested by |
|---|---|---|
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` | To keep the start sequence running accurately while the screen is off, so the horn and vibration cues still fire at the right moment | Both apps |
| `WAKE_LOCK` | To hold the CPU awake for the duration of a running sequence, so cue timing does not drift while the device is idle. The lock is sized to the remaining race and released when the sequence ends | Both apps |
| `POST_NOTIFICATIONS` | To show the ongoing-activity notification Android requires for a running foreground service, and which lets you return to the running race | Both apps |
| `VIBRATE` | To deliver the haptic signals for each race cue | Both apps |

Neither app requests location, microphone, camera, contacts, storage, body sensors, or any health
or fitness permission. Adding the phone app introduced **no permission the watch app did not
already request** — the two apps request exactly the same set — and linking the two apps added no
permission to either.

## Sharing

Race Timer does not share data with anyone, because it does not collect any. The clock readings and
race controls described above pass only between your own two devices.

The apps contain one third-party library: Google Play services' Wearable library, with the parts of
Google Play services it depends on. It provides the Data Layer channel between a paired watch and
phone, and Race Timer uses it for the clock readings and race controls and nothing else. There are no advertising
networks, no analytics providers, and no crash-reporting services.

## Children

Race Timer is a general-purpose sports utility and is not directed at children. Because it collects
no data at all, it collects no data from children.

## Security

The data described above is held in the app's private, sandboxed storage, which the Android
operating system isolates from other apps on the device. The only data either app sends is the
clock readings and race controls exchanged between your own two devices, described above; the device
backup described above is encrypted by Android before it leaves the watch.

## Changes to this policy

If Race Timer's behaviour changes in a way that affects this policy — for example if a future version
gains network access or an optional account — this policy will be updated before that version is
published, and the effective date above will change.

## Contact

Questions about this policy can be sent to **hsc.coach@gmail.com**.

---
