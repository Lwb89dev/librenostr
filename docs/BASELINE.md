# LibreNostr build baseline

Record of compiling the **unmodified** Primal 3.5.25 import before any LibreNostr refactoring.

No production data-flow replacement has been done at this baseline.

## Upstream pin

| Field | Value |
|---|---|
| Upstream commit | `efb88b5af1db9d84eb36b471bf17d49d1c8a8a0c` |
| Message | Primal 3.5.25 release |
| Date | 2026-07-28 |
| Working tree | clean `main`, tracking `upstream/main` |

## Toolchain

| Tool | Version used on this machine |
|---|---|
| Java | OpenJDK 21.0.12 (`/usr/lib/jvm/java-21-openjdk-amd64`) |
| Kotlin (catalog) | 2.4.0 |
| Android Gradle Plugin | 9.2.1 |
| Gradle wrapper | 9.6.0 |
| compileSdk | 37 |
| targetSdk | 36 |
| minSdk | 26 |
| KSP | 2.3.9 |
| Hilt / Dagger | 2.59.2 / 1.3.0 |
| Compose BOM | 2026.06.00 |
| Room | 2.8.4 |
| Ktor | 3.5.1 |

`local.properties` is gitignored and must contain:

```properties
sdk.dir=<Android SDK path>
```

On this machine: `sdk.dir=/home/antona89/Android/Sdk`.

The AOSP flavor already contains `app/src/aosp/google-services.json` in the tree. CI normally unpacks that file from secrets; a local AOSP debug compile does not need the Google flavor secrets.

## Flavors

`app` has dimension `distribution`:

- `google` — Play services, FCM, Play Billing
- `aosp` — F-Droid / Zapstore oriented build

LibreNostr baseline uses **aospDebug**.

## Build command

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew :app:assembleAospDebug
```

CI compile check:

```bash
./gradlew compileAospDebugKotlin
```

## Test commands (upstream CI)

From `.github/workflows/PR-workflow.yml`:

```bash
./gradlew ktlintCheck
./gradlew detekt
./gradlew lint
./gradlew testAospDebugUnitTest
./gradlew allTests
```

Recorded on this machine, 2026-08-27:

| Command | Result |
|---|---|
| `./gradlew :app:assembleAospDebug` | BUILD SUCCESSFUL, 41s |
| `./gradlew :app:testAospDebugUnitTest` | BUILD SUCCESSFUL, 1m 11s — **344 tests, 0 failures, 0 errors, 0 skipped** |
| `./gradlew allTests` | BUILD SUCCESSFUL, 1m 41s — **985 JUnit XML tests across modules, 0 failures** (includes the 344 app tests; iOS simulator tests skipped on Linux) |
| `ktlintCheck` / `detekt` / `lint` | not run in this baseline |
| `compileGoogleDebugKotlin` | not run (needs Google flavor `google-services.json` from CI secrets) |

## Successful build variant

| Item | Status |
|---|---|
| Variant | `aospDebug` |
| Task | `:app:assembleAospDebug` |
| Result | **BUILD SUCCESSFUL** (confirmation rebuild, 41s, 452 tasks, 132 executed / 320 up-to-date) |
| Artifact | `app/build/outputs/apk/aosp/debug/app-aosp-debug.apk` |
| Size | ~192 MB (debug, not minified) |
| First local APK | 2026-08-27 20:58 |

The first Gradle run of the import downloaded the 9.6.0 distribution and configured all KMP modules. The confirmation rebuild on the same machine succeeded. Configuration warnings are listed below. They are upstream deprecations, not LibreNostr regressions.

## Known warnings / failures

### Configuration warnings (non-fatal)

- Gradle 9.6 deprecations in several `build.gradle.kts` files (`properties`, `srcDirs`, `registering`/`getting` delegates).
- `:paging-runtime-ios` warns that `iosSimulatorArm64Test` cannot run on Linux.
- `:core:networking-lightning` warns that `commonTest` exists but Android host tests are not enabled.

### Tests

App unit tests and KMP `allTests` passed on this machine (see table above). Remaining gaps:

- `ktlintCheck` / `detekt` / `lint` — not recorded here
- Google flavor compile — not recorded here
- iOS simulator tests — skipped on Linux by design

### GitHub origin

`origin` is `https://github.com/Lwb89dev/librenostr.git` (private). The unmodified Primal history plus Phase 0 docs are pushed there. `upstream` remains fetch-only.

## What this baseline proves

- The imported tree compiles as an AOSP debug APK on Linux with JDK 21 and a current Android SDK.
- No LibreNostr code changes were required to compile.
- Refactoring can start from a known-good artifact.

## What this baseline does not prove

- Runtime behaviour without Primal cache servers.
- Unit / KMP / UI test health.
- Google flavor compile (`compileGoogleDebugKotlin` needs `app/src/google/google-services.json` from CI secrets).
- iOS / desktop targets.
