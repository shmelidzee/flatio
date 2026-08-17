import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ListingsPage } from "./ListingsPage";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ListingsPage />
    </QueryClientProvider>,
  );
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

const LISTING_SUMMARY = {
  id: 1,
  title: "2-комнатная квартира, 52 м², Минск",
  price: 75000,
  currency: "USD",
  rooms: 2,
  areaTotalM2: 52.5,
  city: "Минск",
  sourceId: "realt",
  publishedAt: "2026-08-17T11:00:00.000Z",
  photoUrl: "https://cdn.example/photo.jpg",
  isNegotiable: false,
};

function mockFetchSequence(...responses: Response[]) {
  const fetchMock = vi.fn();
  responses.forEach((r) => fetchMock.mockResolvedValueOnce(r));
  vi.stubGlobal("fetch", fetchMock);
}

function emptySourcesResponse(): Response {
  return jsonResponse([]);
}

function listingsPage(content: unknown[] = [LISTING_SUMMARY]): Response {
  return jsonResponse({ content, totalPages: 1, last: true, number: 0 });
}

describe("ListingsPage", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("should_show_error_message_when_listings_fetch_fails", async () => {
    mockFetchSequence(emptySourcesResponse(), new Response(null, { status: 500 }));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Не удалось загрузить объявления/)).toBeInTheDocument());
  });

  it("should_show_empty_message_when_no_listings_returned", async () => {
    mockFetchSequence(emptySourcesResponse(), listingsPage([]));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Объявления не найдены/)).toBeInTheDocument());
  });

  it("should_render_listing_row_when_listings_loaded", async () => {
    mockFetchSequence(emptySourcesResponse(), listingsPage());

    renderPage();

    await waitFor(() => expect(screen.getByText(LISTING_SUMMARY.title)).toBeInTheDocument());
    expect(screen.getByText("Минск")).toBeInTheDocument();
    expect(screen.getByText("realt")).toBeInTheDocument();
  });

  it("should_refetch_with_new_filters_when_form_submitted", async () => {
    mockFetchSequence(emptySourcesResponse(), listingsPage());
    renderPage();
    await waitFor(() => expect(screen.getByText(LISTING_SUMMARY.title)).toBeInTheDocument());

    vi.mocked(fetch).mockResolvedValueOnce(listingsPage([]));
    fireEvent.change(screen.getByPlaceholderText("Город"), { target: { value: "Минск" } });
    fireEvent.click(screen.getByText("Найти"));

    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.at(-1);
      const url = new URL(String(call?.[0]), "http://localhost");
      expect(url.searchParams.get("city")).toBe("Минск");
    });
  });

  it("should_open_detail_modal_when_row_clicked", async () => {
    mockFetchSequence(emptySourcesResponse(), listingsPage());
    renderPage();
    await waitFor(() => expect(screen.getByText(LISTING_SUMMARY.title)).toBeInTheDocument());

    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ id: 1, title: LISTING_SUMMARY.title, hasDuplicates: false }));
    fireEvent.click(screen.getByText(LISTING_SUMMARY.title));

    await waitFor(() => expect(screen.getByRole("dialog")).toBeInTheDocument());
  });
});
