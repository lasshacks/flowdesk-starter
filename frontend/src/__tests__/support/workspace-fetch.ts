import { vi } from 'vitest'

/** Keep existing workspace scenarios focused; parent/project integration is tested separately. */
export function stubWorkspaceFetch(fetch: (input: string, init?: RequestInit) => Promise<Response>) {
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    if (/^\/api\/workspaces\/[0-9]+\/projects\?/.test(String(input))) {
      return Promise.resolve(new Response(JSON.stringify({ items: [], page: 0, size: 20, hasNext: false })))
    }
    return fetch(String(input), init)
  })
}
