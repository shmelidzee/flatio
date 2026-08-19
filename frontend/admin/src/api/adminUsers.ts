import { apiFetch } from "./client";
import type { components } from "./schema";

export type AdminUser = components["schemas"]["AdminUserResponse"];
export type AdminUserUpdate = components["schemas"]["AdminUserUpdateRequest"];
export type PageAdminUser = components["schemas"]["PageAdminUserResponse"];
export type UserRoleValue = "USER" | "PRO" | "ADMIN";

/**
 * Filter fields accepted by GET /api/v1/admin/users — mirrors AdminUserSearchCriteria.
 * Defined locally rather than derived from the generated schema, same reasoning as
 * AdminListingFilters in adminListings.ts: springdoc represents the @ModelAttribute-bound
 * record as a nested object, not the flat query-string shape Spring actually binds.
 */
export interface AdminUserFilters {
  role?: UserRoleValue;
  active?: boolean;
}

const USERS_PAGE_SIZE = 20;

export async function fetchUsers(
  filters: AdminUserFilters,
  page: number,
  size: number = USERS_PAGE_SIZE
): Promise<PageAdminUser> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== "") {
      params.set(key, String(value));
    }
  }
  const response = await apiFetch(`/api/v1/admin/users?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch users: HTTP ${response.status}`);
  }
  return (await response.json()) as PageAdminUser;
}

export async function updateUser(id: number, patch: AdminUserUpdate): Promise<AdminUser> {
  const response = await apiFetch(`/api/v1/admin/users/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patch),
  });
  if (!response.ok) {
    throw new Error(await resolveUpdateErrorMessage(response));
  }
  return (await response.json()) as AdminUser;
}

/**
 * Extracts the backend's `ErrorResponse.message` from a failed update response, falling back
 * to a status-code message when the body isn't the expected JSON shape (e.g. an upstream proxy
 * error page).
 */
async function resolveUpdateErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (typeof body.message === "string" && body.message.trim() !== "") {
      return body.message;
    }
  } catch {
    // Body wasn't valid JSON — fall through to the status-based fallback below.
  }
  return `Failed to update user: HTTP ${response.status}`;
}
