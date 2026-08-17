import { useEffect, useRef } from "react";
import type { ReactElement } from "react";
import type { TelegramAuthUser } from "./telegramAuth";

declare global {
  interface Window {
    onTelegramAuth?: (user: TelegramAuthUser) => void;
  }
}

interface TelegramLoginWidgetProps {
  botUsername: string;
  onAuth: (user: TelegramAuthUser) => void;
}

/**
 * Renders Telegram's official Login Widget button by injecting its script tag, per
 * https://core.telegram.org/widgets/login. The widget itself is an iframe Telegram controls —
 * it cannot be built from local markup — so this component's only job is mounting/unmounting
 * that script and wiring its onauth callback to a normal React prop.
 */
export function TelegramLoginWidget({ botUsername, onAuth }: TelegramLoginWidgetProps): ReactElement {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;
    window.onTelegramAuth = onAuth;

    const script = document.createElement("script");
    script.src = "https://telegram.org/js/telegram-widget.js?22";
    script.async = true;
    script.setAttribute("data-telegram-login", botUsername);
    script.setAttribute("data-size", "large");
    script.setAttribute("data-radius", "8");
    script.setAttribute("data-onauth", "onTelegramAuth(user)");
    script.setAttribute("data-request-access", "write");
    container?.appendChild(script);

    return () => {
      delete window.onTelegramAuth;
      container?.replaceChildren();
    };
  }, [botUsername, onAuth]);

  return <div ref={containerRef} />;
}
