import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TelegramLoginWidget } from "./TelegramLoginWidget";
import type { TelegramAuthUser } from "./telegramAuth";

const USER: TelegramAuthUser = {
  id: 1,
  first_name: "John",
  auth_date: 1_700_000_000,
  hash: "hash",
};

describe("TelegramLoginWidget", () => {
  afterEach(() => {
    delete window.onTelegramAuth;
  });

  it("should_inject_widget_script_with_bot_username_when_mounted", () => {
    const { container } = render(<TelegramLoginWidget botUsername="flatio_bot" onAuth={vi.fn()} />);

    const script = container.querySelector("script");
    expect(script).not.toBeNull();
    expect(script?.getAttribute("data-telegram-login")).toBe("flatio_bot");
    expect(script?.getAttribute("data-onauth")).toBe("onTelegramAuth(user)");
    expect(script?.src).toContain("telegram-widget.js");
  });

  it("should_wire_global_callback_to_onauth_prop_when_mounted", () => {
    const onAuth = vi.fn();
    render(<TelegramLoginWidget botUsername="flatio_bot" onAuth={onAuth} />);

    window.onTelegramAuth?.(USER);

    expect(onAuth).toHaveBeenCalledWith(USER);
  });

  it("should_remove_global_callback_when_unmounted", () => {
    const { unmount } = render(<TelegramLoginWidget botUsername="flatio_bot" onAuth={vi.fn()} />);

    unmount();

    expect(window.onTelegramAuth).toBeUndefined();
  });
});
