# Play Console — App content declarations

Every declaration in Play Console's **App content** section for `io.github.sailordave17.racetimer`
(Mad Cow Race Timer), with the answer and the evidence it rests on. Tracked as issue #75, and
re-derived for the phone form factor by [#212](https://github.com/SailorDave17/race-timer/issues/212).

**One `applicationId`, two artifacts.** The watch app and the phone app ship under one Play
listing, so **there is one set of App content answers covering both** — Console asks these
questions of the app, not of each artifact. That is what makes the phone's arrival cheap here and
it is the single most useful finding of the re-derivation: *measured 2026-08-17, the phone
introduces no permission the watch did not already declare*, so no answer below moves.

This file exists to be a **diff base**. The answers are cheap to give and expensive to re-derive: at
the next release, or the moment a permission is added, the question is not "what is true?" but "what
changed since the last time we said this?" — and that is a question only a written answer can answer.

Two boundaries worth stating up front:

- **Console's own list is authoritative at fill-in time.** Play adds sections. A row here that
  Console no longer asks for is harmless; a section Console asks for that is *missing* here is the
  signal to come back and check the build rather than improvise at the upload screen.
- **This document records answers; entering them is a separate act.** *(Until 2026-08-12 this read
  "entering them requires the app to exist in Console, which is #79" — the app has existed since
  2026-08-03, and seven declarations were filed on 2026-08-12. See* What Console actually asked *below
  for what is filed and what remains gated.)*

## The build these answers describe

| | Watch (`:wear`) | Phone (`:phone`) |
|---|---|---|
| Package | `io.github.sailordave17.racetimer` | the **same** `applicationId`, deliberately — one listing, two form factors |
| Declared in | `wear/build.gradle.kts` | `phone/build.gradle.kts` |
| `versionCode` / `versionName` | `1` / `1.0` — uploaded 2026-08-13 | `2` / `1.0` — allocated by [#211](https://github.com/SailorDave17/race-timer/issues/211) from the one monotonic counter both form factors share (epic #196 decision D3). Not yet uploaded. `checkVersionCodeCollision` refuses two modules reusing a number under this one `applicationId` |
| `minSdk` / `targetSdk` / `compileSdk` | `30` / `35` / `35` — the Wear carve-out | `30` / **`36`** / **`36`** — raised by [#261](https://github.com/SailorDave17/race-timer/issues/261) for the 2026-08-31 phone deadline |
| Verified against | `develop` at `d3156e4`, 2026-08-18 | same |

An answer below is true of *those* builds. A later `versionCode` inherits nothing automatically.

**The phone's `targetSdk` had the one deadline against it, and it is discharged.** Play requires API
36 of a phone artifact after **2026-08-31** and the Wear carve-out does not cover it.
[#261](https://github.com/SailorDave17/race-timer/issues/261) raised `:phone` to
`compileSdk`/`targetSdk` **36** on 2026-08-18, thirteen days ahead of that date, after
[#192](https://github.com/SailorDave17/race-timer/issues/192) delivered the AGP 8.13 it needed.

`:wear` stays at **35** on the Wear carve-out, deliberately — the two form factors carry different
target levels and that is correct, not drift.

It is recorded in this table rather than only on the tracker because this document is the diff base
somebody reads at upload time, and `targetSdk 35` on a phone bundle is a rejection rather than a
warning.

## The declarations

| Console section | Answer | Evidence |
|---|---|---|
| **Privacy policy URL** | `https://sailordave17.github.io/race-timer/privacy-policy` — **live**. *(Corrected 2026-08-17: this row said "Not yet live — publication is #73" for five days after #73 published it on 2026-08-12.)* | Source at `docs/privacy-policy.md`, built to the `gh-pages` branch by `.github/scripts/build-privacy-page.py`. **Fetched 2026-08-17** rather than inherited — it answered, and the maintainer block was correctly absent from the rendered page |
| **Data safety** | **No data collected. No data shared.** | Long form below — this is the only row whose answer needs an argument |
| **Content ratings** (IARC questionnaire) | Every substantive question **no**; rates as low as the questionnaire allows | Long form below |
| **Target audience and content** | **13-15, 16-17, and 18 and over.** Owner decision 2026-08-12 — see *Target audience* below | A product decision, not a code fact. **This row said "adults, no child age band" until it was filed**, and the app ships a **Scholastic (ICSA)** sequence, which is high-school sailing |
| **Ads** | **No ads.** Neither app contains advertising | No ad SDK in the dependencies of **any** of the four modules; no `com.google.android.gms.permission.AD_ID` in **either** merged manifest |
| **Advertising ID** | **Not used** | `com.google.android.gms.permission.AD_ID` is not declared in the merged manifest. From `targetSdk` 33 an app must declare it to read the ID at all, so its absence is the answer. **Check the merged manifest, not the source** — Console's own page warns that an SDK's library manifest can inject this permission, which is precisely the check this document already does |
| **News apps** | No | Not a news or magazine app |
| **COVID-19 contact tracing and status apps** | No | No contact-tracing or health-status function |
| **Government apps** | No | Not published on behalf of, or in association with, a government entity |
| **Financial features** | **None.** No in-app purchases, no subscriptions, no financial products | No billing or payments dependency in any of the four build files; no purchase flow in `wear/src` or `phone/src` |
| **Health apps** | **No.** Neither app reads health or fitness data | No `BODY_SENSORS`, no `ACTIVITY_RECOGNITION`, no health permission in **either** merged manifest; no Health Services dependency. It is a sports app that measures **time**, not the athlete |
| **Data deletion** | No account exists, so there is nothing held off-device to delete. Local data is removed by uninstalling the app or clearing its storage from the device's settings | No account or sign-in code in `wear/src` or `phone/src`; neither app transmits anything (no `INTERNET` permission in either merged manifest) |
| **Foreground service permissions** | `specialUse`, with the written justification | `docs/play-store-fgs-justification.md` — paste that document's *Declaration text* section. **This is the only row with real rejection risk**, and the phone raises the stakes rather than changing the answer: **both** artifacts declare `FOREGROUND_SERVICE_SPECIAL_USE`, for the same reason (a race-start countdown is none of the enumerated FGS types), so one rejected argument rejects both. The phone's subtype string is in `phone/src/main/AndroidManifest.xml`; check that the justification document still reads as true of a phone and not only of a wrist before pasting it |
| **App access** | **All functionality is available without any special access.** No login, no region lock, no unlocked-content gate; no reviewer credentials needed — true of **both** apps | No account/sign-in code in `wear/src` or `phone/src`; on the watch the only `startActivity` leaves for a **system settings screen**, never a login or a web page |

And one that Console asks conditionally rather than as its own checklist row:

| Console section | Answer | Evidence |
|---|---|---|
| **Photo and video permissions** (asked only if the permission is declared) | Not applicable | No `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` in **either** merged manifest |

*Advertising ID sat in this conditional table, described as "asked inside Data safety", until the
form was actually opened on 2026-08-12. It is **its own required section** in App content, and
Console states plainly that releases targeting Android 13 are blocked until it is answered — which
this app's `targetSdk` 35 makes unavoidable. It has been moved into the main table above.*

## What Console actually asked — measured 2026-08-12

The section above was written from Play's documentation. On 2026-08-12 the form was opened for the
first time, and **Console does not ask for several things this document anticipated — and does ask
for one it filed as conditional.** Recording the difference row by row rather than as a count, because
the boundary at the top of this file said Console is authoritative at fill-in time, and this is what
that turned out to mean.

| This document's row | In Console's App content? |
|---|---|
| Privacy policy, Data safety, Content ratings, Target audience, Ads, Government apps, Financial features, Health apps | Yes, each its own section |
| **App access** | Yes, but named **"Sign in details"** |
| **Advertising ID** | Yes, and **required** — see the correction above |
| **News apps** | **No.** Asked inside the content-rating questionnaire instead |
| **COVID-19 contact tracing** | **No.** Not asked at all |
| **Data deletion** | **No.** Folded into Data safety |
| **Foreground service permissions** | **No — and this is the one that matters.** See below |

**The FGS justification is not an App content task.** It does not appear until a bundle declaring
`FOREGROUND_SERVICE_SPECIAL_USE` has been uploaded, so the item this document calls "the only row
with real rejection risk" is gated on **#79**, not on the app merely existing in Console. Nothing
in #75 can discharge it. `docs/play-store-fgs-justification.md` stays ready to paste.

Console also lists two tasks that are **not** App content and belong elsewhere: *select an app
category and provide contact details*, and *set up your store listing* — those are #76 / #77 / #78.

### Target audience — why this is 13 and over

Filed as **13-15, 16-17, and 18 and over**. This document previously said adults-only with no child
age band, on the reasoning that the app's user is the race officer running the start rather than the
competitor. That was reversed at fill-in time by owner decision, because the app ships a
**Scholastic (ICSA)** sequence and ICSA is high-school sailing — an age range visible in the product
itself is hard to argue away in a store listing.

Selecting an under-18 band brings the **Families policy** into scope, and Console enumerates four
obligations on saving. All four are satisfied trivially, and *because of what this app is rather than
by any work*: content appropriate for children (a race timer), only child-appropriate ads and only
from certified networks (there are no ads at all), and COPPA / GDPR compliance (no `INTERNET`
permission, nothing transmitted, nothing collected). **The heavier-looking answer cost nothing here.**

One ordering trap found on the way: the age checkboxes below 13 were **disabled**, with Console
explaining that the app's ESRB rating was already 'teen' or higher — before the rating questionnaire
had been completed. A content rating therefore **gates which age groups are selectable**, so answer
Content ratings first if an under-13 band is ever wanted.

### What was filed on 2026-08-12

Seven saved: Ads, Sign in details, Government apps, Financial features, Health apps, Advertising ID,
Target audience. Each saves as **staged**, not submitted — Console's own wording is *"Change saved.
Send for review in Publishing overview"*, so nothing reaches Google until that separate step.

Content ratings was started (category *All Other App Types*, contact `hsc.coach@gmail.com`, IARC
Terms accepted, all nine questionnaire answers **no**) and Data safety was not started.

## Data safety — the reasoning, not only the answer

The answer is **no data collected, no data shared**. Play's own definition is the thing to reason
from, rather than the intuition that a local file is obviously fine:

> "Collect" means transmitting data from your app off a user's device.
> — [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)

Two exemptions on the same page matter here: data **accessed and processed only on the device** does
not need disclosing, and data transferred off-device but **end-to-end encrypted** does not either.
The form is still mandatory for an app that collects nothing.

### What the app actually stores

One `SharedPreferences` file per app, each in that app's own private storage, never shared and
never synced between the two. The count is deliberately not written here — the tables are the
enumeration, for the reason `docs/privacy-policy.md` records having been bitten by twice.

**Watch** — `race_timer_state` (`TimerService.kt:1085`):

| Key | What it is | Line |
|---|---|---|
| `sequence_id` | Which start sequence a race in flight is running | `TimerService.kt:1015` |
| `gun_elapsed_ms` | Scheduled gun time as a monotonic clock reading | `:1016` |
| `gun_wall_clock_ms` | Scheduled gun time as a wall-clock reading, for recovery after a restart | `:1017` |
| `captured_elapsed_ms` | Monotonic reading at the moment the race was saved | `:1018` |
| `picked_sequence_id` | The sequence the sailor last chose — a preference that outlives a race (#88) | `:1034` |
| `last_box_alert_seconds` | The lead time a race was last armed with (#104) | `:1048` |
| `raised_cue_stream` | Which audio stream a running race raised the volume on (#95) | `:1069` |
| `raised_cue_previous_volume` | What that stream's volume was before, so it can be put back (#95) | `:1141` |

**Phone** — `phone_race_state` (`PhoneRacePersistence.kt:110`):

| Key | What it is | Line |
|---|---|---|
| `sequence_id` | Which start sequence a race in flight is running | `PhoneRacePersistence.kt:111` |
| `gun_elapsed_ms` | Scheduled gun time as a monotonic clock reading | `:112` |
| `gun_wall_clock_ms` | Scheduled gun time as a wall-clock reading, for recovery after a restart | `:113` |
| `captured_elapsed_ms` | Monotonic reading at the moment the race was saved | `:114` |
| `picked_sequence_id` | The sequence the officer last chose — a preference that outlives a race (#209) | `:127` |

The phone's set is a **subset** of the watch's, and the three it lacks are behavioural rather than
a policy choice: no `last_box_alert_seconds` because the signal-box lead-in is unbuilt on the phone
(#207), and no volume-receipt pair because the phone never raises a device volume. A Custom race
adds no key on either — `custom_8m` carries its duration inside the sequence id, so
`BuiltInSequences.resolve` rebuilds the whole sequence from that one string.

Clock readings, a sequence identifier, a stream index and a volume integer. No name, no account, no
device identifier, no location, no health value, no free text — **neither** app has any text input
at all (swept 2026-08-17 across `wear/src/main` and `phone/src/main`).

### The part that is not obvious: `allowBackup`, and the two apps answer it differently

**Watch:** `android:allowBackup="true"` (`wear/src/main/AndroidManifest.xml:25`). Android's Auto
Backup is on by default for apps targeting API 23 or higher and **includes `SharedPreferences`
files**, so `race_timer_state` is eligible to be copied into a private folder of the user's Google
Drive. Data does leave the device.

**Phone:** `android:allowBackup="false"`, set explicitly and ratified as epic #196 decision D5
(`phone/src/main/AndroidManifest.xml`, with the reasoning in a comment beside it). Confirmed in the
**merged** manifest 2026-08-17, not just the source. So the argument below is **watch-only**; on
the phone the question does not arise.

The asymmetry is deliberate and worth keeping straight, because it is the kind of thing that gets
"tidied" into consistency later: the watch keeps Auto Backup so a sailor's sequence preference
survives a replacement watch, and the phone declines it because the decision point was the moment
its manifest was authored and nothing there needed to survive a new phone.

**`docs/privacy-policy.md` said the opposite of this section until 2026-08-17**, and it is the
*published* document — it asserted "This data never leaves the watch" while this file had said
"Data does leave the device" since 2026-08-11. Corrected under #212 by giving the policy a
*Device backup* section. Recorded here because two documents disagreeing on a material point, with
the public one carrying the wrong side, is worse than either being wrong alone.

The answer is still *no data collected*, for three independent reasons — listed weakest-first, so the
order to argue them in is clear:

1. **The transfer is the platform's, not the app's.** The definition is "transmitting data *from your
   app*". Auto Backup is performed by the operating system's backup service; the app neither
   initiates it nor can observe it.
2. **It is end-to-end encrypted.** `minSdk` is 30, so every device that can install this build is
   Android 11 or later, and Auto Backup is end-to-end encrypted with the device's PIN, pattern or
   password on Android 9 and above. That is an explicit exemption on Play's own page.
3. **Nothing in the file maps to any Data safety data type.** There is no category in the form that
   the eight keys above belong to. Even if reasons 1 and 2 were both waved away, there would be
   nothing to declare.

**Reason 3 is the one that does not depend on reading a definition a particular way. Lead with it if
this is ever queried**, and use 1 and 2 as support. Setting `allowBackup="false"` would remove the
question, but is not required by this analysis, and would cost a sailor their sequence preference
when they move to a new watch.

## Content ratings — the questionnaire

Answers go to IARC, and ratings are then issued per-territory automatically. Every substantive
question is **no**: no violence, no sexuality, no strong language, no controlled substances, no
gambling, no in-app purchases, no user-to-user interaction, no user-generated content, no location
sharing, no personal information shared with third parties.

Two answers are worth stating with their evidence rather than as assertions, because they are the
ones a reviewer could reasonably expect to be "yes":

- **No user-generated content and no user-to-user interaction.** There is no text input anywhere in
  either app — a sweep of `wear/src/main` **and `phone/src/main`** for `TextField`,
  `BasicTextField`, `EditText` and `RemoteInput` returns nothing (re-run 2026-08-17). Every value
  the sailor or officer sets is chosen from a picker or stepped with `+` / `−`.
- **No unrestricted access to the internet.** Neither app has the `INTERNET` permission. The watch
  contains exactly one `startActivity` call and it opens a **system settings screen**
  (`wear/.../MainActivity.kt:775`), never a browser or a web view; the phone app contains **no
  `startActivity` call at all**, so it cannot leave itself for anywhere.

  *(That citation read `MainActivity.kt:726` until 2026-08-17 and pointed at nothing — the third
  time a line number in these two documents has been found drifted. Re-cite on every re-check.)*

## What would change, and what it would break

The point of this section: a future feature must not silently invalidate a declaration. Each row is a
change that, on the day it merges, makes something above **wrong**.

| If the app gains… | These stop being true |
|---|---|
| **Network access** (`INTERNET`) | Data safety may become *collects* — that depends entirely on what is sent, and it must be re-answered rather than assumed. `docs/privacy-policy.md`'s claim that the app "is not technically capable of sending information anywhere" becomes false. The FGS justification's `dataSync` bullet weakens, and **its strongest single fact — "no `INTERNET` permission" — is gone**, which matters most in an appeal. The content rating's internet-access answer changes |
| **An account or sign-in** | **App access** becomes wrong: Play requires working reviewer credentials, and their absence is a standard review rejection. Data safety gains personal information — at minimum an account identifier. **Data deletion** becomes a real obligation: an account-based app needs an in-app deletion route *and* a web URL that requests deletion. The privacy policy's "no account to create" is false |
| **Health-sensor reads** (`BODY_SENSORS`, `ACTIVITY_RECOGNITION`, Health Services) | The **Health apps** declaration becomes yes and its form must be completed. Data safety gains a Health and fitness type. The FGS argument's "`health` does not fit" bullet becomes **wrong**, and with it the whole `specialUse` case — a reviewer's first move would be to insist on the standard `health` type. The privacy policy's permission table and its "no health or fitness data" claim are both false |
| **Ads or an ads SDK** | The **Ads** declaration flips. **Advertising ID** likely becomes used and must be declared. **Target audience** acquires ad-serving obligations. Data safety gains sharing with a third party. The content rating changes. The privacy policy's "no advertising networks" is false |
| **In-app purchases or subscriptions** | **Financial features** and the content rating both change, and the store listing must say so |
| **Free-text entry** (naming a custom sequence, say) | The content rating's user-generated-content answer changes, and Data safety may gain a type depending on where the text goes |
| **Data Layer sync between the watch and the phone** ([#219](https://github.com/SailorDave17/race-timer/issues/219)) | Data leaves a device **by the app's own action** — the reasoning in *the part that is not obvious* above no longer applies, and Data safety must be re-answered from scratch rather than edited. The watch's standalone `meta-data` declaration would also need re-reading, though it describes *not requiring* a phone rather than *not talking to* one |
| **Haptics on the phone** ([#208](https://github.com/SailorDave17/race-timer/issues/208)) | Adds `VIBRATE` to the phone manifest. Nothing in this document moves — the permission is already declared by the watch and already in the app-wide set — but `docs/privacy-policy.md`'s permission table says `VIBRATE` is **watch-only**, and that row becomes wrong the day it merges |

**One row of this table has already been half-resolved, and the correction is instructive.** It
used to read *"A phone companion or Data Layer sync"* as a single trigger, predicting that a phone
would make data leave the watch by the app's own action. The phone shipped in #197 and **none of
that happened**, because it is a *standalone* app rather than a companion: two apps that never talk
to each other, each storing its own state locally. The row had conflated *a second form factor*
with *a link between them*, and only the second one carries the consequence. Split above so the
shipped half stops implying a re-answer nobody owes.

The common shape: **a permission added to either manifest invalidates a declaration in Play Console,
and nothing in the build fails when it does.** Turning that dependency into an enforced check rather
than a paragraph is [#83](https://github.com/SailorDave17/race-timer/issues/83) — which now has
**two** manifests to read rather than one. That is not a detail: a check written against
`wear/`'s merged manifest alone would pass while the phone gained a permission, which is the exact
failure mode #83 exists to remove, arriving through the module list rather than through the
permission list. Noted here rather than filed separately because #83 is open and this extends its
input, not its purpose.

---

## Maintainer notes — not for Play Console

Every claim above was checked against `develop` at `aafa5de` on **2026-08-11**, and **re-derived
for both form factors against `develop` at `f953e97` on 2026-08-17** (#212).

**Check the merged manifest, not the source file.** The shipped permission set is what
`wear/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
holds after a `:wear:bundleRelease`, and it is **not** the same list as
`wear/src/main/AndroidManifest.xml`. The source declares five permissions; the merged manifest carries
six — androidx.core injects `io.github.sailordave17.racetimer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
(it is built from `applicationId`, so it moved with the rename), an
app-private, signature-level permission that grants nothing to anything else, is not user-visible, and
changes no answer here. It is recorded because *finding an unexpected sixth permission at upload time*
is the kind of surprise this document exists to remove, and because reading only the source file is
how a library-injected permission goes unnoticed. To reproduce:

```sh
./gradlew :wear:bundleRelease
tr '>' '>\n' < wear/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml \
  | grep -E "uses-permission|uses-feature"
```

What that produced on 2026-08-11: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`,
`POST_NOTIFICATIONS`, `VIBRATE`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, plus `uses-feature`
`android.hardware.type.watch` and `android.hardware.audio.output` (`required="false"`).

Other checks run the same day:

- A sweep of `wear/src`, `shared/src`, both build files and `gradle/libs.versions.toml` for
  `internet|okhttp|retrofit|firebase|analytics|crashlytics|admob|ads|billing|purchase|login|signin|account|oauth|Socket|URLConnection`
  returned no code hits — only XML namespace URLs and the English word "reads".
- No text-input composable or view anywhere in `wear/src/main`.
- The eight `race_timer_state` keys, at the lines cited in the table above.

**The module set drifts too, and a sweep that names it goes silently under-scoped.** The sweep above
covered `wear/src` and `shared/src` because those were the only source trees on 2026-08-11. There
are now **four** — `shared/`, `shared-android/` (#200), `wear/` and `phone/` (#197) — and four
build files. The 2026-08-11 record is left as written because it was true of the tree it read; what
would not be true is treating its scope as the current one.

**Re-run 2026-08-17 across all four trees**, plus all four build files and
`gradle/libs.versions.toml`, with the same pattern as before. Two hits, **both comments** — one in
`phone/src/main/AndroidManifest.xml` and one in `phone/src/test/.../LauncherReachabilityTest.kt`,
each explaining why Auto Backup matters *given the absence of* `INTERNET`. No code hit in any
module. Derive the module list with `./gradlew projects` rather than from this paragraph, which is
the same enumeration hazard one level up.

**Both merged release manifests, read 2026-08-17** — built with
`./gradlew :phone:processReleaseMainManifest :wear:processReleaseMainManifest --no-watch-fs
--rerun-tasks`, then read from
`<module>/build/intermediates/merged_manifest/release/processReleaseMainManifest/`:

| | Watch | Phone |
|---|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS` | yes | yes |
| `VIBRATE` | yes | **no** — until #208 |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (androidx-injected) | yes | yes |
| `INTERNET`, `AD_ID`, any media/storage/health permission | no | no |
| `uses-feature` | `android.hardware.type.watch`; `android.hardware.audio.output` `required="false"` | **none at all** |
| `meta-data` | `com.google.android.wearable.standalone=true`, plus androidx startup initialisers | androidx startup initialisers only (emoji2, lifecycle, profileinstaller) |
| `allowBackup` | `true` | `false` (D5) |

**The phone's permission set is a strict subset of the watch's.** That single fact is why the
re-derivation changed no answer in the table at the top, and it is the thing to re-check first at
the next release rather than re-reading every row.

**Line numbers drift.** Every citation here was correct on the date above and will not stay correct;
re-cite them when this document is re-checked rather than trusting them. `docs/privacy-policy.md`
records the same hazard, from having been bitten by it.

**This document is a dependency of the manifest.** If a permission is added, or networking, an
account, a health sensor, an ads SDK or any third-party SDK is introduced, one or more answers above
is wrong the moment that change merges — see *What would change*. #83 exists to turn that dependency
into an enforced check.

**The key-count disagreement with `docs/privacy-policy.md` is RESOLVED**, and this paragraph is
kept as a record rather than deleted. That document's table listed four keys where there were
eight; it was corrected on 2026-08-12 under #73, and this note went on describing the
disagreement as live for five days afterwards. Both documents now enumerate rather than count, and
**neither states a number in prose** — #212 AC 2 made that a criterion after the same drift
happened twice. A stale "known disagreement" is its own small hazard: it invites the next reader
to go and fix something already fixed, and to distrust the document that was right.

**A live disagreement did exist, on a different axis, and was corrected under #212.** The policy
claimed *"This data never leaves the watch"* while this file has said *"Data does leave the
device"* since 2026-08-11 — a material contradiction with the **published** document on the wrong
side of it. The policy now carries a *Device backup* section. The lesson is not about backup: it is
that these two files were cross-checked for *key counts*, which is the easy axis to compare,
while a flat contradiction sat in prose neither check looked at.

**Where the entries actually get made.** The Console app already exists — created 2026-08-03 as
`io.github.sailordave17.racetimer`, status Draft — so #79's first criterion was satisfied before this
document was written, and #79 now covers the *upload*, not the app's creation. Seven declarations
were filed 2026-08-12 (above). Two remain gated and neither can be discharged by #75:

- **Privacy policy URL** — ~~a required field with no value to enter~~ **DISCHARGED**. #73 published
  it on 2026-08-12 to `https://sailordave17.github.io/race-timer/privacy-policy`, served from the
  `gh-pages` branch. *Fetched 2026-08-17 and it answers.* The bullet above described a 404 that had
  stopped being true the same day it was written, which is why the row in the main table now cites a
  fetch rather than a memory.

  **The published copy lags this branch by design.** `.github/workflows/publish-privacy-policy.yml`
  republishes on a push to `develop` touching the policy or its build script, so the #212 revision
  goes live when this work merges — not when it is written. Before the phone upload, confirm the
  live page shows the **17 August 2026** effective date; the policy's own *Changes to this policy*
  section promises an update before a version it affects is published, and that promise is what
  #212 sits ahead of #214 to keep.
- **Foreground service permissions** — not offered as a task until a bundle declaring `specialUse`
  is uploaded. That is **#79**. The text is ready in `docs/play-store-fgs-justification.md` (#74).
