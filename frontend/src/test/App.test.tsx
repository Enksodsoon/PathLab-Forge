import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { vi } from 'vitest'

import * as api from '../api'
import { App } from '../App'

vi.mock('../SlideViewer', () => ({
  SlideViewer: ({ tileSource }: { tileSource: string }) => (
    <div data-testid="forge-osd" data-tile-source={tileSource} />
  ),
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
  importDataset: vi.fn(),
  deleteDataset: vi.fn(),
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

test('keeps crop edits local until the user applies a valid configuration', async () => {
  const dataset: api.Dataset = {
    id: 'dataset-1',
    displayName: 'Main slide.vsi',
    sourceBytes: 2_000_000_000,
    format: 'VSI',
    status: 'READY_TO_CONVERT',
    detail: 'Ready',
    outputPath: '',
    sha256: '',
    selectedSeries: 2,
    width: 165845,
    height: 90735,
    downsample: 2,
    estimatedOutputBytes: 15_000_000_000,
    cropX: 0,
    cropY: 0,
    cropWidth: 165845,
    cropHeight: 90735,
    sourceFingerprint: 'abc123',
    configurationRevision: 'configuration-1',
    currentArtifactRevision: '',
    approvedArtifactRevision: '',
  }
  const mainSeries: api.SeriesInfo = {
    index: 2,
    name: 'Main series',
    width: 165845,
    height: 90735,
    channels: 3,
    sizeZ: 1,
    sizeT: 1,
    pixelType: 'uint8',
    physicalSizeX: 0.27,
    physicalSizeY: 0.27,
    physicalUnit: 'µm',
    resolutionCount: 8,
    rgbPlane: true,
  }
  const capabilities = {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[dataset], capabilities])
  vi.mocked(api.datasets).mockResolvedValue([dataset])
  vi.mocked(api.inspectDataset).mockResolvedValue([mainSeries])
  vi.mocked(api.configure).mockResolvedValue({
    ...dataset,
    cropX: 69790,
    cropY: 23372,
    cropWidth: 11336,
    cropHeight: 11040,
    downsample: 1.5,
  })

  render(<App />)

  expect(await screen.findByTestId('forge-osd')).toBeVisible()
  expect(api.series).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Inspect image series' }))
  const x = await screen.findByRole('spinbutton', { name: 'X' })
  await screen.findByText('82,922 × 45,367')
  fireEvent.change(x, { target: { value: '69790' } })
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Y' }), { target: { value: '23372' } })
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Width' }), { target: { value: '11336' } })
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Height' }), { target: { value: '11040' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Downsample' }), { target: { value: '1.5' } })

  expect(api.configure).not.toHaveBeenCalled()
  expect(screen.getByText('7,557 × 7,360')).toBeVisible()

  fireEvent.click(screen.getByRole('button', { name: 'Apply settings' }))

  await waitFor(() => expect(api.configure).toHaveBeenCalledWith('dataset-1', {
    series: 2,
    downsample: 1.5,
    x: 69790,
    y: 23372,
    width: 11336,
    height: 11040,
  }))
})

test('offers a reliable local-path import when the native picker is unavailable', async () => {
  vi.mocked(api.importDataset).mockResolvedValue({ datasets: [] })
  render(<App />)

  const libraryHeader = (await screen.findByText('Local workspace')).closest('header')
  expect(libraryHeader).not.toBeNull()
  fireEvent.click(within(libraryHeader!).getByRole('button', { name: 'Import' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Local slide path' }), {
    target: { value: 'C:\\slides\\case.ome.tif' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Import this path' }))

  await waitFor(() => expect(api.importDataset).toHaveBeenCalledWith('C:\\slides\\case.ome.tif'))
})

test('removes a slide from the library only after an explicit preservation warning', async () => {
  const dataset: api.Dataset = {
    id: 'removable-slide',
    displayName: 'Case 24.ome.tif',
    sourceBytes: 300_000_000,
    format: 'OME_TIFF',
    status: 'READY_TO_CONVERT',
    detail: 'Ready',
    outputPath: '',
    sha256: '',
    selectedSeries: -1,
    width: 0,
    height: 0,
    downsample: 1.5,
    estimatedOutputBytes: 0,
    cropX: 0,
    cropY: 0,
    cropWidth: 0,
    cropHeight: 0,
    sourceFingerprint: 'source-fingerprint',
    configurationRevision: '',
    currentArtifactRevision: '',
    approvedArtifactRevision: '',
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[dataset], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }])
  vi.mocked(api.datasets)
    .mockResolvedValueOnce([dataset])
    .mockResolvedValueOnce([])
  vi.mocked(api.deleteDataset).mockResolvedValue()

  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Remove from library' }))
  const dialog = screen.getByRole('dialog', { name: 'Remove slide?' })
  expect(within(dialog).getByText(/original VSI or OME-TIFF/i)).toBeVisible()
  expect(within(dialog).getByText(/completed exports remain on disk/i)).toBeVisible()
  fireEvent.click(within(dialog).getByRole('button', { name: 'Remove from Forge' }))

  await waitFor(() => expect(api.deleteDataset).toHaveBeenCalledWith('removable-slide'))
  await waitFor(() => expect(screen.queryByText('Case 24.ome.tif')).not.toBeInTheDocument())
})

test('switches image series immediately and reloads the revision-qualified preview', async () => {
  const dataset: api.Dataset = {
    id: 'series-switch-slide',
    displayName: 'Multi-image.vsi',
    sourceBytes: 1_000_000_000,
    format: 'VSI',
    status: 'READY_TO_CONVERT',
    detail: 'Two image series',
    outputPath: '',
    sha256: '',
    selectedSeries: 0,
    width: 8_000,
    height: 6_000,
    downsample: 1.5,
    estimatedOutputBytes: 300_000_000,
    cropX: 0,
    cropY: 0,
    cropWidth: 8_000,
    cropHeight: 6_000,
    sourceFingerprint: 'multi-image-source',
    configurationRevision: 'series-revision-0',
    currentArtifactRevision: '',
    approvedArtifactRevision: '',
  }
  const first: api.SeriesInfo = {
    index: 0,
    name: 'Overview',
    width: 8_000,
    height: 6_000,
    channels: 3,
    sizeZ: 1,
    sizeT: 1,
    pixelType: 'uint8',
    physicalSizeX: 0.5,
    physicalSizeY: 0.5,
    physicalUnit: 'µm',
    resolutionCount: 4,
    rgbPlane: true,
  }
  const second: api.SeriesInfo = {
    ...first,
    index: 1,
    name: 'Tissue',
    width: 24_000,
    height: 18_000,
    resolutionCount: 6,
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[dataset], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValue([dataset])
  vi.mocked(api.inspectDataset).mockResolvedValue([first, second])
  vi.mocked(api.configure).mockResolvedValue({
    ...dataset,
    selectedSeries: 1,
    width: second.width,
    height: second.height,
    cropWidth: second.width,
    cropHeight: second.height,
    configurationRevision: 'series-revision-1',
  })

  render(<App />)

  expect(await screen.findByTestId('forge-osd')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'Inspect image series' }))
  const seriesSelect = await screen.findByRole('combobox', { name: 'Image series' })
  expect(screen.getByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    expect.stringContaining('revision=series-revision-0'),
  )
  fireEvent.change(seriesSelect, { target: { value: '1' } })

  await waitFor(() => expect(api.configure).toHaveBeenCalledWith('series-switch-slide', {
    series: 1,
    downsample: 1.5,
    x: 0,
    y: 0,
    width: 24_000,
    height: 18_000,
  }))
  await waitFor(() => expect(screen.getByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    expect.stringContaining('revision=series-revision-1'),
  ))
})

test('shows conversion progress and keeps viewer controls locked until validation completes', async () => {
  const converting: api.Dataset = {
    id: 'converting-slide',
    displayName: 'Converting slide.vsi',
    sourceBytes: 3_000_000_000,
    format: 'VSI',
    status: 'CONVERTING',
    detail: 'Exporting QuPath-style rendered RGB with JPEG compression',
    outputPath: '',
    sha256: '',
    selectedSeries: 2,
    width: 49_941,
    height: 62_174,
    downsample: 1.5,
    estimatedOutputBytes: 900_000_000,
    cropX: 0,
    cropY: 0,
    cropWidth: 49_941,
    cropHeight: 62_174,
    sourceFingerprint: 'converting-source',
    configurationRevision: 'conversion-configuration',
    currentArtifactRevision: 'artifact-1',
    approvedArtifactRevision: '',
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[converting], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValue([converting])
  vi.mocked(api.annotations).mockResolvedValue([])
  vi.mocked(api.artifacts).mockResolvedValue({
    currentRevision: 'artifact-1',
    approvedRevision: '',
    revisions: [{
      id: 'artifact-1',
      status: 'CONVERTING',
      createdAt: Date.now(),
      outputWidth: 33_294,
      outputHeight: 41_449,
      omePath: '',
      packagePath: '',
      omeSha256: '',
      packageSha256: '',
      failure: '',
    }],
  })

  render(<App />)

  expect((await screen.findAllByText('Exporting rendered RGB'))[0]).toBeVisible()
  expect(screen.getByRole('progressbar', { name: 'Conversion progress' })).toHaveValue(20)
  expect(screen.getByText('Step 1 of 4')).toBeVisible()
  expect(screen.getByText('The converted result will open automatically after validation.')).toBeVisible()
  expect(screen.queryByTestId('forge-osd')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Zoom in' })).toBeDisabled()
  expect(screen.getByRole('progressbar', {
    name: 'Converting slide.vsi conversion progress',
  })).toHaveValue(20)
})
