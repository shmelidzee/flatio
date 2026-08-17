import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getToken } from "../auth/token";
import { LoginPage } from "./LoginPage";

function renderLoginPage() {
  return render(
    <MemoryRouter initialEntries={["/login"]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<div>dashboard</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function mockFetchSequence(...responses: Response[]) {
  const fetchMock = vi.fn();
  responses.forEach((response) => fetchMock.mockResolvedValueOnce(response));
  vi.stubGlobal("fetch", fetchMock);
}

function botUsernameResponse(): Response {
  return new Response(JSON.stringify({ botUsername: "flatio_bot" }), { status: 200 });
}

describe("LoginPage", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    delete window.onTelegramAuth;
  });

  it("should_show_error_when_bot_username_fetch_fails", async () => {
    mockFetchSequence(new Response(null, { status: 500 }));

    renderLoginPage();

    await waitFor(() => expect(screen.getByText(/Обновите страницу/)).toBeInTheDocument());
  });

  it("should_render_widget_when_bot_username_loaded", async () => {
    mockFetchSequence(botUsernameResponse());

    const { container } = renderLoginPage();

    await waitFor(() => expect(container.querySelector("script")).not.toBeNull());
    expect(container.querySelector("script")?.getAttribute("data-telegram-login")).toBe("flatio_bot");
  });

  it("should_store_token_and_navigate_to_dashboard_when_telegram_auth_succeeds", async () => {
    mockFetchSequence(
      botUsernameResponse(),
      new Response(JSON.stringify({ accessToken: "jwt-token", expiresIn: 3600 }), { status: 200 }),
    );

    renderLoginPage();
    await waitFor(() => expect(window.onTelegramAuth).toBeDefined());
    await act(async () => {
      window.onTelegramAuth?.({ id: 1, first_name: "John", auth_date: 1_700_000_000, hash: "h" });
    });

    await waitFor(() => expect(screen.getByText("dashboard")).toBeInTheDocument());
    expect(getToken()).toBe("jwt-token");
  });

  it("should_show_not_admin_message_when_backend_returns_403", async () => {
    mockFetchSequence(botUsernameResponse(), new Response(null, { status: 403 }));

    renderLoginPage();
    await waitFor(() => expect(window.onTelegramAuth).toBeDefined());
    await act(async () => {
      window.onTelegramAuth?.({ id: 1, first_name: "John", auth_date: 1_700_000_000, hash: "h" });
    });

    await waitFor(() => expect(screen.getByText(/нет доступа к админ-панели/)).toBeInTheDocument());
    expect(getToken()).toBeNull();
  });

  it("should_show_generic_error_message_when_backend_returns_401", async () => {
    mockFetchSequence(botUsernameResponse(), new Response(null, { status: 401 }));

    renderLoginPage();
    await waitFor(() => expect(window.onTelegramAuth).toBeDefined());
    await act(async () => {
      window.onTelegramAuth?.({ id: 1, first_name: "John", auth_date: 1_700_000_000, hash: "h" });
    });

    await waitFor(() => expect(screen.getByText(/Не удалось войти через Telegram/)).toBeInTheDocument());
    expect(getToken()).toBeNull();
  });
});
