import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearToken, setToken } from "../auth/token";
import { apiFetch } from "./client";

describe("apiFetch", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));
    Object.defineProperty(window, "location", {
      value: { href: "" },
      configurable: true,
      writable: true,
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_attach_authorization_header_when_token_present", async () => {
    setToken("stored-jwt");

    await apiFetch("/api/v1/admin/listings");

    const [, init] = vi.mocked(fetch).mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(headers.get("Authorization")).toBe("Bearer stored-jwt");
  });

  it("should_omit_authorization_header_when_no_token_stored", async () => {
    clearToken();

    await apiFetch("/api/v1/admin/listings");

    const [, init] = vi.mocked(fetch).mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(headers.has("Authorization")).toBe(false);
  });

  it("should_clear_token_and_redirect_when_response_is_401", async () => {
    setToken("stale-jwt");
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 401 }));

    await apiFetch("/api/v1/admin/listings");

    expect(window.sessionStorage.getItem("flatio_admin_token")).toBeNull();
    expect(window.location.href).toBe("/admin/login");
  });

  it("should_not_redirect_when_response_is_not_401", async () => {
    setToken("valid-jwt");
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 200 }));

    await apiFetch("/api/v1/admin/listings");

    expect(window.sessionStorage.getItem("flatio_admin_token")).toBe("valid-jwt");
    expect(window.location.href).toBe("");
  });
});
