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

it('requires actual matching loaded pixels and revokes review when the preview fails', () => {
  const callbacks = props(vi.fn())
  const association = { referenceId: 'slide', viewerSlideId: 'slide', datasetId: 'ca38d59a-08ce-44a2-aaf2-cb96bd147bdf',
    artifactRevision: 'fcafc4bf-2350-470c-aa3a-ad5f5a0d9734', packageSha256: 'a'.repeat(64), outputWidth: 100, outputHeight: 100 }
  const bound = { ...draft, associations: { teachingSlides: { slide: association } } }
  const pixels = { previewChecksum: draft.previewChecksum, slideId: 'slide', datasetId: association.datasetId,
    artifactRevision: association.artifactRevision, packageSha256: association.packageSha256 }
  render(<StudyAuthoring {...callbacks} drafts={[bound]} canPreviewSlide={() => true}
    renderSlide={(_slide, _location, loaded) => <><button onClick={() => loaded(pixels)}>Pixels completed</button><button onClick={() => loaded(null)}>Pixels failed</button></>} />)
  expect(screen.getByRole('button', { name: 'I reviewed this task, key, hints and sources' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Pixels completed' }))
  expect(screen.getByRole('button', { name: 'I reviewed this task, key, hints and sources' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: 'Pixels failed' }))
  expect(screen.getByRole('button', { name: 'I reviewed this task, key, hints and sources' })).toBeDisabled()
})

it('moves spatial preview coordinates by hundredths and tenths with keyboard controls', () => {
  const spatialDraft: StudyDraftRecord = { ...draft, definition: { ...draft.definition, tasks: [{
    id: 'spatial', type: 'spatial', slideId: 'slide', prompt: 'Select a landmark', options: [],
    targetX: .2, targetY: .2, targetWidth: .1, targetHeight: .1, tolerance: .05,
    answerKey: '', hints: [], explanation: '', sources: [],
  }] } }
  render(<StudyAuthoring {...props(vi.fn())} drafts={[spatialDraft]} canPreviewSlide={() => true}
    renderSlide={() => <div role="group" aria-label="Rendered teaching fixture" />} />)
  const x = screen.getByRole('spinbutton', { name: 'Preview X coordinate' })
  const y = screen.getByRole('spinbutton', { name: 'Preview Y coordinate' })
  fireEvent.keyDown(x, { key: 'ArrowRight' })
  expect(x).toHaveValue(.51)
  fireEvent.keyDown(x, { key: 'ArrowRight', shiftKey: true })
  expect(x).toHaveValue(.61)
  fireEvent.keyDown(y, { key: 'ArrowUp' })
  expect(y).toHaveValue(.49)
  fireEvent.keyDown(y, { key: 'ArrowDown', shiftKey: true })
  expect(y).toHaveValue(.59)
  fireEvent.change(x, { target: { value: '1' } })
  fireEvent.keyDown(x, { key: 'ArrowRight', shiftKey: true })
  expect(x).toHaveValue(1)
})
