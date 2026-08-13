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

test('uses versioned capability, import, image, and view contracts', async () => {
  const fetch = vi.fn()
    .mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ formats: [], datasets: [], diagnostics: [], series: [] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })))
  vi.stubGlobal('fetch', fetch)

  await api.formats()
  await api.importDatasets(['C:\\slides\\case.czi'])
  await api.images('dataset 1')
  await api.updateView('dataset 1', {
    series: 0,
    z: { mode: 'SLICE', start: 0, end: 0 },
    t: { mode: 'SLICE', start: 0, end: 0 },
    channels: [{ channel: 0, enabled: true, color: '#ffffff', minimum: 0, maximum: 255 }],
    profile: 'DISPLAY_COMPOSITE',
  })

  expect(fetch.mock.calls.map(([path]) => path)).toEqual([
    '/api/v2/desktop/formats',
    '/api/v2/desktop/imports',
    '/api/v2/desktop/datasets/dataset%201/images',
    '/api/v2/desktop/datasets/dataset%201/view',
  ])
  expect(JSON.parse((fetch.mock.calls[1][1] as RequestInit).body as string))
    .toEqual({ paths: ['C:\\slides\\case.czi'] })
  expect((fetch.mock.calls[3][1] as RequestInit).method).toBe('PUT')
})
