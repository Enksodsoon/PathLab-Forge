import { fireEvent, render, screen } from '@testing-library/react'
import { vi } from 'vitest'

import { App } from '../App'

vi.mock('../SlideViewer', () => ({
  SlideViewer: () => <div data-testid="forge-osd" />,
}))

vi.mock('../api', () => ({
  bootstrap: vi.fn(async () => [[], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }]),
  datasets: vi.fn(async () => []),
  capabilities: vi.fn(),
  chooseDatasets: vi.fn(),
  inspectDataset: vi.fn(),
  series: vi.fn(async () => []),
  configure: vi.fn(),
  convert: vi.fn(),
  cancel: vi.fn(),
  artifacts: vi.fn(),
  approve: vi.fn(),
  annotations: vi.fn(async () => []),
  createAnnotation: vi.fn(),
  deleteAnnotation: vi.fn(),
  startViewerPairing: vi.fn(async () => ({
    userCode: 'ABCD-EFGH',
    verificationUrl: 'http://127.0.0.1:8010/admin/connect?code=ABCD-EFGH',
    expiresAt: '2026-07-29T08:30:00Z',
  })),
  getViewerConnection: vi.fn(async () => ({
    connected: false,
    viewerUrl: '',
    deviceName: '',
    scopes: [],
  })),
}))

test('launches directly into the Viewer Canvas Focus shell', async () => {
  render(<App />)

  expect(await screen.findByRole('navigation', { name: 'Library destinations' })).toBeVisible()
  expect(screen.getByRole('main')).toBeVisible()
  expect(screen.getByRole('region', { name: 'Whole-slide viewer' })).toBeVisible()
  expect(screen.getByText('Your slides, ready at launch')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Connect' })).toBeVisible()
})

test('shows the short-lived Viewer verification code', async () => {
  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Viewer account' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Viewer address' }), {
    target: { value: 'http://127.0.0.1:8010' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Request pairing code' }))

  expect(await screen.findByText('ABCD-EFGH')).toBeVisible()
  expect(screen.getByRole('link', { name: 'Open Viewer approval' })).toHaveAttribute(
    'href',
    'http://127.0.0.1:8010/admin/connect?code=ABCD-EFGH',
  )
})
