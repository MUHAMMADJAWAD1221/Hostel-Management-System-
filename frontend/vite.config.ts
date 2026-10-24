import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('/recharts/')) return 'charts'
          if (id.includes('/lucide-react/')) return 'icons'
          if (id.includes('/react-dom/') || id.includes('/react/')) return 'react'
          return undefined
        },
      },
    },
  },
})
