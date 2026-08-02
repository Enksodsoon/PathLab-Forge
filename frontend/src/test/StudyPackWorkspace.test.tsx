import '@testing-library/jest-dom/vitest'

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { vi } from 'vitest'

import * as api from '../api'
import { StudyPackWorkspace } from '../StudyPackWorkspace'

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return { ...actual, saveStudyPack: vi.fn(), publishStudyPack: vi.fn(), importAnkiPackage: vi.fn() }
})

test('imports a real QTI key with provenance, saves it, then publishes the immutable version', async () => {
  vi.mocked(api.saveStudyPack).mockResolvedValue({
    packKey: 'navigation-101', version: 1, title: 'Navigation', checksum: 'a'.repeat(64), masteryEligible: true,
  })
  vi.mocked(api.publishStudyPack).mockResolvedValue({
    id: 'viewer-pack', packKey: 'navigation-101', version: 1, checksum: 'a'.repeat(64), masteryEligible: true, status: 'private',
  })

  render(<StudyPackWorkspace datasets={[{ id: 'local-one', displayName: 'Teaching slide', viewerSlideId: 'viewer-slide-1' }]} onClose={vi.fn()} />)

  fireEvent.change(screen.getByLabelText('Pack key'), { target: { value: 'navigation-101' } })
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Navigation' } })
  fireEvent.change(screen.getByLabelText('Course ID'), { target: { value: 'path-101' } })
  fireEvent.change(screen.getByLabelText('Source'), { target: { value: 'Faculty QTI bank' } })
  fireEvent.change(screen.getByLabelText('Author'), { target: { value: 'Dr Rivera' } })
  fireEvent.change(screen.getByLabelText('License'), { target: { value: 'CC BY 4.0' } })
  fireEvent.change(screen.getByLabelText('Revision'), { target: { value: '2026-08' } })
  const qti = new File([`<?xml version="1.0"?><assessmentItem title="Portal tract"><responseDeclaration identifier="RESPONSE"><correctResponse><value>Upper left</value></correctResponse></responseDeclaration><itemBody><p>Where is the portal tract?</p></itemBody></assessmentItem>`], 'question.xml', { type: 'application/xml' })
  Object.defineProperty(qti, 'text', { value: async () => `<?xml version="1.0"?><assessmentItem title="Portal tract"><responseDeclaration identifier="RESPONSE"><correctResponse><value>Upper left</value></correctResponse></responseDeclaration><itemBody><p>Where is the portal tract?</p></itemBody></assessmentItem>` })
  fireEvent.change(screen.getByLabelText('Import content'), { target: { files: [qti] } })

  expect(await screen.findByText('Where is the portal tract?')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Save immutable version' }))

  await waitFor(() => expect(api.saveStudyPack).toHaveBeenCalledWith(expect.stringContaining('"answerKey":"Upper left"')))
  expect(await screen.findByRole('button', { name: 'Publish privately' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: 'Publish privately' }))
  await waitFor(() => expect(api.publishStudyPack).toHaveBeenCalledWith('a'.repeat(64)))
})

test('keeps a quoted CSV answer instead of splitting it into an invented key', async () => {
  const csv = new File(['prompt,answer\n"Where is it?","Upper, left"'], 'cards.csv', { type: 'text/csv' })
  Object.defineProperty(csv, 'text', { value: async () => 'prompt,answer\n"Where is it?","Upper, left"' })
  render(<StudyPackWorkspace datasets={[]} onClose={vi.fn()} />)

  fireEvent.change(screen.getByLabelText('Import content'), { target: { files: [csv] } })

  expect(await screen.findByText('Where is it?')).toBeVisible()
  expect(screen.getByText('Imported key: Upper, left')).toBeVisible()
})
