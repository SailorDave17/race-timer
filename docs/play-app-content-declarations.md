# Play Console — App content declarations

Every declaration in Play Console's **App content** section for `com.racetimer.wear` (Race Timer for
Wear OS), with the answer and the evidence it rests on. Tracked as issue #75.

This file exists to be a **diff base**. The answers are cheap to give and expensive to re-derive: at
the next release, or the moment a permission is added, the question is not "what is true?" but "what
changed since the last time we said this?" — and that is a question only a written answer can answer.

Two boundaries worth stating up front:

- **Console's own list is authoritative at fill-in time.** Play adds sections. A row here that
  Console no longer asks for is harmless; a section Console asks for that is *missing* here is the
  signal to come back and check the build rather than improvise at the upload screen.
- **This document records answers; it does not enter them.** Entering them requires the app to exist
  in Console, which is #79.

## The build these answers describe

| | |
|---|---|
| Package | `com.racetimer.wear` (`wear/build.gradle.kts:69`) |
| `versionCode` / `versionName` | `1` / `1.0` (`:74`, `:75`) |
| `minSdk` / `targetSdk` / `compileSdk` | `30` / `35` / `35` (`:70`, `:73`, `:66`) |
| Verified against | `develop` at `aafa5de`, 2026-08-11 |

An answer below is true of *that* build. A later `versionCode` inherits nothing automatically.

## The declarations

| Console section | Answer | Evidence |
|---|---|---|
| **Privacy policy URL** | The published URL of `docs/privacy-policy.md`. **Not yet live** — publication is #73. | Source committed at `docs/privacy-policy.md`; the URL is a repo-settings action, not a code fact |
| **Data safety** | **No data collected. No data shared.** | Long form below — this is the only row whose answer needs an argument |
| **Content ratings** (IARC questionnaire) | Every substantive question **no**; rates as low as the questionnaire allows | Long form below |
| **Target audience and content** | Adults. Not designed for, or appealing to, children; no child age band selected | A product decision, not a code fact. Selecting a child band pulls in Families policy requirements that do not apply |
| **Ads** | **No ads.** The app contains no advertising | No ad SDK among the dependencies (`wear/build.gradle.kts`, `dependencies` block); no `com.google.android.gms.permission.AD_ID` in the merged manifest |
| **News apps** | No | Not a news or magazine app |
| **COVID-19 contact tracing and status apps** | No | No contact-tracing or health-status function |
| **Government apps** | No | Not published on behalf of, or in association with, a government entity |
| **Financial features** | **None.** No in-app purchases, no subscriptions, no financial products | No billing or payments dependency (`wear/build.gradle.kts`); no purchase flow anywhere in `wear/src` |
| **Health apps** | **No.** The app reads no health or fitness data | No `BODY_SENSORS`, no `ACTIVITY_RECOGNITION`, no health permission in the merged manifest; no Health Services dependency. It is a sports app that measures **time**, not the athlete |
| **Data deletion** | No account exists, so there is nothing held off-device to delete. Local data is removed by uninstalling the app or clearing its storage from watch settings | No account or sign-in code anywhere in `wear/src`; nothing is transmitted (no `INTERNET` permission) |
| **Foreground service permissions** | `specialUse`, with the written justification | `docs/play-store-fgs-justification.md` — paste that document's *Declaration text* section. **This is the only row with real rejection risk** |
| **App access** | **All functionality is available without any special access.** No login, no region lock, no unlocked-content gate; no reviewer credentials needed | No account/sign-in code in `wear/src`; the only `startActivity` leaves for a **system settings screen**, never a login or a web page (`MainActivity.kt:726`) |

Two more that Console asks conditionally rather than as their own checklist rows:

| Console section | Answer | Evidence |
|---|---|---|
| **Advertising ID** (asked inside Data safety) | Not used | `com.google.android.gms.permission.AD_ID` is not declared in the merged manifest. From `targetSdk` 33 an app must declare it to read the ID at all, so its absence is the answer |
| **Photo and video permissions** (asked only if the permission is declared) | Not applicable | No `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` in the merged manifest |

## Data safety — the reasoning, not only the answer

The answer is **no data collected, no data shared**. Play's own definition is the thing to reason
from, rather than the intuition that a local file is obviously fine:

> "Collect" means transmitting data from your app off a user's device.
> — [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)

Two exemptions on the same page matter here: data **accessed and processed only on the device** does
not need disclosing, and data transferred off-device but **end-to-end encrypted** does not either.
The form is still mandatory for an app that collects nothing.

### What the app actually stores

One `SharedPreferences` file, `race_timer_state`, in the app's private storage
(`TimerService.kt:1014`). It holds **eight** keys:

| Key | What it is | Line |
|---|---|---|
| `sequence_id` | Which start sequence a race in flight is running | `TimerService.kt:1015` |
| `gun_elapsed_ms` | Scheduled gun time as a monotonic clock reading | `:1016` |
| `gun_wall_clock_ms` | Scheduled gun time as a wall-clock reading, for recovery after a restart | `:1017` |
| `captured_elapsed_ms` | Monotonic reading at the moment the race was saved | `:1018` |
| `picked_sequence_id` | The sequence the sailor last chose — a preference that outlives a race (#88) | `:1034` |
| `last_box_alert_seconds` | The lead time a race was last armed with (#104) | `:1048` |
| `raised_cue_stream` | Which audio stream a running race raised the volume on (#95) | `:1069` |
| `raised_cue_previous_volume` | What that stream's volume was before, so it can be put back (#95) | `:1070` |

Clock readings, a sequence identifier, a stream index and a volume integer. No name, no account, no
device identifier, no location, no health value, no free text — the app has **no text input at all**.

### The part that is not obvious: `allowBackup`

`android:allowBackup="true"` is set (`wear/src/main/AndroidManifest.xml:25`). Android's Auto Backup
is on by default for apps targeting API 23 or higher and **includes `SharedPreferences` files**, so
`race_timer_state` is eligible to be copied into a private folder of the user's Google Drive. Data
does leave the device.

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
  the app — a sweep of `wear/src/main` for `TextField`, `BasicTextField`, `EditText` and
  `RemoteInput` returns nothing. Every value the sailor sets is chosen from a picker or stepped with
  `+` / `−`.
- **No unrestricted access to the internet.** The app has no `INTERNET` permission, and the single
  `startActivity` call in the app opens a **system settings screen** (`MainActivity.kt:726`), never a
  browser or a web view.

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
| **A phone companion or Data Layer sync** | Data leaves the watch **by the app's own action** — the reasoning in *the part that is not obvious* above no longer applies, and Data safety must be re-answered from scratch. The standalone `meta-data` declaration in the manifest also becomes wrong |

The common shape: **a permission added to the manifest invalidates a declaration in Play Console, and
nothing in the build fails when it does.** Turning that dependency into an enforced check rather than
a paragraph is #83.

---

## Maintainer notes — not for Play Console

Every claim above was checked against `develop` at `aafa5de` on **2026-08-11**.

**Check the merged manifest, not the source file.** The shipped permission set is what
`wear/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
holds after a `:wear:bundleRelease`, and it is **not** the same list as
`wear/src/main/AndroidManifest.xml`. The source declares five permissions; the merged manifest carries
six — androidx.core injects `com.racetimer.wear.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, an
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

**Line numbers drift.** Every citation here was correct on the date above and will not stay correct;
re-cite them when this document is re-checked rather than trusting them. `docs/privacy-policy.md`
records the same hazard, from having been bitten by it.

**This document is a dependency of the manifest.** If a permission is added, or networking, an
account, a health sensor, an ads SDK or any third-party SDK is introduced, one or more answers above
is wrong the moment that change merges — see *What would change*. #83 exists to turn that dependency
into an enforced check.

**Known disagreement with `docs/privacy-policy.md`.** That document's *Information stored on your
device* table lists **four** keys; there are **eight** (the table above). The four it is missing —
`picked_sequence_id` (#88), `last_box_alert_seconds` (#104), `raised_cue_stream` and
`raised_cue_previous_volume` (both #95) — all landed between 2026-08-02 and 2026-08-05, before that
document's own re-check on 2026-08-09 restated "the four persisted keys" unchanged. None of them
alters a claim the policy makes: they are a preference, a preference, and a two-key receipt for
restoring a device volume, and none is personal, identifying, or transmitted. The **enumeration** is
incomplete, not the conclusion. Correcting it belongs to #73, which is still open.

**Where the entries actually get made.** #79 creates the app in Console; its acceptance criteria carry
"App content section complete with no outstanding items". #73 must publish the privacy policy first,
because the URL is a required field. The FGS text is ready in `docs/play-store-fgs-justification.md`
(#74).
