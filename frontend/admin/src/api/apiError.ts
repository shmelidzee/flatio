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
