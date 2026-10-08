import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { TmaCoreActions } from '../TmaCoreActions'
import type { AnnotationRecord } from '../api'
import type { DeterministicReview, DeterministicRun } from '../DeterministicTools'

it('persists the explicit reviewed snapshot and submits only a present saved core with exact revision/configuration', async () => {
  const run = { id: 'grid-run', annotationId: 'roi', tool: 'tma', status: 'SUCCEEDED', stale: false,
    provenance: { series: 2, z: 3, t: 4, viewRevision: 'view' } } as DeterministicRun
  const present = { id: 'present', parentId: 'roi', geometry: '1,2;3,4', label: 'A1', revision: 1,
    series: 2, z: 3, t: 4, viewRevision: 'view' } as AnnotationRecord
  const review: DeterministicReview = { runId: run.id, revision: 7, stainVector: [], objects:
    ['present', 'missing'].map((id) => ({ id, datasetId: 'dataset', parentId: 'roi', kind: 'TMA_CORE',
      geometry: present.geometry, classification: id, sourceRunId: run.id, revision: 2,
      properties: { missing: String(id === 'missing') } })) }
  const onPersist = vi.fn(async () => [present, { ...present, id: 'missing' }, { ...present, id: 'other-plane', z: 99 }])
  const onSubmit = vi.fn(async () => ({}))
  render(<TmaCoreActions run={run} enabledTools={['he']} toolLabels={{ he: 'H&E' }}
    parameters={{ he: [['hematoxylinThreshold', 'H threshold', .15, 0, 3, .01]] }}
    onLoadReview={async () => review} onPersist={onPersist} onSubmit={onSubmit} />)
  expect(screen.getByRole('button', { name: 'Analyze selected saved core' })).toBeDisabled()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Persist exact reviewed grid' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Persist exact reviewed grid' }))
  await waitFor(() => expect(onPersist).toHaveBeenCalledWith('grid-run', 7))
  await waitFor(() => expect(screen.getByRole('option', { name: 'A1 · ROI revision 1' })).toBeInTheDocument())
  expect(screen.getByLabelText('Saved present core').querySelectorAll('option')).toHaveLength(1)
  fireEvent.change(screen.getByLabelText('H threshold'), { target: { value: '.3' } })
  fireEvent.click(screen.getByRole('button', { name: 'Analyze selected saved core' }))
  await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('grid-run', 7, 'present', 'he', { hematoxylinThreshold: .3 }))
})
