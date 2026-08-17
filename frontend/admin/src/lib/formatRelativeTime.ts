const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/** Formats a past ISO timestamp as a short Russian relative-time string, e.g. "12 мин назад". */
export function formatRelativeTime(isoTimestamp: string | undefined, now: Date = new Date()): string {
  if (!isoTimestamp) {
    return "никогда";
  }
  const elapsedMs = now.getTime() - new Date(isoTimestamp).getTime();
  if (elapsedMs < MINUTE_MS) {
    return "только что";
  }
  if (elapsedMs < HOUR_MS) {
    return `${Math.floor(elapsedMs / MINUTE_MS)} мин назад`;
  }
  if (elapsedMs < DAY_MS) {
    return `${Math.floor(elapsedMs / HOUR_MS)} ч назад`;
  }
  return `${Math.floor(elapsedMs / DAY_MS)} дн назад`;
}
