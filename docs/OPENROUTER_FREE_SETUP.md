# OpenRouter free-model setup in SheV Agent

SheV connects directly to OpenRouter's OpenAI-compatible Chat Completions API.

- Base URL: `https://openrouter.ai/api/v1`
- Default model: `openrouter/free` (automatically routes to an available free model)
- Authentication: `Authorization: Bearer <OpenRouter API key>`
- Endpoint: `/chat/completions`

## Setup

1. Open https://openrouter.ai/keys and sign in.
2. Create an API key. OpenRouter says signup and starting use do not require a subscription or payment card.
3. In SheV Agent open Settings → Connections → **Бесплатные AI-модели · OpenRouter**.
4. Paste the key. Keep the model as `openrouter/free`.
5. Save the key, then press **Проверить подключение**.
6. Once the test returns an answer, enable **Использовать OpenRouter для основного чата**.

The key is encrypted with a dedicated AES-GCM key in Android Keystore. SheV does not log the key. The current message and bounded recent conversation context are sent to OpenRouter when this provider is selected. The remote model cannot directly execute actions on the phone.

## Free-tier limitations

OpenRouter lists free model variants (IDs ending in `:free`) at $0 model pricing. The free variants are rate limited. Current published limits are 20 requests per minute and 50 requests per day for accounts that have not purchased credits. Buying credits is not required to use free models, but can raise the daily request cap. The automatic `openrouter/free` router can select a model that is currently available; model availability and provider capacity can change, so a free model may occasionally be unavailable or return a rate-limit error.

Do not add a payment method or buy credits for this setup. SheV defaults to `openrouter/free`; do not manually enter a paid model ID.

## Official references

- API keys: https://openrouter.ai/keys
- Free models: https://openrouter.ai/collections/free-models
- API quickstart: https://openrouter.ai/docs/quickstart
- Rate limits: https://openrouter.ai/docs/api-reference/limits
