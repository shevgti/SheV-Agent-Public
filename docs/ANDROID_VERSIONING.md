# Android versioning and release rules

## Required invariant

Every APK published for installation must have an Android `versionCode` strictly greater than the highest previously published stable release. Android uses this integer to decide whether an APK can replace an installed package; `versionName` is only the human-readable version label and does not satisfy this requirement.

## Source of truth

- The latest published, non-draft, non-prerelease GitHub Release is the previous-release source of truth.
- Its release notes must contain a machine-readable line: `versionCode: N`.
- The Gradle baseline in `android/app/build.gradle.kts` is a floor, not the release allocator.
- The publishing workflow allocates `max(Gradle baseline, latest published versionCode) + 1` and injects it through `-PSHEV_VERSION_CODE=...`.

## Mandatory release gates

1. Stop if the latest stable release cannot be found or its `versionCode` cannot be parsed. Never guess or silently reuse a number.
2. Run unit tests with the allocated versionCode.
3. Build the APK with the same allocated versionCode.
4. Read the built APK manifest and verify its actual versionCode equals the allocated value and is greater than the previous published value.
5. Publish the APK, checksum, and build-info only after all checks pass.

Do not manually increment the Gradle baseline for each release. Do not publish an APK whose versionCode is equal to or lower than an installed/published build. Keep `versionName` and the release tag aligned as the user-facing release label.
