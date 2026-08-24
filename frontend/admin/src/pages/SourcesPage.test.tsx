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
  lastSyncAt: new Date().toISOString(),
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

  it("should_show_rate_limit_message_when_sources_fetch_returns_429", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 429 }));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Слишком много запросов/)).toBeInTheDocument());
    expect(screen.queryByText(/Источники не найдены/)).not.toBeInTheDocument();
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
    vi.mocked(fetch).mockResolvedValue(jsonResponse([{ ...ONLINER_SOURCE, lastSyncAt: undefined }]));

    renderPage();

    await waitFor(() => expect(screen.getByText("Нет данных")).toBeInTheDocument());
  });

  it("should_render_healthy_badge_when_source_synced_recently", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse([ONLINER_SOURCE]));

    renderPage();

    await waitFor(() => expect(screen.getByText("Здоров")).toBeInTheDocument());
  });

  it("should_render_stale_badge_when_last_sync_is_older_than_threshold_and_interval_is_absent", async () => {
    // issue #390 — syncIntervalMinutes no longer exists on the API response at all (removed in
    // #387); a source that hasn't synced in days must not default to "Здоров" just because there
    // is no configured interval to compare against
    const staleSource = {
      ...ONLINER_SOURCE,
      lastSyncAt: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString(),
    };
    vi.mocked(fetch).mockResolvedValue(jsonResponse([staleSource]));

    renderPage();

    await waitFor(() => expect(screen.getByText("Задержка")).toBeInTheDocument());
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

  it("should_keep_other_rows_enabled_when_one_row_toggle_is_pending", async () => {
    // issue #391 — a mutation shared across the whole table disabled every row's switch while
    // any single row's PATCH was in flight; each row must track its own pending state
    const KUFAR_SOURCE = { ...ONLINER_SOURCE, sourceId: "kufar", displayName: "Kufar", url: "https://api.kufar.by" };
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse([ONLINER_SOURCE, KUFAR_SOURCE]));
    let resolvePatch: (response: Response) => void = () => {};
    vi.mocked(fetch).mockImplementationOnce(
      () => new Promise<Response>((resolve) => { resolvePatch = resolve; }),
    );

    renderPage();
    await waitFor(() => expect(screen.getByText("Onliner")).toBeInTheDocument());

    const switches = screen.getAllByRole("switch");
    fireEvent.click(switches[0]);

    await waitFor(() => expect(switches[0]).toBeDisabled());
    expect(switches[1]).not.toBeDisabled();

    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse([{ ...ONLINER_SOURCE, enabled: false }, KUFAR_SOURCE]));
    resolvePatch(jsonResponse({ ...ONLINER_SOURCE, enabled: false }));

    await waitFor(() => expect(switches[0]).not.toBeDisabled());
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
