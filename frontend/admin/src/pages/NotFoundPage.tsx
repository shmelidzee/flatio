import type { ReactElement } from "react";
import { Link } from "react-router-dom";

/**
 * Catch-all route shown for any URL that doesn't match a known admin route (issue #359).
 * Rendered inside {@link AdminLayout} so the sidebar navigation stays available — the admin
 * is never stuck on an unrecoverable screen.
 */
export function NotFoundPage(): ReactElement {
  return (
    <div className="flex flex-col items-center gap-3 py-16 text-center">
      <p className="text-lg font-semibold text-white">Страница не найдена</p>
      <p className="text-sm text-gray-400">Такого раздела admin-панели не существует.</p>
      <Link to="/" className="mt-2 rounded bg-accent px-4 py-1.5 text-sm text-white hover:bg-accent-hover">
        Вернуться на дашборд
      </Link>
    </div>
  );
}
