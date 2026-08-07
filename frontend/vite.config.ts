/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Bind every interface, not just localhost, so the tailnet goal in the planning
    // map (issue #1) works: opening the tailnet IP/hostname from another device reaches
    // this dev server the same as localhost does.
    host: true,
    // Fail immediately with Vite's own "Port 5173 is already in use" error instead of
    // silently moving to 5174. A silent move was the actual bug (issue #34): the page
    // still loaded looking healthy, and every API call was refused with no explanation
    // connecting the two.
    strictPort: true,
    // Proxy API calls through this dev server so the browser's calls are same-origin
    // (see src/App.tsx's API_BASE_URL). This is what makes the tailnet case work without
    // any CORS config at all: the browser only ever talks to whatever origin loaded the
    // page, and this Node process forwards to the backend on localhost, where it always
    // runs alongside the frontend (issue #2). It also means CORS is no longer load-bearing
    // for the app's own calls in dev - only for anything that talks to the backend directly.
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
  },
})
