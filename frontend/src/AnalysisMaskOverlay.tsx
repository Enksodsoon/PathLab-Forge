import { useId, useMemo } from 'react'
import { parseGeometry, shapePath, validGeometry } from './annotationGeometry'

export type MaskChannel = 'tissue' | 'hematoxylin' | 'eosin'
type Point = { x: number; y: number }
const channels: Record<MaskChannel, [string, number, number, number]> = {
  tissue: ['maskBitsetBase64', 40, 170, 100],
  hematoxylin: ['hematoxylinMaskBitsetBase64', 90, 60, 210],
  eosin: ['eosinMaskBitsetBase64', 235, 70, 135],
}

export function maskRaster(outputs: Record<string, unknown>, channel: MaskChannel) {
  const [key, red, green, blue] = channels[channel]
  const width = Number(outputs.maskWidth), height = Number(outputs.maskHeight)
  const x = Number(outputs.maskX), y = Number(outputs.maskY)
  const sourceWidth = Number(outputs.maskSourceWidth ?? width), sourceHeight = Number(outputs.maskSourceHeight ?? height)
  if (![width, height].every((value) => Number.isInteger(value) && value > 0)
    || width * height > 4_194_304 || ![x, y, sourceWidth, sourceHeight].every(Number.isFinite)
    || sourceWidth <= 0 || sourceHeight <= 0 || typeof outputs[key] !== 'string'
    || String(outputs[key]).length > 699_052) return null
  let bits: string
  try { bits = atob(String(outputs[key])) } catch { return null }
  if (bits.length > Math.ceil(width * height / 8)) return null
  const scale = Math.max(1, width / 512, height / 512)
  const rasterWidth = Math.max(1, Math.floor(width / scale)), rasterHeight = Math.max(1, Math.floor(height / scale))
  const pixels = new Uint8ClampedArray(rasterWidth * rasterHeight * 4)
  for (let row = 0; row < rasterHeight; row++) for (let column = 0; column < rasterWidth; column++) {
    const bit = Math.floor(row * scale) * width + Math.floor(column * scale)
    if (!(bits.charCodeAt(Math.floor(bit / 8)) & (1 << (bit % 8)))) continue
    pixels.set([red, green, blue, 180], (row * rasterWidth + column) * 4)
  }
  return { width: rasterWidth, height: rasterHeight, pixels, x, y, sourceWidth, sourceHeight }
}

/** Render inside the viewer annotation SVG using its exact source-coordinate projection. */
export function AnalysisMaskOverlay({ outputs, channel, project, provenance }: {
  outputs: Record<string, unknown>; channel: MaskChannel; project: (point: Point) => Point
  provenance: { annotationType: string; annotationGeometry: string }
}) {
  const clipId = useId()
  const image = useMemo(() => {
    const raster = maskRaster(outputs, channel)
    if (!raster) return null
    const canvas = document.createElement('canvas')
    canvas.width = raster.width; canvas.height = raster.height
    const context = canvas.getContext('2d')
    if (!context) return null
    const data = context.createImageData(raster.width, raster.height)
    data.data.set(raster.pixels); context.putImageData(data, 0, 0)
    return { ...raster, url: canvas.toDataURL('image/png') }
  }, [outputs, channel])
  if (!image) return null
  const roi = parseGeometry(provenance.annotationGeometry)
  if (!['rectangle', 'ellipse', 'polygon', 'freehand', 'brush_add', 'brush_subtract'].includes(provenance.annotationType)
    || !validGeometry(provenance.annotationType, roi)) return null
  const origin = project({ x: image.x, y: image.y })
  const right = project({ x: image.x + image.sourceWidth, y: image.y })
  const bottom = project({ x: image.x, y: image.y + image.sourceHeight })
  const matrix = [(right.x - origin.x) / image.sourceWidth, (right.y - origin.y) / image.sourceWidth,
    (bottom.x - origin.x) / image.sourceHeight, (bottom.y - origin.y) / image.sourceHeight, origin.x, origin.y]
  if (!matrix.every(Number.isFinite)) return null
  return <g transform={`matrix(${matrix.join(' ')})`} pointerEvents="none">
    <defs><clipPath id={clipId}><path d={shapePath(provenance.annotationType, roi.map((point) => ({ x: point.x - image.x, y: point.y - image.y })))} /></clipPath></defs>
    <image aria-label={`${channel} threshold mask${Number(outputs.maskSampleStride || 1) > 1 ? ' sampled lattice' : ''}`}
      href={image.url} x={0} y={0} width={image.sourceWidth} height={image.sourceHeight} preserveAspectRatio="none"
      clipPath={`url(#${clipId})`} style={{ imageRendering: 'pixelated' }} />
  </g>
}
