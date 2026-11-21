# Video Intelligence for MegaAgent

Status: feasibility verified; Android integration is planned, not yet shipped.

## Selected upstream

Primary candidate: https://github.com/Hemanth-hexo/instagram-reel-mcp

Its README documents a Python MCP server with two tools:
- `inspect_reel(url | video_file)`: creator/caption/date/counts and technical media metadata.
- `analyse_reel(url | video_file)`: sampled keyframes, timestamped transcript (optional Whisper), shot boundaries, transitions, in-shot overlays, motion/camera movement, pacing and an explanatory guide.

It accepts public Instagram Reel/Post/TV URLs and user-uploaded MP4/MOV/M4V/WEBM/MKV. It uses yt-dlp + FFmpeg and optional local faster-whisper. It does not require a paid AI API key for local analysis. Public URL fetching is best-effort and can be blocked/rate-limited by Instagram. It explicitly does not bypass login walls, private accounts, age gates or access controls. Use only content the user owns or is allowed to analyse.

Important deployment constraints:
- It is a separate Python service, not an Android library. The Android app currently runs Kotlin ADK + a local text LiteRT model; adding this MCP server requires a separately hosted/reachable service or a substantial native Android port.
- Remote MCP clients require HTTPS and a protected endpoint. The upstream suggests deploying its container or running locally behind a tunnel. A long random endpoint path or bearer token is required; do not hardcode secrets in the APK.
- Vercel container deployment has practical upload limits; local upload is more reliable. The service may scale to zero and Instagram may block datacenter IPs.
- The user should not be asked to create API keys for basic local video analysis. Whisper model weights download once. Gemini-based alternative is not selected because it requires a Gemini API key.

## Integration design

1. Add a host capability `media.analyse_video` with READ_LOCAL_DATA / ANALYZE_DATA semantics. Do not grant it to generated specialists by default; require explicit allowlisting.
2. Add a user-facing attachment flow for local video files. Validate MIME/container with ffprobe on the analysis service, enforce duration/size limits, and return a stable job/file ID.
3. Add optional Instagram URL input. Only public content; on `not_public` or rate limiting, clearly request an uploaded video file.
4. Call `inspect_reel` then `analyse_reel`. Return structured metadata, timestamped transcript, keyframes, edit timeline, transitions, overlays and motion measurements.
5. Pass bounded text findings and selected keyframes into the MegaAgent reasoning turn. Ask the agent to separate observations from hypotheses and cite timestamps for each claim.
6. For implementation tasks, generate a proposed plan first; no automatic execution of downloaded code or actions. User approval remains required for side effects.
7. Add Russian-language tests using synthetic clips with known cuts, fades, overlays and camera motion; verify transcript-disabled and no-speech paths, malformed files, oversized/long clips, unavailable Instagram links, timeouts and cleanup/TTL.

## Why this is not yet enabled in the APK

The upstream project is a standalone MCP server, and the current Android app has no configured video-analysis endpoint or packaged Python/FFmpeg runtime. Silently pointing the app at an arbitrary public service would expose user videos and create an untrusted dependency. No endpoint, credentials, upload or external deployment have been configured. Therefore this commit records a verified integration design; it does not claim the feature is already working inside the app.

## Acceptance criteria before enabling

- Service runs in a reproducible container and passes upstream tests.
- Authenticated HTTPS endpoint is configured without embedding secrets in the APK.
- Local-file upload and analysis works end-to-end.
- Public Reel analysis either succeeds or returns an actionable `not_public` result without login bypass.
- Android tool registry applies capability policy and per-file limits.
- Unit/integration tests pass; uploaded media is deleted according to a documented TTL.
- User can inspect extracted findings before asking MegaAgent to implement anything.
