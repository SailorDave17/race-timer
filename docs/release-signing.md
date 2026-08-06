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

The release `signingConfig` is only created when that file exists, so CI — which has no
`keystore.properties` — still configures and builds. Signing is local-only by decision (#71): the
move to CI signing is deferred as #81 until the release process is boring.

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
match the fingerprint above. The APK carries the same tell in its filename: `wear-release.apk` when
signed, `wear-release-unsigned.apk` when not.

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

## Version strategy

Set in `wear/build.gradle.kts` `defaultConfig`, currently `versionCode = 1` / `versionName = "1.0"`.

- **`versionCode`** is a monotonic integer, bumped by **+1 for every bundle uploaded to Play** —
  any track, including internal testing. Play rejects a duplicate `versionCode` permanently and a
  used value can never be reclaimed, so the bump happens in the same commit as the release being
  cut, and an upload that gets rejected for other reasons still burns its number.
- **`versionName`** is the human-facing `MAJOR.MINOR` string shown in the store listing. It carries
  no uniqueness requirement and is bumped when the release is worth naming, independently of
  `versionCode`.
