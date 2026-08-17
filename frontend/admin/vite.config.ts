/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  base: "/admin/",
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  server: {
    // In production the SPA and the API are served from the same Spring Boot origin, so
    // relative /api/... calls just work. In `npm run dev` there is no such shared origin,
    // so proxy /api to the local backend — this also sidesteps needing CORS_ALLOWED_ORIGINS
    // configured for the dev server's port, since the browser only ever talks to Vite.
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});
