import { describe, expect, it } from 'vitest'

import {
  MAX_ZOOM_PIXEL_RATIO,
  PREVIEW_IMAGE_LOADER_LIMIT,
  PREVIEW_MAX_TILE_CACHE,
} from '../viewerConfig'

describe('SlideViewer', () => {
  it('stops at native 1:1 resolution instead of enlarging source pixels', () => {
    expect(MAX_ZOOM_PIXEL_RATIO).toBe(1)
  })

  it('bounds native preview requests and decoded tile memory', () => {
    expect(PREVIEW_IMAGE_LOADER_LIMIT).toBe(2)
    expect(PREVIEW_MAX_TILE_CACHE).toBeLessThanOrEqual(128)
  })
})
