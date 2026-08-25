import { EventEmitter } from 'node:events'
import { describe, expect, it, vi } from 'vitest'
import { dropOriginHeader } from './vite.config'

// Regression coverage for the tailnet-submit-403 fix: the vite dev proxy is the app's
// same-origin boundary (see AGENTS.md "Local dev topology"), so a proxied request must
// never carry the browser's Origin header on to the backend - for any host the dev
// server is legitimately serving on, not just localhost. `dropOriginHeader` is the
// `configure` hook wired onto both the `/api` and `/actuator` proxy entries; a real
// dev-server proxy exposes the same `proxyReq` event on an EventEmitter-shaped proxy
// object, so a plain EventEmitter fixture exercises the real wiring, not a re-implementation
// of it.
describe('dropOriginHeader', () => {
  it('strips the Origin header from every proxied request, regardless of its value', () => {
    const proxy = new EventEmitter()
    // @ts-expect-error - the real proxy type is a superset of EventEmitter; the test
    // only needs the `on`/`emit` surface configure() actually uses.
    dropOriginHeader(proxy, {})

    for (const origin of [
      'http://localhost:5173',
      'http://100.116.141.102:5173',
      'http://my-tailnet-host:5173',
    ]) {
      const proxyReq = { removeHeader: vi.fn(), getHeader: () => origin }
      proxy.emit('proxyReq', proxyReq)
      expect(proxyReq.removeHeader).toHaveBeenCalledWith('origin')
    }
  })
})
