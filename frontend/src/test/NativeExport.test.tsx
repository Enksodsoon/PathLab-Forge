import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { NativeExport } from '../App'
import * as api from '../api'

vi.mock('../SlideViewer', () => ({ SlideViewer: () => null }))
vi.mock('../api', () => ({ exportResult: vi.fn(), exportState: vi.fn(), cancelExport: vi.fn() }))
afterEach(() => { cleanup(); window.forgeDesktop = undefined })
it('does not display another job and cancels only with the captured expected job ID', async () => {
  window.forgeDesktop = { selectSources: vi.fn(), selectDirectory: vi.fn(), selectExportDestination: vi.fn(async () => 'C:\\export.json'), revealPath: vi.fn(), openExternal: vi.fn(), onCommand: vi.fn(() => () => {}) }
  vi.mocked(api.exportResult).mockResolvedValue({ id: 'mine', status: 'COPYING', destination: 'C:\\export.json', completedBytes: 0, totalBytes: 10, detail: 'Selected export copying' })
  vi.mocked(api.exportState).mockResolvedValue({ id: 'other', status: 'COMPLETE', destination: 'C:\\other.json', completedBytes: 10, totalBytes: 10, detail: 'Unrelated completed result' })
  vi.mocked(api.cancelExport).mockRejectedValue(new Error('The selected export is no longer the active export.'))
  render(<NativeExport datasetId="dataset" runId="run" />)
  fireEvent.click(screen.getByRole('button', { name: 'Save selected result as…' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('another job')
  expect(screen.queryByText('Unrelated completed result')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Reveal exported file' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel export' }))
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('no longer the active export'))
  expect(api.cancelExport).toHaveBeenCalledWith('mine')
})
