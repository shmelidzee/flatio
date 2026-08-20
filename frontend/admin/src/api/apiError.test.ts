import { describe, expect, it } from "vitest";
import { ApiError } from "./apiError";

describe("ApiError", () => {
  it("should_carry_status_and_message_when_constructed", () => {
    const error = new ApiError(429, "Failed to fetch listings: HTTP 429");

    expect(error.status).toBe(429);
    expect(error.message).toBe("Failed to fetch listings: HTTP 429");
    expect(error).toBeInstanceOf(Error);
  });
});
