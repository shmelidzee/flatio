import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DashboardPage } from "./DashboardPage";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <DashboardPage />
    </QueryClientProvider>,
  );
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

function mockFetch(options: { usersStatus?: number; auditLogStatus?: number } = {}) {
  vi.mocked(fetch).mockImplementation(async (input) => {
    const url = String(input);
    if (url.includes("/api/v1/admin/listings")) {
      return jsonResponse({ content: [], totalElements: 120 });
    }
    if (url.includes("/api/v1/admin/audit-log")) {
      if (options.auditLogStatus) {
        return new Response(null, { status: options.auditLogStatus });
      }
      return jsonResponse({
        content: [
          {
            id: 1,
            adminId: 1,
            adminDisplayName: "Иван Петров",
            action: "updateListingStatus",
            objectType: "LISTING",
            objectId: "42",
            createdAt: new Date().toISOString(),
          },
        ],
      });
    }
    if (url.includes("/api/v1/admin/sync-runs/latest")) {
      return jsonResponse([
        { id: 1, sourceId: "onliner", status: "FAILURE", startedAt: new Date().toISOString() },
      ]);
    }
    if (url.includes("/api/v1/admin/sync-runs")) {
      return jsonResponse({
        content: [
          {
            id: 1,
            sourceId: "onliner",
            status: "SUCCESS",
            startedAt: new Date().toISOString(),
            listingsAdded: 3,
            listingsErrors: 0,
          },
        ],
        totalPages: 1,
      });
    }
    if (url.includes("/api/v1/admin/users")) {
      if (options.usersStatus) {
        return new Response(null, { status: options.usersStatus });
      }
      return jsonResponse({
        content: [{ id: 2, displayName: "Новый Пользователь", createdAt: new Date().toISOString() }],
      });
    }
    if (url.includes("/api/v1/admin/sources")) {
      return jsonResponse([
        { sourceId: "kufar-apt-rent", displayName: "Kufar.by (Apartment Rent)", enabled: true },
      ]);
    }
    return new Response(null, { status: 404 });
  });
}

describe("DashboardPage", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_render_aggregate_stat_cards", async () => {
    mockFetch();

    renderPage();

    await waitFor(() => expect(screen.getByText("120")).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText("1 / 1")).toBeInTheDocument());
  });

  it("should_render_recent_sync_runs_table", async () => {
    mockFetch();

    renderPage();

    await waitFor(() => expect(screen.getByText("SUCCESS")).toBeInTheDocument());
  });

  it("should_render_new_users_block_when_endpoint_available", async () => {
    mockFetch();

    renderPage();

    await waitFor(() => expect(screen.getByText("Новый Пользователь")).toBeInTheDocument());
  });

  it("should_hide_new_users_block_when_endpoint_returns_404", async () => {
    mockFetch({ usersStatus: 404 });

    renderPage();

    await waitFor(() => expect(screen.getByText("SUCCESS")).toBeInTheDocument());
    expect(screen.queryByText("Новые пользователи")).not.toBeInTheDocument();
  });

  it("should_render_audit_log_entries_when_endpoint_available", async () => {
    mockFetch();

    renderPage();

    await waitFor(() => expect(screen.getByText("Иван Петров")).toBeInTheDocument());
    expect(screen.getByText("Изменение статуса объявления")).toBeInTheDocument();
    expect(screen.getByText("Объявление #42")).toBeInTheDocument();
  });

  it("should_hide_audit_log_block_when_endpoint_returns_404", async () => {
    mockFetch({ auditLogStatus: 404 });

    renderPage();

    await waitFor(() => expect(screen.getByText("SUCCESS")).toBeInTheDocument());
    expect(screen.queryByText("Лента админ-действий")).not.toBeInTheDocument();
  });

  it("should_set_title_attribute_to_full_display_name_on_truncated_source_health_row", async () => {
    mockFetch();

    renderPage();

    await waitFor(() => expect(screen.getByText("Kufar.by (Apartment Rent)")).toBeInTheDocument());
    expect(screen.getByText("Kufar.by (Apartment Rent)")).toHaveAttribute(
      "title",
      "Kufar.by (Apartment Rent)",
    );
  });

  it("should_refetch_all_queries_when_refresh_clicked", async () => {
    mockFetch();

    renderPage();
    await waitFor(() => expect(screen.getByText("120")).toBeInTheDocument());
    const callsBeforeRefresh = vi.mocked(fetch).mock.calls.length;

    fireEvent.click(screen.getByRole("button", { name: "Обновить" }));

    await waitFor(() => expect(vi.mocked(fetch).mock.calls.length).toBeGreaterThan(callsBeforeRefresh));
  });
});
