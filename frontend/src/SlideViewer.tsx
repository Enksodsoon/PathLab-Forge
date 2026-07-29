import OpenSeadragon from 'openseadragon'
import { useEffect, useRef, useState } from 'react'

import type { AnnotationRecord } from './api'
import { MAX_ZOOM_PIXEL_RATIO } from './viewerConfig'

interface ViewerPointerEvent {
  position: OpenSeadragon.Point
  preventDefaultAction?: boolean
}

type GestureViewer = OpenSeadragon.Viewer & {
  gestureSettingsMouse: { dragToPan: boolean }
  gestureSettingsTouch: { dragToPan: boolean }
}

export function SlideViewer({
  tileSource,
  activeTool = 'pan',
  annotations = [],
  sourceWidth = 1,
  sourceHeight = 1,
  cropX = 0,
  cropY = 0,
  downsample = 0,
  onCreate,
  onReady,
}: {
  tileSource: string
  activeTool?: string
  annotations?: AnnotationRecord[]
  sourceWidth?: number
  sourceHeight?: number
  cropX?: number
  cropY?: number
  downsample?: number
  onCreate?: (geometry: string) => void
  onReady?: (viewer: OpenSeadragon.Viewer) => void
}) {
  const elementRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null)
  const dragStartRef = useRef<OpenSeadragon.Point | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  useEffect(() => {
    if (!elementRef.current) return
    setLoading(true)
    setLoadError('')
    const viewer = OpenSeadragon({
      element: elementRef.current,
      tileSources: tileSource,
      showNavigator: true,
      navigatorPosition: 'BOTTOM_RIGHT',
      animationTime: 0.35,
      blendTime: 0.1,
      maxZoomPixelRatio: MAX_ZOOM_PIXEL_RATIO,
      zoomPerClick: 1.8,
      zoomPerScroll: 1.35,
      visibilityRatio: 0.1,
      constrainDuringPan: true,
      prefixUrl: '',
      showNavigationControl: false,
    })
    viewerRef.current = viewer
    viewer.addOnceHandler('open', () => {
      setLoading(false)
      onReady?.(viewer)
    })
    viewer.addOnceHandler('open-failed', () => {
      setLoading(false)
      setLoadError('Native-resolution preview could not be opened')
    })
    return () => {
      viewerRef.current = null
      viewer.destroy()
    }
  }, [onReady, tileSource])

  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer) return
    const drawing = !['pan', 'select', 'marquee'].includes(activeTool)
    const gestureViewer = viewer as GestureViewer
    gestureViewer.gestureSettingsMouse.dragToPan = !drawing
    gestureViewer.gestureSettingsTouch.dragToPan = !drawing

    const sourcePoint = (position: OpenSeadragon.Point) => {
      const viewportPoint = viewer.viewport.pointFromPixel(position)
      const imagePoint = viewer.viewport.viewportToImageCoordinates(viewportPoint)
      if (downsample > 0) {
        return new OpenSeadragon.Point(
          imagePoint.x * downsample + cropX,
          imagePoint.y * downsample + cropY,
        )
      }
      const content = viewer.world.getItemAt(0)?.getContentSize()
      return new OpenSeadragon.Point(
        imagePoint.x * sourceWidth / Math.max(1, content?.x || sourceWidth),
        imagePoint.y * sourceHeight / Math.max(1, content?.y || sourceHeight),
      )
    }
    const press = (event: ViewerPointerEvent) => {
      if (!drawing) return
      event.preventDefaultAction = true
      dragStartRef.current = sourcePoint(event.position)
    }
    const release = (event: ViewerPointerEvent) => {
      if (!drawing || !dragStartRef.current) return
      event.preventDefaultAction = true
      const start = dragStartRef.current
      const end = sourcePoint(event.position)
      dragStartRef.current = null
      if (activeTool === 'point' || activeTool === 'text') {
        onCreate?.(pointText(end))
        return
      }
      if (activeTool === 'angle') {
        onCreate?.(`${pointText(start)};${pointText(new OpenSeadragon.Point(end.x, start.y))};${pointText(end)}`)
        return
      }
      onCreate?.(`${pointText(start)};${pointText(end)}`)
    }
    viewer.addHandler('canvas-press', press)
    viewer.addHandler('canvas-release', release)
    return () => {
      viewer.removeHandler('canvas-press', press)
      viewer.removeHandler('canvas-release', release)
    }
  }, [
    activeTool,
    cropX,
    cropY,
    downsample,
    onCreate,
    sourceHeight,
    sourceWidth,
  ])

  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer || !viewer.world.getItemCount()) return
    viewer.clearOverlays()
    const content = viewer.world.getItemAt(0)?.getContentSize()
    const toImage = (point: OpenSeadragon.Point) => downsample > 0
      ? new OpenSeadragon.Point((point.x - cropX) / downsample, (point.y - cropY) / downsample)
      : new OpenSeadragon.Point(
        point.x * Math.max(1, content?.x || sourceWidth) / sourceWidth,
        point.y * Math.max(1, content?.y || sourceHeight) / sourceHeight,
      )
    for (const annotation of annotations) {
      const points = annotation.geometry.split(';').map((value) => {
        const [x, y] = value.split(',').map(Number)
        return toImage(new OpenSeadragon.Point(x, y))
      })
      if (points.some((point) => !Number.isFinite(point.x) || !Number.isFinite(point.y))) continue
      const element = document.createElement('div')
      element.className = 'forge-annotation-overlay'
      element.style.borderColor = annotation.color
      element.setAttribute('aria-label', annotation.label || `${annotation.type} annotation`)
      if (points.length === 1) {
        element.classList.add('point')
        viewer.addOverlay({
          element,
          location: viewer.viewport.imageToViewportCoordinates(points[0]),
          placement: OpenSeadragon.Placement.CENTER,
          checkResize: false,
        })
        continue
      }
      const minimumX = Math.min(...points.map((point) => point.x))
      const maximumX = Math.max(...points.map((point) => point.x))
      const minimumY = Math.min(...points.map((point) => point.y))
      const maximumY = Math.max(...points.map((point) => point.y))
      viewer.addOverlay({
        element,
        location: viewer.viewport.imageToViewportRectangle(
          minimumX,
          minimumY,
          Math.max(2, maximumX - minimumX),
          Math.max(2, maximumY - minimumY),
        ),
      })
    }
  }, [
    annotations,
    cropX,
    cropY,
    downsample,
    sourceHeight,
    sourceWidth,
    tileSource,
  ])

  return (
    <div className="forge-osd-shell">
      <div className="forge-osd" ref={elementRef} data-testid="forge-osd" />
      {loading ? (
        <div className="forge-preview-loading" role="status">
          <span />
          <strong>Building efficient high-detail preview</strong>
          <small>Using the scanner pyramid reduces disk, memory, and processing time.</small>
        </div>
      ) : null}
      {loadError ? <div className="forge-preview-error" role="alert">{loadError}</div> : null}
    </div>
  )
}

function pointText(point: OpenSeadragon.Point) {
  return `${round(point.x)},${round(point.y)}`
}

function round(value: number) {
  return Math.round(value * 1000) / 1000
}
