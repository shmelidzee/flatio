import { describe, expect, it } from "vitest";
import { formatRelativeTime } from "./formatRelativeTime";

const NOW = new Date("2026-08-17T12:00:00Z");

describe("formatRelativeTime", () => {
  it("should_return_never_when_timestamp_is_undefined", () => {
    expect(formatRelativeTime(undefined, NOW)).toBe("никогда");
  });

  it("should_return_just_now_when_under_a_minute_ago", () => {
    expect(formatRelativeTime("2026-08-17T11:59:45Z", NOW)).toBe("только что");
  });

  it("should_return_minutes_ago_when_under_an_hour", () => {
    expect(formatRelativeTime("2026-08-17T11:48:00Z", NOW)).toBe("12 мин назад");
  });

  it("should_return_hours_ago_when_under_a_day", () => {
    expect(formatRelativeTime("2026-08-17T06:00:00Z", NOW)).toBe("6 ч назад");
  });

  it("should_return_days_ago_when_a_day_or_more", () => {
    expect(formatRelativeTime("2026-08-14T12:00:00Z", NOW)).toBe("3 дн назад");
  });
});
