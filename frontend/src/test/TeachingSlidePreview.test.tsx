import { act, cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { TeachingSlidePreview } from '../TeachingSlidePreview'
import type { TeachingAssociation } from '../teachingAssociations'

const mock = vi.hoisted(() => ({ events: {} as Record<string, (...args: never[]) => void>, fullyLoaded: false, imagePoint: { x: 50, y: 25 },
  item: { addHandler: vi.fn(), getContentSize: () => ({ x: 100, y: 50 }), getFullyLoaded: (): boolean => mock.fullyLoaded,
    viewportToImageCoordinates: vi.fn(() => mock.imagePoint) }, destroy: vi.fn() }))
vi.mock('openseadragon', () => ({ default: vi.fn(() => ({ world: { getItemCount: () => 1, getItemAt: () => mock.item },
  viewport: { pointFromPixel: (point: unknown) => point },
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

it('maps clicks through the active image and ignores letterbox gutters or clicks before readiness', async () => {
  mock.fullyLoaded = false
  mock.imagePoint = { x: 25, y: 10 }
  const association = { referenceId: 'local:reference', viewerSlideId: '', datasetId: 'ca38d59a-08ce-44a2-aaf2-cb96bd147bdf',
    artifactRevision: 'fcafc4bf-2350-470c-aa3a-ad5f5a0d9734', packageSha256: 'a'.repeat(64), outputWidth: 100, outputHeight: 50 } as TeachingAssociation
  const onLocation = vi.fn()
  const loaded = vi.fn()
  render(<TeachingSlidePreview association={association} slideId={association.referenceId} previewChecksum="checksum" onPixelsLoaded={loaded} onLocation={onLocation} />)
  act(() => mock.events.open())
  act(() => mock.events['canvas-click']({ quick: true, position: { x: 5, y: 5 } } as never))
  expect(onLocation).not.toHaveBeenCalled()
  mock.fullyLoaded = true
  act(() => mock.events['tile-loaded']())
  await waitFor(() => expect(loaded).toHaveBeenCalledWith(expect.objectContaining({ previewChecksum: 'checksum' })))
  act(() => mock.events['canvas-click']({ quick: true, position: { x: 5, y: 5 } } as never))
  expect(onLocation).toHaveBeenCalledWith(.25, .2)
  mock.imagePoint = { x: -0.1, y: 25 }
  act(() => mock.events['canvas-click']({ quick: true, position: { x: 0, y: 0 } } as never))
  expect(onLocation).toHaveBeenCalledTimes(1)
})
