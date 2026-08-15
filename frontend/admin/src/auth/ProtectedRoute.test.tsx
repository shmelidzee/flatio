import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { setToken } from "./token";
import { ProtectedRoute } from "./ProtectedRoute";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<div>login page</div>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/" element={<div>protected content</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("ProtectedRoute", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("should_redirect_to_login_when_no_token_present", () => {
    renderAt("/");

    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  it("should_render_outlet_when_token_present", () => {
    setToken("abc123");

    renderAt("/");

    expect(screen.getByText("protected content")).toBeInTheDocument();
  });
});
