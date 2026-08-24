import { useEffect, useState } from "react";
import type { ReactElement } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { fetchUsers, updateUser } from "../api/adminUsers";
import type { AdminUser, AdminUserFilters, AdminUserUpdate, UserRoleValue } from "../api/adminUsers";
import { formatRelativeTime } from "../lib/formatRelativeTime";
import { QueryErrorMessage } from "../components/common/QueryErrorMessage";

const USERS_QUERY_KEY = ["admin", "users"];
const ROLE_OPTIONS: UserRoleValue[] = ["USER", "PRO", "ADMIN"];
const ERROR_BANNER_TIMEOUT_MS = 5_000;

const ROLE_LABEL: Record<UserRoleValue, string> = {
  USER: "Пользователь",
  PRO: "PRO",
  ADMIN: "Админ",
};

export function UsersPage(): ReactElement {
  const [filters, setFilters] = useState<AdminUserFilters>({});
  const [page, setPage] = useState(0);
  const [updateError, setUpdateError] = useState<string | null>(null);

  const usersQuery = useQuery({
    queryKey: [...USERS_QUERY_KEY, filters, page],
    queryFn: () => fetchUsers(filters, page),
  });

  // The error banner otherwise stays on screen indefinitely (issue #352) — auto-dismiss it after
  // a timeout so a stale failure doesn't linger once the admin has moved on.
  useEffect(() => {
    if (!updateError) {
      return;
    }
    const timer = window.setTimeout(() => setUpdateError(null), ERROR_BANNER_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [updateError]);

  function applyRoleFilter(role: string): void {
    setFilters((f) => ({ ...f, role: (role || undefined) as UserRoleValue | undefined }));
    setPage(0);
    setUpdateError(null);
  }

  function applyActiveFilter(active: string): void {
    setFilters((f) => ({ ...f, active: active === "" ? undefined : active === "true" }));
    setPage(0);
    setUpdateError(null);
  }

  const users = usersQuery.data?.content ?? [];

  return (
    <div>
      <h1 className="text-xl font-semibold">Пользователи</h1>

      <div className="mt-4 flex gap-3">
        <select
          value={filters.role ?? ""}
          onChange={(e) => applyRoleFilter(e.target.value)}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        >
          <option value="">Роль: любая</option>
          {ROLE_OPTIONS.map((role) => (
            <option key={role} value={role}>
              {ROLE_LABEL[role]}
            </option>
          ))}
        </select>
        <select
          value={filters.active === undefined ? "" : String(filters.active)}
          onChange={(e) => applyActiveFilter(e.target.value)}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        >
          <option value="">Статус: любой</option>
          <option value="true">Активен</option>
          <option value="false">Деактивирован</option>
        </select>
      </div>

      {updateError && (
        <p className="mt-3 text-sm text-red-400">
          Не удалось обновить пользователя: {updateError}
        </p>
      )}

      {usersQuery.isLoading && <p className="mt-4 text-sm text-gray-400">Загрузка…</p>}
      {usersQuery.isError && (
        <QueryErrorMessage
          error={usersQuery.error}
          fallback="Не удалось загрузить пользователей."
          onRetry={() => void usersQuery.refetch()}
        />
      )}
      {users.length === 0 && !usersQuery.isLoading && !usersQuery.isError && (
        <p className="mt-4 text-sm text-gray-400">Пользователи не найдены.</p>
      )}

      {users.length > 0 && (
        <div className="mt-4 overflow-x-auto rounded-lg border border-surface-border">
          <table className="w-full text-left text-sm">
            <thead className="bg-surface-raised text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3 font-medium">Имя</th>
                <th className="px-4 py-3 font-medium">Email</th>
                <th className="px-4 py-3 font-medium">Роль</th>
                <th className="px-4 py-3 font-medium">Статус</th>
                <th className="px-4 py-3 font-medium">Последний визит</th>
                <th className="px-4 py-3 font-medium">Регистрация</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <UserRow key={user.id} user={user} onError={setUpdateError} />
              ))}
            </tbody>
          </table>

          <div className="flex items-center gap-3 border-t border-surface-border px-4 py-3 text-xs text-gray-400">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
              className="rounded border border-surface-border px-2 py-1 disabled:opacity-40"
            >
              ← Назад
            </button>
            <span>
              Страница {page + 1} из {usersQuery.data?.totalPages ?? 1}
            </span>
            <button
              type="button"
              disabled={usersQuery.data?.last === true}
              onClick={() => setPage((p) => p + 1)}
              className="rounded border border-surface-border px-2 py-1 disabled:opacity-40"
            >
              Вперёд →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

interface UserRowProps {
  user: AdminUser;
  onError: (message: string) => void;
}

/**
 * Each row owns its own {@code useMutation} instance rather than sharing one across the whole
 * table (issue #391) — TanStack Query only tracks {@code isPending}/{@code variables} for the
 * most recently invoked call of a given mutation, so a single shared mutation's "saving" state
 * followed whichever row was edited last, not the row a given control actually belongs to.
 * Errors still bubble up to the page-level banner via {@link UserRowProps#onError}.
 */
function UserRow({ user, onError }: UserRowProps): ReactElement {
  const queryClient = useQueryClient();

  const updateMutation = useMutation({
    mutationFn: (patch: AdminUserUpdate) => updateUser(user.id ?? 0, patch),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: USERS_QUERY_KEY });
    },
    onError: (error) => {
      onError(error instanceof Error ? error.message : String(error));
    },
  });

  return (
    <tr className="border-t border-surface-border">
      <td className="px-4 py-3 text-gray-200">{user.displayName}</td>
      <td className="px-4 py-3 text-gray-400">{user.email ?? "—"}</td>
      <td className="px-4 py-3">
        <select
          value={user.role ?? "USER"}
          disabled={updateMutation.isPending}
          onChange={(e) => updateMutation.mutate({ role: e.target.value as UserRoleValue })}
          className="rounded border border-surface-border bg-surface px-2 py-1 text-gray-200 disabled:opacity-50"
        >
          {ROLE_OPTIONS.map((role) => (
            <option key={role} value={role}>
              {ROLE_LABEL[role]}
            </option>
          ))}
        </select>
      </td>
      <td className="px-4 py-3">
        <button
          type="button"
          role="switch"
          aria-checked={user.active}
          aria-label={user.active ? "Деактивировать пользователя" : "Активировать пользователя"}
          disabled={updateMutation.isPending}
          onClick={() => updateMutation.mutate({ active: !user.active })}
          className={`relative h-5 w-9 rounded-full transition-colors disabled:opacity-50 ${
            user.active ? "bg-accent" : "bg-surface-border"
          }`}
        >
          <span
            className={`absolute top-0.5 h-4 w-4 rounded-full bg-white transition-transform ${
              user.active ? "translate-x-4" : "translate-x-0.5"
            }`}
          />
        </button>
      </td>
      <td className="px-4 py-3 text-gray-400">{formatRelativeTime(user.lastSeen)}</td>
      <td className="px-4 py-3 text-gray-400">{formatRelativeTime(user.createdAt)}</td>
    </tr>
  );
}
