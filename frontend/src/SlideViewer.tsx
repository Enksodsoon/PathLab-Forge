import OpenSeadragon from 'openseadragon'
import { memo, useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'

import type { AnnotationRecord } from './api'
import {
  cropFromPoints,
  moveCrop,
  resizeCrop,
  shouldShowCropOverlay,
  type CropBox,
  type CropHandle,
} from './crop'
import {
  MAX_ZOOM_PIXEL_RATIO,
  PREVIEW_IMAGE_LOADER_LIMIT,
  PREVIEW_MAX_TILE_CACHE,
} from './viewerConfig'

interface ViewerPointerEvent {
  position: OpenSeadragon.Point
  preventDefaultAction?: boolean
}

type GestureViewer = OpenSeadragon.Viewer & {
  gestureSettingsMouse: { dragToPan: boolean }
  gestureSettingsTouch: { dragToPan: boolean }
}

interface CropPointerGesture {
  kind: 'move' | 'resize'
  handle?: CropHandle
  start: OpenSeadragon.Point
  initial: CropBox
}

export const SlideViewer = memo(function SlideViewer({
  tileSource,
  activeTool = 'pan',
  cropBox,
  cropEditing = false,
  onCropChange,
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
  cropBox?: CropBox
  cropEditing?: boolean
  onCropChange?: (box: CropBox) => void
  annotations?: AnnotationRecord[]
  sourceWidth?: number
  sourceHeight?: number
  cropX?: number
  cropY?: number
  downsample?: number
  onCreate?: (geometry: string) => void
  onReady?: (viewer: OpenSeadragon.Viewer | null) => void
}) {
  const elementRef = useRef<HTMLDivElement>(null)
  const cropOverlayRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null)
  const dragStartRef = useRef<OpenSeadragon.Point | null>(null)
  const cropGestureRef = useRef<CropPointerGesture | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  const sourcePointFromPixel = (position: OpenSeadragon.Point) => {
    const viewer = viewerRef.current
    if (!viewer) return new OpenSeadragon.Point(0, 0)
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

  const sourcePointFromPointer = (event: ReactPointerEvent<HTMLElement>) => {
    const bounds = elementRef.current?.getBoundingClientRect()
    return sourcePointFromPixel(new OpenSeadragon.Point(
      event.clientX - (bounds?.left || 0),
      event.clientY - (bounds?.top || 0),
    ))
  }

  useEffect(() => {
    if (!elementRef.current) return
    setLoading(true)
    setLoadError('')
    const viewer = OpenSeadragon({
      element: elementRef.current,
      tileSources: tileSource,
      showNavigator: true,
      navigatorPosition: 'BOTTOM_RIGHT',
      animationTime: 0.18,
      blendTime: 0,
      immediateRender: true,
      imageLoaderLimit: PREVIEW_IMAGE_LOADER_LIMIT,
      maxImageCacheCount: PREVIEW_MAX_TILE_CACHE,
      maxTilesPerFrame: 2,
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
      onReady?.(viewer)
    })
    viewer.addOnceHandler('tile-loaded', () => {
      setLoading(false)
    })
    viewer.addOnceHandler('open-failed', () => {
      setLoading(false)
      setLoadError('Native-resolution preview could not be opened')
    })
    return () => {
      viewerRef.current = null
      viewer.destroy()
      onReady?.(null)
    }
  }, [onReady, tileSource])

  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer) return
    const annotationDrawing = !['pan', 'select', 'marquee'].includes(activeTool)
    const drawing = annotationDrawing || cropEditing
    const gestureViewer = viewer as GestureViewer
    gestureViewer.gestureSettingsMouse.dragToPan = !drawing
    gestureViewer.gestureSettingsTouch.dragToPan = !drawing

    const press = (event: ViewerPointerEvent) => {
      if (!drawing) return
      event.preventDefaultAction = true
      dragStartRef.current = sourcePointFromPixel(event.position)
    }
    const drag = (event: ViewerPointerEvent) => {
      if (!cropEditing || !dragStartRef.current) return
      event.preventDefaultAction = true
      onCropChange?.(cropFromPoints(
        dragStartRef.current,
        sourcePointFromPixel(event.position),
        sourceWidth,
        sourceHeight,
      ))
    }
    const release = (event: ViewerPointerEvent) => {
      if (!drawing || !dragStartRef.current) return
      event.preventDefaultAction = true
      const start = dragStartRef.current
      const end = sourcePointFromPixel(event.position)
      dragStartRef.current = null
      if (cropEditing) {
        onCropChange?.(cropFromPoints(start, end, sourceWidth, sourceHeight))
        return
      }
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
    viewer.addHandler('canvas-drag', drag)
    viewer.addHandler('canvas-release', release)
    return () => {
      viewer.removeHandler('canvas-press', press)
      viewer.removeHandler('canvas-drag', drag)
      viewer.removeHandler('canvas-release', release)
    }
  }, [
    activeTool,
    cropEditing,
    cropX,
    cropY,
    downsample,
    onCropChange,
    onCreate,
    sourceHeight,
    sourceWidth,
  ])

  useEffect(() => {
    const viewer = viewerRef.current
    const overlay = cropOverlayRef.current
    if (!viewer || !cropBox || !overlay) return
    let frame = 0
    const projectCrop = () => {
      frame = 0
      if (!viewer.world.getItemCount()) {
        overlay.style.visibility = 'hidden'
        return
      }
      const content = viewer.world.getItemAt(0)?.getContentSize()
      const contentWidth = Math.max(1, content?.x || sourceWidth)
      const contentHeight = Math.max(1, content?.y || sourceHeight)
      const imageLeft = downsample > 0
        ? (cropBox.x - cropX) / downsample
        : cropBox.x * contentWidth / sourceWidth
      const imageTop = downsample > 0
        ? (cropBox.y - cropY) / downsample
        : cropBox.y * contentHeight / sourceHeight
      const imageRight = downsample > 0
        ? (cropBox.x + cropBox.width - cropX) / downsample
        : (cropBox.x + cropBox.width) * contentWidth / sourceWidth
      const imageBottom = downsample > 0
        ? (cropBox.y + cropBox.height - cropY) / downsample
        : (cropBox.y + cropBox.height) * contentHeight / sourceHeight
      const topLeft = viewer.viewport.pixelFromPoint(
        viewer.viewport.imageToViewportCoordinates(imageLeft, imageTop),
        true,
      )
      const bottomRight = viewer.viewport.pixelFromPoint(
        viewer.viewport.imageToViewportCoordinates(imageRight, imageBottom),
        true,
      )
      overlay.style.transform = `translate3d(${topLeft.x}px, ${topLeft.y}px, 0)`
      overlay.style.width = `${Math.max(1, bottomRight.x - topLeft.x)}px`
      overlay.style.height = `${Math.max(1, bottomRight.y - topLeft.y)}px`
      overlay.style.visibility = 'visible'
    }
    const scheduleProjection = () => {
      if (!frame) frame = window.requestAnimationFrame(projectCrop)
    }
    scheduleProjection()
    viewer.addHandler('open', scheduleProjection)
    viewer.addHandler('animation', scheduleProjection)
    viewer.addHandler('resize', scheduleProjection)
    return () => {
      if (frame) window.cancelAnimationFrame(frame)
      viewer.removeHandler('open', scheduleProjection)
      viewer.removeHandler('animation', scheduleProjection)
      viewer.removeHandler('resize', scheduleProjection)
    }
  }, [
    cropBox,
    cropX,
    cropY,
    downsample,
    sourceHeight,
    sourceWidth,
    tileSource,
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

  const startCropGesture = (
    event: ReactPointerEvent<HTMLButtonElement>,
    kind: CropPointerGesture['kind'],
    handle?: CropHandle,
  ) => {
    if (!cropEditing || !cropBox) return
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.setPointerCapture(event.pointerId)
    cropGestureRef.current = {
      kind,
      handle,
      start: sourcePointFromPointer(event),
      initial: cropBox,
    }
  }

  const continueCropGesture = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const gesture = cropGestureRef.current
    if (!gesture) return
    event.preventDefault()
    event.stopPropagation()
    const current = sourcePointFromPointer(event)
    const deltaX = current.x - gesture.start.x
    const deltaY = current.y - gesture.start.y
    onCropChange?.(gesture.kind === 'move'
      ? moveCrop(gesture.initial, deltaX, deltaY, sourceWidth, sourceHeight)
      : resizeCrop(
          gesture.initial,
          gesture.handle || 'se',
          deltaX,
          deltaY,
          sourceWidth,
          sourceHeight,
        ))
  }

  const endCropGesture = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!cropGestureRef.current) return
    continueCropGesture(event)
    cropGestureRef.current = null
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  const showCrop = Boolean(
    cropBox
    && shouldShowCropOverlay(cropEditing, cropBox, sourceWidth, sourceHeight),
  )
  const cropHandleLabels: Record<CropHandle, string> = {
    nw: 'top left',
    n: 'top',
    ne: 'top right',
    e: 'right',
    se: 'bottom right',
    s: 'bottom',
    sw: 'bottom left',
    w: 'left',
  }

  return (
    <div className="forge-osd-shell">
      <div className="forge-osd" ref={elementRef} data-testid="forge-osd" />
      {showCrop && cropBox ? (
        <div
          ref={cropOverlayRef}
          className={`forge-crop-overlay${cropEditing ? ' editing' : ''}`}
          style={{
            left: 0,
            top: 0,
            visibility: 'hidden',
          }}
          data-testid="forge-crop-overlay"
        >
          <span className="forge-crop-label">
            Export {cropBox.width.toLocaleString()} × {cropBox.height.toLocaleString()}
          </span>
          {cropEditing ? (
            <>
              <button
                type="button"
                className="forge-crop-move"
                aria-label="Move crop area"
                onPointerDown={(event) => startCropGesture(event, 'move')}
                onPointerMove={continueCropGesture}
                onPointerUp={endCropGesture}
                onPointerCancel={endCropGesture}
              />
              {(Object.keys(cropHandleLabels) as CropHandle[]).map((handle) => (
                <button
                  type="button"
                  key={handle}
                  className={`forge-crop-handle ${handle}`}
                  aria-label={`Resize crop from ${cropHandleLabels[handle]}`}
                  onPointerDown={(event) => startCropGesture(event, 'resize', handle)}
                  onPointerMove={continueCropGesture}
                  onPointerUp={endCropGesture}
                  onPointerCancel={endCropGesture}
                />
              ))}
            </>
          ) : null}
        </div>
      ) : null}
      {loading && !loadError ? (
        <div className="forge-preview-loading" role="status" aria-live="polite">
          <span aria-hidden="true" />
          <strong>Loading first image</strong>
          <small>Opening a quick overview; full-resolution tiles follow as you zoom.</small>
        </div>
      ) : null}
      {loadError ? <div className="forge-preview-error" role="alert">{loadError}</div> : null}
    </div>
  )
})

function pointText(point: OpenSeadragon.Point) {
  return `${round(point.x)},${round(point.y)}`
}

function round(value: number) {
  return Math.round(value * 1000) / 1000
}
