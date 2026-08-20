import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchUsers, updateUser } from "./adminUsers";
import { ApiError } from "./apiError";

describe("adminUsers", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_build_query_string_from_filters_and_page", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchUsers({ role: "ADMIN", active: true }, 1);

    const [url] = vi.mocked(fetch).mock.calls[0];
    const parsed = new URL(String(url), "http://localhost");
    expect(parsed.pathname).toBe("/api/v1/admin/users");
    expect(parsed.searchParams.get("page")).toBe("1");
    expect(parsed.searchParams.get("size")).toBe("20");
    expect(parsed.searchParams.get("role")).toBe("ADMIN");
    expect(parsed.searchParams.get("active")).toBe("true");
  });

  it("should_use_custom_page_size_when_provided", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchUsers({}, 0, 5);

    const [url] = vi.mocked(fetch).mock.calls[0];
    const parsed = new URL(String(url), "http://localhost");
    expect(parsed.searchParams.get("size")).toBe("5");
  });

  it("should_omit_undefined_filters_from_query_string", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchUsers({}, 0);

    const [url] = vi.mocked(fetch).mock.calls[0];
    const parsed = new URL(String(url), "http://localhost");
    expect(parsed.searchParams.has("role")).toBe(false);
    expect(parsed.searchParams.has("active")).toBe(false);
  });

  it("should_throw_when_fetch_users_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchUsers({}, 0)).rejects.toThrow("HTTP 500");
  });

  it("should_throw_api_error_with_status_when_rate_limited", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 429 }));

    await expect(fetchUsers({}, 0)).rejects.toSatisfy(
      (error: unknown) => error instanceof ApiError && error.status === 429,
    );
  });

  it("should_send_patch_request_when_updating_user", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ id: 1, role: "PRO" }), { status: 200 }),
    );

    const result = await updateUser(1, { role: "PRO" });

    expect(result.role).toBe("PRO");
    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/users/1");
    expect(init?.method).toBe("PATCH");
    expect(init?.body).toBe(JSON.stringify({ role: "PRO" }));
  });

  it("should_throw_when_update_user_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 403 }));

    await expect(updateUser(1, { role: "USER" })).rejects.toThrow("HTTP 403");
  });

  it("should_throw_backend_message_when_update_user_fails_with_error_response_body", async () => {
    // issue #352 — ErrorResponse.message from the backend must be surfaced, not "HTTP 403"
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ message: "Нельзя понизить себе роль администратора" }), { status: 403 }),
    );

    await expect(updateUser(1, { role: "USER" })).rejects.toThrow(
      "Нельзя понизить себе роль администратора",
    );
  });

  it("should_fall_back_to_status_message_when_update_user_error_body_is_not_json", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response("<html>Bad Gateway</html>", { status: 502 }));

    await expect(updateUser(1, { role: "USER" })).rejects.toThrow("HTTP 502");
  });

  it("should_fall_back_to_status_message_when_update_user_error_body_has_blank_message", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ message: "   " }), { status: 403 }),
    );

    await expect(updateUser(1, { role: "USER" })).rejects.toThrow("HTTP 403");
  });
});
