import { act, cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TeachingSlidePreview } from '../TeachingSlidePreview'
import type { TeachingAssociation } from '../teachingAssociations'

const mock = vi.hoisted(() => ({ events: {} as Record<string, (...args: never[]) => void>, fullyLoaded: false,
  item: { addHandler: vi.fn(), getContentSize: () => ({ x: 100, y: 50 }), getFullyLoaded: (): boolean => mock.fullyLoaded }, destroy: vi.fn() }))
vi.mock('openseadragon', () => ({ default: vi.fn(() => ({ world: { getItemCount: () => 1, getItemAt: () => mock.item },
  addHandler: (event: string, callback: (...args: never[]) => void) => { mock.events[event] = callback }, destroy: mock.destroy })) }))
afterEach(cleanup)
it('requires decoded tiles and complete exact viewport; failures revoke loaded-pixel evidence', async () => {
  mock.fullyLoaded = false
  const association = { referenceId: 'local:reference', viewerSlideId: '', datasetId: 'ca38d59a-08ce-44a2-aaf2-cb96bd147bdf',
    artifactRevision: 'fcafc4bf-2350-470c-aa3a-ad5f5a0d9734', packageSha256: 'a'.repeat(64), outputWidth: 100, outputHeight: 50 } as TeachingAssociation
  const loaded = vi.fn()
  render(<TeachingSlidePreview association={association} slideId={association.referenceId} previewChecksum="checksum" onPixelsLoaded={loaded} onLocation={vi.fn()} />)
  act(() => mock.events.open())
  expect(loaded).not.toHaveBeenCalledWith(expect.objectContaining({ packageSha256: association.packageSha256 }))
  mock.fullyLoaded = true
  act(() => mock.events['tile-loaded']())
  await waitFor(() => expect(loaded).toHaveBeenCalledWith(expect.objectContaining({ previewChecksum: 'checksum', artifactRevision: association.artifactRevision })))
  act(() => mock.events['tile-load-failed']())
  expect(loaded).toHaveBeenLastCalledWith(null)
})
