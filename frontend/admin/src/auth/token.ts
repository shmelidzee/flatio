const TOKEN_KEY = "flatio_admin_token";

// sessionStorage, not localStorage: the token is cleared when the tab closes instead of
// persisting indefinitely, which shrinks the window an XSS payload could exfiltrate a live
// token in (issue #321).
export function getToken(): string | null {
  return window.sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  window.sessionStorage.removeItem(TOKEN_KEY);
}

export function isAuthenticated(): boolean {
  return getToken() !== null;
}
