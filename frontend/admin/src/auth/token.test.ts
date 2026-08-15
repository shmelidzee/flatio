import { beforeEach, describe, expect, it } from "vitest";
import { clearToken, getToken, isAuthenticated, setToken } from "./token";

describe("token", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("should_return_null_when_no_token_stored", () => {
    expect(getToken()).toBeNull();
    expect(isAuthenticated()).toBe(false);
  });

  it("should_return_stored_token_when_token_set", () => {
    setToken("abc123");

    expect(getToken()).toBe("abc123");
    expect(isAuthenticated()).toBe(true);
  });

  it("should_return_null_when_token_cleared", () => {
    setToken("abc123");

    clearToken();

    expect(getToken()).toBeNull();
    expect(isAuthenticated()).toBe(false);
  });
});
