import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Em desenvolvimento, /api vai para o Gateway; o navegador vê uma única origem e o Gateway não precisa de CORS.
const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': gatewayUrl,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    restoreMocks: true,
  },
})
