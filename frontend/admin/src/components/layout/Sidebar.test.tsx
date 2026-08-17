import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { setToken, getToken } from "../../auth/token";
import { Sidebar } from "./Sidebar";

describe("Sidebar logout", () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it("should_clear_token_and_navigate_to_login_when_logout_clicked", () => {
    setToken("stored-jwt");

    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/" element={<Sidebar />} />
          <Route path="/login" element={<div>login page</div>} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Выйти" }));

    expect(getToken()).toBeNull();
    expect(screen.getByText("login page")).toBeInTheDocument();
  });
});
