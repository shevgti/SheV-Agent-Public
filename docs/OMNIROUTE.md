# OmniRoute connection in SheV Agent

SheV Agent can connect to an independently hosted OmniRoute instance through its OpenAI-compatible API.

## Requirements

- Run OmniRoute on a computer or server that remains reachable while using the Android app.
- Put the gateway behind HTTPS. The Android app rejects cleartext HTTP.
- Create a dedicated API key limited to inference/chat. Do not use a global administrator (`admin`) or MCP management key.
- Keep the OmniRoute management dashboard and MCP administration interface private. Do not expose unauthenticated management endpoints to the internet.

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

The OmniRoute panel currently provides a separate gateway test/chat surface. It does not yet replace the on-device Gemma runtime or route the primary SheV conversation through OmniRoute. This separation avoids silently changing the active model or sending private local conversation history to a remote provider.

Before enabling gateway-backed primary chat, add an explicit provider selector, per-conversation data-sharing notice, cancellation/streaming support, and tool-call approval mapping. MCP management credentials must never be forwarded to the autonomous local executor.

## Network note

A phone's `127.0.0.1` address refers to the phone itself, not the computer running OmniRoute. Use a reachable HTTPS host name. Avoid exposing OmniRoute's management or MCP endpoints directly to the public internet.
