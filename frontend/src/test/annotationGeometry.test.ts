import { describe, expect, it } from 'vitest'
import { geometryText, parseGeometry, shapePath, validGeometry } from '../annotationGeometry'

describe('actual annotation paths', () => {
  it('preserves all captured vertices and rejects incomplete gestures', () => {
    const points = [{ x: 1, y: 2 }, { x: 3, y: 6 }, { x: 9, y: 8 }]
    expect(parseGeometry(geometryText(points))).toEqual(points)
    expect(shapePath('polygon', points)).toBe('M1,2L3,6L9,8Z')
    expect(shapePath('polyline', points)).toBe('M1,2L3,6L9,8')
    expect(validGeometry('angle', points.slice(0, 2))).toBe(false)
    expect(validGeometry('freehand', points.slice(0, 2))).toBe(false)
    expect(validGeometry('rectangle', [{ x: 1, y: 2 }, { x: 1, y: 5 }])).toBe(false)
  })
  it('renders rectangle bounds and an elliptical arc rather than a path bounding box', () => {
    expect(shapePath('rectangle', [{ x: 10, y: 20 }, { x: 4, y: 8 }])).toBe('M4,8h6v12h-6Z')
    expect(shapePath('ellipse', [{ x: 4, y: 8 }, { x: 10, y: 20 }])).toContain('a3,6')
  })
})
