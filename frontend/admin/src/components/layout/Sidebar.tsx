import type { ReactElement } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { clearToken } from "../../auth/token";

interface NavItem {
  to: string;
  label: string;
}

const NAV_ITEMS: NavItem[] = [
  { to: "/", label: "Дашборд" },
  { to: "/listings", label: "Объявления" },
  { to: "/sources", label: "Источники" },
  { to: "/users", label: "Пользователи" },
];

export function Sidebar(): ReactElement {
  const navigate = useNavigate();

  function handleLogout(): void {
    clearToken();
    navigate("/login", { replace: true });
  }

  return (
    <nav className="flex w-56 shrink-0 flex-col border-r border-surface-border bg-surface-raised p-4">
      <div className="mb-6 text-lg font-semibold">Flatio Admin</div>
      <ul className="flex-1 space-y-1">
        {NAV_ITEMS.map((item) => (
          <li key={item.to}>
            <NavLink
              to={item.to}
              end={item.to === "/"}
              className={({ isActive }) =>
                `block rounded px-3 py-2 text-sm transition-colors ${
                  isActive
                    ? "bg-accent text-white"
                    : "text-gray-300 hover:bg-surface-border hover:text-white"
                }`
              }
            >
              {item.label}
            </NavLink>
          </li>
        ))}
      </ul>
      <button
        type="button"
        onClick={handleLogout}
        className="rounded px-3 py-2 text-left text-sm text-gray-300 transition-colors hover:bg-surface-border hover:text-white"
      >
        Выйти
      </button>
    </nav>
  );
}
