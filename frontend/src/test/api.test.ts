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
