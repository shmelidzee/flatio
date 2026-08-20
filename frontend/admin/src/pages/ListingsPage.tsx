import { useState } from "react";
import type { FormEvent, ReactElement } from "react";
import { useQuery } from "@tanstack/react-query";
import { fetchSources } from "../api/adminSources";
import { fetchListings } from "../api/adminListings";
import type { AdminListingFilters } from "../api/adminListings";
import { formatRelativeTime } from "../lib/formatRelativeTime";
import { ListingDetailModal } from "../components/listings/ListingDetailModal";
import { QueryErrorMessage } from "../components/common/QueryErrorMessage";

const EMPTY_FILTERS: AdminListingFilters = {};

function toOptionalNumber(value: string): number | undefined {
  return value === "" ? undefined : Number(value);
}

export function ListingsPage(): ReactElement {
  const [draft, setDraft] = useState<AdminListingFilters>(EMPTY_FILTERS);
  const [filters, setFilters] = useState<AdminListingFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [selectedListingId, setSelectedListingId] = useState<number | null>(null);

  const sourcesQuery = useQuery({ queryKey: ["admin", "sources"], queryFn: fetchSources });
  const listingsQuery = useQuery({
    queryKey: ["admin", "listings", filters, page],
    queryFn: () => fetchListings(filters, page),
  });

  function applyFilters(e: FormEvent): void {
    e.preventDefault();
    setFilters(draft);
    setPage(0);
  }

  function resetFilters(): void {
    setDraft(EMPTY_FILTERS);
    setFilters(EMPTY_FILTERS);
    setPage(0);
  }

  const listings = listingsQuery.data?.content ?? [];
  const selectedListing = listings.find((l) => l.id === selectedListingId);

  return (
    <div>
      <h1 className="text-xl font-semibold">Объявления</h1>

      <form onSubmit={applyFilters} className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <select
          value={draft.sourceId ?? ""}
          onChange={(e) => setDraft({ ...draft, sourceId: e.target.value || undefined })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        >
          <option value="">Источник: любой</option>
          {sourcesQuery.data?.map((s) => (
            <option key={s.sourceId} value={s.sourceId}>
              {s.displayName}
            </option>
          ))}
        </select>

        <input
          type="text"
          placeholder="Город"
          value={draft.city ?? ""}
          onChange={(e) => setDraft({ ...draft, city: e.target.value || undefined })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        />

        <select
          value={draft.dealType ?? ""}
          onChange={(e) => setDraft({ ...draft, dealType: (e.target.value || undefined) as AdminListingFilters["dealType"] })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        >
          <option value="">Сделка: любая</option>
          <option value="RENT">Аренда</option>
          <option value="RENT_DAILY">Посуточно</option>
          <option value="SELL">Продажа</option>
        </select>

        <select
          value={draft.status ?? ""}
          onChange={(e) => setDraft({ ...draft, status: (e.target.value || undefined) as AdminListingFilters["status"] })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        >
          <option value="">Статус: любой</option>
          <option value="ACTIVE">Активно</option>
          <option value="INACTIVE">Неактивно</option>
          <option value="REPOSTED">Повторное</option>
        </select>

        <input
          type="number"
          placeholder="Цена от"
          value={draft.priceMin ?? ""}
          onChange={(e) => setDraft({ ...draft, priceMin: toOptionalNumber(e.target.value) })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        />
        <input
          type="number"
          placeholder="Цена до"
          value={draft.priceMax ?? ""}
          onChange={(e) => setDraft({ ...draft, priceMax: toOptionalNumber(e.target.value) })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        />
        <input
          type="number"
          placeholder="Площадь от"
          value={draft.areaMin ?? ""}
          onChange={(e) => setDraft({ ...draft, areaMin: toOptionalNumber(e.target.value) })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        />
        <input
          type="number"
          placeholder="Площадь до"
          value={draft.areaMax ?? ""}
          onChange={(e) => setDraft({ ...draft, areaMax: toOptionalNumber(e.target.value) })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        />
        <input
          type="number"
          placeholder="Комнаты"
          value={draft.rooms ?? ""}
          onChange={(e) => setDraft({ ...draft, rooms: toOptionalNumber(e.target.value) })}
          className="rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        />
        <input
          type="text"
          placeholder="Поиск"
          value={draft.query ?? ""}
          onChange={(e) => setDraft({ ...draft, query: e.target.value || undefined })}
          className="col-span-2 rounded border border-surface-border bg-surface px-2 py-1.5 text-gray-200"
        />
        <label className="flex items-center gap-2 text-gray-300">
          <input
            type="checkbox"
            checked={draft.duplicatesOnly ?? false}
            onChange={(e) => setDraft({ ...draft, duplicatesOnly: e.target.checked || undefined })}
          />
          Только дубли
        </label>

        <div className="col-span-2 flex gap-2 sm:col-span-4">
          <button type="submit" className="rounded bg-accent px-4 py-1.5 text-white hover:bg-accent-hover">
            Найти
          </button>
          <button
            type="button"
            onClick={resetFilters}
            className="rounded border border-surface-border px-4 py-1.5 text-gray-300"
          >
            Сбросить
          </button>
        </div>
      </form>

      {listingsQuery.isLoading && <p className="mt-4 text-sm text-gray-400">Загрузка…</p>}
      {listingsQuery.isError && (
        <QueryErrorMessage
          error={listingsQuery.error}
          fallback="Не удалось загрузить объявления."
          onRetry={() => void listingsQuery.refetch()}
        />
      )}
      {listings.length === 0 && !listingsQuery.isLoading && !listingsQuery.isError && (
        <p className="mt-4 text-sm text-gray-400">Объявления не найдены.</p>
      )}

      {listings.length > 0 && (
        <div className="mt-4 overflow-x-auto rounded-lg border border-surface-border">
          <table className="w-full text-left text-sm">
            <thead className="bg-surface-raised text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3 font-medium">Заголовок</th>
                <th className="px-4 py-3 font-medium">Цена</th>
                <th className="px-4 py-3 font-medium">Комнаты</th>
                <th className="px-4 py-3 font-medium">Площадь</th>
                <th className="px-4 py-3 font-medium">Город</th>
                <th className="px-4 py-3 font-medium">Источник</th>
                <th className="px-4 py-3 font-medium">Опубликовано</th>
              </tr>
            </thead>
            <tbody>
              {listings.map((listing) => (
                <tr
                  key={listing.id}
                  role="button"
                  tabIndex={0}
                  aria-label={`Открыть объявление: ${listing.title ?? listing.id}`}
                  className="cursor-pointer border-t border-surface-border hover:bg-surface-raised focus:outline-none focus-visible:bg-surface-raised"
                  onClick={() => setSelectedListingId(listing.id ?? null)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      setSelectedListingId(listing.id ?? null);
                    }
                  }}
                >
                  <td className="px-4 py-3 text-gray-200">{listing.title}</td>
                  <td className="px-4 py-3 text-gray-400">
                    {listing.isNegotiable ? "Договорная" : `${listing.price} ${listing.currency}`}
                  </td>
                  <td className="px-4 py-3 text-gray-400">{listing.rooms ?? "—"}</td>
                  <td className="px-4 py-3 text-gray-400">{listing.areaTotalM2 ?? "—"}</td>
                  <td className="px-4 py-3 text-gray-400">{listing.city ?? "—"}</td>
                  <td className="px-4 py-3 font-mono text-gray-400">{listing.sourceId}</td>
                  <td className="px-4 py-3 text-gray-400">{formatRelativeTime(listing.publishedAt)}</td>
                </tr>
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
              Страница {page + 1} из {listingsQuery.data?.totalPages ?? 1}
            </span>
            <button
              type="button"
              disabled={listingsQuery.data?.last === true}
              onClick={() => setPage((p) => p + 1)}
              className="rounded border border-surface-border px-2 py-1 disabled:opacity-40"
            >
              Вперёд →
            </button>
          </div>
        </div>
      )}

      {selectedListingId !== null && (
        <ListingDetailModal
          listingId={selectedListingId}
          photoUrl={selectedListing?.photoUrl}
          onClose={() => setSelectedListingId(null)}
        />
      )}
    </div>
  );
}
