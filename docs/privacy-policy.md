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
     - 2026-09-05, #208 landed: the phone gained VIBRATE and every row now reads "Both apps".
       The column STAYS — it is the shape the next divergence needs (#219's link is the likely
       one), and a table that had to regrow it would regrow it under time pressure.

   Note the Changes-to-this-policy section promises an update BEFORE a version that affects this
   policy is published. That is why #212 sits ahead of the phone upload (#214) rather than
   beside it, and why publishing this revision is a prerequisite of that upload and not a
   tidy-up after it.

   REVISED 2026-09-25 for the pair link (#219), which moves the effective date to 25 September
   2026. Both apps gained their first third-party library (play-services-wearable, bringing
   play-services-base, -basement and -tasks) and now send clock readings to the user's own paired
   device. Re-argued rather than edited, as note 3 predicted:
   - The Summary's "does not collect, transmit, or share any personal data" and "no network
     access" became "does not collect or share" and "does not request network access", plus a
     paragraph pointing at the new section. Both old phrases were about to be false in the
     letter: the readings ARE transmitted, and Play services has network access even though the
     app does not.
   - "neither is technically capable of sending information anywhere" was REMOVED. It stopped
     being true the day a library that can reach another device shipped, whatever the app does
     with it.
   - "There are no third-party SDKs" and "the app transmits nothing" were replaced, naming the
     library and the one exchange.
   - NEW section, Clock readings exchanged with your paired device. Every claim in it rests on
     code, and the three that could drift are each pinned by a test #219's mutation pass made
     fail on purpose:
       only to the other device, and only while directly connected: PairLink asks and answers
         only a peer whose Node.isNearby() is true (PairLinkTest: "a peer reachable only through
         the cloud is neither asked nor answered", "a peer that leaves range halfway through a
         burst is asked nothing more");
       one device alone sends nothing: no peer, no round ("no peer at all is NoPeer, nothing is
         asked, and the link keeps looking");
       not stored: PairLink's rounds live in memory only, and nothing in shared/ or
         shared-android/ writes them anywhere. This one is read, not tested.
     "Several times a minute" is deliberately not a number. PairLink.BURST_EVERY_MS and
     BURST_SIZE are the numbers, and a count in prose beside a constant is a claim with no owner.
   - Found on the way and CORRECTED in the same revision: the lead-in row said "Watch only", and
     the paragraph above the table said the phone "does not yet offer the signal-box lead-in".
     Both had been false since #207 (2026-09-05), which updated note 2 below and not the
     published table. The phone stores last_box_alert_seconds (PhoneRacePersistence.kt:159).

   REVISED 2026-09-28 for start-on-either-device (#220), which moves the effective date to 28
   September 2026, with the owner's approval at #220's pickup ("revise in this PR"). Two claims
   WIDENED and none was withdrawn:
   - WHAT IS EXCHANGED gained three rows, all from the one new message kind, `start`
     (PairMessage.Start in shared/.../PairLink.kt): the sequence id, the gun and the instant it was
     sent as readings of the sender's elapsedRealtime, the tap's instant on the PHONE's
     elapsedRealtime (the stamp that orders two starts, PairRaceBook), and a random race id. Every
     number is still time-since-boot or a random id; the sequence id is the one field new in kind.
     docs/pair-protocol.md is the format.
     A wall-clock stamp was built first and REJECTED on hardware before any commit: the owner's watch
     ran 3.2-3.4 s behind the phone with automatic time on both, which decided every crossing start.
     So no time of day crosses the link. If a later change ever orders starts by wall clock, this
     policy gains a time-of-day row the day it does.
   - WHEN IT IS EXCHANGED widened from "while the app is on screen" to "and from the moment a race
     is started until its gun". D2 was ratified on that condition (PairLink.holdUntil), and it is
     the one change a sailor could notice: an app asks its paired device with the screen off,
     during a race, for the length of the countdown and no longer.
   - "It is not stored" gained its one qualification: a JOINED race is persisted as any race is,
     through the existing race-snapshot keys in the stored-data table (no new key, no new file).
     The stamp and the race id are held in memory only (PairRaceBook) — read, not tested.
   - "Nothing in it identifies you" gained "a race start says which sequence you ran and when" —
     "when" as a clock reading, per the row above, not a time of day.
   The two claims that could drift are pinned by tests #220's mutation pass made fail on purpose:
     only to a nearby peer, both directions: PairLinkTest "a start is neither sent to nor taken
       from a peer reachable only through the cloud" (mutation M5, 1 red as predicted);
     one device alone sends nothing: PairRaceTest "with no counterpart the race starts exactly as
       it would on a phone that never heard of a watch" and "with no Data Layer at all the race is
       the same race".
   No manifest and no dependency moved, so docs/declared-surface.lock is unchanged — the lock
   cannot see a new message kind, which is why this revision was an acceptance of the story's
   pickup rather than a lock refusal.

   REVISED AGAIN 2026-09-28, the same day, for the mirrored controls and pre-start setup (#221),
   with the owner's approval at #221's pickup ("revise all three in this PR"). The effective date
   moves to 29 September 2026: the revision was written late on the 28th and is expected to merge,
   and so to publish, on the 29th (the owner's choice at #221's commit gate). If it merges on a
   later day still, move the date to the merge day. Every claim WIDENED and none was withdrawn:
   - The section heading and its five cross-references said "race starts"; they say "race
     controls". A heading naming one message kind would under-describe the section below it.
   - WHAT IS EXCHANGED gained three rows, one per new message kind (PairMessage.Sync, .End and
     .Setup in shared/.../PairLink.kt; docs/pair-protocol.md is the format): a Sync's moved gun and
     its stamp as elapsedRealtime readings, with a random id; an End Race's elapsed time, a
     DURATION rather than a reading of any clock; and a start screen's sequence id, lead-in alert,
     stamp and random id. The race-id row gained its second purpose: a sync or end names its race
     and is applied only to it. Still no time of day crosses the link.
   - "It is not stored" gained the start screen's choice: a setup taken from the peer is saved in
     the EXISTING picked-sequence and lead-in keys (PhoneSetupStore.kt, WearSetupStore in
     RaceTimerApplication.kt, both through the stores already listed). No new key, no new file. A
     moved gun is saved in the existing race-snapshot keys, as a joined start's is.
   - "Nothing in it identifies you" widened to name what a sync, an end and a choice say.
   The two claims that could drift are pinned by tests #221's mutation pass made fail on purpose:
     only to a nearby peer, both directions, for all three new kinds: PairLinkTest "a Sync, an End
       Race and a setup are neither sent to nor taken from a peer reachable only through the
       cloud" (mutations M8 and M9, 2 red each, as predicted);
     one device alone sends nothing: PairRaceTest "with no counterpart a pick sends nothing and the
       screen is the app's own" and "a race with no peer syncs exactly as it always did, and sends
       nothing".
   No manifest and no dependency moved, so docs/declared-surface.lock is unchanged again.

   REVISED 2026-09-29 for the link's drop and return (#222), with the owner's approval taken
   mid-story, when the new message kind was built ("revise in this PR"). The effective date stays 29 September 2026,
   the day #221's revision took, provided this merges on the 29th as well; if it merges on a later
   day, move the date to the merge day. Every claim WIDENED and none was withdrawn:
   - WHAT IS EXCHANGED gained one row, for the one new message kind (PairMessage.Check in
     shared/.../PairLink.kt; docs/pair-protocol.md is the format). Every field is one the table
     already listed — the sequence id, the gun and the instant it left as elapsedRealtime readings,
     the start's stamp and race id, the latest Sync's stamp and random id — plus one bit, whether
     the sending device set that gun itself. What is new is the OCCASION: it is sent when the peer
     comes back within reach (PairLink's onPeerNearby) by a device running a race, and not
     otherwise. Still no time of day crosses the link.
   - The paragraph above the table gained the sentence saying when, and why.
   - "It is not stored" gained "or matched to it when the two come back within reach": a gun a
     reconnect corrects is saved in the EXISTING race-snapshot keys (persistSnapshot in both
     services' correctGun), as a moved gun is. No new key, no new file.
   The two claims that could drift are pinned by tests #222's mutation pass made fail on purpose:
     only to a nearby peer, both directions: PairLinkTest "a check is neither sent to nor taken
       from a peer reachable only through the cloud" (mutation M12, 1 red as predicted);
     one device alone sends nothing: PairRaceTest "a phone with no watch is never told a link is
       lost" asserts no check was sent, and "a race stopped here is not rejoined when the devices
       meet" that a device running nothing names no race (mutation M9, 1 red as predicted).
   No manifest and no dependency moved, so docs/declared-surface.lock is unchanged again.

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
       PHONE -- "phone_race_state" (PhoneRacePersistence.kt PREFS_NAME :136):
         PREF_SEQUENCE_ID :137, PREF_GUN_ELAPSED :138, PREF_GUN_WALL_CLOCK :139,
         PREF_CAPTURED_ELAPSED :140, PREF_PICKED_SEQUENCE_ID :153 (#209),
         PREF_LAST_BOX_ALERT :159 (#207, added 2026-09-05; the phone line numbers moved
         :110-:127 -> :136-:159 with it, the fourth re-cite).
     The watch line numbers had ALL drifted again since 2026-08-12 (:1014-:1070 -> :1085-:1141),
     which is the third time this note has had to re-cite them and is the argument for the rule
     below rather than an incidental.
     The phone stores a SUBSET, and the difference is behavioural, not a policy question: no
     volume-receipt pair because the phone never raises a device volume. (It said "no lead-in
     key because #207 is unbuilt" until #207 built it; the lead-in key is now on both.) A Custom race adds nothing -- custom_8m carries its duration inside
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
       PHONE, re-read 2026-09-05 (#208): VIBRATE added. The two permission lists are now
         IDENTICAL; uses-feature and meta-data unchanged. Read off docs/declared-surface.lock,
         which the CI check regenerated from the merged manifest in the same change.
     So the phone's permission set is a SUBSET of the watch's (equal to it since #208), and the
     app-wide list is unchanged by the phone existing. That is the single most useful fact for
     Play: no declaration that rests on the permission list has to move. DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is
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

   The two that were predicted to land next, and what each breaks:
   - #208 gives the phone haptics, which adds VIBRATE to the phone manifest. The permission
     table's "Watch app only" becomes wrong that day, and it is the only row that changes.
     LANDED 2026-09-05, exactly as predicted: one row moved, the effective date moved with it
     (the Changes section promises that), and the lock caught the manifest change before the
     document was opened — which is the mechanism working in the direction it was built for.
   - #219 links the two devices over the Wearable Data Layer. That is the big one: data would
     then leave a device BY THE APP'S OWN ACTION, and every "transmits nothing" claim here has
     to be re-argued from scratch rather than edited. #212 was sequenced before the link stories
     for exactly this reason, and the re-check is an acceptance criterion on the link story
     rather than a follow-up nobody owns.
     LANDED 2026-09-25 (#219), and the lock refused it exactly as designed. The refusal named new
     release-runtime coordinates only: the four play-services artifacts in all three Android
     modules and, on the phone, androidx.fragment with the androidx libraries it brings (the
     lock lists them, so this note does not). No locked line of either merged manifest moved. The
     manifests did gain two entries the lock does not record, GoogleApiActivity (exported false)
     and the com.google.android.gms.version meta-data; both are recorded, with the bundle-size
     change, in docs/play-app-content-declarations.md. The sweep in note 2 was re-run across all
     four trees the same day and still finds only the two Auto Backup comments.

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
