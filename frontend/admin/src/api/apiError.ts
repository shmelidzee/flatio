/**
 * Error thrown by admin API calls when a response is not ok, carrying the HTTP status so
 * callers can distinguish e.g. a 429 rate limit from a generic failure (issue #360).
 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/**
 * Extracts the backend's `ErrorResponse.message` from a failed mutation response, falling back
 * to a generic status-code message when the body isn't the expected JSON shape (e.g. an upstream
 * proxy error page) or carries no message.
 *
 * <p>Originally introduced only for {@code updateUser} (issue #352); every other admin mutation
 * (source/listing updates) discarded the response body and always showed the generic fallback,
 * hiding the backend's actual validation reason. Applied uniformly across all admin mutations
 * that can return a meaningful {@code ErrorResponse.message} (issue #393).
 *
 * @param response      the failed (`!response.ok`) fetch Response, not yet consumed
 * @param fallbackAction short description of the action for the fallback message,
 *                       e.g. "update source" — produces "Failed to update source: HTTP 400"
 * @returns the backend's error message, or a generic fallback derived from the HTTP status
 */
export async function resolveErrorMessage(response: Response, fallbackAction: string): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (typeof body.message === "string" && body.message.trim() !== "") {
      return body.message;
    }
  } catch {
    // Body wasn't valid JSON — fall through to the status-based fallback below.
  }
  return `Failed to ${fallbackAction}: HTTP ${response.status}`;
}
