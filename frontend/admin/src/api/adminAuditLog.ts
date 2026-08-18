import { apiFetch } from "./client";
import type { components } from "./schema";

export type AdminAuditLogEntry = components["schemas"]["AdminAuditLogResponse"];
export type PageAdminAuditLogEntry = components["schemas"]["PageAdminAuditLogResponse"];

export async function fetchRecentAuditLog(size: number): Promise<PageAdminAuditLogEntry> {
  const params = new URLSearchParams({ page: "0", size: String(size) });
  const response = await apiFetch(`/api/v1/admin/audit-log?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch audit log: HTTP ${response.status}`);
  }
  return (await response.json()) as PageAdminAuditLogEntry;
}
