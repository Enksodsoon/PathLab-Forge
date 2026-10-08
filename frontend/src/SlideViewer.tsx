import OpenSeadragon from 'openseadragon'
import { memo, useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'

import type { AnnotationRecord } from './api'
import { geometryText, parseGeometry, shapePath, validGeometry, type AnnotationPoint } from './annotationGeometry'
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
  selectedAnnotationId,
  onSelect,
  onUpdate,
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
  selectedAnnotationId?: string
  onSelect?: (id: string) => void
  onUpdate?: (id: string, geometry: string) => void
  onReady?: (viewer: OpenSeadragon.Viewer | null) => void
}) {
  const elementRef = useRef<HTMLDivElement>(null)
  const cropOverlayRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<OpenSeadragon.Viewer | null>(null)
  const dragStartRef = useRef<OpenSeadragon.Point | null>(null)
  const cropGestureRef = useRef<CropPointerGesture | null>(null)
  const optimizingRef = useRef(false)
  const draftRef = useRef<AnnotationPoint[]>([])
  const [draft, setDraft] = useState<AnnotationPoint[]>([])
  const [projectionEpoch, setProjectionEpoch] = useState(0)
  const editRef = useRef<{ id: string; vertex?: number; start: AnnotationPoint; points: AnnotationPoint[] } | null>(null)
  const [edited, setEdited] = useState<{ id: string; points: AnnotationPoint[] } | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [sourceEpoch, setSourceEpoch] = useState(0)
  const [optimizing, setOptimizing] = useState(false)

  useEffect(() => {
    if (!tileSource.includes('/preview/slide.dzi')) {
      setOptimizing(false)
      return
    }
    let cancelled = false
    let timer = 0
    const inspectMode = async () => {
      try {
        const response = await fetch(tileSource, { cache: 'no-store' })
        if (cancelled) return
        const mode = response.headers.get('x-pathlab-preview-mode')
        if (mode === 'preparing') {
          optimizingRef.current = true
          setOptimizing(true)
          timer = window.setTimeout(inspectMode, 1500)
        } else if (mode === 'failed') {
          optimizingRef.current = false
          setOptimizing(false)
          setLoading(false)
          setLoadError('Forge could not prepare the reusable OME-TIFF viewer')
        } else {
          setOptimizing(false)
          if (mode === 'persistent' && optimizingRef.current) {
            optimizingRef.current = false
            setSourceEpoch((value) => value + 1)
          }
        }
      } catch {
        if (!cancelled) timer = window.setTimeout(inspectMode, 2500)
      }
    }
    void inspectMode()
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [tileSource])

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

  const sourcePointFromPointer = (event: ReactPointerEvent<Element>) => {
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
      tileSources: `${tileSource}${tileSource.includes('?') ? '&' : '?'}viewerCache=${sourceEpoch}`,
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
      if (tileSource.includes('/preview/slide.dzi')) {
        optimizingRef.current = true
        setOptimizing(true)
        setLoading(false)
        setLoadError('')
        return
      }
      setLoading(false)
      setLoadError('Native-resolution preview could not be opened')
    })
    return () => {
      viewerRef.current = null
      viewer.destroy()
      onReady?.(null)
    }
  }, [onReady, sourceEpoch, tileSource])

  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer) return
    const annotationDrawing = !['pan', 'select', 'marquee'].includes(activeTool)
    const drawing = annotationDrawing || cropEditing
    const gestureViewer = viewer as GestureViewer
    gestureViewer.gestureSettingsMouse.dragToPan = !drawing
    gestureViewer.gestureSettingsTouch.dragToPan = !drawing

    draftRef.current = []
    setDraft([])
    dragStartRef.current = null
    const setPoints = (points: AnnotationPoint[]) => {
      draftRef.current = points
      setDraft(points)
    }
    const finish = () => {
      if (validGeometry(activeTool, draftRef.current)) onCreate?.(geometryText(draftRef.current))
      setPoints([])
    }
    const multiClick = ['polygon', 'polyline', 'angle'].includes(activeTool)
    const sampled = ['freehand', 'brush_add', 'brush_subtract'].includes(activeTool)
    const cancel = () => {
      dragStartRef.current = null
      cropGestureRef.current = null
      editRef.current = null
      setEdited(null)
      setPoints([])
    }
    const keydown = (event: KeyboardEvent) => {
      if (event.target instanceof Element && event.target.matches('input,textarea,select,[contenteditable="true"]')) return
      if (event.key === 'Escape') {
        cancel()
      } else if (event.key === 'Enter' && multiClick) {
        event.preventDefault()
        finish()
      } else if (event.key === 'Backspace' && multiClick && draftRef.current.length) {
        event.preventDefault()
        setPoints(draftRef.current.slice(0, -1))
      }
    }

    const press = (event: ViewerPointerEvent) => {
      if (!drawing) return
      event.preventDefaultAction = true
      dragStartRef.current = sourcePointFromPixel(event.position)
      if (!cropEditing && !multiClick) setPoints([dragStartRef.current])
    }
    const drag = (event: ViewerPointerEvent) => {
      if (!drawing || !dragStartRef.current) return
      event.preventDefaultAction = true
      if (!cropEditing) {
        if (multiClick) return
        const point = sourcePointFromPixel(event.position)
        if (sampled) {
          const previous = draftRef.current.at(-1)
          if (!previous || Math.hypot(point.x - previous.x, point.y - previous.y) >= 0.1) {
            // ponytail: 4096 vertices per stroke; simplify paths if longer strokes are needed.
            if (draftRef.current.length < 4095) setPoints([...draftRef.current, point])
          }
        } else setPoints([dragStartRef.current, point])
        return
      }
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
        setPoints([end])
        finish()
        return
      }
      if (multiClick) {
        const previous = draftRef.current.at(-1)
        if (!previous || previous.x !== end.x || previous.y !== end.y) setPoints([...draftRef.current, end])
        if (activeTool === 'angle' && draftRef.current.length === 3) finish()
        return
      }
      setPoints(sampled ? [...draftRef.current, end] : [start, end])
      finish()
    }
    const doubleClick = (event: ViewerPointerEvent) => {
      if (!multiClick) return
      event.preventDefaultAction = true
      finish()
    }
    viewer.addHandler('canvas-press', press)
    viewer.addHandler('canvas-drag', drag)
    viewer.addHandler('canvas-release', release)
    viewer.addHandler('canvas-double-click', doubleClick)
    window.addEventListener('keydown', keydown)
    window.addEventListener('pointercancel', cancel)
    window.addEventListener('blur', cancel)
    return () => {
      viewer.removeHandler('canvas-press', press)
      viewer.removeHandler('canvas-drag', drag)
      viewer.removeHandler('canvas-release', release)
      viewer.removeHandler('canvas-double-click', doubleClick)
      window.removeEventListener('keydown', keydown)
      window.removeEventListener('pointercancel', cancel)
      window.removeEventListener('blur', cancel)
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
    tileSource,
    sourceEpoch,
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
    if (!viewer) return
    let frame = 0
    const redraw = () => {
      if (!frame) frame = window.requestAnimationFrame(() => {
        frame = 0
        setProjectionEpoch((value) => value + 1)
      })
    }
    redraw()
    viewer.addHandler('open', redraw)
    viewer.addHandler('animation', redraw)
    viewer.addHandler('resize', redraw)
    return () => {
      if (frame) window.cancelAnimationFrame(frame)
      viewer.removeHandler('open', redraw)
      viewer.removeHandler('animation', redraw)
      viewer.removeHandler('resize', redraw)
    }
  }, [tileSource, sourceEpoch])

  const project = (point: AnnotationPoint): AnnotationPoint => {
    const viewer = viewerRef.current
    if (!viewer?.world.getItemCount()) return point
    const content = viewer.world.getItemAt(0)?.getContentSize()
    const x = downsample > 0 ? (point.x - cropX) / downsample
      : point.x * Math.max(1, content?.x || sourceWidth) / sourceWidth
    const y = downsample > 0 ? (point.y - cropY) / downsample
      : point.y * Math.max(1, content?.y || sourceHeight) / sourceHeight
    return viewer.viewport.pixelFromPoint(viewer.viewport.imageToViewportCoordinates(x, y), true)
  }

  const beginEdit = (event: ReactPointerEvent<SVGElement>, annotation: AnnotationRecord, vertex?: number) => {
    if (!['select', 'marquee'].includes(activeTool) || !onUpdate) return
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.setPointerCapture(event.pointerId)
    onSelect?.(annotation.id)
    editRef.current = { id: annotation.id, vertex, start: sourcePointFromPointer(event), points: parseGeometry(annotation.geometry) }
  }
  const continueEdit = (event: ReactPointerEvent<SVGElement>) => {
    const edit = editRef.current
    if (!edit) return
    event.preventDefault()
    event.stopPropagation()
    const point = sourcePointFromPointer(event)
    const points = edit.points.map((original, index) => edit.vertex === undefined
      ? { x: original.x + point.x - edit.start.x, y: original.y + point.y - edit.start.y }
      : index === edit.vertex ? point : original)
    setEdited({ id: edit.id, points })
    return points
  }
  const endEdit = (event: ReactPointerEvent<SVGElement>) => {
    const edit = editRef.current
    const points = continueEdit(event)
    if (edit && points) {
      const annotation = annotations.find((item) => item.id === edit.id)
      if (annotation && validGeometry(annotation.type, points)) onUpdate?.(edit.id, geometryText(points))
    }
    editRef.current = null
    setEdited(null)
  }
  const cancelEdit = () => { editRef.current = null; setEdited(null) }

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
      <svg aria-label="Slide annotations" data-projection={projectionEpoch}
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none', overflow: 'hidden' }}>
        {annotations.map((annotation) => {
          const points = (edited?.id === annotation.id ? edited.points : parseGeometry(annotation.geometry)).map(project)
          if (!points.length) return null
          const selectable = ['select', 'marquee'].includes(activeTool)
          const selected = selectedAnnotationId === annotation.id
          const common = {
            tabIndex: selectable ? 0 : undefined,
            role: selectable ? 'button' : undefined,
            'aria-label': annotation.label || `${annotation.type} annotation`,
            onFocus: () => { if (selectable) onSelect?.(annotation.id) },
            onKeyDown: (event: React.KeyboardEvent<SVGElement>) => {
              if (!selectable || !onUpdate) return
              const delta: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }
              if (!delta[event.key]) return
              event.preventDefault()
              event.stopPropagation()
              const [x, y] = delta[event.key]
              const step = event.shiftKey ? 10 : 1
              onUpdate(annotation.id, geometryText(parseGeometry(annotation.geometry).map((point) => ({ x: point.x + x * step, y: point.y + y * step }))))
            },
            onPointerDown: (event: ReactPointerEvent<SVGElement>) => beginEdit(event, annotation),
            onPointerMove: continueEdit,
            onPointerUp: endEdit,
            onPointerCancel: cancelEdit,
            style: { pointerEvents: selectable ? 'auto' as const : 'none' as const, cursor: selectable ? 'move' : undefined },
          }
          return <g key={annotation.id} stroke={annotation.color} strokeWidth={selected ? 3 : 2}
            fill="none" aria-label={annotation.label || `${annotation.type} annotation`}>
            {['point', 'text'].includes(annotation.type)
              ? <g {...common}><circle cx={points[0].x} cy={points[0].y} r="5" />
                  {annotation.type === 'text' ? <text x={points[0].x + 8} y={points[0].y} stroke="none" fill={annotation.color}>{annotation.label}</text> : null}</g>
              : <path {...common} d={shapePath(annotation.type, points)} vectorEffect="non-scaling-stroke"
                  fill={['rectangle', 'ellipse', 'polygon', 'freehand', 'brush_add', 'brush_subtract'].includes(annotation.type) ? annotation.color : 'none'} fillOpacity="0.12" />}
            {selected && selectable ? points.map((point, index) => <circle key={index} cx={point.x} cy={point.y} r="5" fill="white"
              {...common} onPointerDown={(event) => beginEdit(event, annotation, index)} />) : null}
          </g>
        })}
        {draft.length ? <path d={shapePath(activeTool, draft.map(project))} stroke="#f3b33d" strokeWidth="2" fill="none" strokeDasharray="5 3" /> : null}
      </svg>
      {draft.length && ['polygon', 'polyline', 'angle'].includes(activeTool) ? <div role="status" style={{ position: 'absolute', bottom: 12, left: 12 }}>Click vertices. Enter finishes; Backspace removes a vertex; Escape cancels.</div> : null}
      {showCrop && cropBox ? (
        <div
          ref={cropOverlayRef}
          className={`forge-crop-overlay${cropEditing ? ' editing' : ''}`}
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
      {optimizing && !loadError ? (
        <div className="forge-preview-cache-status" role="status" aria-live="polite">
          <span aria-hidden="true" />
          <strong>Optimizing this OME-TIFF for smooth viewing</strong>
          <small>You can keep using Forge. This reusable local tile pyramid is built only once.</small>
        </div>
      ) : null}
      {loadError ? <div className="forge-preview-error" role="alert">{loadError}</div> : null}
    </div>
  )
})
