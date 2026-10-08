import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { handlers, viewer } = vi.hoisted(() => {
  const handlers = new Map<string, Set<(event: unknown) => void>>()
  const viewer = {
    addHandler: (name: string, handler: (event: unknown) => void) => {
      if (!handlers.has(name)) handlers.set(name, new Set())
      handlers.get(name)!.add(handler)
    },
    removeHandler: (name: string, handler: (event: unknown) => void) => handlers.get(name)?.delete(handler),
    addOnceHandler: vi.fn(), destroy: vi.fn(),
    gestureSettingsMouse: { dragToPan: true }, gestureSettingsTouch: { dragToPan: true },
    world: { getItemCount: () => 1, getItemAt: () => ({ getContentSize: () => ({ x: 100, y: 100 }) }) },
    viewport: {
      pointFromPixel: (point: unknown) => point,
      viewportToImageCoordinates: (point: unknown) => point,
      imageToViewportCoordinates: (x: number, y: number) => ({ x, y }),
      pixelFromPoint: (point: unknown) => point,
    },
  }
  return { handlers, viewer }
})
vi.mock('openseadragon', () => ({ default: Object.assign(() => viewer, { Point: class {
  constructor(public x: number, public y: number) {}
} }) }))
import { SlideViewer } from '../SlideViewer'

function emit(name: string, x: number, y: number) {
  act(() => { handlers.get(name)?.forEach((handler) => handler({ position: { x, y } })) })
}
function click(x: number, y: number) { emit('canvas-press', x, y); emit('canvas-release', x, y) }
afterEach(() => { cleanup(); handlers.clear() })

describe('annotation gesture capture', () => {
  it('projects reviewed objects through crop and downsample as read-only geometry', () => {
    const onSelect = vi.fn(), onUpdate = vi.fn()
    render(<SlideViewer tileSource="slide.dzi" sourceWidth={1000} sourceHeight={1000}
      cropX={100} cropY={200} downsample={2} activeTool="select" onSelect={onSelect} onUpdate={onUpdate}
      analysisOverlays={[{ id: 'run:core', type: 'rectangle', geometry: '120,220;140,260', label: 'Reviewed core', color: '#39c7a3' }]} />)
    const shape = screen.getByLabelText('Reviewed core')
    expect(shape.querySelector('path')?.getAttribute('d')).toBe('M10,10h10v20h-10Z')
    fireEvent.pointerDown(shape)
    fireEvent.keyDown(shape, { key: 'ArrowRight' })
    expect(onSelect).not.toHaveBeenCalled(); expect(onUpdate).not.toHaveBeenCalled()
  })
  it('captures polygon and angle vertices without fabricated corners, with Escape and Enter', () => {
    const onCreate = vi.fn()
    const { rerender } = render(<SlideViewer tileSource="slide.dzi" sourceWidth={100} sourceHeight={100} activeTool="polygon" onCreate={onCreate} />)
    click(10, 20); click(30, 70); click(90, 40)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onCreate).toHaveBeenLastCalledWith('10,20;30,70;90,40')
    click(1, 2); click(3, 4)
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onCreate).toHaveBeenCalledTimes(1)
    rerender(<SlideViewer tileSource="slide.dzi" sourceWidth={100} sourceHeight={100} activeTool="angle" onCreate={onCreate} />)
    click(7, 3); click(1, 2); click(4, 9)
    expect(onCreate).toHaveBeenLastCalledWith('7,3;1,2;4,9')
  })
  it('keeps real stroke samples in source coordinates and cancels pointer loss', () => {
    const onCreate = vi.fn()
    render(<SlideViewer tileSource="slide.dzi" activeTool="freehand" cropX={100} cropY={200} downsample={2} onCreate={onCreate} />)
    emit('canvas-press', 1, 2); emit('canvas-drag', 3, 7); emit('canvas-drag', 8, 9); emit('canvas-release', 9, 10)
    expect(onCreate).toHaveBeenCalledWith('102,204;106,214;116,218;118,220')
    emit('canvas-press', 1, 2); emit('canvas-drag', 3, 7)
    fireEvent(window, new Event('pointercancel'))
    emit('canvas-release', 9, 10)
    expect(onCreate).toHaveBeenCalledTimes(1)
  })
  it('composes brush strokes only into a selected scoped parent and cancels before committing', () => {
    const onCreate = vi.fn(), onComposeBrush = vi.fn()
    const annotations = [{ id: 'parent', type: 'rectangle', geometry: '0,0;100,100', label: 'ROI', color: '#ffaa22', createdAt: 0, updatedAt: 0, revision: 1, parentId: '', classification: '', series: 0, z: 0, t: 0, viewRevision: 'view' }]
    const { rerender } = render(<SlideViewer tileSource="slide.dzi" sourceWidth={100} sourceHeight={100} activeTool="brush_subtract" onCreate={onCreate} onComposeBrush={onComposeBrush} annotations={annotations} />)
    emit('canvas-press', 2, 2); emit('canvas-drag', 8, 2); emit('canvas-drag', 8, 8); emit('canvas-release', 2, 8)
    expect(onCreate).not.toHaveBeenCalled(); expect(onComposeBrush).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('Select a saved closed ROI')
    rerender(<SlideViewer tileSource="slide.dzi" sourceWidth={100} sourceHeight={100} activeTool="brush_subtract" selectedAnnotationId="parent" onComposeBrush={onComposeBrush} annotations={annotations} />)
    emit('canvas-press', 2, 2); emit('canvas-drag', 8, 2); emit('canvas-drag', 8, 8); fireEvent.keyDown(window, { key: 'Escape' }); emit('canvas-release', 2, 8)
    expect(onComposeBrush).not.toHaveBeenCalled()
    emit('canvas-press', 2, 2); emit('canvas-drag', 8, 2); emit('canvas-drag', 8, 8); emit('canvas-release', 2, 8)
    expect(onComposeBrush).toHaveBeenCalledWith('parent', 'brush_subtract', '2,2;8,2;8,8;2,8', 1)
  })
  it('renders and keyboard edits a compound mask with independent hole contours', () => {
    const onUpdate = vi.fn()
    render(<SlideViewer tileSource="slide.dzi" sourceWidth={100} sourceHeight={100} activeTool="select" onUpdate={onUpdate} annotations={[{ id: 'mask', type: 'roi_mask', geometry: 'mask/1|0,0;10,0;10,10;0,10|2,2;8,2;8,8;2,8', label: 'Hole ROI', color: '#ffaa22', createdAt: 0, updatedAt: 0, revision: 2, parentId: '', classification: '' }]} />)
    const path = screen.getByRole('button', { name: 'Hole ROI' })
    expect(path).toHaveAttribute('fill-rule', 'evenodd')
    expect(path).toHaveAttribute('d', 'M0,0L10,0L10,10L0,10ZM2,2L8,2L8,8L2,8Z')
    fireEvent.keyDown(path, { key: 'ArrowRight' })
    expect(onUpdate).toHaveBeenCalledWith('mask', 'mask/1|1,0;11,0;11,10;1,10|3,2;9,2;9,8;3,8')
  })
  it('renders real paths and supports keyboard moves without altering vertices', () => {
    const onUpdate = vi.fn()
    render(<SlideViewer tileSource="slide.dzi" sourceWidth={100} sourceHeight={100} activeTool="select" onUpdate={onUpdate} annotations={[{
      id: 'p', type: 'polygon', geometry: '1,2;3,7;8,9', label: 'Region', color: '#ffaa22',
      createdAt: 0, updatedAt: 0, revision: 1, parentId: '', classification: '',
    }]} />)
    const path = screen.getByRole('button', { name: 'Region' })
    expect(path).toHaveAttribute('d', 'M1,2L3,7L8,9Z')
    fireEvent.keyDown(path, { key: 'ArrowRight', shiftKey: true })
    expect(onUpdate).toHaveBeenCalledWith('p', '11,2;13,7;18,9')
  })
})
