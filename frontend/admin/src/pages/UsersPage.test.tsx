import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UsersPage } from "./UsersPage";

const SELF_ROLE_CHANGE_ERROR_MESSAGE = "Нельзя понизить себе роль администратора";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <UsersPage />
    </QueryClientProvider>,
  );
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

const IVAN = {
  id: 1,
  displayName: "Иван Петров",
  email: "ivan@example.com",
  role: "USER",
  active: true,
  createdAt: "2026-08-10T12:00:00.000Z",
  lastSeen: "2026-08-17T11:48:00.000Z",
};

describe("UsersPage", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_show_error_message_when_users_fetch_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Не удалось загрузить пользователей/)).toBeInTheDocument());
  });

  it("should_show_rate_limit_message_when_users_fetch_returns_429", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 429 }));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Слишком много запросов/)).toBeInTheDocument());
    expect(screen.queryByText(/Пользователи не найдены/)).not.toBeInTheDocument();
  });

  it("should_show_empty_message_when_no_users_returned", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ content: [], totalPages: 0 }));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Пользователи не найдены/)).toBeInTheDocument());
  });

  it("should_render_user_row_when_users_loaded", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));

    renderPage();

    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());
    expect(screen.getByText("ivan@example.com")).toBeInTheDocument();
  });

  it("should_send_patch_request_when_active_toggle_clicked", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ ...IVAN, active: false }));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [{ ...IVAN, active: false }], totalPages: 1 }));

    renderPage();
    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("switch"));

    await waitFor(() => {
      const [url, patchInit] = vi.mocked(fetch).mock.calls[1];
      expect(url).toBe("/api/v1/admin/users/1");
      expect(patchInit?.method).toBe("PATCH");
      expect(patchInit?.body).toBe(JSON.stringify({ active: false }));
    });
  });

  it("should_send_patch_request_when_role_changed", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ ...IVAN, role: "PRO" }));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [{ ...IVAN, role: "PRO" }], totalPages: 1 }));

    renderPage();
    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());

    fireEvent.change(screen.getByDisplayValue("Пользователь"), { target: { value: "PRO" } });

    await waitFor(() => {
      const [url, patchInit] = vi.mocked(fetch).mock.calls[1];
      expect(url).toBe("/api/v1/admin/users/1");
      expect(patchInit?.method).toBe("PATCH");
      expect(patchInit?.body).toBe(JSON.stringify({ role: "PRO" }));
    });
  });

  it("should_show_error_message_when_update_forbidden", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 403 }));

    renderPage();
    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("switch"));

    await waitFor(() => expect(screen.getByText(/Не удалось обновить пользователя/)).toBeInTheDocument());
  });

  it("should_show_backend_error_message_when_update_fails_with_json_error_body", async () => {
    // issue #352 — the banner must surface ErrorResponse.message, not a bare "HTTP 403"
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse({ message: SELF_ROLE_CHANGE_ERROR_MESSAGE }, 403),
    );

    renderPage();
    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("switch"));

    await waitFor(() =>
      expect(screen.getByText(new RegExp(SELF_ROLE_CHANGE_ERROR_MESSAGE))).toBeInTheDocument(),
    );
    expect(screen.queryByText(/HTTP 403/)).not.toBeInTheDocument();
  });

  it("should_reset_error_banner_when_role_filter_changed", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 403 }));
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));

    renderPage();
    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("switch"));
    await waitFor(() => expect(screen.getByText(/Не удалось обновить пользователя/)).toBeInTheDocument());

    fireEvent.change(screen.getByDisplayValue("Роль: любая"), { target: { value: "ADMIN" } });

    await waitFor(() =>
      expect(screen.queryByText(/Не удалось обновить пользователя/)).not.toBeInTheDocument(),
    );
  });

  it("should_keep_other_rows_enabled_when_one_row_toggle_is_pending", async () => {
    // issue #391 — a mutation shared across the whole table disabled every row's controls while
    // any single row's PATCH was in flight; each row must track its own pending state
    const PETR = { ...IVAN, id: 2, displayName: "Пётр Сидоров", email: "petr@example.com" };
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [IVAN, PETR], totalPages: 1, last: true }));
    let resolvePatch: (response: Response) => void = () => {};
    vi.mocked(fetch).mockImplementationOnce(
      () => new Promise<Response>((resolve) => { resolvePatch = resolve; }),
    );

    renderPage();
    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());

    const switches = screen.getAllByRole("switch");
    fireEvent.click(switches[0]);

    await waitFor(() => expect(switches[0]).toBeDisabled());
    expect(switches[1]).not.toBeDisabled();

    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [{ ...IVAN, active: false }, PETR], totalPages: 1, last: true }));
    resolvePatch(jsonResponse({ ...IVAN, active: false }));

    await waitFor(() => expect(switches[0]).not.toBeDisabled());
  });

  it("should_auto_dismiss_error_banner_after_timeout", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [IVAN], totalPages: 1, last: true }));
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 403 }));

    renderPage();
    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("switch"));
    await waitFor(() => expect(screen.getByText(/Не удалось обновить пользователя/)).toBeInTheDocument());

    act(() => {
      vi.advanceTimersByTime(5_000);
    });

    await waitFor(() =>
      expect(screen.queryByText(/Не удалось обновить пользователя/)).not.toBeInTheDocument(),
    );
    vi.useRealTimers();
  });
});
