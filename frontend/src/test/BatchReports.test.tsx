import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { BatchReports, type BatchReport, type BatchSummary } from '../BatchReports'
afterEach(cleanup)

it('reports saved identities, scopes a single retry and exports the original batch', async () => {
  const item = { snapshot: { id: 'slide', displayName: 'Fixture', sourcePath: '/saved/source', sourceFingerprint: 'saved-fingerprint', configurationRevision: 'saved-config', selectedSeries: 2, downsample: 2, cropX: 1, cropY: 2, cropWidth: 3, cropHeight: 4 }, artifactRevisionId: 'pinned-artifact', state: 'FAILED', detail: 'Forced fixture failure', attempts: 1 }
  const batch: BatchSummary = { id: 'batch', createdAt: 1, format: 'OME_DYNAMIC_V1', items: [item] }
  const report: BatchReport = { batchId: 'batch', createdAt: 1, format: 'OME_DYNAMIC_V1', queuePaused: true, slides: [{ item, artifact: null, artifactBytes: 0, delivery: { state: 'NOT_REQUESTED', detail: 'No scoped delivery', nextAction: 'Approve artifact' }, nextAction: 'Review failure' }] }
  const onReport = vi.fn().mockResolvedValue(report), onRetry = vi.fn().mockResolvedValue(undefined), onExport = vi.fn()
  render(<BatchReports batches={[batch]} onReport={onReport} onRetry={onRetry} onCancel={vi.fn()} onExport={onExport} />)
  await screen.findByText('Forced fixture failure')
  expect(screen.getByText('Source identity: saved-fingerprint')).toBeInTheDocument()
  expect(screen.getByText('Artifact: pinned-artifact')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Retry Fixture' }))
  await waitFor(() => expect(onRetry).toHaveBeenCalledWith('batch', 'slide'))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Export batch CSV' })).not.toBeDisabled())
  fireEvent.click(screen.getByRole('button', { name: 'Export batch CSV' }))
  expect(onExport).toHaveBeenCalledWith('batch', 'csv')
  expect(screen.getByRole('button', { name: 'Cancel unfinished slides' })).toBeDisabled()
})
