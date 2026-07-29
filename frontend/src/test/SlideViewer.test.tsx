import { describe, expect, it } from 'vitest'

import { MAX_ZOOM_PIXEL_RATIO } from '../viewerConfig'

describe('SlideViewer', () => {
  it('stops at native 1:1 resolution instead of enlarging source pixels', () => {
    expect(MAX_ZOOM_PIXEL_RATIO).toBe(1)
  })
})
