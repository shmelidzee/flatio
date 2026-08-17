import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ListingDetailModal } from "./ListingDetailModal";

function renderModal(onClose = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return {
    onClose,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ListingDetailModal listingId={42} photoUrl="https://cdn.example/photo.jpg" onClose={onClose} />
      </QueryClientProvider>,
    ),
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

const DETAIL = {
  id: 42,
  title: "2-комнатная квартира, 52 м², Минск",
  description: "Сдаётся квартира с мебелью",
  city: "Минск",
  district: "Советский район",
  address: "ул. Пушкина, 5",
  price: 75000,
  currency: "USD",
  priceLabel: null,
  status: "ACTIVE",
  hasDuplicates: false,
  priceHistory: [{ price: 75000, currency: "USD", recordedAt: "2026-01-15T11:00:00Z" }],
};

describe("ListingDetailModal", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_show_error_message_when_detail_fetch_fails", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 500 }));

    renderModal();

    await waitFor(() => expect(screen.getByText(/Не удалось загрузить объявление/)).toBeInTheDocument());
  });

  it("should_render_listing_details_when_loaded", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(DETAIL));

    renderModal();

    await waitFor(() => expect(screen.getByText(DETAIL.title)).toBeInTheDocument());
    expect(screen.getByText(DETAIL.description)).toBeInTheDocument();
    expect(screen.getByText(/Минск, Советский район, ул. Пушкина, 5/)).toBeInTheDocument();
  });

  it("should_not_show_unlink_button_when_listing_has_no_duplicates", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(DETAIL));

    renderModal();

    await waitFor(() => expect(screen.getByText(DETAIL.title)).toBeInTheDocument());
    expect(screen.queryByText("Отвязать дубликат")).not.toBeInTheDocument();
  });

  it("should_show_unlink_button_when_listing_has_duplicates", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ ...DETAIL, hasDuplicates: true }));

    renderModal();

    await waitFor(() => expect(screen.getByText("Отвязать дубликат")).toBeInTheDocument());
  });

  it("should_send_patch_request_when_deactivate_clicked", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(DETAIL));
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ id: 42, status: "INACTIVE" }));
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ ...DETAIL, status: "INACTIVE" }));

    renderModal();
    await waitFor(() => expect(screen.getByText("Деактивировать")).toBeInTheDocument());

    fireEvent.click(screen.getByText("Деактивировать"));

    await waitFor(() => {
      const patchCall = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === "PATCH");
      expect(patchCall).toBeDefined();
      expect(patchCall?.[1]?.body).toBe(JSON.stringify({ status: "INACTIVE" }));
    });
  });

  it("should_call_on_close_when_backdrop_clicked", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(DETAIL));
    const { onClose } = renderModal();
    await waitFor(() => expect(screen.getByText(DETAIL.title)).toBeInTheDocument());

    fireEvent.click(screen.getByRole("dialog").parentElement as HTMLElement);

    expect(onClose).toHaveBeenCalled();
  });
});
