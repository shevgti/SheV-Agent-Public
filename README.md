# SheV Agent Public

Public mirror of the SheV Agent Android application. This repository contains reviewed application source and build workflow. The original development repository remains separate.

## Build
Open Actions and run **SheV Agent Android**. Successful builds upload a debug APK artifact for testing. Debug APKs are not production releases.

## Hybrid architecture
ChatGPT acts as supervisory intelligence; the Android application remains the local executor and permission authority. See `docs/HYBRID_ARCHITECTURE.md` and `docs/BRIDGE_PROTOCOL_V1.md`.

## Safety
Review `docs/SAFETY.md`. Never commit signing keys, API tokens, local.properties or user data.

The public mirror build workflow runs unit tests and assembles a debug APK on pushes to the Android source tree.
