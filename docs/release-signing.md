# Release signing and versioning

How a Play-uploadable build gets signed, and how versions are assigned. Passwords and key material
are deliberately absent from this file — fingerprints are public information, credentials are not.

## Play App Signing

Play App Signing is mandatory for new apps: Google holds the app signing key, and this repo holds an
**upload key** used only to sign bundles for upload. Losing the upload key is recoverable through
Google support; it is still a bad afternoon, so the keystore and its password are backed up off the
development machine.

## The upload key

- Keystore: `C:/Users/HSCCo/keys/race-timer-upload.jks` — **outside the repo**, never committed.
  `.gitignore` excludes `*.jks`, `*.keystore`, and `keystore.properties`.
- Alias: `upload` (RSA 4096, SHA384withRSA, valid to 2053).
- Certificate SHA-256 fingerprint:

  ```
  91:8A:82:57:4C:74:BC:3A:96:70:79:94:60:4A:53:5D:77:E3:10:18:54:5A:9B:83:C5:C0:08:AB:FE:2D:C8:F6
  ```

  This answers "is this the build I think it is?" later:
  `keytool -printcert -jarfile wear/build/outputs/bundle/release/wear-release.aab` must show this
  fingerprint on any bundle headed for Play.

## Local signing config

`wear/build.gradle.kts` reads a gitignored `keystore.properties` at the repo root:

```properties
storeFile=C:/Users/HSCCo/keys/race-timer-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

**`storeFile` is an absolute path, so this file is machine-specific and must never be copied
between machines.** On a second development machine the keystore lands wherever that machine puts
it, and the line above has to name *that* path. Copying the file verbatim points `storeFile` at a
directory that does not exist — and because the whole `signingConfig` is conditional on the file,
the failure is an **unsigned bundle and a green build**, not an error. This is the same trap as
having no `keystore.properties` at all, arrived at from the opposite direction.

The release `signingConfig` is only created when that file exists, so CI — which has no
`keystore.properties` — still configures and builds. Signing is local-only by decision (#71): the
move to CI signing is deferred as #81 until the release process is boring.

One consequence worth stating, because it makes a local run of the CI gate misleading: **CI and this
machine execute different task graphs for `:wear:bundleRelease`.** CI has no `keystore.properties`,
so no release `signingConfig` exists and the bundle builds unsigned; locally the file exists and the
build takes a signing path CI never runs. A `bundleRelease` failure here therefore does not imply a
CI failure, and a pass here does not prove CI's path works. To reproduce CI's, move
`keystore.properties` **outside the repo** — never rename it in place, since a `.bak` beside it would
hold both passwords in the repo root (the exact defect the #133 rehearsal found; `.gitignore` now
globs `keystore.properties*`).

Build a signed bundle with `./gradlew :wear:bundleRelease`; the output is
`wear/build/outputs/bundle/release/wear-release.aab`.

### Always check the bundle is actually signed

`BUILD SUCCESSFUL` does not mean signed. Because the `signingConfig` is created only when
`keystore.properties` exists, a machine missing that file builds an **unsigned** bundle and reports
success — *measured 2026-08-06 (#127)*, when the keystore had gone missing from this laptop and
`:wear:bundleRelease` completed in 1m41s producing an unsigned `.aab` and a
`wear-release-unsigned.apk`, with nothing in the build output saying so.

So the signature is a separate question from the build, and it has to be asked separately:

```bash
keytool -printcert -jarfile wear/build/outputs/bundle/release/wear-release.aab
```

`Not a signed jar file` is the failure. A signed bundle prints the certificate, and its SHA-256 must
match the fingerprint above.

**Do not run that command against the APK.** `keytool -printcert -jarfile` reads **v1 JAR signatures
only**, and AGP signs this app with APK Signature Scheme v2 without emitting v1 — so on a correctly
signed APK it prints `Not a signed jar file` *and exits 0*. Measured 2026-08-10 (#127) on an APK that
`apksigner` verified against the fingerprint above in the same minute. The command is right for the
bundle and reports the opposite of the truth for the APK.

Use `apksigner`, which exits non-zero when verification actually fails:

```bash
apksigner verify --print-certs wear/build/outputs/apk/release/wear-release.apk
```

Two consequences worth stating rather than rediscovering:

- **Never test keytool's exit code.** `keytool ... && echo signed` prints `signed` in both
  directions. Check the output.
- **The two tools spell the fingerprint differently** — keytool colon-separated uppercase
  (`91:8A:82:...`), apksigner bare lowercase (`918a8257...`). Strip separators and casefold before
  comparing, or an identical certificate reads as a mismatch.

The APK also carries a cruder tell in its filename: `wear-release.apk` when signed,
`wear-release-unsigned.apk` when not. That one needs no tooling, but it says only that *something*
signed it, never *with what*.

## Release artefacts and crash reports

`:wear:bundleRelease` is finalized by `archiveReleaseArtifacts`, which copies the bundle and its R8
mapping to `release-archive/v<versionCode>/` at the repo root. That directory is gitignored, and it
exists because `build/outputs/` is wiped by `clean` — a crash report filed after the next build would
otherwise have no mapping to deobfuscate against.

Two things to know about what the mapping is worth here:

- **The app's own frames are already readable.** `proguard-rules.pro` keeps `com.racetimer.**`
  wholesale, so R8 renames only library code and `mapping.txt` maps only library frames. That keep is
  documented in place, including the fact that no measured failure sits behind it; narrowing it is
  #128's to verify.
- **The archive is local-only until it is uploaded.** Play Console takes the mapping alongside the
  bundle (#79) and deobfuscates server-side, which is what makes it durable. Until that upload
  happens, `release-archive/` lives on one laptop — the same shape of risk that lost the upload
  keystore's local copy on 2026-08-06.

Archived per upload, alongside the `versionCode` bump the release commit carries.

## Restoring the upload key on a new machine

The key is only as recoverable as its weakest half. Both halves are backed up, in **two different
stores**, and neither is in this repo:

| Half | Where it lives |
|---|---|
| `race-timer-upload.jks` | Google Drive, 4,296 bytes, linked from #71 |
| Store password and key password | **Google Password Manager**, entry `race-timer-upload-keystore.com`, username `upload` |

**One secret, not two.** `storePassword` and `keyPassword` hold the **same value** for this keystore, so the
manager entry stores one password and both properties take it. The entry's *username* field carries the key
alias, `upload`, which is the third thing a restore needs and is otherwise easy to forget.

**What this protects against, and what it does not.** Owner decision, 2026-08-10. The two halves are
in separate stores, so no single folder holds both and a shared Drive link discloses nothing usable.
They are behind the **same Google account**, so that account is the single control that matters: 2FA
on it is what this arrangement rests on, and it is load-bearing rather than advisory. The rejected
alternative was a password manager on an independent account, which removes the shared-account
exposure at the cost of a second tool to maintain; it stays the upgrade path if this key ever guards
something already published.

### The procedure

Agents do not move key material — that division held through #71 and #127 and it holds here. The
owner performs steps 1 and 2; an agent can verify from step 3 down, by metadata and hash only.

1. Download `race-timer-upload.jks` from Drive to `C:/Users/HSCCo/keys/`. Confirm **4,296 bytes**; a
   different size means a different file, and the fingerprint check below will fail anyway.
2. Recreate `keystore.properties` at the repo root using the template in *Local signing config*
   above, taking both passwords from Google Password Manager. It is gitignored and stays local.
3. Build: `./gradlew :wear:clean :wear:bundleRelease`. **`BUILD SUCCESSFUL` proves nothing here** —
   see *Always check the bundle is actually signed*. A missing or wrong `keystore.properties` yields
   an unsigned bundle and the same green line.
4. Verify the certificate is the one this project is known by:

   ```bash
   keytool -printcert -jarfile wear/build/outputs/bundle/release/wear-release.aab
   ```

   The SHA-256 must equal the fingerprint recorded under *The upload key* above, which is the value
   established on #71:

   ```
   91:8A:82:57:4C:74:BC:3A:96:70:79:94:60:4A:53:5D:77:E3:10:18:54:5A:9B:83:C5:C0:08:AB:FE:2D:C8:F6
   ```

   Anything else — including `Not a signed jar file` — means the restore did not work. Do not upload
   the bundle.

**A procedure that has never been run is a claim, not a recovery path** — so this one was rehearsed
rather than merely written. #133, closed 2026-08-10, is the record.

The rehearsal found three things the written-and-reviewed procedure had not, which is the argument
for running it rather than a footnote to it:

- **`.gitignore` was exact-name.** `keystore.properties.PRE-REHEARSAL` — the obvious name for a
  working backup — was untracked and *not ignored*, holding both passwords in the repo root for
  about twenty minutes. The rule is now globbed (`keystore.properties*`), so name any working copy
  with that prefix and never a suffix-free variant.
- **Both passwords hold the same value**, so the manager stores one secret rather than the two the
  prose implied.
- **"It's in Google Password Manager" is a category, not a location.** The entry name is what a
  restore actually needs, which is why it is written out above.

Generalised into cairn as `running-a-procedure-finds-what-writing-it-cannot-2026-08-10`.

## Losing the upload key: which side of the line this project is on

**Nothing has been uploaded to Play yet** — `versionCode` is still `1` and no track has received a
bundle. That places this project on the **cheap** side of the line, and it is worth knowing the line
moves permanently at the first upload:

- **Before the first upload** — losing the upload key costs a `keytool` run. Generate a new keystore,
  update `keystore.properties`, record the new fingerprint here. No one outside this repo has seen
  the old key, so nothing depends on it. Free.
- **After the first upload** — the key is registered with Play App Signing as *the* accepted upload
  identity. Replacing it is a **Google support request**, not a local command: you generate a new key,
  raise a request to register it, and wait. Uploads are blocked until it is granted.

So the recovery path above is cheap insurance today and becomes load-bearing the moment #79 pushes the
first bundle. That is the deadline on this work, and it is not a date.

## Version strategy

Set in `wear/build.gradle.kts` `defaultConfig`, currently `versionCode = 1` / `versionName = "1.0"`.

- **`versionCode`** is a monotonic integer, bumped by **+1 for every bundle uploaded to Play** —
  any track, including internal testing. Play rejects a duplicate `versionCode` permanently and a
  used value can never be reclaimed, so the bump happens in the same commit as the release being
  cut, and an upload that gets rejected for other reasons still burns its number.
- **`versionName`** is the human-facing `MAJOR.MINOR` string shown in the store listing. It carries
  no uniqueness requirement and is bumped when the release is worth naming, independently of
  `versionCode`.
