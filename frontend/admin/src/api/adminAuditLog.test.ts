import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRecentAuditLog } from "./adminAuditLog";

describe("adminAuditLog", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_request_recent_audit_log_entries", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));

    await fetchRecentAuditLog(10);

    const [url] = vi.mocked(fetch).mock.calls[0];
    expect(url).toBe("/api/v1/admin/audit-log?page=0&size=10");
  });

  it("should_return_parsed_page_when_fetch_succeeds", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ content: [{ id: 1, action: "updateUser" }] }), { status: 200 }),
    );

    const result = await fetchRecentAuditLog(10);

    expect(result.content).toEqual([{ id: 1, action: "updateUser" }]);
  });

  it("should_throw_when_fetch_audit_log_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    await expect(fetchRecentAuditLog(10)).rejects.toThrow("HTTP 500");
  });
});
