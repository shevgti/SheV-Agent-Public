## SheV Chat companion app

The separate `:shevchat` Android application (`com.shev.chat`) is a no-API companion to the official ChatGPT Android app. It selects one of the user's projects, prepares a project-specific continuation prompt from the SheV-Memory entry point, copies it to the clipboard, and opens the installed ChatGPT app. Optional AccessibilityService can tap the official app's visible New chat control only after the user explicitly enables the service and requests the action.

**Platform boundary:** SheV Chat cannot read ChatGPT's private transcript or silently submit prompts through a supported public API. The user pastes the copied context in ChatGPT. No API key, API billing, WebView login, cookie access, scraping or private endpoint is used.

Build with `gradle :shevchat:assembleDebug`. See [SheV Chat architecture](docs/SHEV_CHAT_PRODUCT_ARCHITECTURE.md).

# SheV Agent Public

Public mirror of the SheV Agent Android application. This repository contains reviewed application source and build workflow. The original development repository remains separate.

## Build
Open Actions and run **SheV Agent Android**. Successful builds upload a debug APK artifact for testing. Debug APKs are not production releases.

## Hybrid architecture
ChatGPT acts as supervisory intelligence; the Android application remains the local executor and permission authority. See `docs/HYBRID_ARCHITECTURE.md` and `docs/BRIDGE_PROTOCOL_V1.md`.

## Safety
Review `docs/SAFETY.md`. Never commit signing keys, API tokens, local.properties or user data.

The public mirror build workflow runs unit tests and assembles a debug APK on pushes to the Android source tree.
