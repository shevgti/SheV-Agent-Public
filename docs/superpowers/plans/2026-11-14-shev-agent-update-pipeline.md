# SheV Agent Update Pipeline Implementation Plan

> **For agentic workers:** Execute inline in this session using test-first changes and verify every workflow result.

**Goal:** Make every successful main-branch Android build publish a uniquely identifiable GitHub Release that the app's single update checker can discover, verify, download, and hand to Android for installation.

**Architecture:** Keep GitHub Releases as the public update channel. Publish each build under a unique tag derived from the app version and monotonically increasing Android versionCode; include explicit versionName/versionCode metadata and SHA-256 assets. Route scheduled notifications and the settings card through ReleaseUpdater, with archive validation before installation.

**Tech Stack:** Kotlin, Android PackageManager/FileProvider, JUnit, GitHub Actions, Gradle.

**Spec:** User-approved update repair: one update mechanism for notifications/manual checks, unique release identifiers, monotonically increasing versionCode, SHA-256 verification, APK identity/signature validation, and reliable system installer handoff.

## Global Constraints
- Keep package/application ID `com.shev.agent`.
- Preserve minSdk 26 and ARM64 Codex runtime packaging.
- Never install an APK unless its package identity, versionCode, signing certificate, and SHA-256 are validated.
- Do not publish a release from an unsuccessful Android workflow.
- Keep update notifications and manual checks on the same release metadata and download implementation.

## Review Focus
- Existing installations below the new versionCode discover the latest release.
- Repeated builds with the same versionName still receive distinct tags and remain discoverable.
- Old releases without versionName metadata remain readable using their tag as fallback.
- Missing/malformed checksum or APK metadata fails closed with a clear error.
- An APK signed with a different key or for a different package is rejected before Android's installer opens.

---

### Task 1: Release metadata parsing
**Files:**
- Create: `android/app/src/main/java/com/shev/agent/updates/ReleaseManifestParser.kt`
- Create: `android/app/src/test/java/com/shev/agent/updates/ReleaseManifestParserTest.kt`
- Modify: `android/app/src/main/java/com/shev/agent/updates/ReleaseUpdater.kt`

**Interfaces:**
- Produces `ReleaseManifestParser.parse(JSONObject, currentVersionCode): ReleaseInfo?`.
- Preserve fallback parsing for the currently published release body, which has `versionCode` but no `versionName`.

- [ ] Add tests for metadata parsing, same-versionName distinct builds, outdated release, missing checksum/APK, and malformed versionCode.
- [ ] Run the new test and observe the expected failure.
- [ ] Implement parser and route ReleaseUpdater.check through it.
- [ ] Run parser tests and the full unit suite.

### Task 2: APK identity and signer validation
**Files:**
- Modify: `android/app/src/main/java/com/shev/agent/updates/ReleaseUpdater.kt`
- Create/modify: `android/app/src/test/java/com/shev/agent/updates/ReleaseInstallValidationTest.kt` (pure policy tests where possible).

- [ ] Validate package name and versionCode from the downloaded archive before invoking PackageInstaller.
- [ ] Compare archive signing certificate(s) with the installed application's signing certificate(s); reject mismatch.
- [ ] Keep checksum verification before archive inspection and installation.
- [ ] Verify API 26+ behavior and run unit suite.

### Task 3: Single update path and publication
**Files:**
- Modify: `android/app/src/main/java/com/shev/agent/update/UpdateCheckWorker.kt`
- Modify: `android/app/src/main/java/com/shev/agent/updates/ReleaseUpdateCard.kt`
- Modify: `.github/workflows/publish-update.yml`
- Optionally remove obsolete `AppUpdater.kt` only after confirming there are no call sites.

- [ ] Keep notification worker and manual card on ReleaseUpdater.
- [ ] Publish only after successful main-branch Android workflow; retain manual dispatch.
- [ ] Allocate versionCode as max published code + 1.
- [ ] Generate a unique tag `v<versionName>-build.<versionCode>`; include `versionName:`, `versionCode:`, and checksum asset in release.
- [ ] Remove tag-push release recursion and fixed-tag collision.
- [ ] Verify GitHub Actions YAML, full unit suite, Android build, and actual published release metadata.

### Completion contract
- Latest public release has a unique tag, explicit versionName/versionCode, APK, checksum, and build-info.
- ReleaseUpdater returns it only when its versionCode exceeds the installed app's code.
- Both manual and notification checks use the same implementation.
- APK identity, signer, and checksum are checked before installer handoff.
- CI evidence is reviewed; no claim of device-level update success without a phone test.
