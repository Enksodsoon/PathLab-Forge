import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { vi } from 'vitest'

import * as api from '../api'
import type { Dataset, PivotSession } from '../api'
import { PivotWorkspace } from '../PivotWorkspace'

vi.mock('../SlideViewer', () => ({
  SlideViewer: ({
    onSelectLocation,
    onViewportSettled,
  }: {
    onSelectLocation?: (point: { x: number; y: number }) => void
    onViewportSettled?: (snapshot: { x: number; y: number; zoom: number }) => void
  }) => (
    <div data-testid="pivot-viewer">
      {onSelectLocation ? (
        <button
          type="button"
          onClick={() => {
            onViewportSettled?.({ x: 100, y: 100, zoom: 1 })
            onViewportSettled?.({ x: 180, y: 140, zoom: 2 })
            onSelectLocation({ x: 320, y: 240 })
          }}
        >Select training location</button>
      ) : null}
    </div>
  ),
}))

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ...actual,
    pivotStatus: vi.fn(),
    compilePivot: vi.fn(),
    pivotSession: vi.fn(),
    startPivotSession: vi.fn(),
    submitPivot: vi.fn(),
    hintPivot: vi.fn(),
    skipPivot: vi.fn(),
    endPivot: vi.fn(),
  }
})

const dataset: Dataset = {
  id: 'pivot-slide',
  displayName: 'Unannotated teaching slide.ome.tif',
  sourceBytes: 10_000,
  format: 'OME_TIFF',
  status: 'READY_TO_CONVERT',
  detail: 'Ready',
  outputPath: '',
  sha256: '',
  selectedSeries: 0,
  width: 1_000,
  height: 800,
  downsample: 1,
  estimatedOutputBytes: 0,
  projectedFileBytes: 0,
  projectedFileLowerBytes: 0,
  projectedFileUpperBytes: 0,
  cropX: 0,
  cropY: 0,
  cropWidth: 1_000,
  cropHeight: 800,
  sourceFingerprint: 'fingerprint',
  configurationRevision: 'revision',
  currentArtifactRevision: '',
  approvedArtifactRevision: '',
}

const firstTask = {
  id: 'task-one',
  queryUrl: '/pivot/task-one.jpg',
  difficulty: 0.5,
  difficultyLabel: 'Moderate' as const,
  scaleGap: 4,
  index: 1,
  total: 2,
}

const secondTask = { ...firstTask, id: 'task-two', queryUrl: '/pivot/task-two.jpg', index: 2 }

function session(currentTask = firstTask): PivotSession {
  return {
    id: 'session-one',
    state: 'ACTIVE',
    startedAt: 1,
    updatedAt: 1,
    completedTasks: currentTask.index - 1,
    skippedTasks: 0,
    hintsUsed: 0,
    totalTasks: 2,
    currentTask,
    recentAttempts: [],
  }
}

test('starts a coordinate-grounded task and submits the selected slide location', async () => {
  vi.mocked(api.pivotStatus).mockResolvedValue({
    status: 'READY',
    totalTasks: 2,
    inspectedCandidates: 24,
    generationMs: 18,
    nonDiagnostic: true,
  })
  vi.mocked(api.pivotSession).mockRejectedValue(new Error('No active session'))
  vi.mocked(api.startPivotSession).mockResolvedValue(session())
  vi.mocked(api.submitPivot).mockResolvedValue({
    normalizedError: 0.25,
    distancePixels: 32,
    rating: 'MATCH',
    target: { x: 300, y: 220, width: 128, height: 128 },
    session: {
      ...session(secondTask),
      completedTasks: 1,
      recentAttempts: [{
        taskId: 'task-one',
        normalizedError: 0.25,
        rating: 'MATCH',
        elapsedMs: 1_200,
        confidence: 2,
      }],
    },
  })

  render(<PivotWorkspace dataset={dataset} tileSource="/preview/slide.dzi" onClose={vi.fn()} />)

  expect(await screen.findByText('Your annotation-free training set is ready')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Start session' }))
  expect(await screen.findByText('Find this region on the slide')).toBeVisible()
  expect(screen.getByText('No diagnostic labels are used.')).toBeVisible()

  fireEvent.click(screen.getByRole('button', { name: 'Select training location' }))
  fireEvent.click(screen.getByRole('button', { name: 'Submit location' }))

  expect(await screen.findByText('Good match')).toBeVisible()
  expect(screen.getByText('0.25 target widths')).toBeVisible()
  await waitFor(() => expect(api.submitPivot).toHaveBeenCalledWith(
    dataset.id,
    expect.objectContaining({
      x: 320,
      y: 240,
      panDistance: expect.any(Number),
      confidence: 2,
    }),
  ))
})
