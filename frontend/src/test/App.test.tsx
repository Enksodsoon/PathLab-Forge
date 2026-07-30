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
  estimate: vi.fn(async (_id: string, values: { downsample: number; width: number; height: number }) => ({
    outputWidth: Math.max(1, Math.floor(values.width / values.downsample)),
    outputHeight: Math.max(1, Math.floor(values.height / values.downsample)),
    fileBytes: Math.round(100_000_000 / values.downsample),
    fileLowerBytes: Math.round(50_000_000 / values.downsample),
    fileUpperBytes: Math.round(200_000_000 / values.downsample),
    workspaceBytes: Math.round(400_000_000 / (values.downsample ** 2)),
  })),
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

test('gives the expanded product rail enough width to show its labels', async () => {
  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Expand navigation rail' }))

  const rail = screen.getByRole('complementary', { name: 'Product navigation' })
  const shell = rail.closest('.pathlab-canvas-shell')
  expect(shell).toHaveClass('rail-expanded')
  expect(within(rail).getByText('Slide library')).toBeVisible()
  expect(within(rail).getByText('Import')).toBeVisible()
  expect(within(rail).getByText('Viewer account')).toBeVisible()
  expect(within(rail).getByText('Disconnect')).toBeVisible()
})

test('collapses and restores the slide inspector without losing its state', async () => {
  const dataset: api.Dataset = {
    id: 'collapsible-inspector-slide',
    displayName: 'Collapsible slide.ome.tif',
    sourceBytes: 320_000_000,
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
    projectedFileBytes: 0,
    projectedFileLowerBytes: 0,
    projectedFileUpperBytes: 0,
    cropX: 0,
    cropY: 0,
    cropWidth: 0,
    cropHeight: 0,
    sourceFingerprint: 'collapsible-source',
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
  vi.mocked(api.datasets).mockResolvedValue([dataset])

  render(<App />)

  const inspectorLabel = await screen.findByText('Slide inspector')
  const host = inspectorLabel.closest('.forge-canvas-host')
  const inspector = inspectorLabel.closest('aside')
  expect(host).not.toHaveClass('inspector-collapsed')
  expect(inspector).toBeVisible()

  fireEvent.click(within(inspectorLabel.closest('header')!).getByRole('button', {
    name: 'Collapse slide inspector',
  }))

  expect(host).toHaveClass('inspector-collapsed')
  expect(inspector).not.toBeVisible()
  const restore = screen.getByRole('button', { name: 'Open slide inspector' })
  expect(restore).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(restore)

  expect(host).not.toHaveClass('inspector-collapsed')
  expect(inspector).toBeVisible()
  expect(screen.getByRole('heading', { name: 'Collapsible slide.ome.tif' })).toBeVisible()

  const collapseLibrary = screen.getByRole('button', { name: 'Collapse slide library' })
  const navigator = collapseLibrary.closest('.pathlab-local-navigator')
  fireEvent.click(collapseLibrary)

  expect(host).toHaveClass('navigator-collapsed')
  expect(navigator).not.toBeVisible()
  const restoreLibrary = screen.getByRole('button', { name: 'Slide library' })
  expect(restoreLibrary).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(restoreLibrary)

  expect(host).not.toHaveClass('navigator-collapsed')
  expect(navigator).toBeVisible()
  expect(screen.getByRole('button', {
    name: /Collapsible slide\.ome\.tif Ready to convert/,
  })).toHaveClass('active')
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
    projectedFileBytes: 900_000_000,
    projectedFileLowerBytes: 300_000_000,
    projectedFileUpperBytes: 2_000_000_000,
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
  expect(screen.getByText(/Estimated OME-TIFF ≈/)).toBeVisible()
  expect(screen.getByText(/Expected range/)).toBeVisible()
  expect(screen.getByText(/Peak conversion workspace ≤/)).toBeVisible()
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
  const imported: api.Dataset = {
    id: 'instant-import',
    displayName: 'case.ome.tif',
    sourceBytes: 300_000_000,
    format: 'OME_TIFF',
    status: 'READY',
    detail: 'Ready to inspect',
    outputPath: '',
    sha256: '',
    selectedSeries: -1,
    width: 0,
    height: 0,
    downsample: 1,
    estimatedOutputBytes: 0,
    projectedFileBytes: 0,
    projectedFileLowerBytes: 0,
    projectedFileUpperBytes: 0,
    cropX: 0,
    cropY: 0,
    cropWidth: 0,
    cropHeight: 0,
    sourceFingerprint: 'instant-source',
    configurationRevision: '',
    currentArtifactRevision: '',
    approvedArtifactRevision: '',
  }
  let finishImportRequest!: (result: { datasets: api.Dataset[] }) => void
  vi.mocked(api.importDataset).mockImplementation(() => new Promise((resolve) => {
    finishImportRequest = resolve
  }))
  const mainSeries: api.SeriesInfo = {
    index: 0,
    name: 'Main image',
    width: 12_000,
    height: 8_000,
    channels: 3,
    sizeZ: 1,
    sizeT: 1,
    pixelType: 'uint8',
    physicalSizeX: .25,
    physicalSizeY: .25,
    physicalUnit: 'µm',
    resolutionCount: 4,
    rgbPlane: true,
  }
  let finishInspection!: (series: api.SeriesInfo[]) => void
  vi.mocked(api.inspectDataset).mockImplementation(() => new Promise((resolve) => {
    finishInspection = resolve
  }))
  render(<App />)

  const libraryHeader = (await screen.findByText('Local workspace')).closest('header')
  expect(libraryHeader).not.toBeNull()
  fireEvent.click(within(libraryHeader!).getByRole('button', { name: 'Import' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Local slide path' }), {
    target: { value: 'C:\\slides\\case.ome.tif' },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Import this path' }))

  await waitFor(() => expect(api.importDataset).toHaveBeenCalledWith('C:\\slides\\case.ome.tif'))
  expect(screen.getByText('Preparing imported slide')).toBeVisible()
  finishImportRequest({ datasets: [imported] })
  await waitFor(() => expect(api.inspectDataset).toHaveBeenCalledWith('instant-import'))
  const previewStatus = screen.getByText('Opening slide').closest('[role="status"]')
  expect(previewStatus).toHaveTextContent('preparing the first visible tile')
  finishInspection([mainSeries])
  await waitFor(() => expect(screen.queryByText('Opening slide')).not.toBeInTheDocument())
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
    projectedFileBytes: 0,
    projectedFileLowerBytes: 0,
    projectedFileUpperBytes: 0,
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
    projectedFileBytes: 48_000_000,
    projectedFileLowerBytes: 16_000_000,
    projectedFileUpperBytes: 120_000_000,
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
  const tissueSeries = await screen.findByRole('button', {
    name: 'Tissue, 24000 by 18000 pixels',
  })
  expect(screen.getByRole('button', {
    name: 'Overview, 8000 by 6000 pixels',
  }).querySelector('img')).toHaveAttribute(
    'src',
    expect.stringContaining('/series/0/thumbnail?v=multi-image-source'),
  )
  expect(tissueSeries).toHaveTextContent('6 pyramid levels')
  expect(screen.getByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    expect.stringContaining('revision=series-revision-0'),
  )
  fireEvent.click(tissueSeries)

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
    projectedFileBytes: 310_000_000,
    projectedFileLowerBytes: 45_000_000,
    projectedFileUpperBytes: 650_000_000,
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
      omeBytes: 0,
      packageSha256: '',
      failure: '',
    }],
  })

  render(<App />)

  expect((await screen.findAllByText('Exporting rendered RGB'))[0]).toBeVisible()
  expect(screen.getByRole('progressbar', { name: 'Conversion progress' })).toHaveValue(15)
  expect(screen.getByText('Step 1 of 5')).toBeVisible()
  expect(screen.getByText(/Large whole-slide exports can take several minutes/)).toBeVisible()
  expect(screen.getByText(/Elapsed/)).toBeVisible()
  expect(screen.queryByTestId('forge-osd')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Zoom in' })).toBeDisabled()
  expect(screen.getByRole('progressbar', {
    name: 'Converting slide.vsi conversion progress',
  })).toHaveValue(15)
})

test('opens the converted viewer while the upload package is still building', async () => {
  const packaging: api.Dataset = {
    id: 'packaging-slide',
    displayName: 'Packaging slide.vsi',
    sourceBytes: 3_000_000_000,
    format: 'VSI',
    status: 'DZI_READY',
    detail: 'Validated DZI tiles; result is viewable while the upload package builds',
    outputPath: 'C:\\exports\\export.ome.tif',
    sha256: 'ome-hash',
    selectedSeries: 3,
    width: 72_792,
    height: 66_004,
    downsample: 1,
    estimatedOutputBytes: 20_000_000_000,
    projectedFileBytes: 480_000_000,
    projectedFileLowerBytes: 200_000_000,
    projectedFileUpperBytes: 1_000_000_000,
    cropX: 0,
    cropY: 0,
    cropWidth: 72_792,
    cropHeight: 66_004,
    sourceFingerprint: 'packaging-source',
    configurationRevision: 'packaging-configuration',
    currentArtifactRevision: 'packaging-artifact',
    approvedArtifactRevision: '',
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[packaging], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValue([packaging])
  vi.mocked(api.artifacts).mockResolvedValue({
    currentRevision: 'packaging-artifact',
    approvedRevision: '',
    revisions: [{
      id: 'packaging-artifact',
      status: 'READY',
      createdAt: Date.now(),
      outputWidth: 72_792,
      outputHeight: 66_004,
      omePath: 'C:\\exports\\export.ome.tif',
      packagePath: 'C:\\exports\\slide.plslide',
      omeSha256: 'ome-hash',
      omeBytes: 480_000_000,
      packageSha256: '',
      failure: '',
    }],
  })

  render(<App />)

  expect(await screen.findByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    expect.stringContaining('/derivative/slide.dzi?revision=packaging-artifact'),
  )
  expect(screen.getByText('Result viewable · building upload package')).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Approve exact result' })).not.toBeInTheDocument()
})

test('replaces the estimate with the measured OME-TIFF size after conversion', async () => {
  const ready: api.Dataset = {
    id: 'measured-slide',
    displayName: 'Measured slide.vsi',
    sourceBytes: 1_116_691_456,
    format: 'VSI',
    status: 'READY',
    detail: 'Conversion validated',
    outputPath: 'C:\\exports\\export.ome.tif',
    sha256: 'ome-hash',
    selectedSeries: 0,
    width: 8_021,
    height: 9_366,
    downsample: 8,
    estimatedOutputBytes: 4_687_992,
    projectedFileBytes: 856_000,
    projectedFileLowerBytes: 390_000,
    projectedFileUpperBytes: 1_875_000,
    cropX: 0,
    cropY: 0,
    cropWidth: 8_021,
    cropHeight: 9_366,
    sourceFingerprint: 'measured-source',
    configurationRevision: 'measured-configuration',
    currentArtifactRevision: 'measured-artifact',
    approvedArtifactRevision: '',
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[ready], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValue([ready])
  vi.mocked(api.inspectDataset).mockResolvedValue([{
    index: 0,
    name: 'Label',
    width: 8_021,
    height: 9_366,
    channels: 3,
    sizeZ: 1,
    sizeT: 1,
    pixelType: 'uint8',
    physicalSizeX: 1,
    physicalSizeY: 1,
    physicalUnit: 'px',
    resolutionCount: 1,
    rgbPlane: true,
  }])
  vi.mocked(api.artifacts).mockResolvedValue({
    currentRevision: 'measured-artifact',
    approvedRevision: '',
    revisions: [{
      id: 'measured-artifact',
      status: 'READY',
      createdAt: Date.now(),
      outputWidth: 1_002,
      outputHeight: 1_170,
      omePath: 'C:\\exports\\export.ome.tif',
      packagePath: 'C:\\exports\\prepared.plslide',
      omeSha256: 'ome-hash',
      omeBytes: 874_756,
      packageSha256: 'package-hash',
      failure: '',
    }],
  })

  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Inspect image series' }))
  expect(await screen.findByText('OME-TIFF file 854.3 KB · measured')).toBeVisible()
  expect(screen.queryByText(/Estimated OME-TIFF ≈/)).not.toBeInTheDocument()
  expect(screen.getByText('Peak conversion workspace ≤ 4.5 MB')).toBeVisible()

  fireEvent.change(screen.getByRole('combobox', { name: 'Downsample' }), {
    target: { value: '4' },
  })

  expect(await screen.findByText('Estimated OME-TIFF ≈ 23.8 MB')).toBeVisible()
  expect(screen.queryByText(/· measured/)).not.toBeInTheDocument()
  expect(api.estimate).toHaveBeenCalledWith(
    'measured-slide',
    { downsample: 4, width: 8_021, height: 9_366 },
    expect.any(AbortSignal),
  )
})
