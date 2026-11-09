# Free private connection: OmniRoute + Tailscale

This guide is for a user who has a Windows/Linux/macOS computer that can stay online and an Android phone. OmniRoute runs on the computer; the phone connects privately over HTTPS. No public port forwarding is needed.

Tailscale's Personal plan is currently free for individual use (up to six users, unlimited user devices). Tailscale Serve provides HTTPS access only inside your private tailnet. See the official [Personal pricing](https://tailscale.com/pricing) and [Serve documentation](https://tailscale.com/docs/features/tailscale-serve).

## 1. Install the prerequisites

On the computer:
- Install Docker Desktop (Windows/macOS) or Docker Engine + Compose (Linux).
- Install Tailscale and sign in with your personal account.

On the Android phone:
- Install Tailscale and sign in to the same account.
- Keep the Tailscale VPN connected when using SheV Agent.

## 2. Run OmniRoute on the computer

Open a terminal:

~~~bash
git clone https://github.com/cheaper-inference/OmniRoute.git
cd OmniRoute
cp .env.example .env
~~~

Edit the .env file before starting:
- Replace INITIAL_PASSWORD=CHANGEME with a long unique password.
- Set API_KEY_SECRET to a random secret (the template documents openssl rand -hex 32).
- Set REQUIRE_API_KEY=true.
- Leave APP_BIND_HOST at its loopback default (127.0.0.1). Do not publish the service on all network interfaces.

Start the base profile:

~~~bash
docker compose --profile base up -d --build
~~~

Open the local dashboard on the computer at http://127.0.0.1:20128, sign in with the password, and connect at least one provider. Some providers offer free tiers; they may require separate accounts, verification, or their own credentials. OmniRoute itself does not grant unlimited free inference.

## 3. Create a least-privilege inference key

In the OmniRoute dashboard:
1. Open API Manager / API Keys (or Endpoints, depending on the dashboard version).
2. Create a key for SheV Agent.
3. Leave manage and admin scopes disabled. The Android app only needs inference access to /v1/chat/completions.
4. Copy the key once and keep it private.

Do not use your dashboard password, an oma_live_ management token, or an MCP management key in SheV Agent.

## 4. Give the phone private HTTPS access

On the computer, after both devices are signed in to Tailscale and HTTPS certificates are enabled for your tailnet, run:

~~~bash
tailscale serve --bg 20128
~~~

Tailscale prints a private HTTPS URL similar to:

~~~text
https://computer-name.your-tailnet.ts.net
~~~

This URL is reachable only from devices in your tailnet. Do not use tailscale funnel for this setup; Funnel is public internet exposure, while Serve is private tailnet access.

Keep the computer powered on and connected to Tailscale while you use the Android app.

## 5. Connect SheV Agent

In the app's **OmniRoute · AI-шлюз** panel:
- Address: https://computer-name.your-tailnet.ts.net/v1
- Model: auto or a model ID shown in the catalog
- API key: the inference-only key created above

Press **Сохранить**, then **Проверить**. If model discovery is blocked by the dashboard's separate login policy, enter a model ID manually and use the test prompt instead.

After the connection works, enable **Использовать OmniRoute для основного чата**. The main chat will then use the remote provider without requiring the large local Gemma download. Turn the switch off to return to local mode.

## Security and privacy

- Do not expose OmniRoute directly to the public internet.
- Keep the dashboard password strong and keep the dashboard/MCP routes inside your tailnet.
- Keep REQUIRE_API_KEY=true; leave the server bound to loopback and let Tailscale Serve proxy it.
- Use a dedicated inference-only key, never manage or admin.
- When primary OmniRoute chat is enabled, the app sends the current message, up to 12 recent completed turns, and the rolling summary to your configured provider. Disable the switch to stop remote transmission.
- Remote model responses cannot directly execute Android tools or device actions.
