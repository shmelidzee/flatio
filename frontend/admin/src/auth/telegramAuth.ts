/** Payload delivered by the Telegram Login Widget's onauth callback. */
export interface TelegramAuthUser {
  id: number;
  first_name: string;
  last_name?: string;
  username?: string;
  photo_url?: string;
  auth_date: number;
  hash: string;
}

interface AuthResponse {
  accessToken: string;
  expiresIn: number;
}

/** Thrown when the backend rejects a Telegram Login Widget payload. */
export class TelegramLoginError extends Error {
  constructor(public readonly status: number) {
    super(status === 403 ? "not-admin" : "invalid");
  }
}

/**
 * Fetches the public Telegram bot username needed to render the Login Widget, via
 * GET /api/v1/auth/telegram-bot-username. Not user-specific and not a secret, so — like the
 * login exchange below — this does not go through apiFetch.
 */
export async function fetchTelegramBotUsername(): Promise<string> {
  const response = await fetch("/api/v1/auth/telegram-bot-username");
  if (!response.ok) {
    throw new Error(`Failed to fetch Telegram bot username: HTTP ${response.status}`);
  }
  const body = (await response.json()) as { botUsername: string };
  return body.botUsername;
}

/**
 * Exchanges a Telegram Login Widget payload for a JWT via POST /api/v1/auth/telegram-login-widget.
 * This endpoint is where the token is obtained, so it deliberately does not go through apiFetch
 * (which attaches an existing token and redirects on 401 — neither applies before login).
 *
 * @throws TelegramLoginError if the backend rejects the payload (401 invalid/expired
 *   signature, or 403 the Telegram account is not a registered admin)
 */
export async function loginWithTelegramWidget(user: TelegramAuthUser): Promise<AuthResponse> {
  const response = await fetch("/api/v1/auth/telegram-login-widget", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(user),
  });

  if (!response.ok) {
    throw new TelegramLoginError(response.status);
  }

  return (await response.json()) as AuthResponse;
}
