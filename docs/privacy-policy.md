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

<!--
MAINTAINER NOTES — remove this block before publishing.

1. PUBLISHED 2026-08-12; REVISED 2026-08-17 for the phone app (#212), which moves the effective
   date to 17 August 2026. Contact address hsc.coach@gmail.com, an owner decision taken with the
   scraping risk stated. The same address was given to IARC on the content-rating questionnaire.

   THE 2026-08-17 REVISION IS NOT ONLY ADDITIVE. Two claims changed rather than widened:
   - "This data never leaves the watch" was REMOVED, and replaced by the Device backup section.
     It was false as published. allowBackup is true on the watch, so Auto Backup is eligible to
     copy race_timer_state into the user's Google account -- which is precisely what
     docs/play-app-content-declarations.md has said at length since 2026-08-11 ("Data does leave
     the device"), while this document, the PUBLISHED one, asserted the opposite. The two
     documents disagreed on a material point and the public copy carried the wrong side. The
     phone did not cause this; editing the sentence to add the phone is what surfaced it, and
     restating a falsehood while rewriting the line around it was not an option.
   - The permission table gained a "Requested by" column because VIBRATE is watch-only. The
     phone app has no haptics until #208, and a policy that claimed one would be describing a
     capability the app does not have.

   Note the Changes-to-this-policy section promises an update BEFORE a version that affects this
   policy is published. That is why #212 sits ahead of the phone upload (#214) rather than
   beside it, and why publishing this revision is a prerequisite of that upload and not a
   tidy-up after it.

2. Every factual claim here was checked against the tree on 2026-08-01 and RE-CHECKED on 2026-08-09,
   after PRs #113, #116 and #132 had merged. All still true:
   - No INTERNET permission in wear/src/main/AndroidManifest.xml.
   - A grep across wear/src, shared/src, both build files and the version catalog for
     http/okhttp/retrofit/firebase/analytics/crashlytics/URL(/Socket returned nothing.
     SCOPE NOTE 2026-08-13: #200 added a third module, so a re-run of this grep must also cover
     shared-android/src and shared-android/build.gradle.kts. The 2026-08-09 result stands as
     recorded — the code it covered did not change, it moved — but the module list above is no
     longer the whole tree and re-running it as written would under-scope the check.
   - THE KEY COUNT IS DELIBERATELY NOT STATED HERE, in prose, anywhere (#212 AC 2). The table in
     Information stored on your device IS the enumeration; derive the number from it or from the
     tree, never from a sentence. This is not a style preference -- it is the specific defect
     this document has already shipped twice. It said "four" from 2026-08-01, and the explicit
     2026-08-09 RE-CHECK copied "the four persisted keys" forward unchanged while four more had
     landed between 2026-08-02 and 2026-08-05, so the re-check's date then vouched for a count
     nobody had recounted. Corrected 2026-08-12; the count is now gone rather than corrected
     again, because a cardinal in prose beside a list that can grow is a claim with no owner.
     (cairn memory: a-computable-claim-does-not-belong-in-prose.)
     RE-DERIVED 2026-08-17 against develop at f953e97, both modules, by reading the declarations
     rather than counting a remembered list:
       WATCH -- "race_timer_state" (TimerService.kt PREFS_NAME :1085):
         PREF_SEQUENCE_ID :1086, PREF_GUN_ELAPSED :1087, PREF_GUN_WALL_CLOCK :1088,
         PREF_CAPTURED_ELAPSED :1089, PREF_PICKED_SEQUENCE_ID :1105 (#88),
         PREF_LAST_BOX_ALERT :1119 (#104), PREF_RAISED_STREAM :1140 and
         PREF_RAISED_PREVIOUS_VOLUME :1141 (both #95).
       PHONE -- "phone_race_state" (PhoneRacePersistence.kt PREFS_NAME :110):
         PREF_SEQUENCE_ID :111, PREF_GUN_ELAPSED :112, PREF_GUN_WALL_CLOCK :113,
         PREF_CAPTURED_ELAPSED :114, PREF_PICKED_SEQUENCE_ID :127 (#209).
     The watch line numbers had ALL drifted again since 2026-08-12 (:1014-:1070 -> :1085-:1141),
     which is the third time this note has had to re-cite them and is the argument for the rule
     below rather than an incidental.
     The phone stores a SUBSET, and the difference is behavioural, not a policy question: no
     lead-in key because #207 is unbuilt, and no volume-receipt pair because the phone never
     raises a device volume. A Custom race adds nothing -- custom_8m carries its duration inside
     picked_sequence_id, so BuiltInSequences.resolve rebuilds the sequence from that one string
     (PhoneRacePersistence.kt :115-:126 says so in its own words).
   - RE-VERIFIED 2026-08-17 at f953e97, ALL FOUR module trees, closing the 2026-08-13 scope note
     above rather than leaving it open:
       * The sweep now covers wear/src, shared/src, shared-android/src AND phone/src, plus all
         four build files and gradle/libs.versions.toml. Pattern:
         internet|okhttp|retrofit|firebase|analytics|crashlytics|admob|billing|purchase|oauth|
         Socket|URLConnection. TWO hits, both COMMENTS explaining why Auto Backup matters given
         the absence of INTERNET (phone AndroidManifest.xml:29, LauncherReachabilityTest.kt:50).
         No code hit in any module.
       * No INTERNET in EITHER merged release manifest -- and the merged manifest is the check,
         not the source file, because a library can inject a permission the source never names
         (cairn memory: verify-the-artefact-not-its-ingredients). Both were built with
         ./gradlew :phone:processReleaseMainManifest :wear:processReleaseMainManifest
         --no-watch-fs --rerun-tasks and read from
         <module>/build/intermediates/merged_manifest/release/processReleaseMainManifest/.
       * No text input in phone/src/main either -- same sweep as the watch's
         (TextField|BasicTextField|EditText|RemoteInput), no hits, which is what keeps the
         "nothing you could type" sentence true of both apps.
   - STILL inherited from 2026-08-09 and NOT re-verified: the wake-lock timeout behaviour on the
     watch. Stated so the line between checked and carried-forward stays readable.
   - The wake lock is PARTIAL and acquired with a timeout of remaining race + margin
     (TimerService.kt:710-712, released at :717).
   - MERGED release manifests, 2026-08-17, verbatim:
       WATCH: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, WAKE_LOCK, POST_NOTIFICATIONS,
         VIBRATE, plus the injected DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION. uses-feature
         android.hardware.type.watch, and android.hardware.audio.output required="false"
         (#95/#132). meta-data com.google.android.wearable.standalone=true.
       PHONE: the same list WITHOUT VIBRATE, plus the same injected permission. NO uses-feature
         at all. Its only meta-data are androidx startup initialisers (emoji2, lifecycle,
         profileinstaller), none of which is declaration-relevant.
     So the phone's permission set is a SUBSET of the watch's, and the app-wide list is unchanged
     by the phone existing. That is the single most useful fact for Play: no declaration that
     rests on the permission list has to move. DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is
     androidx-injected, built from applicationId, app-private and signature-level, and is not
     user-visible -- it is recorded so that finding it at upload time is not a surprise, and it
     is deliberately absent from the published table, which lists what the app ASKS FOR and why.

   The line numbers above had all drifted by 2026-08-09 (they read :473-476, :517 and :369-384, none
   of which point at the code they named). Re-cite them whenever this note is re-checked; a stale
   citation is how a check that was really run stops being reproducible.

3. THIS POLICY IS A DEPENDENCY OF BOTH MANIFESTS -- wear/ AND phone/ since #197. If a permission
   is added to EITHER, or any networking or third-party SDK is introduced anywhere in the four
   module trees, this file is wrong the moment that change merges. Treat a manifest change in
   either module as requiring a matching edit here.

   SINCE #83 THAT DEPENDENCY IS ENFORCED, AND THIS PARAGRAPH IS NO LONGER THE GUARD. The paragraph
   you are reading only ever worked if whoever added a permission happened to open a file they had
   no reason to open, and the consequence of the miss is a PUBLISHED policy that lies about the
   shipped app. So the externally-visible surface of both apps is now snapshotted in
   docs/declared-surface.lock and checked by .github/scripts/declared-surface.py, which runs as the
   first Gradle step of .github/workflows/ci.yml. Adding a permission, an exported component or a
   dependency fails that step, and the failure names this file as one of three to re-check before
   the lock may be regenerated.

   What the lock reads, so you know what it cannot tell you. It reads the MERGED release manifests
   of :wear and :phone, not the source files -- which is what lets it see a permission or an
   exported receiver injected by a dependency, the case note 2 above already treats as the real
   check. It also reads the release-runtime dependency coordinates of all four modules, as
   group:artifact with no version, so a version bump is silent and a NEW dependency is not. It
   does NOT read test-only dependencies (they do not ship, so they cannot falsify anything here),
   and it does not read uses-sdk -- so the targetSdk deadline recorded in
   docs/play-app-content-declarations.md is still tracked only on the tracker.

   The lock does not know what this document SAYS. It detects that the surface moved; deciding
   which sentence above is now false is still a human reading this file. Regenerating the lock
   without doing that reading defeats the whole mechanism, which is why the failure message says
   so rather than just printing a diff.

   The two that will land next, and what each breaks:
   - #208 gives the phone haptics, which adds VIBRATE to the phone manifest. The permission
     table's "Watch app only" becomes wrong that day, and it is the only row that changes.
   - #219 links the two devices over the Wearable Data Layer. That is the big one: data would
     then leave a device BY THE APP'S OWN ACTION, and every "transmits nothing" claim here has
     to be re-argued from scratch rather than edited. #212 was sequenced before the link stories
     for exactly this reason, and the re-check is an acceptance criterion on the link story
     rather than a follow-up nobody owns.

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

   CHANGING THE PAGES SOURCE IS A TWO-CALL OPERATION AND THE SECOND CALL IS NOT OPTIONAL.
   `PUT /repos/{owner}/{repo}/pages` writes the new source and queues NO BUILD. The config write is
   real -- `GET .../pages` reads the new branch back at once -- but the site goes on serving the
   PREVIOUS build indefinitely. So a source change is not finished until you have run

       gh api -X POST repos/SailorDave17/race-timer/pages/builds

   Measured 2026-08-12, repointing the source from `release` to `gh-pages`: `/` answered 200 for
   five solid minutes while `/privacy-policy` answered 404, and `GET .../pages/builds/latest` still
   named the old commit `27849a2` the whole time. That pairing is the trap -- a live site plus a
   successful config write reads as "it worked", and the only contrary signal is a 404 on the path
   you just created, which reads far more naturally as a wrong URL than as a site that never
   rebuilt. Five minutes went into re-deriving the URL before anyone checked the build.

   Then verify the SERVED BYTES rather than the configuration: check that `pages/builds/latest`
   names the commit you pushed, curl the canonical path rather than `/`, and treat any content
   assertion as conditional on having got a 200 first -- a 404 page satisfies "contains no secrets"
   trivially. A push to the source branch does trigger a build the ordinary way; it is specifically
   the configuration change that does not, which is why this is easy to go a long time without
   meeting. (#175. Full write-up, with the two other Pages successes that are not successes, in
   cairn `memory/reference/github-pages-publishing-surface-2026-08-12.md`.)

5. The maintainer block you are reading is stripped at publish time and never reaches the web. The
   build script asserts the strip happened and refuses to publish otherwise -- an HTML comment
   renders as nothing, so a failed strip would look exactly like a successful one.

   Note 2 above said docs/release-signing.md "names the keystore path, the key alias, and which
   password-manager entry holds the credentials". CORRECTED 2026-08-12: #172 redacted all three,
   and that file now states plainly that locators are deliberately not written there. The sentence
   had become a pointer to a file as though it still held them.
-->
