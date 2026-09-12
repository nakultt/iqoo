import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The built bundle is served by Ktor from the same origin as the API (§6.3),
// so production needs no CORS grant. In dev, Vite proxies /v1 to the backend
// to keep that single-origin assumption true while hot-reloading.
export default defineConfig({
  plugins: [react()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: {
      '/v1': { target: 'http://localhost:8080', changeOrigin: true },
      '/health': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
