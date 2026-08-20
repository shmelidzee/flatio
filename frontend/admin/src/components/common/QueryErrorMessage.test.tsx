import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/apiError";
import { QueryErrorMessage } from "./QueryErrorMessage";

describe("QueryErrorMessage", () => {
  it("should_show_fallback_message_when_error_is_not_rate_limited", () => {
    render(<QueryErrorMessage error={new Error("boom")} fallback="Не удалось загрузить объявления." />);

    expect(screen.getByText("Не удалось загрузить объявления.")).toBeInTheDocument();
  });

  it("should_show_rate_limit_message_when_error_status_is_429", () => {
    render(
      <QueryErrorMessage error={new ApiError(429, "HTTP 429")} fallback="Не удалось загрузить объявления." />,
    );

    expect(screen.getByText(/Слишком много запросов/)).toBeInTheDocument();
    expect(screen.queryByText("Не удалось загрузить объявления.")).not.toBeInTheDocument();
  });

  it("should_call_on_retry_when_retry_button_clicked", () => {
    const onRetry = vi.fn();
    render(<QueryErrorMessage error={new Error("boom")} fallback="Не удалось загрузить." onRetry={onRetry} />);

    fireEvent.click(screen.getByText("Повторить"));

    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("should_not_render_retry_button_when_on_retry_omitted", () => {
    render(<QueryErrorMessage error={new Error("boom")} fallback="Не удалось загрузить." />);

    expect(screen.queryByText("Повторить")).not.toBeInTheDocument();
  });
});
