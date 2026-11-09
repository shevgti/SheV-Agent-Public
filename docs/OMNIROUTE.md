# OmniRoute connection in SheV Agent

SheV Agent can connect to an independently hosted OmniRoute instance through its OpenAI-compatible API. OmniRoute is a separate model gateway; it does not connect this app to the current ChatGPT conversation or make ChatGPT's supervisory intelligence available inside Android.

## Requirements

- Run OmniRoute on a computer or server that remains reachable while using the Android app.
- Put the gateway behind HTTPS. The Android app rejects cleartext HTTP.
- Create a dedicated API key limited to inference/chat. Do not use a global administrator (`admin`) or MCP management key.
- Keep the OmniRoute management dashboard and MCP administration interface private. Do not expose unauthenticated management endpoints to the internet.


For a no-public-port-forwarding setup using a computer + Android phone, follow [OMNIROUTE_TAILSCALE_SETUP.md](OMNIROUTE_TAILSCALE_SETUP.md).

## Required gateway security settings

OmniRoute's example environment defaults to `REQUIRE_API_KEY=false`. Before exposing an instance beyond loopback, enable `REQUIRE_API_KEY=true` (or the equivalent Dashboard → Feature Flags setting) so inference endpoints require a Bearer API key. The default Docker Compose profile binds published ports to `127.0.0.1`; do not change `APP_BIND_HOST` to `0.0.0.0` unless API-key enforcement is enabled or a trusted authenticated reverse proxy is in front.

Create an ordinary inference API key in Dashboard → API Manager / API Keys with **no** `manage` or `admin` scope. Those scopes grant management capabilities and are not needed by SheV Agent. Do not paste a dashboard password, `oma_live_…` management token, or MCP token into the Android app.

Note: OmniRoute documents that `GET /v1/models` follows its dashboard/login posture separately from the `REQUIRE_API_KEY` gate used for inference requests. If model discovery is unavailable under your server's login policy, enter the model ID manually and use the test-chat request to verify inference. Do not weaken dashboard authentication to make model discovery work.

Keep the management dashboard and MCP endpoints private. Only publish the HTTPS endpoint required by the Android client, with strong authentication and current provider credentials. Review OmniRoute's official [Management Auth guide](https://github.com/cheaper-inference/OmniRoute/wiki/Management-Auth) and [Environment reference](https://github.com/cheaper-inference/OmniRoute/blob/release/v3.8.54/docs/reference/ENVIRONMENT.md) before exposing the server.

## Android setup

1. Open the SheV Agent home screen and expand **OmniRoute · AI-шлюз**.
2. Enter the HTTPS base URL ending in `/v1`, for example `https://ai.example.net/v1`.
3. Enter a model ID or a route configured in OmniRoute.
4. Paste a dedicated inference-only API key.
5. Press **Сохранить**. The key is encrypted using a dedicated AES-GCM key in Android Keystore.
6. Press **Проверить** to query `GET /v1/models`.
7. Enter a short prompt and press **Отправить** to query `POST /v1/chat/completions`.

The token is never written to application logs. **Забыть ключ** clears the stored gateway configuration.

## Current integration boundary

The OmniRoute panel provides both a separate gateway test/chat surface and an explicit switch for the primary SheV chat. When the switch is enabled, the main chat sends the current message, up to 12 recent completed turns, and the rolling summary to the configured remote model. The user must enable this deliberately. Turn the switch off to return to the on-device Gemma runtime.

Remote OmniRoute responses do not receive direct access to Android tools or device actions. The remote-mode system instruction tells the model not to claim it performed local actions. Tool calling and approval-gated local execution remain available only through the local ADK runtime until a separately reviewed bridge is implemented.

Before enabling gateway-backed primary chat, add an explicit provider selector, per-conversation data-sharing notice, cancellation/streaming support, and tool-call approval mapping. MCP management credentials must never be forwarded to the autonomous local executor.

## Network note

A phone's `127.0.0.1` address refers to the phone itself, not the computer running OmniRoute. Use a reachable HTTPS host name. Avoid exposing OmniRoute's management or MCP endpoints directly to the public internet.
