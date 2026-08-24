import { describe, expect, it } from "vitest";
import { isAllowedImageUrl } from "./isAllowedImageUrl";

describe("isAllowedImageUrl", () => {
  it.each([
    "https://onliner.by/photo.jpg",
    "https://cdn.onliner.by/photo.jpg",
    "https://r.onliner.by/photo.jpg",
    "https://kufar.by/photo.jpg",
    "https://rms.kufar.by/v1/gallery/photo.jpg",
    "https://realt.by/photo.jpg",
    "https://img.realt.by/photo.jpg",
  ])("should_allow_%s", (url) => {
    expect(isAllowedImageUrl(url)).toBe(true);
  });

  it.each([
    ["http://onliner.by/photo.jpg", "not https"],
    ["https://evil.example/photo.jpg", "unrelated domain"],
    ["https://onliner.by.evil.example/photo.jpg", "suffix trick — real domain as a subdomain prefix"],
    ["https://notonliner.by/photo.jpg", "similar-looking domain, no dot boundary"],
    ["data:image/png;base64,iVBORw0KGgo=", "data URI"],
    ["javascript:alert(1)", "javascript URI"],
    ["not a url", "malformed"],
    ["", "empty string"],
  ])("should_reject_%s (%s)", (url) => {
    expect(isAllowedImageUrl(url)).toBe(false);
  });

  it("should_reject_null", () => {
    expect(isAllowedImageUrl(null)).toBe(false);
  });

  it("should_reject_undefined", () => {
    expect(isAllowedImageUrl(undefined)).toBe(false);
  });
});
