import { apiFetch } from "./client";
import type { components } from "./schema";

export type ListingSummary = components["schemas"]["ListingSummaryResponse"];
export type ListingDetail = components["schemas"]["ListingResponse"];
export type PageListingSummary = components["schemas"]["PageListingSummaryResponse"];
export type ListingStatusValue = "ACTIVE" | "INACTIVE" | "REPOSTED";

/**
 * Filter fields accepted by GET /api/v1/admin/listings — mirrors AdminListingSearchCriteria.
 * Defined locally rather than derived from the generated schema: springdoc represents this
 * @ModelAttribute-bound record as a nested "criteria" object in the OpenAPI operation, which does
 * not match the flat query-string shape Spring actually binds (?dealType=...&city=...).
 */
export interface AdminListingFilters {
  dealType?: "RENT" | "SELL" | "RENT_DAILY";
  propertyType?: string;
  sourceId?: string;
  city?: string;
  priceMin?: number;
  priceMax?: number;
  rooms?: number;
  areaMin?: number;
  areaMax?: number;
  query?: string;
  status?: ListingStatusValue;
  duplicatesOnly?: boolean;
}

const LISTINGS_PAGE_SIZE = 20;

export async function fetchListings(filters: AdminListingFilters, page: number): Promise<PageListingSummary> {
  const params = new URLSearchParams({ page: String(page), size: String(LISTINGS_PAGE_SIZE) });
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== "") {
      params.set(key, String(value));
    }
  }
  const response = await apiFetch(`/api/v1/admin/listings?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch listings: HTTP ${response.status}`);
  }
  return (await response.json()) as PageListingSummary;
}

/** GET /api/v1/listings/{id} — the public detail endpoint, reused here rather than duplicating it
 * under /admin: it already returns everything the moderation modal needs (description, address,
 * price history), and admins carry a JWT that satisfies its `authenticated()` requirement. */
export async function fetchListingDetail(id: number): Promise<ListingDetail> {
  const response = await apiFetch(`/api/v1/listings/${id}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch listing detail: HTTP ${response.status}`);
  }
  return (await response.json()) as ListingDetail;
}

export async function updateListingStatus(id: number, status: ListingStatusValue): Promise<ListingSummary> {
  const response = await apiFetch(`/api/v1/admin/listings/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status }),
  });
  if (!response.ok) {
    throw new Error(`Failed to update listing status: HTTP ${response.status}`);
  }
  return (await response.json()) as ListingSummary;
}

export async function unlinkDuplicateGroup(id: number): Promise<void> {
  const response = await apiFetch(`/api/v1/admin/listings/${id}/duplicate-group`, { method: "DELETE" });
  if (!response.ok) {
    throw new Error(`Failed to unlink duplicate group: HTTP ${response.status}`);
  }
}

/** Dashboard aggregate: total count of ACTIVE listings, read off a 1-row page's totalElements. */
export async function fetchActiveListingsCount(): Promise<number> {
  const params = new URLSearchParams({ status: "ACTIVE", page: "0", size: "1" });
  const response = await apiFetch(`/api/v1/admin/listings?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch active listings count: HTTP ${response.status}`);
  }
  const page = (await response.json()) as PageListingSummary;
  return page.totalElements ?? 0;
}
