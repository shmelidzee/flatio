import { apiFetch } from "./client";
import { ApiError, resolveErrorMessage } from "./apiError";
import type { components } from "./schema";

export type AdminSource = components["schemas"]["AdminSourceResponse"];
export type AdminSourceUpdate = components["schemas"]["AdminSourceUpdateRequest"];
export type AdminSyncRun = components["schemas"]["AdminSyncRunResponse"];
export type PageAdminSyncRun = components["schemas"]["PageAdminSyncRunResponse"];

const SYNC_RUNS_PAGE_SIZE = 20;

export async function fetchSources(): Promise<AdminSource[]> {
  const response = await apiFetch("/api/v1/admin/sources");
  if (!response.ok) {
    throw new ApiError(response.status, `Failed to fetch sources: HTTP ${response.status}`);
  }
  return (await response.json()) as AdminSource[];
}

export async function updateSource(sourceId: string, patch: AdminSourceUpdate): Promise<AdminSource> {
  const response = await apiFetch(`/api/v1/admin/sources/${encodeURIComponent(sourceId)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
  });
  if (!response.ok) {
    throw new ApiError(response.status, await resolveErrorMessage(response, "update source"));
  }
  return (await response.json()) as AdminSource;
}

export async function fetchSyncRuns(sourceId: string, page: number): Promise<PageAdminSyncRun> {
  const params = new URLSearchParams({
    sourceId,
    page: String(page),
    size: String(SYNC_RUNS_PAGE_SIZE),
  });
  const response = await apiFetch(`/api/v1/admin/sync-runs?${params.toString()}`);
  if (!response.ok) {
    throw new ApiError(response.status, `Failed to fetch sync runs: HTTP ${response.status}`);
  }
  return (await response.json()) as PageAdminSyncRun;
}

export async function fetchLatestSyncRuns(): Promise<AdminSyncRun[]> {
  const response = await apiFetch("/api/v1/admin/sync-runs/latest");
  if (!response.ok) {
    throw new ApiError(response.status, `Failed to fetch latest sync runs: HTTP ${response.status}`);
  }
  return (await response.json()) as AdminSyncRun[];
}

/**
 * Fetches the most recent {@code size} sync runs, optionally filtered to a single source.
 * Used by the dashboard's "recent runs" list (no sourceId) and source health strip (with sourceId)
 * — both need a small, differently-sized page than SourcesPage's own paginated history view.
 */
export async function fetchRecentSyncRuns(size: number, sourceId?: string): Promise<PageAdminSyncRun> {
  const params = new URLSearchParams({ page: "0", size: String(size) });
  if (sourceId) {
    params.set("sourceId", sourceId);
  }
  const response = await apiFetch(`/api/v1/admin/sync-runs?${params.toString()}`);
  if (!response.ok) {
    throw new ApiError(response.status, `Failed to fetch recent sync runs: HTTP ${response.status}`);
  }
  return (await response.json()) as PageAdminSyncRun;
}
