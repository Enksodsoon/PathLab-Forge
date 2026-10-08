import OpenSeadragon from 'openseadragon'
import { useEffect, useRef, useState } from 'react'
import { artifactDziUrl } from './api'
import { PREVIEW_IMAGE_LOADER_LIMIT, PREVIEW_MAX_TILE_CACHE } from './viewerConfig'
import type { TeachingAssociation, TeachingPixels } from './teachingAssociations'

export function TeachingSlidePreview({ association, slideId, previewChecksum, onPixelsLoaded, onLocation }: {
  association: TeachingAssociation; slideId: string; previewChecksum: string
  onPixelsLoaded: (pixels: TeachingPixels | null) => void; onLocation: (x: number, y: number) => void
}) {
  const element = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null)
  const callbacks = useRef({ onPixelsLoaded, onLocation })
  callbacks.current = { onPixelsLoaded, onLocation }
  const [error, setError] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    setLoaded(false); setError(''); callbacks.current.onPixelsLoaded(null)
    if (!element.current || !previewChecksum || ![association.referenceId, association.viewerSlideId].includes(slideId)) return
    let disposed = false, failed = false, tileCount = 0, frame = 0
    const viewer = OpenSeadragon({ element: element.current, tileSources: artifactDziUrl(association.datasetId, association.artifactRevision),
      showNavigationControl: false, imageLoaderLimit: PREVIEW_IMAGE_LOADER_LIMIT, maxImageCacheCount: PREVIEW_MAX_TILE_CACHE,
      loadTilesWithAjax: true, gestureSettingsMouse: { clickToZoom: false }, gestureSettingsTouch: { clickToZoom: false } })
    viewerRef.current = viewer
    const invalidate = (detail: string) => {
      if (disposed) return
      failed = true; setLoaded(false); setError(detail); callbacks.current.onPixelsLoaded(null)
    }
    const inspect = () => {
      if (disposed || failed || !tileCount || !viewer.world.getItemCount()) return
      const item = viewer.world.getItemAt(0), dimensions = item.getContentSize()
      if (dimensions.x !== association.outputWidth || dimensions.y !== association.outputHeight) {
        invalidate('Loaded teaching dimensions differ from the exact associated artifact.'); return
      }
      if (!item.getFullyLoaded()) return
      setLoaded(true)
      callbacks.current.onPixelsLoaded({ previewChecksum, slideId, datasetId: association.datasetId,
        artifactRevision: association.artifactRevision, packageSha256: association.packageSha256 })
    }
    viewer.addHandler('open', () => {
      viewer.world.getItemAt(0)?.addHandler('fully-loaded-change', inspect)
      inspect()
    })
    viewer.addHandler('tile-loaded', () => {
      tileCount++
      if (!frame) frame = window.requestAnimationFrame(() => { frame = 0; inspect() })
    })
    viewer.addHandler('tile-load-failed', () => invalidate('An exact teaching tile failed to load. Retry before confirming review.'))
    viewer.addHandler('open-failed', () => invalidate('The exact local teaching artifact could not be opened.'))
    viewer.addHandler('canvas-click', (event: { position: OpenSeadragon.Point; quick?: boolean }) => {
      if (!event.quick || failed || !viewer.world.getItemCount()) return
      const point = viewer.world.getItemAt(0).viewportToImageCoordinates(viewer.viewport.pointFromPixel(event.position))
      const x = point.x / association.outputWidth, y = point.y / association.outputHeight
      if (Number.isFinite(x) && Number.isFinite(y) && x >= 0 && y >= 0 && x <= 1 && y <= 1) callbacks.current.onLocation(x, y)
    })
    return () => {
      disposed = true; if (frame) window.cancelAnimationFrame(frame)
      callbacks.current.onPixelsLoaded(null); viewerRef.current = null; viewer.destroy()
    }
  }, [association.datasetId, association.artifactRevision, association.packageSha256, association.outputWidth,
    association.outputHeight, association.referenceId, association.viewerSlideId, slideId, previewChecksum, retry])
  return <section aria-label="Exact offline teaching pixels">
    <div ref={element} style={{ height: 420, width: '100%', background: '#111' }} aria-label="Teaching slide viewport" />
    <button type="button" onClick={() => viewerRef.current?.viewport.zoomBy(1.5)}>Zoom teaching slide in</button>
    <button type="button" onClick={() => viewerRef.current?.viewport.zoomBy(1 / 1.5)}>Zoom teaching slide out</button>
    <button type="button" onClick={() => viewerRef.current?.viewport.goHome()}>Reset teaching view</button>
    <p role="status">{loaded ? 'Exact associated teaching pixels loaded. Inspect every question location before reviewing.' : 'Loading the exact local teaching artifact…'}</p>
    {error ? <><p role="alert">{error}</p><button type="button" onClick={() => setRetry((value) => value + 1)}>Retry exact teaching pixels</button></> : null}
  </section>
}
