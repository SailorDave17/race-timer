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

<!--
MAINTAINER NOTES — remove this block before publishing.

1. PUBLISHED 2026-08-12. Effective date and contact address are filled in: hsc.coach@gmail.com, an
   owner decision taken with the scraping risk stated. The same address was given to IARC on the
   content-rating questionnaire the same day.

2. Every factual claim here was checked against the tree on 2026-08-01 and RE-CHECKED on 2026-08-09,
   after PRs #113, #116 and #132 had merged. All still true:
   - No INTERNET permission in wear/src/main/AndroidManifest.xml.
   - A grep across wear/src, shared/src, both build files and the version catalog for
     http/okhttp/retrofit/firebase/analytics/crashlytics/URL(/Socket returned nothing.
     SCOPE NOTE 2026-08-13: #200 added a third module, so a re-run of this grep must also cover
     shared-android/src and shared-android/build.gradle.kts. The 2026-08-09 result stands as
     recorded — the code it covered did not change, it moved — but the module list above is no
     longer the whole tree and re-running it as written would under-scope the check.
   - CORRECTED 2026-08-12: there are EIGHT persisted keys, not four. This note said four from
     2026-08-01, and the 2026-08-09 re-check restated "the four persisted keys" unchanged while
     four more had landed between 2026-08-02 and 2026-08-05. The table above now lists all eight.
     Re-counted directly against the tree today rather than inherited: eight "const val PREF_"
     declarations in TimerService.kt, written to the "race_timer_state" prefs file (PREFS_NAME at
     :1014) --
       PREF_SEQUENCE_ID :1015, PREF_GUN_ELAPSED :1016, PREF_GUN_WALL_CLOCK :1017,
       PREF_CAPTURED_ELAPSED :1018, PREF_PICKED_SEQUENCE_ID :1034 (#88),
       PREF_LAST_BOX_ALERT :1048 (#104), PREF_RAISED_STREAM :1069 and
       PREF_RAISED_PREVIOUS_VOLUME :1070 (both #95).
     None of the four that were missing changes a claim this policy makes -- two preferences and a
     two-key receipt for restoring a device volume, none personal, identifying or transmitted. The
     ENUMERATION was incomplete; the conclusion was not. That is exactly why an enumeration in a
     published document is worth more than a summary sentence: only the list can be found wrong.
   - NOT re-verified today, inherited from the 2026-08-09 check: the no-INTERNET claim, the
     dependency grep, the wake-lock behaviour and the permission table.
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

4. Publishing: the canonical URL is

       https://sailordave17.github.io/race-timer/privacy-policy

   served from the `gh-pages` branch, which holds the rendered policy AND NOTHING ELSE.
   `.github/scripts/build-privacy-page.py` builds it and `.github/workflows/publish-privacy-policy.yml`
   republishes on every change to this file. Do not hand-edit `gh-pages`; the next publish
   overwrites it. The URL must stay publicly reachable with no login -- Play does fetch it, and a
   dead privacy policy URL is grounds for removal.

   CORRECTED 2026-08-12. This note previously said "GitHub Pages, serving docs/ from the default
   branch (develop)", and reasoned that publishing the whole docs/ folder "adds no new exposure,
   because this repository is already public". Two things were wrong with that:
   - It described a configuration that was never applied. Pages was in fact serving branch
     `release` at path `/` -- a branch 112 commits and eleven days stale -- so the reasoning was
     applied to a hypothetical while something else was live. Found 2026-08-12 by reading the
     Pages API rather than this note.
   - "Already public, so no new exposure" understates it. A Jekyll-rendered page carries SEO tags
     and is indexed; a file in a git tree is not. Measured: the stale site was serving
     `/docs/watch-setup` with the watch's pairing address `192.168.1.73:41017` on it. A private
     RFC1918 address, so not remotely reachable and not an emergency -- but nobody knew the page
     existed, which is the actual finding.
   The publish-only branch replaces that blacklist with a whitelist: nothing can reach the site by
   being dropped into a folder, because only the build script writes the branch.

5. The maintainer block you are reading is stripped at publish time and never reaches the web. The
   build script asserts the strip happened and refuses to publish otherwise -- an HTML comment
   renders as nothing, so a failed strip would look exactly like a successful one.

   Note 2 above said docs/release-signing.md "names the keystore path, the key alias, and which
   password-manager entry holds the credentials". CORRECTED 2026-08-12: #172 redacted all three,
   and that file now states plainly that locators are deliberately not written there. The sentence
   had become a pointer to a file as though it still held them.
-->
