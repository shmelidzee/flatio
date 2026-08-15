import type { ReactElement } from "react";
import { Outlet } from "react-router-dom";
import { Sidebar } from "./Sidebar";

export function AdminLayout(): ReactElement {
  return (
    <div className="flex min-h-screen bg-surface text-white">
      <Sidebar />
      <main className="flex-1 p-6">
        <Outlet />
      </main>
    </div>
  );
}
