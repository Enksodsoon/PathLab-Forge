import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { vi } from 'vitest'

import * as api from '../api'
import type { AiResearchResult, Dataset } from '../api'
import { AiResearchWorkspace } from '../AiResearchWorkspace'

vi.mock('../SlideViewer', () => ({
  SlideViewer: ({ selectedEvidenceRegionId }: { selectedEvidenceRegionId?: string }) => (
    <div data-testid="ai-viewer">Focused: {selectedEvidenceRegionId || 'none'}</div>
  ),
}))

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ...actual,
    aiResearchStatus: vi.fn(),
    aiLabAdapters: vi.fn(),
    aiResearchResult: vi.fn(),
    analyzeWithAi: vi.fn(),
  }
})

const dataset: Dataset = {
  id: 'ai-slide', displayName: 'Real WSI.ome.tif', sourceBytes: 1, format: 'OME_TIFF',
  status: 'READY_TO_CONVERT', detail: 'Ready', outputPath: '', sha256: '', selectedSeries: 0,
  width: 10000, height: 8000, downsample: 1, estimatedOutputBytes: 0, projectedFileBytes: 0,
  projectedFileLowerBytes: 0, projectedFileUpperBytes: 0, cropX: 0, cropY: 0,
  cropWidth: 10000, cropHeight: 8000, sourceFingerprint: 'f', configurationRevision: 'r',
  currentArtifactRevision: '', approvedArtifactRevision: '',
}

const result: AiResearchResult = {
  schema_version: 2, label: 'IC', coarse_group: 'Malignant', confidence: .72,
  needs_review: false, review: { required: false, confidence_threshold: .5, reason: 'passed' },
  probabilities: { IC: .72, DCIS: .28 }, tile_count: 128, source_tile_pixels: 444,
  suspected_regions: [
    { id: 'evidence-1', rank: 1, x: 100, y: 200, width: 888, height: 444, tile_count: 2, score: 3, relative_score: 1, maximum_attention: .2, maximum_contribution: 2, auto_selected: true },
    { id: 'evidence-2', rank: 2, x: 4000, y: 5000, width: 444, height: 444, tile_count: 1, score: 1.5, relative_score: .5, maximum_attention: .1, maximum_contribution: 1.5, auto_selected: false },
  ],
  auto_selected_region_id: 'evidence-1', evidence_interpretation: 'Model evidence',
  region_interpretation: 'Research only', model: 'mil-fixture', intended_use: 'research',
  runtime_seconds: 4.2,
  source_coordinate_transform: {
    origin_x: 10, origin_y: 20, scale_x: 1.5, scale_y: 1.5, applied: true,
  },
}

test('automatically focuses the strongest suspected region and permits region review', async () => {
  vi.mocked(api.aiResearchStatus).mockResolvedValue({ available: true, busy: false, active_dataset_id: null, detail: 'Ready' })
  vi.mocked(api.aiLabAdapters).mockResolvedValue({ research_only: true, not_diagnostic: true, one_job_at_a_time: true, items: [] })
  vi.mocked(api.aiResearchResult).mockResolvedValue(result)

  render(<AiResearchWorkspace dataset={dataset} tileSource="/slide.dzi" onClose={vi.fn()} />)

  expect(await screen.findByText('Focused: evidence-1')).toBeVisible()
  expect(screen.getByText(/Evidence Challenger/)).toBeVisible()
  expect(screen.getByText('2 distinct regions')).toBeVisible()
  expect(screen.getByText(/x 160 · y 320/)).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: /Evidence region 2/ }))
  await waitFor(() => expect(screen.getByText('Focused: evidence-2')).toBeVisible())
})

test('runs a new WSI analysis from the research bench', async () => {
  vi.mocked(api.aiResearchStatus).mockResolvedValue({ available: true, busy: false, active_dataset_id: null, detail: 'Ready' })
  vi.mocked(api.aiLabAdapters).mockResolvedValue({ research_only: true, not_diagnostic: true, one_job_at_a_time: true, items: [] })
  vi.mocked(api.aiResearchResult).mockRejectedValue(new Error('No result'))
  vi.mocked(api.analyzeWithAi).mockResolvedValue(result)

  render(<AiResearchWorkspace dataset={dataset} tileSource="/slide.dzi" onClose={vi.fn()} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Analyze WSI' }))
  expect(await screen.findByText('Focused: evidence-1')).toBeVisible()
  expect(api.analyzeWithAi).toHaveBeenCalledWith(dataset.id)
})
