import OpenSeadragon from 'openseadragon'
import { useEffect, useRef, useState } from 'react'
import { teachingPreviewUrl } from './api'
import { PREVIEW_IMAGE_LOADER_PER_VIEWER_LIMIT, PREVIEW_TILE_CACHE_PER_VIEWER } from './viewerConfig'
import { createRegisteredOpenSeadragonViewer } from './viewerResourceBudget'
import type { TeachingAssociation, TeachingPixels } from './teachingAssociations'

export function teachingCoordinateFromPixel(viewer: OpenSeadragon.Viewer, position: OpenSeadragon.Point, width: number, height: number) {
  if (!viewer.world.getItemCount()) return null
  const item = viewer.world.getItemAt(0)
  const imagePoint = item.viewportToImageCoordinates(viewer.viewport.pointFromPixel(position))
  if (!Number.isFinite(imagePoint.x) || !Number.isFinite(imagePoint.y)
    || imagePoint.x < 0 || imagePoint.y < 0 || imagePoint.x > width || imagePoint.y > height) return null
  return { x: imagePoint.x / width, y: imagePoint.y / height }
}

export function TeachingSlidePreview({ association, slideId, previewChecksum, onPixelsLoaded, onLocation, selectedLocation, onViewerReady }: {
  association: TeachingAssociation; slideId: string; previewChecksum: string
  onPixelsLoaded: (pixels: TeachingPixels | null) => void; onLocation: (x: number, y: number) => void
  selectedLocation?: { x: number; y: number }
  onViewerReady?: (viewer: OpenSeadragon.Viewer | null) => void
}) {
  const element = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null)
  const callbacks = useRef({ onPixelsLoaded, onLocation, onViewerReady })
  callbacks.current = { onPixelsLoaded, onLocation, onViewerReady }
  const [error, setError] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    setLoaded(false); setError(''); callbacks.current.onPixelsLoaded(null)
    const container = element.current
    if (!container || !previewChecksum || ![association.referenceId, association.viewerSlideId].includes(slideId)) return
    let disposed = false, failed = false, pixelsReady = false, tileCount = 0, frame = 0
    const { viewer, release: releaseResourceBudget } = createRegisteredOpenSeadragonViewer('teaching-preview', () => OpenSeadragon({ element: container, tileSources: teachingPreviewUrl(association.datasetId, association.artifactRevision),
      showNavigationControl: false, imageLoaderLimit: PREVIEW_IMAGE_LOADER_PER_VIEWER_LIMIT, maxImageCacheCount: PREVIEW_TILE_CACHE_PER_VIEWER,
      loadTilesWithAjax: true, gestureSettingsMouse: { clickToZoom: false }, gestureSettingsTouch: { clickToZoom: false } }))
    viewerRef.current = viewer
    callbacks.current.onViewerReady?.(viewer)
    const invalidate = (detail: string) => {
      if (disposed) return
      failed = true; pixelsReady = false; setLoaded(false); setError(detail); callbacks.current.onPixelsLoaded(null)
    }
    const inspect = () => {
      if (disposed || failed || !tileCount || !viewer.world.getItemCount()) return
      const item = viewer.world.getItemAt(0), dimensions = item.getContentSize()
      if (dimensions.x !== association.outputWidth || dimensions.y !== association.outputHeight) {
        invalidate('Loaded teaching dimensions differ from the exact associated artifact.'); return
      }
      if (!item.getFullyLoaded()) return
      pixelsReady = true
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
      if (!event.quick || failed || !pixelsReady) return
      const point = teachingCoordinateFromPixel(viewer, event.position, association.outputWidth, association.outputHeight)
      if (point) callbacks.current.onLocation(point.x, point.y)
    })
    return () => {
      disposed = true; if (frame) window.cancelAnimationFrame(frame)
      callbacks.current.onPixelsLoaded(null); callbacks.current.onViewerReady?.(null); viewerRef.current = null; viewer.destroy(); releaseResourceBudget()
    }
  }, [association.datasetId, association.artifactRevision, association.packageSha256, association.outputWidth,
    association.outputHeight, association.referenceId, association.viewerSlideId, slideId, previewChecksum, retry])
  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer || !loaded || !selectedLocation || !viewer.world.getItemCount()) return
    const item = viewer.world.getItemAt(0)
    const marker = document.createElement('span')
    marker.className = 'teaching-location-marker'
    marker.dataset.testid = 'teaching-location-marker'
    marker.setAttribute('aria-hidden', 'true')
    viewer.addOverlay({
      element: marker,
      location: item.imageToViewportCoordinates(new OpenSeadragon.Point(
        selectedLocation.x * association.outputWidth,
        selectedLocation.y * association.outputHeight,
      )),
      placement: OpenSeadragon.Placement.CENTER,
    })
    return () => { viewer.removeOverlay(marker) }
  }, [association.outputHeight, association.outputWidth, loaded, selectedLocation?.x, selectedLocation?.y])

  return <section aria-label="Exact offline teaching pixels">
    <div ref={element} className="teaching-slide-viewport" role="group" aria-label="Teaching slide viewport" data-testid="teaching-slide-viewport" />
    <button type="button" onClick={() => viewerRef.current?.viewport.zoomBy(1.5)}>Zoom teaching slide in</button>
    <button type="button" onClick={() => viewerRef.current?.viewport.zoomBy(1 / 1.5)}>Zoom teaching slide out</button>
    <button type="button" onClick={() => viewerRef.current?.viewport.goHome()}>Reset teaching view</button>
    <p role="status">{loaded ? 'Exact associated teaching pixels loaded. Inspect every question location before reviewing.' : 'Loading the exact local teaching artifact…'}</p>
    {error ? <><p role="alert">{error}</p><button type="button" onClick={() => setRetry((value) => value + 1)}>Retry exact teaching pixels</button></> : null}
  </section>
}
