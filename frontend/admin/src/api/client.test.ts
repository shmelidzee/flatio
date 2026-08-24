import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearToken, setToken } from "../auth/token";

// apiFetch tracks "already redirecting" as module-level state (issue #395) — reset the module
// between tests so that state doesn't leak from one test into the next.
async function importFreshApiFetch() {
  vi.resetModules();
  const mod = await import("./client");
  return mod.apiFetch;
}

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
    const apiFetch = await importFreshApiFetch();
    setToken("stored-jwt");

    await apiFetch("/api/v1/admin/listings");

    const [, init] = vi.mocked(fetch).mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(headers.get("Authorization")).toBe("Bearer stored-jwt");
  });

  it("should_omit_authorization_header_when_no_token_stored", async () => {
    const apiFetch = await importFreshApiFetch();
    clearToken();

    await apiFetch("/api/v1/admin/listings");

    const [, init] = vi.mocked(fetch).mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(headers.has("Authorization")).toBe(false);
  });

  it("should_clear_token_and_redirect_when_response_is_401", async () => {
    const apiFetch = await importFreshApiFetch();
    setToken("stale-jwt");
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 401 }));

    await apiFetch("/api/v1/admin/listings");

    expect(window.sessionStorage.getItem("flatio_admin_token")).toBeNull();
    expect(window.location.href).toBe("/admin/login");
  });

  it("should_not_redirect_when_response_is_not_401", async () => {
    const apiFetch = await importFreshApiFetch();
    setToken("valid-jwt");
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 200 }));

    await apiFetch("/api/v1/admin/listings");

    expect(window.sessionStorage.getItem("flatio_admin_token")).toBe("valid-jwt");
    expect(window.location.href).toBe("");
  });

  it("should_redirect_only_once_when_multiple_parallel_requests_get_401", async () => {
    // issue #395 — DashboardPage fires several useQuery calls in parallel; with an expired token
    // each independently got a 401 and each called clearToken()/navigated, since
    // window.location.href navigation is asynchronous and doesn't stop the rest of the JS running
    const apiFetch = await importFreshApiFetch();
    setToken("stale-jwt");
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 401 }));
    let hrefSetCount = 0;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: {
        get href() {
          return "";
        },
        set href(_value: string) {
          hrefSetCount += 1;
        },
      },
    });

    await Promise.all([
      apiFetch("/api/v1/admin/listings"),
      apiFetch("/api/v1/admin/sources"),
      apiFetch("/api/v1/admin/users"),
    ]);

    expect(hrefSetCount).toBe(1);
  });
});
