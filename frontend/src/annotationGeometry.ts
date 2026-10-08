export interface AnnotationPoint { x: number; y: number }

export function parseGeometry(geometry: string): AnnotationPoint[] {
  const points = geometry.split(';').map((value) => {
    const [x, y] = value.split(',').map(Number)
    return { x, y }
  })
  return points.every((point) => Number.isFinite(point.x) && Number.isFinite(point.y)) ? points : []
}

export function geometryText(points: AnnotationPoint[]) {
  return points.map(({ x, y }) => `${Math.round(x * 1000) / 1000},${Math.round(y * 1000) / 1000}`).join(';')
}

export function validGeometry(type: string, points: AnnotationPoint[]) {
  if (points.some(({ x, y }) => !Number.isFinite(x) || !Number.isFinite(y))) return false
  if (['point', 'text'].includes(type)) return points.length === 1
  if (['rectangle', 'ellipse'].includes(type)) return points.length === 2
    && points[0].x !== points[1].x && points[0].y !== points[1].y
  if (type === 'angle') return points.length === 3
    && (points[0].x !== points[1].x || points[0].y !== points[1].y)
    && (points[1].x !== points[2].x || points[1].y !== points[2].y)
  if (['polygon', 'freehand', 'brush_add', 'brush_subtract'].includes(type)) return points.length >= 3
  return points.length >= 2
}

export function shapePath(type: string, points: AnnotationPoint[]) {
  if (!points.length) return ''
  if (['rectangle', 'ellipse'].includes(type) && points.length === 2) {
    const x = Math.min(points[0].x, points[1].x)
    const y = Math.min(points[0].y, points[1].y)
    const width = Math.abs(points[1].x - points[0].x)
    const height = Math.abs(points[1].y - points[0].y)
    if (type === 'rectangle') return `M${x},${y}h${width}v${height}h${-width}Z`
    return `M${x},${y + height / 2}a${width / 2},${height / 2} 0 1,0 ${width},0a${width / 2},${height / 2} 0 1,0 ${-width},0Z`
  }
  const closed = ['polygon', 'freehand', 'brush_add', 'brush_subtract'].includes(type)
  return points.map((point, index) => `${index ? 'L' : 'M'}${point.x},${point.y}`).join('') + (closed ? 'Z' : '')
}
