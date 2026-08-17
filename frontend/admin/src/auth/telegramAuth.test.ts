import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TelegramLoginError, fetchTelegramBotUsername, loginWithTelegramWidget } from "./telegramAuth";
import type { TelegramAuthUser } from "./telegramAuth";

const USER: TelegramAuthUser = {
  id: 12345,
  first_name: "John",
  auth_date: 1_700_000_000,
  hash: "signed-hash",
};

describe("fetchTelegramBotUsername", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_return_bot_username_when_request_succeeds", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ botUsername: "flatio_bot" }), { status: 200 }),
    );

    const result = await fetchTelegramBotUsername();

    expect(result).toBe("flatio_bot");
    expect(fetch).toHaveBeenCalledWith("/api/v1/auth/telegram-bot-username");
  });

  it("should_throw_when_request_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchTelegramBotUsername()).rejects.toThrow("HTTP 500");
  });
});

describe("loginWithTelegramWidget", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_return_auth_response_when_backend_accepts_payload", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ accessToken: "jwt-token", expiresIn: 3600 }), { status: 200 }),
    );

    const result = await loginWithTelegramWidget(USER);

    expect(result.accessToken).toBe("jwt-token");
    expect(result.expiresIn).toBe(3600);
    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/auth/telegram-login-widget",
      expect.objectContaining({ method: "POST", body: JSON.stringify(USER) }),
    );
  });

  it("should_throw_not_admin_error_when_backend_returns_403", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 403 }));

    await expect(loginWithTelegramWidget(USER)).rejects.toThrow(TelegramLoginError);
    try {
      await loginWithTelegramWidget(USER);
    } catch (err) {
      expect((err as TelegramLoginError).status).toBe(403);
      expect((err as TelegramLoginError).message).toBe("not-admin");
    }
  });

  it("should_throw_invalid_error_when_backend_returns_401", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 401 }));

    try {
      await loginWithTelegramWidget(USER);
      expect.unreachable("expected loginWithTelegramWidget to throw");
    } catch (err) {
      expect((err as TelegramLoginError).status).toBe(401);
      expect((err as TelegramLoginError).message).toBe("invalid");
    }
  });
});
