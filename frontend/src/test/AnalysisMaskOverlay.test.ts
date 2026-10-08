import { describe, expect, it } from 'vitest'
import { maskRaster } from '../AnalysisMaskOverlay'

describe('actual analysis mask raster', () => {
  it('decodes LSB-first threshold bits, keeps excluded pixels transparent and preserves source extent', () => {
    const raster = maskRaster({ maskWidth: 3, maskHeight: 2, maskX: 10, maskY: 20,
      maskSourceWidth: 6, maskSourceHeight: 4, maskSampleStride: 2,
      hematoxylinMaskBitsetBase64: btoa(String.fromCharCode(0b100001)) }, 'hematoxylin')!
    expect(raster.sourceWidth).toBe(6)
    expect(raster.sourceHeight).toBe(4)
    expect([raster.x, raster.y]).toEqual([10, 20])
    expect(Array.from(raster.pixels.filter((_, index) => index % 4 === 3))).toEqual([180, 0, 0, 0, 0, 180])
  })
  it('bounds raster work and rejects malformed masks', () => {
    expect(maskRaster({ maskWidth: 10_000, maskHeight: 10_000, maskX: 0, maskY: 0, maskBitsetBase64: '' }, 'tissue')).toBeNull()
    expect(maskRaster({ maskWidth: 1, maskHeight: 1, maskX: 0, maskY: 0, maskBitsetBase64: '***' }, 'tissue')).toBeNull()
    expect(maskRaster({ maskWidth: 1, maskHeight: 1, maskX: 0, maskY: 0, maskBitsetBase64: btoa('xx') }, 'tissue')).toBeNull()
    const raster = maskRaster({ maskWidth: 2048, maskHeight: 2048, maskX: 0, maskY: 0, maskBitsetBase64: '' }, 'tissue')!
    expect([raster.width, raster.height]).toEqual([512, 512])
  })
})
