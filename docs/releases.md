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

## Why the count matters beyond provenance

This is also the only thing in this repo that **counts releases**, and one deferred decision reads
off it: [#81](https://github.com/SailorDave17/race-timer/issues/81) holds the move of release
signing into CI until *three or more releases have been cut by hand*. Until this table has three
uploaded rows, that trigger cannot be checked — which is why #81's AC 1 points here.

## The log

| versionCode | versionName | Commit | Track | Uploaded | Notes |
|---|---|---|---|---|---|
| 1 | 1.0 | [`cadaea9`](https://github.com/SailorDave17/race-timer/commit/cadaea9) | Internal testing | *(not yet uploaded)* | First build. Prepared 2026-08-12; **re-taken** from `cadaea9` after the artifact was rebuilt — see below. |

## How each field is established

Do not copy these from a previous row — every one of them is a measurement, and the point of the log
is that it was taken rather than remembered.

- **`versionCode` / `versionName`** — `wear/build.gradle.kts` `defaultConfig`, in the commit named in
  the row. `versionCode` is bumped in the same commit as the release being cut; see
  [`release-signing.md`](release-signing.md) §Version strategy for why a burned number can never be
  reclaimed.
- **Commit** — `git rev-parse HEAD` on a **clean** tree at build time. A dirty tree makes the SHA a
  lie about what shipped, so check `git status` before trusting it. **If the bundle is rebuilt before
  it is uploaded, re-take this field** — a prepared row's SHA describes the artifact that existed
  when the row was written. A rebuild used to replace that artifact silently; since
  [#184](https://github.com/SailorDave17/race-timer/issues/184) the archive records its own
  provenance and will not do so, but the row is still yours to keep honest.
- **Uploaded** — the date Play accepted the bundle, from the Console, not the date it was built.
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
