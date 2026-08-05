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

## Version strategy

Set in `wear/build.gradle.kts` `defaultConfig`, currently `versionCode = 1` / `versionName = "1.0"`.

- **`versionCode`** is a monotonic integer, bumped by **+1 for every bundle uploaded to Play** —
  any track, including internal testing. Play rejects a duplicate `versionCode` permanently and a
  used value can never be reclaimed, so the bump happens in the same commit as the release being
  cut, and an upload that gets rejected for other reasons still burns its number.
- **`versionName`** is the human-facing `MAJOR.MINOR` string shown in the store listing. It carries
  no uniqueness requirement and is bumped when the release is worth naming, independently of
  `versionCode`.
