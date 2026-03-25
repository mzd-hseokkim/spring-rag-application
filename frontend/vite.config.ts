import path from "path"
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(() => {
  const apiBase = process.env.VITE_API_BASE || 'http://localhost:8080'
  const wsBase = apiBase.replace(/^http/, 'ws')

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "./src"),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: apiBase,
          changeOrigin: true,
          timeout: 600000,
        },
        '/uploads': {
          target: apiBase,
          changeOrigin: true,
        },
        '/ws': {
          target: wsBase,
          ws: true,
        },
      },
    },
  }
})
