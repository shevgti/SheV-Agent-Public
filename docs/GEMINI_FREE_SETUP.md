# Gemini free-tier connection in SheV Agent

SheV can use Google's Gemini API directly. The Android client uses Google's documented OpenAI-compatible endpoint:

- Base URL: `https://generativelanguage.googleapis.com/v1beta/openai`
- Default model: `gemini-2.5-flash`
- Authentication: `Authorization: Bearer <Gemini API key>`
- Chat endpoint: `/chat/completions`

## Setup

1. Open https://aistudio.google.com/apikey.
2. Create a Gemini API key for a project.
3. In SheV Agent open Settings → Connections → **Бесплатный AI · Google Gemini**.
4. Paste the key and leave model as `gemini-2.5-flash`.
5. Save the key, then use **Проверить подключение**.
6. After the test returns an answer, enable **Использовать Gemini для основного чата**.

The key is encrypted with a dedicated AES-GCM key in Android Keystore. SheV never logs the key. The remote chat sends the current message and bounded recent conversation context to Google. Remote Gemini responses cannot directly execute device actions.

## Free-tier caveats

Google's Gemini Developer API has a free tier for selected models, including Gemini 2.5 Flash at the time this guide was written. Free access has rate limits and model availability restrictions; limits can change by account, region, project and model. Google may use content from free-tier requests to improve its products, so do not send secrets, passwords, financial account data or other sensitive information.

Official references:
- https://ai.google.dev/gemini-api/docs/pricing
- https://ai.google.dev/gemini-api/docs/openai
- https://aistudio.google.com/apikey

## OmniRoute

OmniRoute remains an optional advanced gateway for combining multiple providers and fallback routes. It is separate from the direct Gemini integration and must run on a reachable computer/server. A phone-only setup does not host the gateway by itself. Do not configure OmniRoute until the direct Gemini connection is tested.
