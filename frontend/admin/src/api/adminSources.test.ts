import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchLatestSyncRuns, fetchRecentSyncRuns, fetchSources, fetchSyncRuns, updateSource } from "./adminSources";
import { ApiError } from "./apiError";

describe("adminSources", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_return_sources_when_fetch_succeeds", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify([{ sourceId: "onliner", displayName: "Onliner" }]), { status: 200 }),
    );

    const result = await fetchSources();

    expect(result).toEqual([{ sourceId: "onliner", displayName: "Onliner" }]);
    expect(fetch).toHaveBeenCalledWith("/api/v1/admin/sources", expect.anything());
  });

  it("should_throw_when_fetch_sources_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchSources()).rejects.toThrow("HTTP 500");
  });

  it("should_throw_api_error_with_status_when_rate_limited", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 429 }));

    await expect(fetchSources()).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.status === 429,
    );
  });

  it("should_send_patch_request_when_updating_source", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ sourceId: "onliner", enabled: false }), { status: 200 }),
    );

    const result = await updateSource("onliner", { enabled: false });

    expect(result.enabled).toBe(false);
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/sources/onliner");
    expect(init?.method).toBe("PATCH");
    expect(init?.body).toBe(JSON.stringify({ enabled: false }));
  });

  it("should_throw_when_update_source_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 404 }));

    await expect(updateSource("unknown", { enabled: false })).rejects.toThrow("HTTP 404");
  });

  it("should_throw_backend_message_when_update_source_fails_with_error_response_body", async () => {
    // issue #393 — ErrorResponse.message from the backend must be surfaced, not "HTTP 400"
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ message: "Источник уже отключён другим админом" }), { status: 400 }),
    );

    await expect(updateSource("onliner", { enabled: false })).rejects.toThrow(
      "Источник уже отключён другим админом",
    );
  });

  it("should_request_paginated_sync_runs_for_source", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ content: [], totalPages: 0 }), { status: 200 }),
    );

    await fetchSyncRuns("onliner", 1);

    const [url] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/sync-runs?sourceId=onliner&page=1&size=20");
  });

  it("should_throw_when_fetch_sync_runs_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchSyncRuns("onliner", 0)).rejects.toThrow("HTTP 500");
  });

  it("should_return_latest_sync_runs_per_source", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify([{ sourceId: "onliner", status: "SUCCESS" }]), { status: 200 }),
    );

    const result = await fetchLatestSyncRuns();

    expect(result).toEqual([{ sourceId: "onliner", status: "SUCCESS" }]);
    expect(fetch).toHaveBeenCalledWith("/api/v1/admin/sync-runs/latest", expect.anything());
  });

  it("should_throw_when_fetch_latest_sync_runs_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchLatestSyncRuns()).rejects.toThrow("HTTP 500");
  });

  it("should_request_recent_sync_runs_without_source_filter", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchRecentSyncRuns(5);

    const [url] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/sync-runs?page=0&size=5");
  });

  it("should_request_recent_sync_runs_filtered_by_source", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchRecentSyncRuns(10, "onliner");

    const [url] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/sync-runs?page=0&size=10&sourceId=onliner");
  });

  it("should_throw_when_fetch_recent_sync_runs_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchRecentSyncRuns(5)).rejects.toThrow("HTTP 500");
  });
});
