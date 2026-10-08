import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { StudyAuthoring, type StudyAuthoringProps, type StudyDraftRecord } from '../StudyAuthoring'

afterEach(cleanup)
const draft: StudyDraftRecord = { id: 'draft', name: 'Faculty draft', revision: 4, associations: {}, issues: [], previewChecksum: 'exact', reviewedTaskIds: [], approvedChecksum: '', updatedAt: 1,
  definition: { schema: 'pathlab.study-pack/1', packKey: 'pack', version: 1, title: 'Teaching', author: 'Faculty', license: 'CC-BY-4.0', provenance: 'Manual', revision: 'r1', languages: ['en'], slides: [{ viewerSlideId: 'slide', sha256: 'a'.repeat(64), displayName: 'Teaching slide' }],
    tasks: [{ id: 'q1', type: 'multiple-choice', slideId: 'slide', prompt: 'Faculty supplied question', options: ['A', 'B'], answerKey: 'B', hints: ['Hint'], explanation: 'Faculty supplied explanation', sources: [{ title: 'Source', url: 'https://example.org' }] }] } }
function props(onSave: StudyAuthoringProps['onSave']): StudyAuthoringProps {
  return { drafts: [draft], onSave, onCreate: vi.fn(), onDuplicate: vi.fn(), onHistory: vi.fn(), onRecover: vi.fn(), onPreview: vi.fn(), onReviewTask: vi.fn(), onApprove: vi.fn(), onImport: vi.fn(), onImportQuestions: vi.fn(), onExport: vi.fn() }
}
it('invalidates exact preview immediately on edit and autosaves locally with CAS', async () => {
  const save = vi.fn().mockImplementation(async (value: StudyDraftRecord, revision: number) => ({ ...value, revision: revision + 1 }))
  render(<StudyAuthoring {...props(save)} />)
  expect(screen.getByRole('article', { name: 'Exact faculty preview' })).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Edited offline' } })
  expect(screen.queryByRole('article', { name: 'Exact faculty preview' })).not.toBeInTheDocument()
  await waitFor(() => expect(save).toHaveBeenCalledWith(expect.objectContaining({ definition: expect.objectContaining({ title: 'Edited offline' }), previewChecksum: '' }), 4))
})
it('serializes edits arriving during a pending autosave without losing the latest content', async () => {
  let finish: ((value: StudyDraftRecord) => void) | undefined
  const save = vi.fn().mockImplementationOnce(() => new Promise<StudyDraftRecord>((resolve) => { finish = resolve }))
    .mockImplementation(async (value: StudyDraftRecord, revision: number) => ({ ...value, revision: revision + 1 }))
  render(<StudyAuthoring {...props(save)} />)
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'First' } })
  await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Latest' } })
  finish!({ ...save.mock.calls[0][0], revision: 5 })
  await waitFor(() => expect(save).toHaveBeenCalledWith(expect.objectContaining({ definition: expect.objectContaining({ title: 'Latest' }) }), 5))
  expect(screen.getByLabelText('Title')).toHaveValue('Latest')
})

it('requires successful slide readiness in addition to a render callback for faculty review', () => {
  const callbacks = props(vi.fn())
  const { rerender } = render(<StudyAuthoring {...callbacks} renderSlide={() => <div>Loading pixels</div>} />)
  expect(screen.getByRole('button', { name: 'I reviewed this task, key, hints and sources' })).toBeDisabled()
  rerender(<StudyAuthoring {...callbacks} renderSlide={() => <div>Exact pixels ready</div>} canPreviewSlide={() => true} />)
  expect(screen.getByRole('button', { name: 'I reviewed this task, key, hints and sources' })).toBeEnabled()
})
