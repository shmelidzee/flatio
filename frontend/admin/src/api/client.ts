import { clearToken, getToken } from "../auth/token";

/**
 * Fetch wrapper for authenticated admin API calls.
 * Attaches the stored bearer token, and on a 401 response clears it and hard-redirects to
 * /login — a full navigation, not a router push, since the token is no longer valid and any
 * in-memory app state built on it should not survive.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (token !== null) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(path, { ...init, headers });

  if (response.status === 401) {
    clearToken();
    window.location.href = "/admin/login";
  }

  return response;
}
