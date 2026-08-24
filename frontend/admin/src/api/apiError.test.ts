import { describe, expect, it } from "vitest";
import { ApiError, resolveErrorMessage } from "./apiError";

describe("ApiError", () => {
  it("should_carry_status_and_message_when_constructed", () => {
    const error = new ApiError(429, "Failed to fetch listings: HTTP 429");

    expect(error.status).toBe(429);
    expect(error.message).toBe("Failed to fetch listings: HTTP 429");
    expect(error).toBeInstanceOf(Error);
  });
});

describe("resolveErrorMessage", () => {
  it("should_return_backend_message_when_body_has_non_blank_message", async () => {
    const response = new Response(JSON.stringify({ message: "Причина отказа от бэкенда" }), { status: 400 });

    const result = await resolveErrorMessage(response, "do something");

    expect(result).toBe("Причина отказа от бэкенда");
  });

  it("should_fall_back_to_status_message_when_body_is_not_json", async () => {
    const response = new Response("<html>Bad Gateway</html>", { status: 502 });

    const result = await resolveErrorMessage(response, "do something");

    expect(result).toBe("Failed to do something: HTTP 502");
  });

  it("should_fall_back_to_status_message_when_message_is_blank", async () => {
    const response = new Response(JSON.stringify({ message: "   " }), { status: 403 });

    const result = await resolveErrorMessage(response, "do something");

    expect(result).toBe("Failed to do something: HTTP 403");
  });

  it("should_fall_back_to_status_message_when_message_field_is_absent", async () => {
    const response = new Response(JSON.stringify({ error: "unexpected shape" }), { status: 500 });

    const result = await resolveErrorMessage(response, "do something");

    expect(result).toBe("Failed to do something: HTTP 500");
  });
});
