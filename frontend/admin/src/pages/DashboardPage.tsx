import { useMemo } from "react";
import type { ReactElement } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchActiveListingsCount } from "../api/adminListings";
import { fetchLatestSyncRuns, fetchRecentSyncRuns, fetchSources } from "../api/adminSources";
import type { AdminSource } from "../api/adminSources";
import { fetchUsers } from "../api/adminUsers";
import { formatRelativeTime } from "../lib/formatRelativeTime";

const DAY_MS = 24 * 60 * 60 * 1000;
const HEALTH_STRIP_SIZE = 10;
const RECENT_RUNS_SIZE = 5;
const NEW_USERS_SIZE = 5;

const SYNC_STATUS_LABEL: Record<string, string> = {
  SUCCESS: "SUCCESS",
  FAILURE: "FAILED",
};

const SYNC_STATUS_BADGE_CLASS: Record<string, string> = {
  SUCCESS: "bg-emerald-500/20 text-emerald-400",
  FAILURE: "bg-red-500/20 text-red-400",
};

export function DashboardPage(): ReactElement {
  const queryClient = useQueryClient();

  const activeListingsQuery = useQuery({
    queryKey: ["admin", "dashboard", "active-listings-count"],
    queryFn: fetchActiveListingsCount,
  });
  const sourcesQuery = useQuery({ queryKey: ["admin", "sources"], queryFn: fetchSources });
  const latestRunsQuery = useQuery({
    queryKey: ["admin", "sync-runs", "latest"],
    queryFn: fetchLatestSyncRuns,
  });
  const recentRunsQuery = useQuery({
    queryKey: ["admin", "sync-runs", "recent"],
    queryFn: () => fetchRecentSyncRuns(RECENT_RUNS_SIZE),
  });
  // retry: false — a 404 here means the admin users endpoint isn't deployed yet, and the block
  // below is hidden rather than shown as an error (issue #324 AC).
  const newUsersQuery = useQuery({
    queryKey: ["admin", "users", "dashboard-recent"],
    queryFn: () => fetchUsers({}, 0, NEW_USERS_SIZE),
    retry: false,
  });

  const sources = sourcesQuery.data ?? [];
  const activeSources = sources.filter((s) => s.enabled).length;

  const syncErrors24h = useMemo(() => {
    const cutoff = Date.now() - DAY_MS;
    return (latestRunsQuery.data ?? []).filter(
      (run) => run.status === "FAILURE" && run.startedAt !== undefined && new Date(run.startedAt).getTime() >= cutoff
    ).length;
  }, [latestRunsQuery.data]);

  const newUsersNotDeployed =
    newUsersQuery.isError && newUsersQuery.error instanceof Error && newUsersQuery.error.message.includes("404");
  const recentRuns = recentRunsQuery.data?.content ?? [];
  const newUsers = newUsersQuery.data?.content ?? [];

  function refresh(): void {
    void queryClient.invalidateQueries({ queryKey: ["admin"] });
  }

  return (
    <div>
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Дашборд</h1>
        <button
          type="button"
          onClick={refresh}
          className="rounded border border-surface-border px-3 py-1.5 text-sm text-gray-300 hover:bg-surface-raised"
        >
          Обновить
        </button>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard
          label="Активные объявления"
          value={
            activeListingsQuery.isLoading ? "…" : activeListingsQuery.isError ? "—" : String(activeListingsQuery.data)
          }
        />
        <StatCard
          label="Источники"
          value={sourcesQuery.isLoading ? "…" : sourcesQuery.isError ? "—" : `${activeSources} / ${sources.length}`}
        />
        <StatCard
          label="Ошибки синков за 24ч"
          value={latestRunsQuery.isLoading ? "…" : latestRunsQuery.isError ? "—" : String(syncErrors24h)}
          alert={syncErrors24h > 0}
        />
      </div>

      <section className="mt-6">
        <h2 className="text-sm font-medium text-gray-400">Здоровье источников</h2>
        {sourcesQuery.isLoading && <p className="mt-2 text-sm text-gray-400">Загрузка…</p>}
        {sourcesQuery.isError && <p className="mt-2 text-sm text-red-400">Не удалось загрузить источники.</p>}
        {sources.length > 0 && (
          <div className="mt-2 space-y-2">
            {sources.map((source) => (
              <SourceHealthRow key={source.sourceId} source={source} />
            ))}
          </div>
        )}
      </section>

      <section className="mt-6">
        <h2 className="text-sm font-medium text-gray-400">Последние запуски синхронизации</h2>
        {recentRunsQuery.isLoading && <p className="mt-2 text-sm text-gray-400">Загрузка…</p>}
        {recentRunsQuery.isError && <p className="mt-2 text-sm text-red-400">Не удалось загрузить запуски.</p>}
        {recentRuns.length > 0 && (
          <div className="mt-2 overflow-x-auto rounded-lg border border-surface-border">
            <table className="w-full text-left text-sm">
              <thead className="bg-surface-raised text-xs uppercase tracking-wide text-gray-500">
                <tr>
                  <th className="px-4 py-2 font-medium">Источник</th>
                  <th className="px-4 py-2 font-medium">Статус</th>
                  <th className="px-4 py-2 font-medium">Начало</th>
                  <th className="px-4 py-2 font-medium">Добавлено</th>
                  <th className="px-4 py-2 font-medium">Ошибок</th>
                </tr>
              </thead>
              <tbody>
                {recentRuns.map((run) => (
                  <tr key={run.id} className="border-t border-surface-border">
                    <td className="px-4 py-2 font-mono text-gray-300">{run.sourceId}</td>
                    <td className="px-4 py-2">
                      <span
                        className={`rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${
                          SYNC_STATUS_BADGE_CLASS[run.status ?? ""] ?? "bg-surface-border text-gray-400"
                        }`}
                      >
                        {SYNC_STATUS_LABEL[run.status ?? ""] ?? run.status}
                      </span>
                    </td>
                    <td className="px-4 py-2 text-gray-400">{formatRelativeTime(run.startedAt)}</td>
                    <td className="px-4 py-2 text-gray-400">{run.listingsAdded}</td>
                    <td className="px-4 py-2 text-gray-400">{run.listingsErrors}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {!newUsersNotDeployed && (
        <section className="mt-6">
          <h2 className="text-sm font-medium text-gray-400">Новые пользователи</h2>
          {newUsersQuery.isLoading && <p className="mt-2 text-sm text-gray-400">Загрузка…</p>}
          {newUsersQuery.isError && (
            <p className="mt-2 text-sm text-red-400">Не удалось загрузить пользователей.</p>
          )}
          {newUsers.length > 0 && (
            <ul className="mt-2 divide-y divide-surface-border rounded-lg border border-surface-border">
              {newUsers.map((user) => (
                <li key={user.id} className="flex items-center justify-between px-4 py-2 text-sm">
                  <span className="text-gray-200">{user.displayName}</span>
                  <span className="text-gray-400">{formatRelativeTime(user.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}

interface StatCardProps {
  label: string;
  value: string;
  alert?: boolean;
}

function StatCard({ label, value, alert }: StatCardProps): ReactElement {
  return (
    <div className="rounded-lg border border-surface-border bg-surface-raised p-4">
      <p className="text-xs uppercase tracking-wide text-gray-500">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${alert ? "text-red-400" : "text-white"}`}>{value}</p>
    </div>
  );
}

function SourceHealthRow({ source }: { source: AdminSource }): ReactElement {
  const runsQuery = useQuery({
    queryKey: ["admin", "sync-runs", "health", source.sourceId],
    queryFn: () => fetchRecentSyncRuns(HEALTH_STRIP_SIZE, source.sourceId),
    enabled: source.sourceId !== undefined,
  });

  const runs = [...(runsQuery.data?.content ?? [])].reverse();

  return (
    <div className="flex items-center gap-3 rounded border border-surface-border px-3 py-2">
      <span className="w-32 truncate text-sm text-gray-300">{source.displayName}</span>
      <div className="flex items-center gap-1">
        {runsQuery.isLoading && <span className="text-xs text-gray-500">Загрузка…</span>}
        {runs.map((run) => (
          <span
            key={run.id}
            title={`${run.status ?? "?"} · ${formatRelativeTime(run.startedAt)}`}
            className={`h-3 w-3 rounded-full ${run.status === "SUCCESS" ? "bg-emerald-400" : "bg-red-400"}`}
          />
        ))}
        {!runsQuery.isLoading && runs.length === 0 && <span className="text-xs text-gray-500">Нет данных</span>}
      </div>
    </div>
  );
}
