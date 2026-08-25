/// <reference types="vitest/config" />
import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

// Strip the browser's Origin header before forwarding to the backend. A same-origin
// POST from the page still carries an Origin header (GETs don't), and that header
// names whatever host actually loaded the page - localhost, a tailnet IP, a future
// tailnet DNS name, anything this dev server is legitimately serving on (issue #34,
// "Local dev topology"). Left alone, that header reaches the backend looking like a
// foreign cross-origin request, and Spring's CORS check (WebConfig, allowed-origins
// defaulting to localhost:5173/5174 only) 403s it - a real bug, not a hardening
// feature, because this hop is server-to-server (this Node process to the backend on
// localhost), never a browser CORS request at all. Removing the header is what makes
// that true for the backend too: with no Origin header, Spring's CorsUtils.isCorsRequest
// is false and the request proceeds as ordinary same-origin traffic, for any host this
// server is bound to - not just one hardcoded address.
// Exported (not just used inline) so vite.config.test.ts can exercise it directly -
// it's the one piece of this config with real logic worth a regression test.
export const dropOriginHeader: ProxyOptions['configure'] = (proxy) => {
  proxy.on('proxyReq', (proxyReq) => {
    proxyReq.removeHeader('origin')
  })
}

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
    // runs alongside the frontend (issue #2). This proxy hop is the same-origin boundary,
    // so it also strips the Origin header it forwards (see dropOriginHeader above) - CORS
    // is not load-bearing for the app's own calls in dev, for GETs or POSTs, on localhost
    // or a tailnet address - only for anything that talks to the backend directly.
    proxy: {
      '/api': { target: 'http://localhost:8080', configure: dropOriginHeader },
      '/actuator': { target: 'http://localhost:8080', configure: dropOriginHeader },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
  },
})
