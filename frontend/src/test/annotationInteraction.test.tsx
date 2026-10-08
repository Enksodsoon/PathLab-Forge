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
