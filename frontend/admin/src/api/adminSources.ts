import { apiFetch } from "./client";
import type { components } from "./schema";

export type AdminSource = components["schemas"]["AdminSourceResponse"];
export type AdminSourceUpdate = components["schemas"]["AdminSourceUpdateRequest"];
export type AdminSyncRun = components["schemas"]["AdminSyncRunResponse"];
export type PageAdminSyncRun = components["schemas"]["PageAdminSyncRunResponse"];

const SYNC_RUNS_PAGE_SIZE = 20;

export async function fetchSources(): Promise<AdminSource[]> {
  const response = await apiFetch("/api/v1/admin/sources");
  if (!response.ok) {
    throw new Error(`Failed to fetch sources: HTTP ${response.status}`);
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
    throw new Error(`Failed to update source: HTTP ${response.status}`);
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
    throw new Error(`Failed to fetch sync runs: HTTP ${response.status}`);
  }
  return (await response.json()) as PageAdminSyncRun;
}
