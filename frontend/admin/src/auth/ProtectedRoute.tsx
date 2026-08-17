import type { ReactElement } from "react";
import { Navigate, Outlet } from "react-router-dom";
import { isAuthenticated } from "./token";

/**
 * Guards nested routes behind a token being present in sessionStorage.
 * Only checks presence, not validity/expiry — decoding/verifying a JWT client-side would just
 * duplicate the server's own check. An expired or otherwise invalid token is instead caught by
 * apiFetch (see api/client.ts): any admin API call that comes back 401 clears the token and
 * hard-redirects to /login.
 */
export function ProtectedRoute(): ReactElement {
  if (!isAuthenticated()) {
    return <Navigate to="/login" replace />;
  }
  return <Outlet />;
}
