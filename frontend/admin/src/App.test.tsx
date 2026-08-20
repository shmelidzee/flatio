import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { setToken } from "./auth/token";

function renderAt(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("App routing", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 500 })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_redirect_unknown_route_to_login_when_not_authenticated", async () => {
    // No catch-all bypass of the auth guard — an unmatched path for a logged-out visitor
    // still lands on /login, not a blank screen (issue #359).
    renderAt("/foobar-nonexistent");

    expect(await screen.findByText("Войдите через Telegram, чтобы продолжить.")).toBeInTheDocument();
  });

  it("should_show_not_found_page_with_link_home_when_route_unmatched", () => {
    setToken("abc123");

    renderAt("/foobar-nonexistent");

    expect(screen.getByText("Страница не найдена")).toBeInTheDocument();
    expect(screen.getByText("Вернуться на дашборд")).toHaveAttribute("href", "/");
    // Sidebar navigation is still available — the admin is never stuck.
    expect(screen.getByText("Flatio Admin")).toBeInTheDocument();
  });

  it("should_show_not_found_page_for_a_deeply_nested_unmatched_path", () => {
    setToken("abc123");

    renderAt("/listings/foo/bar/baz");

    expect(screen.getByText("Страница не найдена")).toBeInTheDocument();
  });
});
