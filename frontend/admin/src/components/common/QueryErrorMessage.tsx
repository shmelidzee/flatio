import type { ReactElement } from "react";
import { ApiError } from "../../api/apiError";

const RATE_LIMIT_MESSAGE =
  "Слишком много запросов — превышен лимит частоты запросов. Это временное ограничение, попробуйте ещё раз через минуту.";

interface QueryErrorMessageProps {
  /** The error thrown by the failed query, typically an {@link ApiError}. */
  error: unknown;
  /** Message shown for any error that isn't a 429 rate limit. */
  fallback: string;
  /** Called when the admin clicks "Повторить"; omit to hide the retry button. */
  onRetry?: () => void;
}

/**
 * Renders a query's error state, replacing generic "не найдено" empty-state text.
 *
 * <p>Shows a distinct explanation for HTTP 429 (rate limit) responses instead of the generic
 * fallback message, with an optional retry action (issue #360).
 */
export function QueryErrorMessage({ error, fallback, onRetry }: QueryErrorMessageProps): ReactElement {
  const isRateLimited = error instanceof ApiError && error.status === 429;

  return (
    <p className="mt-4 text-sm text-red-400">
      {isRateLimited ? RATE_LIMIT_MESSAGE : fallback}
      {onRetry && (
        <button type="button" onClick={onRetry} className="ml-2 underline hover:text-red-300">
          Повторить
        </button>
      )}
    </p>
  );
}
