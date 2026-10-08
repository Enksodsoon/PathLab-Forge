import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { DeterministicTools, type DeterministicRun } from '../DeterministicTools'

afterEach(cleanup)
const annotations = [{ id: 'roi', type: 'rectangle', geometry: '0,0;10,10', label: 'Saved region', color: '#ffaa22',
  parentId: '', classification: '', createdAt: 1, updatedAt: 1, revision: 1, series: 2, z: 3, t: 4, viewRevision: 'view' }]
const run: DeterministicRun = { id: 'run', datasetId: 'dataset', annotationId: 'roi', tool: 'tma', status: 'SUCCEEDED',
  createdAt: 1, startedAt: 1, finishedAt: 2, detail: 'Saved research result', stale: false,
  configuration: { rows: 1, columns: 1 }, outputs: { cores: [] },
  provenance: { sourceFingerprint: 'source', annotationGeometry: '0,0;10,10', annotationType: 'rectangle', annotationRevision: 1,
    series: 2, z: 3, t: 4, viewRevision: 'view', algorithm: 'tma-v1', units: 'source pixels', configurationSha256: 'config', secondaryInputs: {} } }

it('configures exact saved ROI without running disabled packs', async () => {
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  const props = { datasetId: 'dataset', annotations, runs: [], enabledTools: [], onSubmit, onCancel: vi.fn(), onRefresh: vi.fn().mockResolvedValue(undefined), onExport: vi.fn() }
  const { rerender } = render(<DeterministicTools {...props} />)
  expect(screen.getByRole('button', { name: 'Run locally' })).toBeDisabled()
  rerender(<DeterministicTools {...props} enabledTools={['he']} />)
  fireEvent.change(screen.getByLabelText('Hematoxylin threshold (OD)'), { target: { value: '0.4' } })
  fireEvent.click(screen.getByRole('button', { name: 'Run locally' }))
  await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ datasetId: 'dataset', annotationId: 'roi', tool: 'he', configuration: { hematoxylinThreshold: .4, eosinThreshold: .15 } })))
})

it('persists a corrected TMA label/missing flag with the review revision and exports provenance', async () => {
  const review = { runId: 'run', revision: 3, stainVector: [], objects: [{ id: 'core', datasetId: 'dataset', parentId: 'roi', kind: 'TMA_CORE',
    geometry: '0,0;10,10', classification: 'Core 1', sourceRunId: 'run', properties: { missing: 'false' }, revision: 2 }] }
  const onSave = vi.fn().mockImplementation(async (_id, value) => ({ ...value, revision: 4 }))
  const onExport = vi.fn()
  render(<DeterministicTools datasetId="dataset" annotations={annotations} runs={[run]} enabledTools={[]}
    onSubmit={vi.fn()} onCancel={vi.fn()} onRefresh={vi.fn().mockResolvedValue(undefined)} onExport={onExport}
    onLoadReview={vi.fn().mockResolvedValue(review)} onSaveReview={onSave} />)
  const label = await screen.findByLabelText('Label')
  fireEvent.change(label, { target: { value: 'Corrected core' } })
  fireEvent.click(screen.getByLabelText('Core missing'))
  fireEvent.click(screen.getByRole('button', { name: 'Save reviewed changes' }))
  await waitFor(() => expect(onSave).toHaveBeenCalledWith('run', expect.objectContaining({ revision: 3, objects: [expect.objectContaining({ classification: 'Corrected core', properties: { missing: 'true' }, sourceRunId: 'run' })] })))
  fireEvent.click(screen.getByRole('button', { name: 'Export result and provenance' }))
  expect(onExport).toHaveBeenCalledWith('run')
})
