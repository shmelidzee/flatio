import type { ReactElement } from "react";
import { Navigate, Outlet } from "react-router-dom";
import { isAuthenticated } from "./token";

/**
 * Guards nested routes behind a valid token in localStorage.
 * Real Telegram Login Widget auth flow ships in a follow-up issue (#321) — until then this
 * only checks token presence, not validity/expiry.
 */
export function ProtectedRoute(): ReactElement {
  if (!isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <Outlet />;
}
