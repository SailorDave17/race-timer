# Release log

Every bundle **uploaded to Play**, newest first. This file exists to keep one question answerable:
*which commit is on testers' watches?* Without it that becomes unanswerable within two releases
([#79](https://github.com/SailorDave17/race-timer/issues/79) AC 11).

## What counts as a row

A row is written **when the upload happens**, not when the build is cut. A bundle that was built and
never uploaded burns no `versionCode` and is not a release — recording it here would make this log
disagree with Play, which is the one thing it cannot afford to do.

So a row with an empty **Uploaded** cell is a *prepared build*, not a release. It stays that way
until somebody uploads it and fills the date in.

## Why the count mattered beyond provenance — and no longer does

This used to be the only thing in this repo that **counted releases**, because one deferred decision
read off it: [#81](https://github.com/SailorDave17/race-timer/issues/81) held the move of release
signing into CI until *three or more releases had been cut by hand*, and its AC 1 pointed here.

**#81 shipped on 2026-08-18 with the count at one.** The owner took the decision early rather than
waiting for the trigger, so the trigger was discharged by the work landing rather than by firing.
Counting the rows is therefore **no longer a step in writing one** — that instruction is retired, and
this paragraph exists so the retirement is visible to whoever next reads the row-writing procedure
below and wonders where the counting step went.

The log's original purpose is untouched: *which commit is on testers' watches?*

## The log

| versionCode | Form factor | versionName | Commit | Track | Uploaded | Notes |
|---|---|---|---|---|---|---|
| 1 | Wear | 1.0 | [`089f216`](https://github.com/SailorDave17/race-timer/commit/089f216) | `wear:internal` | 2026-08-13 | First build. Track cell read *"Internal testing"* — the Console's label — until 2026-08-18, when the Play API was asked directly and answered **`wear:internal`**, a Wear-form-factor track distinct from the plain `internal` track, which is empty. The label and the track id are not the same string, and #81's workflow publishes by id. Commit **re-taken a second time**: prepared from `cadaea9` on `develop`, then rebuilt from `release` at `089f216`, which is the artifact Play accepted. **Rolled out to internal testers** — owner-asserted 2026-08-17. This cell read *"sitting as a draft on the track — no tester has it yet"* until then: written true at upload time on 2026-08-13, and never revisited. |

## One counter, two form factors (#211)

`versionCode` is **one monotonic counter shared by both modules** (epic #196 decision D3), which is
why this table has a **Form factor** column: the number alone no longer says which artifact took it.

Both modules declare the same `applicationId`, so Play treats them as one app and a `versionCode` is
permanently unique within it. `:wear` burned 1 on 2026-08-13; `:phone` holds 2, allocated by #211 and
not yet uploaded. **The next upload takes the next free number whichever module ships it** — so if
the watch ships an update before the phone does, the watch takes 3 and the phone's 2 waits.

`./gradlew checkVersionCodeCollision` refuses two modules declaring the same number under one
applicationId, and every `bundleRelease` depends on it — so this table records the allocation rather
than being the only thing preventing a collision.

## Cutting a release from CI (#81)

Since #81 the ordinary way to release is to bump `versionCode`/`versionName` in the module being
released — `wear/build.gradle.kts` or, since #211, `phone/build.gradle.kts` — merge, then push a tag:

```bash
git tag v1.1 && git push origin v1.1
```

`.github/workflows/release.yml` then signs **both** modules, verifies each certificate against the
recorded fingerprint, publishes each to its own track (`:wear` to **`wear:internal`**, `:phone` to
**`internal`** — the non-Wear form factor's track, since #211), and attaches both `.aab` files and
both `mapping.txt` files to the run. The tag must match the `versionName` of **both** modules or the
workflow refuses before building.

**A row is still written by hand, and it is still written when the upload happens.** CI does not
touch this file. What changes is where the fields come from:

- **Commit** — the tagged commit, `git rev-parse --short <tag>`. CI builds from a clean checkout of
  exactly that commit, so the dirty-tree caveat below cannot apply to a CI release.
- **Uploaded** — still Play's date from the Console, still UTC. The workflow succeeding tells you
  the bundle was accepted by the API; the Console is what dates it.
- **Notes** — record the workflow run URL. That is the CI equivalent of the build-time verification
  evidence recorded for build 1, and it expires less than a sentence about track state does.

Local signing remains available and correct for a release cut by hand; `release-signing.md` covers
both paths and says which is which.

## How each field is established

Do not copy these from a previous row — every one of them is a measurement, and the point of the log
is that it was taken rather than remembered.

- **`versionCode` / `versionName`** — the `defaultConfig` of **the module being released**
  (`wear/build.gradle.kts` or `phone/build.gradle.kts`), in the commit named in the row. Since #211
  the counter is shared across both, so the number also tells you which module *cannot* use it; see
  [`release-signing.md`](release-signing.md) §Version strategy for why a burned number can never be
  reclaimed.
- **Commit** — `git rev-parse HEAD` on a **clean** tree at build time. A dirty tree makes the SHA a
  lie about what shipped, so check `git status` before trusting it. **If the bundle is rebuilt before
  it is uploaded, re-take this field** — a prepared row's SHA describes the artifact that existed
  when the row was written. A rebuild used to replace that artifact silently; since
  [#184](https://github.com/SailorDave17/race-timer/issues/184) the archive records its own
  provenance and will not do so, but the row is still yours to keep honest.
- **Uploaded** — the date Play accepted the bundle, from the Console, not the date it was built.
  **The Console renders this in UTC**, so an evening upload from a US timezone is stamped the *next*
  day: versionCode 1 was uploaded at 21:25 EDT on 2026-08-12 and the Console reads
  `Aug 13, 2026, 1:25 AM`. Take the Console's date, not your local one — otherwise this column and
  Play disagree by a day and the log stops being the tiebreak it exists to be.
- **Notes** — free text, with one rule: a sentence describing the **track state** (draft, rolled
  out, halted) is a claim with an expiry, and this table has no instrument that reads Play. Date it,
  and **re-read the previous row's note whenever you write a new one**. That used to hang off
  counting the rows for #81; #81 has shipped and the counting step is retired, so the obligation is
  re-anchored here rather than quietly leaving with it — the occasion is now writing a row, which is
  the only moment anyone opens this file with intent. Row 1's said *draft, no tester has it yet* after that had stopped
  being true — for how long is unreconstructable, because nothing dated the rollout, which is the
  second half of the same defect. The log's whole purpose is answering *which commit is on
  testers' watches?*
- **Signing** — every bundle here is signed with the upload key whose SHA-256 fingerprint is recorded
  in [`release-signing.md`](release-signing.md). Verify with `keytool -printcert -jarfile <bundle>`;
  that command is correct for an `.aab` and **wrong for an APK**, where it prints
  `Not a signed jar file` and still exits 0.

## The archive stamps its own provenance, and refuses a mismatch (#184)

`release-archive/v<versionCode>/` carries a **`provenance.txt`** written by `archiveReleaseArtifacts`:
the commit, the branch, whether the working tree was clean, and the version pair. Its first line is
the `identity:` — the commit plus the clean/dirty flag — and that is what gets compared.

**A release build will not silently replace an archive whose identity differs**, and it does one of
two things depending on which situation it is in:

| This build | Archive holds | What happens |
|---|---|---|
| **dirty** | anything different | **skipped**, loudly, build carries on — a dirty build is not an upload candidate, and `:wear:bundleRelease` is a quality-gate step that must not fail for a non-code reason |
| **clean** | a different clean build | **refused**, build fails — two shippable artifacts, and guessing which wins is not safe |
| either | the same identity | overwritten, which is a no-op |

Deliberate replacement in either case is `-ParchiveOverwrite`. Both messages name the two identities.

Why this exists, and why the flag is half the check: the archive keys on `versionCode` alone, which
moves once per Play upload while branches move constantly — so before #184 every release build on
every branch wrote to the same directory and the last one won, silently. *Measured 2026-08-12*: the
bundle prepared for the first upload was replaced **three times in three hours** by ordinary gate runs
on an unrelated branch. Nothing failed; the `.aab` is gitignored so `git status` stayed clean, and a
signed bundle from the wrong branch is indistinguishable from the right one without opening the dex.

**All three had the same commit.** A branch cut from the integration branch and not yet committed has
exactly the base SHA, so a commit-only check would have passed every time while the working tree
carried the change that made the artifact wrong. `clean@X → dirty@X` is the signal, and it is proven
in both directions — a same-commit dirty flip is refused, an identical re-run is not.

**A `dirty: true` stamp means the commit does not describe the bundle.** That is allowed — iteration
needs it — but do not upload one without recording what was different.

## Build 1 — verification taken at build time

Recorded here because the evidence is cheapest to capture at the moment the artifact is produced,
and some of it cannot be reconstructed afterwards.

- Built from a clean tree at `cadaea9` (develop, with #179 merged), all Gradle tasks executed
  (`--rerun-tasks`), so no step was served from the cache.
- `:shared:test` — **367 tests, 0 failures**, confirmed from `shared/build/test-results/test/*.xml`
  rather than from Gradle's own summary, and CI is green on `cadaea9` itself. `cadaea9` differs from
  the originally-recorded `f4f7e7d` by documentation only — `git diff f4f7e7d cadaea9 -- wear/src
  shared/src` is empty — so the app binary is the same one those tests ran against.
- Signature — `CN=Race Timer Upload, O=SailorDave17`, SHA-256 matching the fingerprint in
  `release-signing.md`.
- Package identity inside the bundle — `io.github.sailordave17.racetimer`, with `com.racetimer.wear`
  present only as the Kotlin namespace on component names. That split is deliberate and is
  [#169](https://github.com/SailorDave17/race-timer/pull/169); see
  `wear/build.gradle.kts` for why `namespace` deliberately did not move.
- Artifacts retained at `release-archive/v1/` — `wear-release.aab` and `mapping.txt`. That directory
  is gitignored, so **the mapping exists on one machine only** until the bundle is uploaded and Play
  takes its own copy. A crash report from this build cannot be deobfuscated without it.
