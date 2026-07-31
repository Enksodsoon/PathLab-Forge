import { describe, expect, it } from 'vitest'

import {
  cropFromPoints,
  estimateCropOutput,
  isFullSlideCrop,
  moveCrop,
  resizeCrop,
  shouldShowCropOverlay,
} from '../crop'

describe('crop geometry', () => {
  it('normalizes reverse drawing and clamps it to the source slide', () => {
    expect(cropFromPoints({ x: 900, y: 800 }, { x: -20, y: 50 }, 1000, 900)).toEqual({
      x: 0,
      y: 50,
      width: 900,
      height: 750,
    })
  })

  it('moves without allowing the crop to leave the source slide', () => {
    expect(moveCrop({ x: 100, y: 100, width: 400, height: 300 }, 800, -200, 1000, 900))
      .toEqual({ x: 600, y: 0, width: 400, height: 300 })
  })

  it('reshapes from corners and edges while retaining a valid rectangle', () => {
    const box = { x: 100, y: 100, width: 400, height: 300 }
    expect(resizeCrop(box, 'nw', 50, 25, 1000, 900))
      .toEqual({ x: 150, y: 125, width: 350, height: 275 })
    expect(resizeCrop(box, 'e', 1000, 0, 1000, 900))
      .toEqual({ x: 100, y: 100, width: 900, height: 300 })
    expect(resizeCrop(box, 's', 0, -1000, 1000, 900))
      .toEqual({ x: 100, y: 100, width: 400, height: 1 })
  })

  it('recognizes a full-slide crop', () => {
    expect(isFullSlideCrop({ x: 0, y: 0, width: 1000, height: 900 }, 1000, 900)).toBe(true)
    expect(isFullSlideCrop({ x: 1, y: 0, width: 999, height: 900 }, 1000, 900)).toBe(false)
  })

  it('keeps the crop overlay off until crop editing is explicitly selected', () => {
    const full = { x: 0, y: 0, width: 1000, height: 900 }
    const partial = { x: 100, y: 100, width: 400, height: 300 }

    expect(shouldShowCropOverlay(false, full, 1000, 900)).toBe(false)
    expect(shouldShowCropOverlay(false, partial, 1000, 900)).toBe(true)
    expect(shouldShowCropOverlay(true, full, 1000, 900)).toBe(false)
    expect(shouldShowCropOverlay(true, partial, 1000, 900)).toBe(true)
  })
})

describe('live crop estimate', () => {
  it('changes immediately with crop area and downsample', () => {
    const full = estimateCropOutput(
      { x: 0, y: 0, width: 165845, height: 90735 },
      1.5,
      2_000_000_000,
      false,
    )
    const cropped = estimateCropOutput(
      { x: 69790, y: 23372, width: 11336, height: 11040 },
      1.5,
      2_000_000_000,
      false,
    )

    expect(cropped.outputWidth).toBe(7557)
    expect(cropped.outputHeight).toBe(7360)
    expect(cropped.fileBytes).toBeLessThan(full.fileBytes)
    expect(cropped.workspaceBytes).toBeLessThan(full.workspaceBytes)
  })

  it('keeps a valid compression range when source size is unavailable', () => {
    const estimate = estimateCropOutput(
      { x: 0, y: 0, width: 1, height: 1 },
      1,
      0,
      true,
    )

    expect(estimate.fileLowerBytes).toBeGreaterThan(0)
    expect(estimate.fileBytes).toBeGreaterThanOrEqual(estimate.fileLowerBytes)
    expect(estimate.fileUpperBytes).toBeGreaterThanOrEqual(estimate.fileBytes)
  })
})
