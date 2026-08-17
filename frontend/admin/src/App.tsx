import type { ReactElement } from "react";
import { Route, Routes } from "react-router-dom";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { AdminLayout } from "./components/layout/AdminLayout";
import { ListingsPage } from "./pages/ListingsPage";
import { LoginPage } from "./pages/LoginPage";
import { PlaceholderPage } from "./pages/PlaceholderPage";
import { SourcesPage } from "./pages/SourcesPage";

export function App(): ReactElement {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AdminLayout />}>
          <Route path="/" element={<PlaceholderPage title="Дашборд" />} />
          <Route path="/listings" element={<ListingsPage />} />
          <Route path="/sources" element={<SourcesPage />} />
          <Route path="/users" element={<PlaceholderPage title="Пользователи" />} />
        </Route>
      </Route>
    </Routes>
  );
}
