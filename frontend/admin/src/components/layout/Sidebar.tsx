import type { ReactElement } from "react";
import { NavLink } from "react-router-dom";

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
  return (
    <nav className="w-56 shrink-0 border-r border-surface-border bg-surface-raised p-4">
      <div className="mb-6 text-lg font-semibold">Flatio Admin</div>
      <ul className="space-y-1">
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
    </nav>
  );
}
