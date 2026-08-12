# Privacy Policy — Race Timer

**Effective date:** [DATE OF PUBLICATION]
**Applies to:** Race Timer for Wear OS (`io.github.sailordave17.racetimer`)

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
closed, or the watch restarting mid-sequence. This data never leaves the watch.

Stored in the app's private storage (`race_timer_state`):

| What | Why |
|---|---|
| The identifier of the selected start sequence | So a restored race resumes at its own duration and cadence |
| The scheduled gun time, as a monotonic clock reading | So the countdown resumes at the correct remaining time |
| The scheduled gun time, as a wall-clock reading | Best-effort recovery after a device restart, when the monotonic reading is no longer valid |
| The monotonic clock reading at the moment the race was saved | To detect a restart and fall back to the wall-clock value |

This is timing state only. It contains nothing about you, and nothing that identifies you or your
watch.

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

Questions about this policy can be sent to **[CONTACT EMAIL]**.

---

<!--
MAINTAINER NOTES — remove this block before publishing.

1. Fill in [DATE OF PUBLICATION] and [CONTACT EMAIL] before publishing. Consider a dedicated address
   rather than a personal one; this URL is public and gets scraped.

2. Every factual claim here was checked against the tree on 2026-08-01 and RE-CHECKED on 2026-08-09,
   after PRs #113, #116 and #132 had merged. All still true:
   - No INTERNET permission in wear/src/main/AndroidManifest.xml.
   - A grep across wear/src, shared/src, both build files and the version catalog for
     http/okhttp/retrofit/firebase/analytics/crashlytics/URL(/Socket returned nothing.
   - The four persisted keys are PREF_SEQUENCE_ID, PREF_GUN_ELAPSED, PREF_GUN_WALL_CLOCK and
     PREF_CAPTURED_ELAPSED, written to the "race_timer_state" prefs file
     (TimerService.kt:804-807, constants at :857-861).
   - The wake lock is PARTIAL and acquired with a timeout of remaining race + margin
     (TimerService.kt:710-712, released at :717).
   - The five declared permissions are exactly the four rows in the table plus
     FOREGROUND_SERVICE_SPECIAL_USE, which shares a row with FOREGROUND_SERVICE. The manifest also
     gained uses-feature android.hardware.audio.output required="false" (#95/#132) -- a feature, not
     a permission, so the table above stays complete.

   The line numbers above had all drifted by 2026-08-09 (they read :473-476, :517 and :369-384, none
   of which point at the code they named). Re-cite them whenever this note is re-checked; a stale
   citation is how a check that was really run stops being reproducible.

3. THIS POLICY IS A DEPENDENCY OF THE MANIFEST. If a permission is added, or any networking or
   third-party SDK is introduced, this file is wrong the moment that change merges. Treat a manifest
   change as requiring a matching edit here.

4. Publishing: enable GitHub Pages for this repo and serve docs/, or paste the rendered text into a
   Pages site. The URL must be publicly reachable with no login -- Play does fetch it, and a dead
   privacy policy URL is grounds for removal.
-->
