import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    chunkSizeWarningLimit: 1000, // Recharts is large; this only silences the size warning
  },
  server: {
    port: 5173,
    strictPort: true, // the backend CORS config only allows port 5173
  },
});
