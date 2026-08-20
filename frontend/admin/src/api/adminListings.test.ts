import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  fetchActiveListingsCount,
  fetchListingDetail,
  fetchListings,
  unlinkDuplicateGroup,
  updateListingStatus,
} from "./adminListings";
import { ApiError } from "./apiError";

describe("adminListings", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_build_query_string_from_filters_and_page", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchListings({ city: "Минск", priceMin: 500, duplicatesOnly: true }, 2);

    const [url] = vi.mocked(fetch).mock.calls[0];
    const parsed = new URL(String(url), "http://localhost");
    expect(parsed.pathname).toBe("/api/v1/admin/listings");
    expect(parsed.searchParams.get("page")).toBe("2");
    expect(parsed.searchParams.get("size")).toBe("20");
    expect(parsed.searchParams.get("city")).toBe("Минск");
    expect(parsed.searchParams.get("priceMin")).toBe("500");
    expect(parsed.searchParams.get("duplicatesOnly")).toBe("true");
  });

  it("should_omit_undefined_filters_from_query_string", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchListings({}, 0);

    const [url] = vi.mocked(fetch).mock.calls[0];
    const parsed = new URL(String(url), "http://localhost");
    expect(parsed.searchParams.has("city")).toBe(false);
    expect(parsed.searchParams.has("dealType")).toBe(false);
  });

  it("should_throw_when_fetch_listings_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchListings({}, 0)).rejects.toThrow("HTTP 500");
  });

  it("should_throw_api_error_with_status_when_rate_limited", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 429 }));

    await expect(fetchListings({}, 0)).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.status === 429,
    );
  });

  it("should_fetch_listing_detail_via_public_endpoint", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ id: 42 }), { status: 200 }));

    const result = await fetchListingDetail(42);

    expect(result).toEqual({ id: 42 });
    expect(fetch).toHaveBeenCalledWith("/api/v1/listings/42", expect.anything());
  });

  it("should_throw_when_fetch_listing_detail_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 404 }));

    await expect(fetchListingDetail(999)).rejects.toThrow("HTTP 404");
  });

  it("should_send_patch_request_when_updating_listing_status", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ id: 1 }), { status: 200 }));

    await updateListingStatus(1, "INACTIVE");

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/listings/1");
    expect(init?.method).toBe("PATCH");
    expect(init?.body).toBe(JSON.stringify({ status: "INACTIVE" }));
  });

  it("should_throw_when_update_listing_status_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 404 }));

    await expect(updateListingStatus(1, "INACTIVE")).rejects.toThrow("HTTP 404");
  });

  it("should_send_delete_request_when_unlinking_duplicate_group", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 200 }));

    await unlinkDuplicateGroup(7);

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/listings/7/duplicate-group");
    expect(init?.method).toBe("DELETE");
  });

  it("should_throw_when_unlink_duplicate_group_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(unlinkDuplicateGroup(7)).rejects.toThrow("HTTP 500");
  });

  it("should_return_total_elements_when_fetching_active_listings_count", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ content: [], totalElements: 42 }), { status: 200 }),
    );

    const result = await fetchActiveListingsCount();

    expect(result).toBe(42);
    const [url] = vi.mocked(fetch).mock.calls[0];
    const parsed = new URL(String(url), "http://localhost");
    expect(parsed.pathname).toBe("/api/v1/admin/listings");
    expect(parsed.searchParams.get("status")).toBe("ACTIVE");
    expect(parsed.searchParams.get("size")).toBe("1");
  });

  it("should_throw_when_fetch_active_listings_count_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchActiveListingsCount()).rejects.toThrow("HTTP 500");
  });
});
