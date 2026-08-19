import { useState } from "react";
import type { ReactElement } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchSources, fetchSyncRuns, updateSource } from "../api/adminSources";
import type { AdminSource, AdminSourceUpdate, AdminSyncRun } from "../api/adminSources";
import { formatRelativeTime } from "../lib/formatRelativeTime";

const SOURCES_QUERY_KEY = ["admin", "sources"];

/** A source counts as stale once this many sync intervals have passed without a successful sync. */
const STALE_INTERVAL_FACTOR = 2;

type Health = "healthy" | "stale" | "never";

function sourceHealth(source: AdminSource): Health {
  if (!source.lastSyncAt) {
    return "never";
  }
  const elapsedMinutes = (Date.now() - new Date(source.lastSyncAt).getTime()) / 60_000;
  const interval = source.syncIntervalMinutes ?? 0;
  return interval > 0 && elapsedMinutes > interval * STALE_INTERVAL_FACTOR ? "stale" : "healthy";
}

const HEALTH_LABEL: Record<Health, string> = {
  healthy: "Здоров",
  stale: "Задержка",
  never: "Нет данных",
};

const HEALTH_DOT_CLASS: Record<Health, string> = {
  healthy: "bg-emerald-400",
  stale: "bg-amber-400",
  never: "bg-gray-500",
};

const SYNC_STATUS_BADGE_CLASS: Record<string, string> = {
  SUCCESS: "bg-emerald-500/20 text-emerald-400",
  FAILURE: "bg-red-500/20 text-red-400",
};

const SYNC_STATUS_LABEL: Record<string, string> = {
  SUCCESS: "SUCCESS",
  FAILURE: "FAILED",
};

function formatDuration(durationMs: number | undefined): string {
  if (durationMs === undefined) {
    return "—";
  }
  const seconds = durationMs / 1000;
  return seconds < 60 ? `${seconds.toFixed(1)} с` : `${Math.floor(seconds / 60)} мин ${Math.round(seconds % 60)} с`;
}

export function SourcesPage(): ReactElement {
  const queryClient = useQueryClient();
  const [expandedSourceId, setExpandedSourceId] = useState<string | null>(null);

  const sourcesQuery = useQuery({ queryKey: SOURCES_QUERY_KEY, queryFn: fetchSources });

  const updateMutation = useMutation({
    mutationFn: ({ sourceId, patch }: { sourceId: string; patch: AdminSourceUpdate }) =>
      updateSource(sourceId, patch),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SOURCES_QUERY_KEY });
    },
  });

  function toggleExpanded(sourceId: string): void {
    setExpandedSourceId((current) => (current === sourceId ? null : sourceId));
  }

  return (
    <div>
      <h1 className="text-xl font-semibold">Источники</h1>

      {sourcesQuery.isLoading && <p className="mt-4 text-sm text-gray-400">Загрузка…</p>}
      {sourcesQuery.isError && <p className="mt-4 text-sm text-red-400">Не удалось загрузить источники.</p>}
      {sourcesQuery.data?.length === 0 && <p className="mt-4 text-sm text-gray-400">Источники не найдены.</p>}

      {sourcesQuery.data && sourcesQuery.data.length > 0 && (
        <div className="mt-4 overflow-x-auto rounded-lg border border-surface-border">
          <table className="w-full text-left text-sm">
            <thead className="bg-surface-raised text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3 font-medium">Код</th>
                <th className="px-4 py-3 font-medium">Название</th>
                <th className="px-4 py-3 font-medium">URL</th>
                <th className="px-4 py-3 font-medium">Страна</th>
                <th className="px-4 py-3 font-medium">Статус</th>
                <th className="px-4 py-3 font-medium">Последний успешный запуск</th>
                <th className="px-4 py-3 font-medium">Здоровье</th>
              </tr>
            </thead>
            <tbody>
              {sourcesQuery.data.map((source) => (
                <SourceRow
                  key={source.sourceId}
                  source={source}
                  expanded={expandedSourceId === source.sourceId}
                  saving={updateMutation.isPending && updateMutation.variables?.sourceId === source.sourceId}
                  onToggleExpanded={() => toggleExpanded(source.sourceId ?? "")}
                  onToggleEnabled={() =>
                    updateMutation.mutate({ sourceId: source.sourceId ?? "", patch: { enabled: !source.enabled } })
                  }
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

interface SourceRowProps {
  source: AdminSource;
  expanded: boolean;
  saving: boolean;
  onToggleExpanded: () => void;
  onToggleEnabled: () => void;
}

function SourceRow({ source, expanded, saving, onToggleExpanded, onToggleEnabled }: SourceRowProps): ReactElement {
  const health = sourceHealth(source);

  return (
    <>
      <tr
        role="button"
        tabIndex={0}
        aria-expanded={expanded}
        aria-label={`История синков: ${source.displayName ?? source.sourceId}`}
        className="cursor-pointer border-t border-surface-border hover:bg-surface-raised focus:outline-none focus-visible:bg-surface-raised"
        onClick={onToggleExpanded}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onToggleExpanded();
          }
        }}
      >
        <td className="px-4 py-3 font-mono text-gray-300">{source.sourceId}</td>
        <td className="px-4 py-3">{source.displayName}</td>
        <td className="max-w-xs truncate px-4 py-3 text-gray-400">{source.url}</td>
        <td className="px-4 py-3 text-gray-400">{source.countryCode}</td>
        <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
          <button
            type="button"
            role="switch"
            aria-checked={source.enabled}
            aria-label={source.enabled ? "Отключить источник" : "Включить источник"}
            disabled={saving}
            onClick={onToggleEnabled}
            className={`relative h-5 w-9 rounded-full transition-colors disabled:opacity-50 ${
              source.enabled ? "bg-accent" : "bg-surface-border"
            }`}
          >
            <span
              className={`absolute top-0.5 h-4 w-4 rounded-full bg-white transition-transform ${
                source.enabled ? "translate-x-4" : "translate-x-0.5"
              }`}
            />
          </button>
        </td>
        <td className="px-4 py-3 text-gray-400">{formatRelativeTime(source.lastSyncAt)}</td>
        <td className="px-4 py-3">
          <span className="flex items-center gap-2">
            <span className={`h-2 w-2 rounded-full ${HEALTH_DOT_CLASS[health]}`} />
            <span className="text-gray-400">{HEALTH_LABEL[health]}</span>
          </span>
        </td>
      </tr>
      {expanded && (
        <tr className="border-t border-surface-border bg-surface">
          <td colSpan={7} className="px-4 py-4">
            <SyncRunHistory sourceId={source.sourceId ?? ""} />
          </td>
        </tr>
      )}
    </>
  );
}

function SyncRunHistory({ sourceId }: { sourceId: string }): ReactElement {
  const [page, setPage] = useState(0);

  const runsQuery = useQuery({
    queryKey: ["admin", "sync-runs", sourceId, page],
    queryFn: () => fetchSyncRuns(sourceId, page),
  });

  if (runsQuery.isLoading) {
    return <p className="text-sm text-gray-400">Загрузка истории синков…</p>;
  }
  if (runsQuery.isError) {
    return <p className="text-sm text-red-400">Не удалось загрузить историю синков.</p>;
  }
  const runs = runsQuery.data?.content ?? [];
  if (runs.length === 0) {
    return <p className="text-sm text-gray-400">Запусков синка ещё не было.</p>;
  }

  return (
    <div>
      <table className="w-full text-left text-xs">
        <thead className="text-gray-500">
          <tr>
            <th className="px-2 py-1 font-medium">Тип</th>
            <th className="px-2 py-1 font-medium">Статус</th>
            <th className="px-2 py-1 font-medium">Начало</th>
            <th className="px-2 py-1 font-medium">Длительность</th>
            <th className="px-2 py-1 font-medium">Получено</th>
            <th className="px-2 py-1 font-medium">Добавлено</th>
            <th className="px-2 py-1 font-medium">Обновлено</th>
            <th className="px-2 py-1 font-medium">Ошибок</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run: AdminSyncRun) => (
            <tr key={run.id} className="border-t border-surface-border">
              <td className="px-2 py-1.5 text-gray-300">{run.syncType}</td>
              <td className="px-2 py-1.5">
                <span
                  className={`rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${
                    SYNC_STATUS_BADGE_CLASS[run.status ?? ""] ?? "bg-surface-border text-gray-400"
                  }`}
                >
                  {SYNC_STATUS_LABEL[run.status ?? ""] ?? run.status}
                </span>
              </td>
              <td className="px-2 py-1.5 text-gray-400">{formatRelativeTime(run.startedAt)}</td>
              <td className="px-2 py-1.5 text-gray-400">{formatDuration(run.durationMs)}</td>
              <td className="px-2 py-1.5 text-gray-400">{run.listingsFetched}</td>
              <td className="px-2 py-1.5 text-gray-400">{run.listingsAdded}</td>
              <td className="px-2 py-1.5 text-gray-400">{run.listingsUpdated}</td>
              <td className="px-2 py-1.5 text-gray-400">{run.listingsErrors}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {(runsQuery.data?.totalPages ?? 0) > 1 && (
        <div className="mt-2 flex items-center gap-3 text-xs text-gray-400">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="rounded border border-surface-border px-2 py-1 disabled:opacity-40"
          >
            ← Назад
          </button>
          <span>
            Страница {page + 1} из {runsQuery.data?.totalPages}
          </span>
          <button
            type="button"
            disabled={runsQuery.data?.last === true}
            onClick={() => setPage((p) => p + 1)}
            className="rounded border border-surface-border px-2 py-1 disabled:opacity-40"
          >
            Вперёд →
          </button>
        </div>
      )}
    </div>
  );
}
