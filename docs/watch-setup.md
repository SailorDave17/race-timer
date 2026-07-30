# Watch Setup — Deploying to a Physical Wear OS Watch

How to get a debug build of Race Timer onto a real watch, and how to confirm the build that is running
is the one you just made. Written so re-onboarding a new watch or a new dev machine takes minutes.

Everything here was performed on hardware on 2026-07-30 unless a step is marked **UNVERIFIED**.

## Inventory

### Watch

| | |
|---|---|
| Model | Samsung Galaxy Watch 5 Pro (`SM-R925U`) |
| Platform | Wear OS on Android 16 (API 36), One UI Watch 8.0 |
| adb guid | `adb-RFATA20KL3F-vv8Xj6` |
| Connect address | `192.168.1.73:41017` — DHCP, expect this to change |

### Paired phone

Pairing a Samsung watch requires the **Galaxy Wearable** app, not the generic Wear OS companion app.
The phone is only needed for initial pairing and system settings — Race Timer runs standalone on the
watch, and no phone is needed at race time.

| | |
|---|---|
| Phone model | **TODO — record when next paired** |
| Galaxy Wearable version | **TODO — record when next paired** |

### Dev machine

| | |
|---|---|
| OS | Windows 11 Pro |
| adb | `C:\Users\HSCCo\AppData\Local\Android\Sdk\platform-tools\adb.exe`, with `ANDROID_HOME` set |
| JDK | Any modern JDK. `:shared` declares a JVM 8 toolchain, which is rarely installed; `settings.gradle.kts` registers the Foojay resolver so Gradle downloads a matching JDK instead of failing with *No matching toolchains found*. |
| Gradle | Wrapper-pinned to 8.4 — use `./gradlew`, never a system Gradle |

No Android Studio install is required for deploying. It is only convenient for creating emulator
images via the AVD Manager, and the emulator is **not** a substitute for this procedure — see
[Why hardware](#why-hardware) below.

## One-time watch setup

1. **Enable Developer options.** Settings → System → About → tap **Build number** ×7.

   Some Samsung/One UI versions route this through Settings → About watch → Software info → tap
   **Software version** ×7 instead. If Build number is not visible under About, look for Software
   info.

2. **Enable debugging.** Settings → Developer options → enable **ADB debugging** *and* **Wireless
   debugging**.

## Pairing over adb-over-Wi-Fi

The watch and dev machine must be on the same network with client isolation off.

1. `ping <watch-ip>` first. The watch answers ICMP, so this separates a network or AP-isolation
   problem from a pairing problem *before* you spend a pairing attempt.

2. On the watch, open Developer options → Wireless debugging → **Pair new device**, and leave that
   screen up. The pairing service only advertises while it is open.

3. Read **both** ports off the watch now:
   - the **connect** port, on the main *Wireless debugging* screen;
   - the **pair** port, on the *Pair new device* sub-screen, next to the 6-digit code.

   These are different, and this is the single biggest time sink in this whole procedure. `adb pair`
   needs the **pair** port. Pairing against the connect port fails with:

   ```
   error: protocol fault (couldn't read status message): No error
   ```

   which reads like a wrong or expired code but means the wrong port.

4. Pair, passing the code as an argument rather than answering the prompt — an agent or CI shell has
   no stdin for it:

   ```bash
   adb pair <watch-ip>:<pair-port> <6-digit-code>
   ```

5. `adb mdns services` — this discovers the connect address and auto-connects. Confirm with
   `adb devices -l`; the device should flip from `offline` to `device`.

Pairing survives reboots; the connect address does not. After a watch reboot or DHCP lease change,
step 5 alone is usually enough.

## Build and install

```bash
./gradlew :shared:test        # pure-JVM engine tests, no device needed — run these first
./gradlew :wear:installDebug  # build and deploy to the connected watch
```

The app installs as `com.racetimer.wear`.

`minSdk 30` / `compileSdk 34` / `targetSdk 34` install cleanly on this Android 16 (API 36) watch. No
`compileSdk` bump has been needed; don't raise it speculatively.

### Confirm what actually landed

A successful build log is not evidence the watch is running that APK — a stale install, a failed
incremental push, or a second connected device all look like success. Compare hashes:

```bash
adb shell pm path com.racetimer.wear     # -> package:/data/app/.../base.apk
adb pull <that-path> ./on-device.apk
sha256sum ./on-device.apk wear/build/outputs/apk/debug/wear-debug.apk
```

Do this whenever a change *appears* to have had no effect. It is faster than re-debugging a fix that
was never installed.

## Smoke check on hardware

Run these after a deploy. They are the minimum that says the watch build works, and they are
deliberately about the countdown surviving real device behaviour rather than about correctness of the
timer math — the math is covered by `:shared:test`.

- [ ] App launches on the watch, no crash on the sequence picker.
- [ ] One full 3-minute (Club) sequence runs to the gun, with the expected cues.
- [ ] The sequence survives a screen-off cycle: let the display dim, tap to wake, confirm it is still
      counting and still correct.

Haptic verification is deliberately **not** part of this check. Haptics are validated last in the
MVP.

## Why hardware

An emulator showing no symptom is evidence the emulator does not model the subsystem, not evidence
the bug is absent. The Wear emulator has no real accelerometer, so it cannot reproduce the
orientation bug that shipped in `82c8283` — and the revert that followed cited an emulator test as
proof the attribute was inert, which was wrong.

Verify anything touching sensors, orientation, power, radios, or haptics on this watch, and design
the check so a buggy and a fixed build can actually differ. For orientation specifically that means
testing with a **non-zero** wrist/button placement setting: at factory default (`0`) both builds look
identical.

See [CLAUDE.md](../CLAUDE.md) for the orientation and foreground-service constraints themselves.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `error: protocol fault (couldn't read status message): No error` on `adb pair` | Used the connect port | Use the pair port from the *Pair new device* screen |
| Watch not in `adb devices` after a reboot | Connect address changed | `adb mdns services` to rediscover; pairing itself persists |
| `ping` fails | Different network, or AP client isolation | Fix the network before touching adb |
| *No matching toolchains found* | JVM 8 toolchain absent and resolver not applied | Confirm the Foojay plugin block in `settings.gradle.kts` is intact |
| Change seems to have no effect on the watch | Stale APK on device | Compare hashes as above |
| Service dies shortly after start with `ForegroundServiceDidNotStartInTimeException` | A sticky restart delivering a null intent | `TimerService` returns `START_NOT_STICKY` on purpose — see CLAUDE.md before changing it |
