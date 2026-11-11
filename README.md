## Agent Creator — specialised agent factory

SheV Agent now includes a local, tool-free Agent Creator. It designs specialist manifests, validates host tool IDs and policies, stores drafts in Room, previews portable Claude Code Markdown, and exports only after explicit user approval. Generated definitions remain inert: approval does not activate an agent or grant permissions.

The Creator runs in a separate ADK session with no tools and a bounded model-call budget. It shares the already-loaded local model; it does not load another model copy or use an external LLM API.

See [Agent Creator guide](docs/AGENT-CREATOR.md) and [agent authoring rules](docs/AGENTS.md).

# SheV Agent Public

Public mirror of the SheV Agent Android application. This repository contains reviewed application source and build workflow. The original development repository remains separate.

## Build
Open Actions and run **SheV Agent Android**. Successful builds upload a debug APK artifact for testing. Debug APKs are not production releases.

## Hybrid architecture
ChatGPT acts as supervisory intelligence; the Android application remains the local executor and permission authority. See `docs/HYBRID_ARCHITECTURE.md` and `docs/BRIDGE_PROTOCOL_V1.md`.

## Safety
Review `docs/SAFETY.md`. Never commit signing keys, API tokens, local.properties or user data.

The public mirror build workflow runs unit tests and assembles a debug APK on pushes to the Android source tree.
