import type { ReactElement } from "react";
import { Route, Routes } from "react-router-dom";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { AdminLayout } from "./components/layout/AdminLayout";
import { LoginPage } from "./pages/LoginPage";
import { PlaceholderPage } from "./pages/PlaceholderPage";

export function App(): ReactElement {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AdminLayout />}>
          <Route path="/" element={<PlaceholderPage title="Дашборд" />} />
          <Route path="/listings" element={<PlaceholderPage title="Объявления" />} />
          <Route path="/sources" element={<PlaceholderPage title="Источники" />} />
          <Route path="/users" element={<PlaceholderPage title="Пользователи" />} />
        </Route>
      </Route>
    </Routes>
  );
}
