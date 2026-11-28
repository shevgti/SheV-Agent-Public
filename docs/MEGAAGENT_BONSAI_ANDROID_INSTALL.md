# MegaAgent — Ternary Bonsai 2 9B on Android

## Scope

- Phone build only; target ABI is `arm64-v8a`.
- Model: **Ternary Bonsai 2 9B**, repository `prism-ml/Ternary-Bonsai-2-9B-gguf`.
- Initial model artifact: `Ternary-Bonsai-2-9B-Q2_0.gguf` (about 2.56 GB).
- Initial scope is text inference. The separate vision projector (`mmproj`, about 0.62 GB) is intentionally not downloaded until image input is implemented.
- Keep the existing Gemma/LiteRT backend intact. Bonsai is a separate GGUF backend.
- Five specialist roles (researcher, planner, engineer, executor, reviewer) share one loaded model/runtime; never load five model copies. Bonsai chat now routes handler-backed tool calls through the same schema validation, host executor, capability grants and policy as the ADK runtime. Each role has a restricted capability allowlist. Verified skills are retrieved from the local library and injected as task context; the starter catalog contains 11 curated playbooks. High-risk actions remain blocked unless a separate explicit-approval flow is provided.

## Why the runtime is a separate component

The current app model store and runtime are LiteRT-LM-specific: its pinned artifact is a `.litertlm` file and `SheVAgentRuntime` constructs `LiteRtLmModel`. Bonsai is GGUF and requires llama.cpp. Renaming a GGUF file or passing it to LiteRT-LM will not work.

The supported installation has three independently verifiable pieces:

1. **Native runtime** — ARM64 Android shared libraries built from the PrismML fork. The fork is required for the Prism-specific PQ2_0/PTQ1_0 encodings; the first integration targets its Q2_0 artifact.
2. **Model data** — the pinned Hugging Face GGUF model artifact, downloaded into app-private storage with resumable transfer, a verified content digest, and atomic promotion.
3. **App bridge** — a JNI/Kotlin backend that loads the GGUF using the native runtime, applies a conservative 2048-token context initially, serializes generation calls across agents, and releases native memory on unload. Token streaming is not yet implemented in the Bonsai JNI bridge.

Do not mark the model as installed until all three checks pass. The native runtime is built and packaged separately from model weights so the APK does not contain a multi-gigabyte model.

## Native runtime build

Workflow: `.github/workflows/bonsai-android-runtime.yml`.

It builds the PrismML llama.cpp source at immutable commit
`43f31f400c66dd44ce52a044119db77c271fed06` with Android NDK 27.2.12479018,
`arm64-v8a`, Android API 28, shared libraries, KleidiAI enabled, and OpenMP disabled.
The workflow emits a runtime ZIP and SHA-256 file as an Actions artifact and publishes the same files to the durable prerelease tag `bonsai-android-arm64-latest` for in-app installation.

The in-app installer downloads the model from an immutable Hugging Face revision and verifies its SHA-256. It downloads the runtime release asset, verifies it against the SHA-256 pinned in the app (`7319130a139a079e957c2640e244c8ed53bf64d970efd33ddc4133fc75e5f720`), then extracts it with path-traversal protection into app-private storage.

## Required app installation UX

- Explicit user action before downloading either component; show sizes and storage requirements first.
- Show separate progress/status for runtime and model; support pause/cancel/retry and HTTP range resume.
- Keep partial downloads separate from installed files.
- Verify pinned source revision, expected file length, and SHA-256 before atomic install.
- Install into app-private storage; never execute downloaded shell scripts or binaries.
- Verify ABI and required shared libraries before loading the native engine.
- On failure, retain the existing LiteRT model and backend unchanged and report the failed stage.
- Check available memory before loading. A 16 GB device specification does not guarantee that a 9B model will fit alongside Android and the app; begin with a small context and expose a clear memory warning.

## Integration and acceptance checklist

- [ ] Runtime ZIP builds reproducibly in CI and has a recorded SHA-256.
- [ ] Runtime bundle is fetched by the app from a pinned release/artifact URL and verified.
- [ ] GGUF model download is pinned to an immutable Hugging Face revision and verified against the repository's LFS SHA-256.
- [ ] JNI bridge loads the model and returns a one-token / short-prompt smoke-test response.
- [ ] Streaming generation, cancellation, unload, and reload are tested.
- [ ] Existing LiteRT/Gemma download, verification, and inference tests remain green.
- [x] All five specialist roles share the same backend/model instance sequentially.
- [x] Bonsai chat can request handler-backed tools through the shared registry/executor and CapabilityPolicy; each role has a restricted capability allowlist.
- [x] Verified local skill context is retrieved for the task; 11 curated built-in playbooks are seeded into the local library.
- [ ] Add independent tests for Bonsai JSON tool-call parsing and role-level allowlists.
- [ ] Implement token streaming and an explicit approval UI for high-risk tool actions.
- [ ] Device test on the target POCO confirms load success, memory headroom, thermal behavior, sustained generation speed, tool invocation and subagent synthesis.

## Upstream references

- Model: https://huggingface.co/prism-ml/Ternary-Bonsai-2-9B-gguf
- Runtime fork: https://github.com/PrismML-Eng/llama.cpp
- Runtime commit: https://github.com/PrismML-Eng/llama.cpp/commit/43f31f400c66dd44ce52a044119db77c271fed06
- Android build guidance: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
