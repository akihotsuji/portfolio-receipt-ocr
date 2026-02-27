/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],

  base: "/receipt-ocr/",

  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },

  server: {
    port: 5175,
    host: "0.0.0.0",
    watch: {
      usePolling: true,
      interval: 1000,
    },
    hmr: {
      host: "localhost",
      port: 5175,
    },
  },

  build: {
    outDir: "dist",
    sourcemap: true,
  },

  test: {
    globals: true,
    environment: "jsdom",
    coverage: {
      provider: "v8",
      reporter: ["text", "json", "html"],
    },
  },
});
