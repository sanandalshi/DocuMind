import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // In development the API runs on :8080; in Docker, Nginx proxies /api instead.
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } },
  },
});
