import type { ReactElement } from "react";

/**
 * Placeholder login screen. The real Telegram Login Widget flow is implemented in #321
 * (blocked by this issue) — this page only reserves the route and layout.
 */
export function LoginPage(): ReactElement {
  return (
    <div className="flex min-h-screen items-center justify-center bg-surface text-white">
      <div className="rounded-lg border border-surface-border bg-surface-raised p-8 text-center">
        <h1 className="text-xl font-semibold">Flatio Admin</h1>
        <p className="mt-2 text-sm text-gray-400">
          Вход через Telegram появится в следующем issue.
        </p>
      </div>
    </div>
  );
}
