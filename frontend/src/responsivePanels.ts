export type ResponsivePanel = 'navigator' | 'inspector'

export interface ResponsivePanelsState {
  width: number
  navigatorDesktopOpen: boolean
  inspectorDesktopOpen: boolean
  navigatorOverlayOpen: boolean
  inspectorOverlayOpen: boolean
}

export type ResponsivePanelsAction =
  | { type: 'resize'; width: number }
  | { type: 'toggle'; panel: ResponsivePanel }
  | { type: 'open'; panel: ResponsivePanel }
  | { type: 'close'; panel: ResponsivePanel }

export function createResponsivePanelsState(width: number): ResponsivePanelsState {
  return {
    width,
    navigatorDesktopOpen: true,
    inspectorDesktopOpen: true,
    navigatorOverlayOpen: false,
    inspectorOverlayOpen: false,
  }
}

export function isPanelDocked(panel: ResponsivePanel, width: number) {
  return panel === 'navigator' ? width > 960 : width > 1200
}

export function isPanelOpen(state: ResponsivePanelsState, panel: ResponsivePanel) {
  if (isPanelDocked(panel, state.width)) {
    return panel === 'navigator' ? state.navigatorDesktopOpen : state.inspectorDesktopOpen
  }
  return panel === 'navigator' ? state.navigatorOverlayOpen : state.inspectorOverlayOpen
}

function withPanelOpen(state: ResponsivePanelsState, panel: ResponsivePanel, open: boolean): ResponsivePanelsState {
  const docked = isPanelDocked(panel, state.width)
  let next = { ...state }
  if (panel === 'navigator') {
    if (docked) next.navigatorDesktopOpen = open
    else next.navigatorOverlayOpen = open
  } else if (docked) next.inspectorDesktopOpen = open
  else next.inspectorOverlayOpen = open

  if (open && state.width <= 960) {
    const other: ResponsivePanel = panel === 'navigator' ? 'inspector' : 'navigator'
    next = withPanelOpen(next, other, false)
  }
  return next
}

export function responsivePanelsReducer(state: ResponsivePanelsState, action: ResponsivePanelsAction): ResponsivePanelsState {
  if (action.type === 'resize') {
    if (!Number.isFinite(action.width) || action.width <= 0 || action.width === state.width) return state
    const next = { ...state, width: action.width }
    if (isPanelDocked('navigator', action.width) !== isPanelDocked('navigator', state.width)) next.navigatorOverlayOpen = false
    if (isPanelDocked('inspector', action.width) !== isPanelDocked('inspector', state.width)) next.inspectorOverlayOpen = false
    return next
  }
  if (action.type === 'toggle') return withPanelOpen(state, action.panel, !isPanelOpen(state, action.panel))
  return withPanelOpen(state, action.panel, action.type === 'open')
}
