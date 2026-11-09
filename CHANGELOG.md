# SheV Agent changelog

## 0.1.8 — OmniRoute primary chat
- Added an explicit provider switch for the primary SheV conversation.
- Main chat can run through OmniRoute without downloading the 2.58 GB local Gemma model.
- Bounded encrypted chat history and rolling summary are sent only after explicit opt-in.
- Remote responses cannot directly execute local Android tools.

## 0.1.7 — OmniRoute integration preview
- Added a separate OmniRoute gateway panel to the home screen.
- Added model discovery and selection from the gateway's OpenAI-compatible model catalog.
- Added a prompt/response test surface for `/v1/chat/completions`.
- Added encrypted API-key persistence using a dedicated Android Keystore AES-GCM key.
- Added explicit removal of saved gateway credentials.
- Kept HTTPS mandatory for Android-to-server traffic.
- Documented secure deployment and the current integration boundary in `docs/OMNIROUTE.md`.

This is an integration preview, not yet the default provider for the primary SheV conversation. The local Gemma runtime remains available.
