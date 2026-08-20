import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { App } from "./App";
import { ApiError } from "./api/apiError";
import "./styles.css";

const MAX_RETRIES = 3;

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Client errors (4xx, including 429 rate limiting) won't succeed on immediate retry —
      // retrying a 429 only makes the caller's rate-limit budget worse (issue #360). Retry stays
      // on for network failures and 5xx, matching the previous default behaviour for those.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < MAX_RETRIES,
    },
  },
});

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename="/admin">
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
