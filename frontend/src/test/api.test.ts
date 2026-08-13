import { afterEach, expect, test, vi } from 'vitest'

import * as api from '../api'

afterEach(() => {
  vi.unstubAllGlobals()
})

test('reuses dataset payload when conditional request returns 304', async () => {
  const payload = { datasets: [{ id: 'slide-1' }] }
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify(payload), {
      status: 200,
      headers: { 'Content-Type': 'application/json', ETag: '"workspace-1"' },
    }))
    .mockResolvedValueOnce(new Response(null, { status: 304 }))
  vi.stubGlobal('fetch', fetch)

  expect(await api.datasets()).toEqual(payload.datasets)
  expect(await api.datasets()).toEqual(payload.datasets)

  const secondInit = fetch.mock.calls[1][1] as RequestInit
  expect(new Headers(secondInit.headers).get('If-None-Match')).toBe('"workspace-1"')
})

test('refreshes the local session and retries one write after Forge restarts', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ error: 'forbidden' }), {
      status: 403, headers: { 'Content-Type': 'application/json' },
    }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ authenticated: true }), {
      status: 200, headers: { 'Content-Type': 'application/json', 'X-Forge-CSRF': 'fresh-token' },
    }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ items: [], folders: [], conflicts: [] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
  vi.stubGlobal('fetch', fetch)

  await expect(api.syncViewerLibrary()).resolves.toEqual({ items: [], folders: [], conflicts: [] })
  expect(fetch).toHaveBeenCalledTimes(3)
  expect(new Headers((fetch.mock.calls[2][1] as RequestInit).headers).get('X-Forge-CSRF'))
    .toBe('fresh-token')
})
