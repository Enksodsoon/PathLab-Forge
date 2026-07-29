import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: '/assets/',
  plugins: [react()],
  build: {
    outDir: '../build/frontend/web',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: 'app.js',
        chunkFileNames: 'chunk-[name]-[hash].js',
        manualChunks: (id) => {
          if (id.includes('/openseadragon/')) return 'openseadragon'
          if (id.includes('/@phosphor-icons/')) return 'icons'
          if (id.includes('/react/') || id.includes('/react-dom/')) return 'react'
          return undefined
        },
        assetFileNames: (asset) => asset.names.some((name) => name.endsWith('.css'))
          ? 'app.css'
          : 'asset-[name]-[hash][extname]',
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
})
