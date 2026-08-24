import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ReactElement } from "react";
import {
  fetchListingDetail,
  unlinkDuplicateGroup,
  updateListingStatus,
} from "../../api/adminListings";
import { isAllowedImageUrl } from "../../lib/isAllowedImageUrl";

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: "Активно",
  INACTIVE: "Неактивно",
  REPOSTED: "Повторное размещение",
};

interface ListingDetailModalProps {
  listingId: number;
  photoUrl: string | null | undefined;
  onClose: () => void;
}

export function ListingDetailModal({ listingId, photoUrl, onClose }: ListingDetailModalProps): ReactElement {
  const queryClient = useQueryClient();

  const detailQuery = useQuery({
    queryKey: ["admin", "listing", listingId],
    queryFn: () => fetchListingDetail(listingId),
  });

  const deactivateMutation = useMutation({
    mutationFn: () => updateListingStatus(listingId, "INACTIVE"),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin", "listing", listingId] });
      void queryClient.invalidateQueries({ queryKey: ["admin", "listings"] });
    },
  });

  const unlinkMutation = useMutation({
    mutationFn: () => unlinkDuplicateGroup(listingId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin", "listing", listingId] });
      void queryClient.invalidateQueries({ queryKey: ["admin", "listings"] });
    },
  });

  const listing = detailQuery.data;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Объявление"
        className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-lg border border-surface-border bg-surface-raised p-6 text-sm"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <h2 className="text-lg font-semibold text-white">Объявление #{listingId}</h2>
          <button type="button" onClick={onClose} aria-label="Закрыть" className="text-gray-400 hover:text-white">
            ✕
          </button>
        </div>

        {detailQuery.isLoading && <p className="text-gray-400">Загрузка…</p>}
        {detailQuery.isError && <p className="text-red-400">Не удалось загрузить объявление.</p>}

        {listing && (
          <div className="space-y-4">
            {isAllowedImageUrl(photoUrl) && (
              <img src={photoUrl} alt={listing.title ?? ""} className="w-full rounded-md object-cover" />
            )}

            <div>
              <h3 className="text-base font-medium text-white">{listing.title}</h3>
              {listing.description && <p className="mt-1 text-gray-400">{listing.description}</p>}
            </div>

            <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-gray-300">
              <dt className="text-gray-500">Адрес</dt>
              <dd>{[listing.city, listing.district, listing.address].filter(Boolean).join(", ") || "—"}</dd>
              <dt className="text-gray-500">Цена</dt>
              <dd>
                {listing.priceLabel ?? `${listing.price} ${listing.currency}`}
              </dd>
              <dt className="text-gray-500">Статус</dt>
              <dd>{STATUS_LABEL[listing.status ?? ""] ?? listing.status}</dd>
            </dl>

            {listing.priceHistory && listing.priceHistory.length > 0 && (
              <div>
                <h4 className="mb-1 text-xs font-medium uppercase tracking-wide text-gray-500">
                  История цены
                </h4>
                <ul className="space-y-0.5 text-gray-400">
                  {listing.priceHistory.map((entry, index) => (
                    <li key={index}>
                      {entry.price} {entry.currency} — {entry.recordedAt}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <div className="flex gap-2 pt-2">
              {listing.sourceUrl && (
                <a
                  href={listing.sourceUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="rounded border border-surface-border px-3 py-1.5 text-gray-300 hover:text-white"
                >
                  Открыть на источнике
                </a>
              )}
              <button
                type="button"
                disabled={listing.status === "INACTIVE" || deactivateMutation.isPending}
                onClick={() => deactivateMutation.mutate()}
                className="rounded bg-red-500/20 px-3 py-1.5 text-red-400 disabled:opacity-40"
              >
                Деактивировать
              </button>
              {listing.hasDuplicates && (
                <button
                  type="button"
                  disabled={unlinkMutation.isPending}
                  onClick={() => unlinkMutation.mutate()}
                  className="rounded border border-surface-border px-3 py-1.5 text-gray-300 disabled:opacity-40"
                >
                  Отвязать дубликат
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
