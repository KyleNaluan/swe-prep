// Shared backend-call helpers, used by both the practice editor (App) and the warm-up
// runner (Warmup). Extracted so the two surfaces name backend failures identically
// (issue #34) rather than each rolling its own fetch.

// Empty by default: the dev server proxies /api to the backend (vite.config.ts) so every
// call is same-origin, whether the page was opened as localhost or a tailnet address.
// That makes CORS a non-issue for the app's own calls in dev. Set VITE_API_BASE_URL to
// call a backend directly, bypassing the proxy.
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

// The content endpoints answer a failure with a { error } body (see the backend's
// ContentErrorHandler); surface that message rather than a bare status code.
export async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: string }
    if (body && typeof body.error === 'string') return body.error
  } catch {
    // fall through to the status
  }
  return `backend returned ${response.status}`
}

// A wrapper around fetch that names the cause when the request never got an HTTP
// response at all. Browsers reject a fetch with a bare "TypeError: Failed to fetch" for
// several distinct failures - the backend is down, the network is unreachable, or the
// browser silently blocked the response as cross-origin - and deliberately do not say
// which, so that message alone is not actionable. This is the failure mode issue #34
// tracked: the page loaded fine and every call went nowhere with nothing explaining why.
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const url = `${API_BASE_URL}${path}`
  try {
    return await fetch(url, init)
  } catch {
    throw new Error(
      `Could not reach the backend at ${url}. Check that it is running, and, if this page ` +
        `was opened from a different host or port than usual, that this origin is allowed ` +
        `by the backend's CORS config (sweprep.web.allowed-origins).`,
    )
  }
}
