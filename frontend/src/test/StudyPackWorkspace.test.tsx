import '@testing-library/jest-dom/vitest'

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, vi } from 'vitest'

import * as api from '../api'
import { StudyPackWorkspace } from '../StudyPackWorkspace'

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return { ...actual, saveStudyPack: vi.fn(), publishStudyPack: vi.fn(), importAnkiPackage: vi.fn(),
    importQtiPackage: vi.fn(), viewerSlideAssociations: vi.fn(), approvePivotManifest: vi.fn() }
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.viewerSlideAssociations).mockResolvedValue([{
    datasetId: 'local-one', viewerSlideId: 'viewer-slide-1', sha256: 'b'.repeat(64),
    displayName: 'Teaching slide', license: 'institution-restricted',
  }])
})

test('imports a real QTI key with provenance, saves it, then publishes the immutable version', async () => {
  vi.mocked(api.saveStudyPack).mockResolvedValue({
    packKey: 'navigation-101', version: 1, title: 'Navigation', checksum: 'a'.repeat(64), masteryEligible: true,
  })
  vi.mocked(api.publishStudyPack).mockResolvedValue({
    id: 'viewer-pack', packKey: 'navigation-101', version: 1, checksum: 'a'.repeat(64), masteryEligible: true, status: 'private',
  })

  render(<StudyPackWorkspace datasets={[{ id: 'local-one', displayName: 'Teaching slide' }]} onClose={vi.fn()} />)

  fireEvent.change(screen.getByLabelText('Pack key'), { target: { value: 'navigation-101' } })
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Navigation' } })
  fireEvent.change(screen.getByLabelText('Course ID'), { target: { value: 'path-101' } })
  await screen.findByRole('option', { name: /Teaching slide/ })
  const xml = `<?xml version="1.0"?><assessmentItem title="Portal tract"><responseDeclaration identifier="RESPONSE"><correctResponse><value>Upper left</value></correctResponse></responseDeclaration><itemBody><p>Where is the portal tract?</p></itemBody><source>Faculty QTI bank</source><author>Dr Rivera</author><license>CC BY 4.0</license><revision>2026-08</revision></assessmentItem>`
  const qti = new File([xml], 'question.xml', { type: 'application/xml' })
  Object.defineProperty(qti, 'text', { value: async () => xml })
  fireEvent.change(screen.getByLabelText('Import content'), { target: { files: [qti] } })

  expect(await screen.findByText('Where is the portal tract?')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Save immutable version' }))

  await waitFor(() => expect(api.saveStudyPack).toHaveBeenCalledWith(expect.stringContaining('"answerKey":"Upper left"')))
  expect(api.saveStudyPack).toHaveBeenCalledWith(expect.stringContaining('"source":"Faculty QTI bank"'))
  expect(await screen.findByRole('button', { name: 'Publish privately' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: 'Publish privately' }))
  await waitFor(() => expect(api.publishStudyPack).toHaveBeenCalledWith('a'.repeat(64)))
})

test('invalidates the saved checksum when private publication fails', async () => {
  vi.mocked(api.saveStudyPack).mockResolvedValue({
    packKey: 'failure', version: 1, title: 'Failure', checksum: 'c'.repeat(64), masteryEligible: true,
  })
  vi.mocked(api.publishStudyPack).mockRejectedValue(new Error('Viewer unavailable'))
  render(<StudyPackWorkspace datasets={[{ id: 'local-one', displayName: 'Teaching slide' }]} onClose={vi.fn()} />)
  fireEvent.change(screen.getByLabelText('Pack key'), { target: { value: 'failure' } })
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Failure' } })
  fireEvent.change(screen.getByLabelText('Course ID'), { target: { value: 'course' } })
  fireEvent.change(screen.getByLabelText('Source'), { target: { value: 'Faculty' } })
  fireEvent.change(screen.getByLabelText('Author'), { target: { value: 'Dr R' } })
  fireEvent.change(screen.getByLabelText('License'), { target: { value: 'L' } })
  fireEvent.change(screen.getByLabelText('Revision'), { target: { value: 'R' } })
  await screen.findByRole('option', { name: /Teaching slide/ })
  const csv = new File(['prompt,answer\nP,A'], 'cards.csv', { type: 'text/csv' })
  Object.defineProperty(csv, 'text', { value: async () => 'prompt,answer\nP,A' })
  fireEvent.change(screen.getByLabelText('Import content'), { target: { files: [csv] } })
  await screen.findByText('P')
  fireEvent.click(screen.getByRole('button', { name: 'Save immutable version' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Publish privately' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Publish privately' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Viewer unavailable')
  expect(screen.getByRole('button', { name: 'Publish privately' })).toBeDisabled()
})

test('keeps a quoted CSV answer instead of splitting it into an invented key', async () => {
  const csv = new File(['prompt,answer\n"Where is it?","Upper, left"'], 'cards.csv', { type: 'text/csv' })
  Object.defineProperty(csv, 'text', { value: async () => 'prompt,answer\n"Where is it?","Upper, left"' })
  render(<StudyPackWorkspace datasets={[]} onClose={vi.fn()} />)

  fireEvent.change(screen.getByLabelText('Import content'), { target: { files: [csv] } })

  expect(await screen.findByText('Where is it?')).toBeVisible()
  expect(screen.getByText('Explicit key: Upper, left')).toBeVisible()
})

test('rejects oversized text before reading it and exposes a keyboard button', async () => {
  render(<StudyPackWorkspace datasets={[]} onClose={vi.fn()} />)
  const picker = screen.getByRole('button', { name: 'Import content' })
  picker.focus()
  expect(picker).toHaveFocus()
  const oversized = new File(['x'], 'large.csv', { type: 'text/csv' })
  Object.defineProperty(oversized, 'size', { value: 2 * 1024 * 1024 + 1 })
  const read = vi.fn()
  Object.defineProperty(oversized, 'text', { value: read })
  fireEvent.change(screen.getByLabelText('Import content'), { target: { files: [oversized] } })
  expect(await screen.findByRole('alert')).toHaveTextContent('exceeds 2 MiB')
  expect(read).not.toHaveBeenCalled()
})

test('paginates a large imported preview instead of rendering every card', async () => {
  vi.mocked(api.importAnkiPackage).mockResolvedValue(Array.from({ length: 120 }, (_, index) => ({
    id: `anki-${index}`, prompt: `Prompt ${index}`, answerKey: `Key ${index}`, keyOrigin: 'imported' as const,
  })))
  render(<StudyPackWorkspace datasets={[]} onClose={vi.fn()} />)
  const file = new File(['zip'], 'cards.apkg')
  fireEvent.change(screen.getByLabelText('Import content'), { target: { files: [file] } })
  expect(await screen.findByText('Page 1 of 3')).toBeVisible()
  expect(screen.getAllByRole('listitem')).toHaveLength(50)
})
