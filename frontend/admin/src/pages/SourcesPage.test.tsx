import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SourcesPage } from "./SourcesPage";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SourcesPage />
    </QueryClientProvider>,
  );
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

const ONLINER_SOURCE = {
  sourceId: "onliner",
  displayName: "Onliner",
  url: "https://r.onliner.by",
  countryCode: "BY",
  enabled: true,
  syncIntervalMinutes: 60,
  lastSyncAt: "2026-08-17T11:48:00.000Z",
  createdAt: "2026-01-10T12:00:00.000Z",
};

describe("SourcesPage", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_show_error_message_when_sources_fetch_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Не удалось загрузить источники/)).toBeInTheDocument());
  });

  it("should_show_empty_message_when_no_sources_returned", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse([]));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Источники не найдены/)).toBeInTheDocument());
  });

  it("should_render_source_row_when_sources_loaded", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse([ONLINER_SOURCE]));

    renderPage();

    await waitFor(() => expect(screen.getByText("Onliner")).toBeInTheDocument());
    expect(screen.getByText("onliner")).toBeInTheDocument();
    expect(screen.getByText("BY")).toBeInTheDocument();
  });

  it("should_render_health_badge_when_source_has_never_synced", async () => {
    // issue #354 — sourceHealth() still uses syncIntervalMinutes internally, only the editable
    // column was removed; a source with no lastSyncAt must still show "Нет данных"
    vi.mocked(fetch).mockResolvedValue(jsonResponse([{ ...ONLINER_SOURCE, lastSyncAt: undefined }]));

    renderPage();

    await waitFor(() => expect(screen.getByText("Нет данных")).toBeInTheDocument());
  });

  it("should_not_render_interval_column_when_sources_loaded", async () => {
    // issue #354 — the non-functional "Интервал, мин" column and its editable input were removed
    vi.mocked(fetch).mockResolvedValue(jsonResponse([ONLINER_SOURCE]));

    renderPage();

    await waitFor(() => expect(screen.getByText("Onliner")).toBeInTheDocument());
    expect(screen.queryByText("Интервал, мин")).not.toBeInTheDocument();
    expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
  });

  it("should_send_patch_request_when_enabled_toggle_clicked", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse([ONLINER_SOURCE]));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ ...ONLINER_SOURCE, enabled: false }));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse([{ ...ONLINER_SOURCE, enabled: false }]));

    renderPage();
    await waitFor(() => expect(screen.getByText("Onliner")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("switch"));

    await waitFor(() => {
      const [, patchInit] = vi.mocked(fetch).mock.calls[1];
      expect(patchInit?.method).toBe("PATCH");
      expect(patchInit?.body).toBe(JSON.stringify({ enabled: false }));
    });
  });

  it("should_expand_sync_run_history_when_row_clicked", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse([ONLINER_SOURCE]));
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse({
        content: [
          {
            id: 1,
            sourceId: "onliner",
            syncType: "DELTA",
            status: "SUCCESS",
            startedAt: "2026-08-17T11:48:00.000Z",
            finishedAt: "2026-08-17T11:48:05.000Z",
            durationMs: 5000,
            listingsFetched: 10,
            listingsAdded: 2,
            listingsUpdated: 3,
            listingsErrors: 0,
          },
        ],
        totalPages: 1,
        last: true,
      }),
    );

    renderPage();
    await waitFor(() => expect(screen.getByText("Onliner")).toBeInTheDocument());

    fireEvent.click(screen.getByText("Onliner"));

    await waitFor(() => expect(screen.getByText("SUCCESS")).toBeInTheDocument());
    const historyTable = screen.getByText("SUCCESS").closest("table") as HTMLTableElement;
    expect(within(historyTable).getByText("DELTA")).toBeInTheDocument();

    // issue #354 — colSpan must match the remaining 7 columns after removing "Интервал, мин"
    const historyCell = historyTable.closest("td") as HTMLTableCellElement;
    expect(historyCell).toHaveAttribute("colspan", "7");
  });

  it("should_expand_sync_run_history_when_row_activated_with_enter_key", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse([ONLINER_SOURCE]));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ content: [], totalPages: 0 }));

    renderPage();
    await waitFor(() => expect(screen.getByText("Onliner")).toBeInTheDocument());

    const row = screen.getByRole("button", { name: /Onliner/ });
    fireEvent.keyDown(row, { key: "Enter" });

    await waitFor(() => expect(row).toHaveAttribute("aria-expanded", "true"));
  });
});
