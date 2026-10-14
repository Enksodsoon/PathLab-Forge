import { describe, expect, it } from 'vitest'
import { createResponsivePanelsState, isPanelOpen, responsivePanelsReducer } from '../responsivePanels'

describe('responsive panel state', () => {
  it('uses separate docked breakpoints and starts transient overlays closed', () => {
    for (const width of [320, 600, 960, 961, 1200, 1201, 1920]) {
      const state = createResponsivePanelsState(width)
      expect(isPanelOpen(state, 'navigator')).toBe(width > 960)
      expect(isPanelOpen(state, 'inspector')).toBe(width > 1200)
    }
  })

  it('keeps desktop preferences across temporary overlay layouts', () => {
    let state = createResponsivePanelsState(1440)
    state = responsivePanelsReducer(state, { type: 'close', panel: 'navigator' })
    state = responsivePanelsReducer(state, { type: 'close', panel: 'inspector' })
    state = responsivePanelsReducer(state, { type: 'resize', width: 800 })
    expect(isPanelOpen(state, 'navigator')).toBe(false)
    expect(isPanelOpen(state, 'inspector')).toBe(false)
    state = responsivePanelsReducer(state, { type: 'open', panel: 'navigator' })
    state = responsivePanelsReducer(state, { type: 'resize', width: 1440 })
    expect(isPanelOpen(state, 'navigator')).toBe(false)
    expect(isPanelOpen(state, 'inspector')).toBe(false)
  })

  it('closes only a panel overlay when that panel changes layout mode', () => {
    let state = responsivePanelsReducer(createResponsivePanelsState(900), { type: 'open', panel: 'inspector' })
    state = responsivePanelsReducer(state, { type: 'resize', width: 1000 })
    expect(isPanelOpen(state, 'inspector')).toBe(true)
    state = responsivePanelsReducer(state, { type: 'resize', width: 1201 })
    expect(isPanelOpen(state, 'inspector')).toBe(true)
    expect(state.inspectorOverlayOpen).toBe(false)
  })

  it('makes the two overlays mutually exclusive only at and below 960px', () => {
    let state = createResponsivePanelsState(900)
    state = responsivePanelsReducer(state, { type: 'open', panel: 'navigator' })
    state = responsivePanelsReducer(state, { type: 'open', panel: 'inspector' })
    expect(isPanelOpen(state, 'navigator')).toBe(false)
    expect(isPanelOpen(state, 'inspector')).toBe(true)

    state = responsivePanelsReducer(state, { type: 'resize', width: 1000 })
    state = responsivePanelsReducer(state, { type: 'open', panel: 'inspector' })
    expect(isPanelOpen(state, 'navigator')).toBe(true)
    expect(isPanelOpen(state, 'inspector')).toBe(true)
  })
})
