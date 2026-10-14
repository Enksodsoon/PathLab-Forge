import OpenSeadragon from 'openseadragon'

import {
  PREVIEW_IMAGE_LOADER_LIMIT,
  PREVIEW_IMAGE_LOADER_PER_VIEWER_LIMIT,
  PREVIEW_MAX_OSD_VIEWERS,
  PREVIEW_TILE_CACHE_PER_VIEWER,
} from './viewerConfig'

type Loader = OpenSeadragon.ImageLoader
type Viewer = OpenSeadragon.Viewer
type ViewerRole = 'workspace-primary' | 'workspace-overview' | 'teaching-preview'

type LoaderRegistration = {
  viewer: Viewer
  role: ViewerRole
  retired: boolean
}

type CacheMetrics = {
  _maxCacheItemCount?: number
  numCachesLoaded?: () => number
}

type Snapshot = {
  viewerCount: number
  trackedViewerCount: number
  activeLoaderJobs: number
  queuedLoaderJobs: number
  activeLoaders: number
  scheduledLoaders: number
  waitingLoaders: number
  maxLoaderJobs: number
  maxCacheTiles: number
  cachedTiles: number
  peaks: {
    activeLoaderJobs: number
    queuedLoaderJobs: number
    cachedTiles: number
    viewers: number
  }
  viewers: Array<{
    id: string
    role: ViewerRole
    retired: boolean
    jobsInProgress: number
    queuedJobs: number
    runtimeJobLimit: number
    cacheLimit: number
    cachedTiles: number
    container: { width: number; height: number }
    bounds: { x: number; y: number; width: number; height: number } | null
    center: { x: number; y: number } | null
    zoom: number | null
  }>
}

type InstrumentedWindow = Window & {
  __forgeViewerResourceSnapshot?: () => Snapshot
}

type MutableLoaderPrototype = {
  addJob: (this: Loader, options: Record<string, unknown>) => boolean
  clear: (this: Loader) => void
}

type QueueJob = { start: () => void }

const registrations = new Map<Loader, LoaderRegistration>()
const scheduled = new Set<Loader>()
const waiting: Loader[] = []
const viewerIds = new Map<Viewer, string>()
const trackedViewers = new Map<Viewer, { role: ViewerRole; retired: boolean }>()
let nextViewerId = 0
let reservedCacheOwners = 0
let installed = false
const observedPeaks = { activeLoaderJobs: 0, queuedLoaderJobs: 0, cachedTiles: 0, viewers: 0 }

function loaderPrototype(): MutableLoaderPrototype | null {
  const imageLoader = (OpenSeadragon as unknown as {
    ImageLoader?: { prototype?: MutableLoaderPrototype }
  }).ImageLoader
  return imageLoader?.prototype ?? null
}

function cacheMetrics(viewer: Viewer): CacheMetrics {
  return viewer.tileCache as unknown as CacheMetrics
}

function getViewerResourceSnapshot(): Snapshot {
  const viewers = [...trackedViewers.entries()].map(([viewer, state]) => {
    const loader = viewer.imageLoader
    const cache = cacheMetrics(viewer)
    const containerSize = viewer.viewport?.getContainerSize()
    let center: { x: number; y: number } | null = null
    let zoom: number | null = null
    let bounds: { x: number; y: number; width: number; height: number } | null = null
    try {
      if (viewer.viewport && viewer.world.getItemCount()) {
        const current = viewer.viewport.getCenter(true)
        center = { x: current.x, y: current.y }
        zoom = viewer.viewport.getZoom(true)
        const viewBounds = viewer.viewport.getBounds(true)
        bounds = { x: viewBounds.x, y: viewBounds.y, width: viewBounds.width, height: viewBounds.height }
      }
    } catch {
      // A viewer can be between construction and open while a snapshot is taken.
    }
    return {
      id: viewerIds.get(viewer) || 'retired',
      role: state.role,
      retired: state.retired,
      jobsInProgress: loader.jobsInProgress,
      queuedJobs: loader.jobQueue.length,
      runtimeJobLimit: loader.jobLimit,
      cacheLimit: cache._maxCacheItemCount ?? 0,
      cachedTiles: cache.numCachesLoaded?.() ?? 0,
      container: { width: containerSize?.x ?? 0, height: containerSize?.y ?? 0 },
      bounds,
      center,
      zoom,
    }
  })
  const loaders = [...registrations.keys()]
  const snapshot: Snapshot = {
    viewerCount: viewers.filter((viewer) => !viewer.retired).length,
    trackedViewerCount: viewers.length,
    activeLoaderJobs: loaders.reduce((total, loader) => total + loader.jobsInProgress, 0),
    queuedLoaderJobs: loaders.reduce((total, loader) => total + loader.jobQueue.length, 0),
    activeLoaders: loaders.filter((loader) => loader.jobsInProgress > 0).length,
    scheduledLoaders: scheduled.size,
    waitingLoaders: waiting.length,
    maxLoaderJobs: PREVIEW_IMAGE_LOADER_LIMIT,
    maxCacheTiles: PREVIEW_MAX_OSD_VIEWERS * PREVIEW_TILE_CACHE_PER_VIEWER,
    cachedTiles: viewers.reduce((total, viewer) => total + viewer.cachedTiles, 0),
    peaks: observedPeaks,
    viewers,
  }
  observedPeaks.activeLoaderJobs = Math.max(observedPeaks.activeLoaderJobs, snapshot.activeLoaderJobs)
  observedPeaks.queuedLoaderJobs = Math.max(observedPeaks.queuedLoaderJobs, snapshot.queuedLoaderJobs)
  observedPeaks.cachedTiles = Math.max(observedPeaks.cachedTiles, snapshot.cachedTiles)
  observedPeaks.viewers = Math.max(observedPeaks.viewers, snapshot.viewerCount)
  return snapshot
}

function publishSnapshot() {
  if (typeof window === 'undefined' || !import.meta.env.DEV) return
  ;(window as InstrumentedWindow).__forgeViewerResourceSnapshot = getViewerResourceSnapshot
  getViewerResourceSnapshot()
}

function removeWaiting(loader: Loader) {
  const index = waiting.indexOf(loader)
  if (index >= 0) waiting.splice(index, 1)
}

function retireLoader(loader: Loader) {
  const registration = registrations.get(loader)
  if (!registration) return
  registration.retired = true
  removeWaiting(loader)
  if (loader.jobsInProgress === 0 && loader.jobQueue.length === 0) {
    scheduled.delete(loader)
    loader.jobLimit = -1
    registrations.delete(loader)
    forgetRetiredViewer(registration.viewer)
  }
}

function forgetRetiredViewer(viewer: Viewer) {
  const state = trackedViewers.get(viewer)
  if (!state?.retired) return
  trackedViewers.delete(viewer)
  viewerIds.delete(viewer)
}

function grant(loader: Loader) {
  if (!registrations.has(loader) || scheduled.has(loader)) return
  scheduled.add(loader)
  loader.jobLimit = PREVIEW_IMAGE_LOADER_PER_VIEWER_LIMIT
  if (loader.jobsInProgress < loader.jobLimit && loader.jobQueue.length > 0) {
    const job = loader.jobQueue.shift() as QueueJob | undefined
    if (job) {
      // Match ImageLoader.addJob's start/count sequence for the already queued job.
      job.start()
      loader.jobsInProgress++
    }
  } else if (loader.jobsInProgress === 0) {
    // OSD clears queued jobs before each world draw. A waiting loader first
    // receives a slot, then its redraw resubmits whichever tile is still visible.
    registrations.get(loader)?.viewer.forceRedraw()
  }
}

function pump() {
  while (scheduled.size < PREVIEW_IMAGE_LOADER_LIMIT && waiting.length > 0) {
    const loader = waiting.shift()!
    const registration = registrations.get(loader)
    if (!registration || registration.retired) continue
    grant(loader)
  }
  publishSnapshot()
}

function settle(loader: Loader) {
  const registration = registrations.get(loader)
  if (!registration) return
  if (loader.jobsInProgress === 0 && loader.jobQueue.length === 0) {
    scheduled.delete(loader)
    loader.jobLimit = -1
    if (registration.retired) {
      registrations.delete(loader)
      forgetRetiredViewer(registration.viewer)
    }
    pump()
    return
  }
  publishSnapshot()
}

function requestSlot(loader: Loader) {
  if (!registrations.has(loader) || scheduled.has(loader) || waiting.includes(loader)) return
  if (scheduled.size < PREVIEW_IMAGE_LOADER_LIMIT) grant(loader)
  else {
    loader.jobLimit = -1
    waiting.push(loader)
  }
  publishSnapshot()
}

export function installViewerResourceBudget() {
  if (installed) return
  const prototype = loaderPrototype()
  if (!prototype) return
  installed = true

  const originalAddJob = prototype.addJob
  const originalClear = prototype.clear

  prototype.addJob = function (this: Loader, options: Record<string, unknown>) {
    if (!registrations.has(this)) return originalAddJob.call(this, options)
    requestSlot(this)
    const callback = options.callback
    const wrappedOptions = {
      ...options,
      callback: (...args: unknown[]) => {
        try {
          if (typeof callback === 'function') callback(...args)
        } finally {
          // ImageLoader.completeJob may start the next local job before this callback.
          // Inspect after the current call stack so synchronous fixture sources behave too.
          queueMicrotask(() => settle(this))
        }
      },
    }
    const result = originalAddJob.call(this, wrappedOptions)
    publishSnapshot()
    return result
  }

  prototype.clear = function (this: Loader) {
    originalClear.call(this)
    if (!this.jobsInProgress) {
      const registration = registrations.get(this)
      if (registration && waiting.includes(this) && !registration.retired) {
        // OSD calls clear before each draw. Preserve only the scheduler intent;
        // OSD aborts the stale queued ImageJobs and submits current tiles again.
        publishSnapshot()
      } else if (scheduled.has(this) && !registration?.retired) {
        // Let the current world draw submit a tile after a grant before deciding
        // that the reserved slot is idle.
        queueMicrotask(() => {
          if (this.jobsInProgress === 0 && this.jobQueue.length === 0) settle(this)
        })
      } else {
        removeWaiting(this)
        settle(this)
      }
    } else {
      publishSnapshot()
    }
  }

  publishSnapshot()
}

function registerOne(viewer: Viewer, role: ViewerRole) {
  const id = `${role}-${++nextViewerId}`
  viewerIds.set(viewer, id)
  trackedViewers.set(viewer, { role, retired: false })
  const loader = viewer.imageLoader
  registrations.set(loader, { viewer, role, retired: false })
  loader.jobLimit = -1
  const cache = cacheMetrics(viewer)
  cache._maxCacheItemCount = PREVIEW_TILE_CACHE_PER_VIEWER
  return { viewer, loader }
}

export function createRegisteredOpenSeadragonViewer(
  role: 'workspace-primary' | 'teaching-preview',
  createViewer: () => Viewer,
) {
  if (!loaderPrototype()) return { viewer: createViewer(), release: () => {} }
  installViewerResourceBudget()
  const reservedOwners = role === 'workspace-primary' ? 2 : 1
  const activeCacheOwners = [...trackedViewers.values()].filter((state) => !state.retired).length
  if (activeCacheOwners + reservedCacheOwners + reservedOwners > PREVIEW_MAX_OSD_VIEWERS) {
    throw new Error(`Forge OpenSeadragon viewer count exceeded its ${PREVIEW_MAX_OSD_VIEWERS}-viewer cache budget.`)
  }
  reservedCacheOwners += reservedOwners
  let viewer: Viewer
  let overview: Viewer | null
  let owned: ReturnType<typeof registerOne>[]
  try {
    viewer = createViewer()
    overview = role === 'workspace-primary' ? viewer.navigator : null
    owned = [registerOne(viewer, role)]
    if (overview) owned.push(registerOne(overview, 'workspace-overview'))
  } catch (error) {
    reservedCacheOwners -= reservedOwners
    throw error
  }
  reservedCacheOwners -= reservedOwners
  publishSnapshot()

  let released = false
  return {
    viewer,
    release: () => {
      if (released) return
      released = true
      for (const item of owned) {
        const state = trackedViewers.get(item.viewer)
        if (state) state.retired = true
        retireLoader(item.loader)
        if (!registrations.has(item.loader)) {
          trackedViewers.delete(item.viewer)
          viewerIds.delete(item.viewer)
        }
      }
      pump()
      publishSnapshot()
    },
  }
}

installViewerResourceBudget()
