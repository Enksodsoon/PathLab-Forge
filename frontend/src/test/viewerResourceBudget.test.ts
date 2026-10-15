import { describe, expect, it, vi } from 'vitest'

vi.mock('openseadragon', () => ({ default: vi.fn() }))

describe('viewer resource budget setup', () => {
  it('leaves lightweight OpenSeadragon test stubs untouched when ImageLoader is unavailable', async () => {
    vi.resetModules()
    const { createRegisteredOpenSeadragonViewer } = await import('../viewerResourceBudget')
    const viewer = {}
    const registered = createRegisteredOpenSeadragonViewer('teaching-preview', () => viewer as never)

    expect(registered.viewer).toBe(viewer)
    expect(registered.release).toBeTypeOf('function')
    expect(registered.release).not.toThrow()
  })

  it('rejects an over-budget viewer before constructing it', async () => {
    vi.resetModules()
    const OpenSeadragon = (await import('openseadragon')).default as unknown as {
      ImageLoader?: { prototype: { addJob: () => boolean; clear: () => void } }
    }
    OpenSeadragon.ImageLoader = { prototype: { addJob: () => true, clear: () => {} } }
    vi.resetModules()
    const { createRegisteredOpenSeadragonViewer } = await import('../viewerResourceBudget')
    const makeViewer = (withNavigator = false) => {
      const createInstance = () => ({
        imageLoader: { jobsInProgress: 0, jobQueue: [], jobLimit: 1 },
        tileCache: {},
        world: { getItemCount: () => 0 },
        forceRedraw: vi.fn(),
      })
      const viewer: ReturnType<typeof createInstance> & { navigator?: ReturnType<typeof createInstance> } = createInstance()
      if (withNavigator) viewer.navigator = createInstance()
      return viewer as never
    }
    expect(() => createRegisteredOpenSeadragonViewer('teaching-preview', () => { throw new Error('construction failed') })).toThrow('construction failed')
    const primary = createRegisteredOpenSeadragonViewer('workspace-primary', () => makeViewer(true))
    const teaching = createRegisteredOpenSeadragonViewer('teaching-preview', () => makeViewer())
    const createOverflow = vi.fn(() => makeViewer())

    expect(() => createRegisteredOpenSeadragonViewer('teaching-preview', createOverflow)).toThrow(/cache budget/)
    expect(createOverflow).not.toHaveBeenCalled()
    primary.release()
    teaching.release()
  })
})
