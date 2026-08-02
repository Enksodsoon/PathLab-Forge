import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { vi } from 'vitest'

import * as api from '../api'
import { App } from '../App'

vi.mock('../SlideViewer', () => ({
  SlideViewer: ({
    tileSource,
    cropBox,
    cropEditing,
    onCropChange,
  }: {
    tileSource: string
    cropBox?: { x: number; y: number; width: number; height: number }
    cropEditing?: boolean
    onCropChange?: (box: { x: number; y: number; width: number; height: number }) => void
  }) => (
    <div
      data-testid="forge-osd"
      data-tile-source={tileSource}
      data-crop-box={cropBox ? JSON.stringify(cropBox) : ''}
    >
      {cropEditing ? (
        <>
          <button
            type="button"
            aria-label="Test draw crop"
            onClick={() => onCropChange?.({ x: 69790, y: 23372, width: 11336, height: 11040 })}
          />
          <button
            type="button"
            aria-label="Test reshape crop"
            onClick={() => onCropChange?.({ x: 69790, y: 23372, width: 10000, height: 9000 })}
          />
        </>
      ) : null}
    </div>
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
  importProjectFolder: vi.fn(async () => ({
    datasets: [],
    project: { root: 'C:\\cases', imported: 0, failed: '' },
  })),
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
  artifacts: vi.fn(async () => ({
    currentRevision: '',
    approvedRevision: '',
    revisions: [],
  })),
  renameArtifact: vi.fn(),
  deleteArtifact: vi.fn(),
  artifactPackageUrl: (id: string, revision: string) =>
    `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}/package`,
  artifactDziUrl: (id: string, revision: string) =>
    `/api/datasets/${encodeURIComponent(id)}/artifacts/${encodeURIComponent(revision)}/derivative/slide.dzi`,
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
  exchangeViewerPairing: vi.fn(),
  revokeViewerConnection: vi.fn(),
  uploadApprovedArtifact: vi.fn(),
  getViewerUpload: vi.fn(),
  pivotStatus: vi.fn(async () => ({
    status: 'READY',
    totalTasks: 12,
    inspectedCandidates: 120,
    generationMs: 80,
    nonDiagnostic: true,
  })),
  compilePivot: vi.fn(),
  pivotSession: vi.fn(async () => { throw new Error('No active session') }),
  startPivotSession: vi.fn(),
  submitPivot: vi.fn(),
  hintPivot: vi.fn(),
  skipPivot: vi.fn(),
  endPivot: vi.fn(),
}))

test('opens PIVOT training from a ready unannotated slide', async () => {
  const dataset: api.Dataset = {
    id: 'pivot-entry-slide',
    displayName: 'Unannotated slide.ome.tif',
    sourceBytes: 250_000_000,
    format: 'OME_TIFF',
    status: 'READY_TO_CONVERT',
    detail: 'Ready',
    outputPath: '',
    sha256: '',
    selectedSeries: 0,
    width: 4_000,
    height: 2_000,
    downsample: 1,
    estimatedOutputBytes: 0,
    projectedFileBytes: 0,
    projectedFileLowerBytes: 0,
    projectedFileUpperBytes: 0,
    cropX: 0,
    cropY: 0,
    cropWidth: 4_000,
    cropHeight: 2_000,
    sourceFingerprint: 'pivot-source',
    configurationRevision: 'pivot-config',
    currentArtifactRevision: '',
    approvedArtifactRevision: '',
  }
  vi.mocked(api.bootstrap).mockResolvedValueOnce([[dataset], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValueOnce([dataset])

  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'PIVOT training' }))
  expect(await screen.findByRole('main', { name: 'PIVOT training workspace' })).toBeVisible()
  expect(screen.getByText('Your annotation-free training set is ready')).toBeVisible()
  expect(screen.queryByText('No diagnostic labels are used.')).not.toBeInTheDocument()
  expect(screen.getByText('Research mode · non-diagnostic · local only')).toBeVisible()
})

test('launches directly into the Viewer Canvas Focus shell', async () => {
  render(<App />)

  expect(await screen.findByRole('navigation', { name: 'Library destinations' })).toBeVisible()
  expect(screen.getByRole('main')).toBeVisible()
  expect(screen.getByRole('region', { name: 'Whole-slide viewer' })).toBeVisible()
  expect(screen.getByText('Your slides, ready at launch')).toBeVisible()
  expect(screen.getByRole('button', { name: 'Connect' })).toBeVisible()
})

test('presents an imported cohort SVS as a native whole-slide source', async () => {
  const dataset: api.Dataset = {
    id: 'cohort-svs', displayName: 'BRACS_1003718.svs', sourceBytes: 10_000,
    format: 'SVS', status: 'READY_TO_CONVERT', detail: 'Ready', outputPath: '', sha256: '',
    selectedSeries: 0, width: 17_135, height: 11_733, downsample: 1,
    estimatedOutputBytes: 0, projectedFileBytes: 0, projectedFileLowerBytes: 0,
    projectedFileUpperBytes: 0, cropX: 0, cropY: 0, cropWidth: 17_135, cropHeight: 11_733,
    sourceFingerprint: 'svs-source', configurationRevision: 'svs-config',
    currentArtifactRevision: '', approvedArtifactRevision: '',
  }
  vi.mocked(api.bootstrap).mockResolvedValueOnce([[dataset], {
    conversionRuntime: 'Bio-Formats test', derivativeRuntime: 'libvips test',
    vsiConversion: true, dziGeneration: true, downsamples: [1, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValueOnce([dataset])
  const datasetCallsBeforeRender = vi.mocked(api.datasets).mock.calls.length

  render(<App />)

  expect(await screen.findByText(/SVS · Ready to convert · Original source viewer/)).toBeVisible()
  expect(screen.getByText('SVS whole slide')).toBeVisible()
  expect(screen.getByRole('button', { name: 'AI evidence' })).toBeVisible()
  await waitFor(() => expect(api.datasets).toHaveBeenCalledTimes(datasetCallsBeforeRender + 1))
})

test('keeps the viewer visible by collapsing the navigator on compact browser widths', async () => {
  const originalWidth = window.innerWidth
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 648 })
  try {
    const view = render(<App />)
    const main = await screen.findByRole('main')
    expect(main.closest('.forge-canvas-host')).toHaveClass('navigator-collapsed')
    expect(screen.getByRole('button', { name: 'Slide library' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    view.unmount()
  } finally {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: originalWidth })
  }
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
  expect(within(rail).getByText('Connect Viewer')).toBeVisible()
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

test('defaults local pairing to the Viewer web origin', async () => {
  render(<App />)

  await waitFor(() => {
    expect(api.datasets).toHaveBeenCalled()
    expect(api.getViewerConnection).toHaveBeenCalled()
  })
  fireEvent.click(await screen.findByRole('button', { name: 'Viewer account' }))

  expect(screen.getByRole('textbox', { name: 'Viewer address' })).toHaveValue(
    'http://127.0.0.1:5173',
  )
  expect(screen.getByRole('button', { name: 'Request pairing code' })).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Disconnect this device' })).not.toBeInTheDocument()
})

test('shows connected account details and revokes only after confirmation', async () => {
  vi.mocked(api.getViewerConnection).mockResolvedValueOnce({
    connected: true,
    viewerUrl: 'http://127.0.0.1:5173',
    deviceName: 'PathLab Forge on Windows',
    scopes: ['desktop:ingest', 'slides:private:read'],
  })
  let finishRevoke!: () => void
  vi.mocked(api.revokeViewerConnection).mockImplementationOnce(() => new Promise<void>((resolve) => {
    finishRevoke = resolve
  }))
  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'PathLab Forge on Windows' }))
  const dialog = screen.getByRole('dialog', { name: 'Viewer connection' })
  expect(within(dialog).getByText('http://127.0.0.1:5173')).toBeVisible()
  expect(within(dialog).getByText('PathLab Forge on Windows')).toBeVisible()
  expect(within(dialog).getByText('desktop:ingest')).toBeVisible()
  expect(within(dialog).getByText('slides:private:read')).toBeVisible()

  fireEvent.click(within(dialog).getByRole('button', { name: 'Disconnect this device' }))
  expect(api.revokeViewerConnection).not.toHaveBeenCalled()
  fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm disconnect' }))
  expect(api.revokeViewerConnection).toHaveBeenCalledTimes(1)
  expect(within(dialog).getByText('PathLab Forge on Windows')).toBeVisible()

  finishRevoke()
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Viewer connection' }))
    .not.toBeInTheDocument())
  expect(screen.getByRole('button', { name: 'Viewer account' })).toBeVisible()
})

test('keeps pairing retryable and explains pending approval', async () => {
  vi.mocked(api.exchangeViewerPairing).mockRejectedValueOnce(new Error(
    'Viewer pairing is not approved yet (409): {"error":"PAIRING_PENDING"}',
  ))
  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Viewer account' }))
  fireEvent.click(screen.getByRole('button', { name: 'Request pairing code' }))
  await screen.findByText('ABCD-EFGH')
  fireEvent.click(screen.getByRole('button', { name: 'I approved this device' }))

  expect(await screen.findByText(
    'Approval is still pending. Approve the code in Viewer, then try again.',
  )).toBeVisible()
  expect(screen.getByRole('button', { name: 'I approved this device' })).toBeVisible()
})

test.each([
  ['pairing expired', 'This pairing code expired. Request a new code and approve it in Viewer.'],
  ['credential revoked (401)', 'The Viewer credential was revoked. Connect this device again.'],
])('explains retryable Viewer exchange failure: %s', async (failure, expected) => {
  vi.mocked(api.exchangeViewerPairing).mockRejectedValueOnce(new Error(failure))
  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Viewer account' }))
  fireEvent.click(screen.getByRole('button', { name: 'Request pairing code' }))
  await screen.findByText('ABCD-EFGH')
  fireEvent.click(screen.getByRole('button', { name: 'I approved this device' }))

  expect(await screen.findByText(expected)).toBeVisible()
  expect(screen.getByRole('button', { name: 'I approved this device' })).toBeVisible()
})

test.each([
  ['Viewer URL must use HTTPS', 'Enter a valid HTTPS Viewer URL, or a loopback HTTP address for local testing.'],
  ['Connection refused', 'Viewer is unreachable. Start Viewer and confirm its web address, then retry.'],
])('explains retryable Viewer connection failure: %s', async (failure, expected) => {
  vi.mocked(api.startViewerPairing).mockRejectedValueOnce(new Error(failure))
  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Viewer account' }))
  fireEvent.click(screen.getByRole('button', { name: 'Request pairing code' }))

  expect(await screen.findByText(expected)).toBeVisible()
  expect(screen.getByRole('button', { name: 'Request pairing code' })).toBeVisible()
})

test('updates dimensions and file size live while drawing and reshaping a crop', async () => {
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
  expect(screen.getByTestId('forge-osd')).toBeVisible()
  await screen.findByRole('button', { name: 'Draw crop on slide' })
  expect(screen.getByRole('button', { name: 'Draw crop on slide' })).toHaveAttribute('aria-pressed', 'false')
  expect(screen.queryByText('Precise crop coordinates')).not.toBeInTheDocument()
  expect(screen.queryAllByRole('spinbutton')).toHaveLength(0)
  await screen.findByText('82,922 × 45,367')
  expect(screen.getByText(/Estimated temporary staging ≈/)).toBeVisible()
  expect(screen.getByText(/Expected range/)).toBeVisible()
  expect(screen.getByText(/Peak conversion workspace ≤/)).toBeVisible()

  fireEvent.click(screen.getByRole('button', {
    name: 'Main series, 165845 by 90735 pixels',
  }))
  expect(api.configure).not.toHaveBeenCalled()
  expect(screen.queryByText('Opening selected series in the viewer…')).not.toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: 'Draw crop on slide' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Test draw crop' }))
  expect(screen.getByText('5,668 × 5,520')).toBeVisible()
  const drawnEstimate = screen.getByText(/Estimated temporary staging ≈/).textContent
  expect(drawnEstimate).toContain('live')

  fireEvent.click(screen.getByRole('button', { name: 'Test reshape crop' }))
  expect(screen.getByText('5,000 × 4,500')).toBeVisible()
  expect(screen.getByText(/Estimated temporary staging ≈/).textContent).not.toBe(drawnEstimate)

  fireEvent.change(screen.getByRole('combobox', { name: 'Downsample' }), { target: { value: '1.5' } })

  expect(api.configure).not.toHaveBeenCalled()
  expect(screen.getByText('6,666 × 6,000')).toBeVisible()

  fireEvent.click(screen.getByRole('button', { name: 'Apply crop & export settings' }))

  await waitFor(() => expect(api.configure).toHaveBeenCalledWith('dataset-1', {
    series: 2,
    downsample: 1.5,
    x: 69790,
    y: 23372,
    width: 10000,
    height: 9000,
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

test('offers recursive project-folder import from the same compact dialog', async () => {
  render(<App />)

  const libraryHeader = (await screen.findByText('Local workspace')).closest('header')
  fireEvent.click(within(libraryHeader!).getByRole('button', { name: 'Import' }))
  fireEvent.click(screen.getByRole('button', { name: 'Choose project folder…' }))

  await waitFor(() => expect(api.importProjectFolder).toHaveBeenCalledWith(undefined))
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
  expect(within(dialog).getByText(/original SVS, VSI or OME-TIFF/i)).toBeVisible()
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
    stage: 'REGIONS_RENDERING',
    completedUnits: 2,
    totalUnits: 10,
    elapsedMs: 14_000,
    estimatedRemainingMs: 28_000,
    unitsPerSecond: 0.5,
    resourceProfile: 'adaptive-12c-32gb',
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
      dziBytes: 0,
      packageBytes: 0,
      jpegQuality: 0,
      minimumWindowedSsim: 0,
      maximumRoiMeanDeltaE00: 0,
      minimumEdgeDetailRetention: 0,
      encoderProfile: '',
      packageSha256: '',
      failure: '',
    }],
  })

  render(<App />)

  expect((await screen.findAllByText('Reading source regions in parallel'))[0]).toBeVisible()
  expect(screen.getByRole('progressbar', { name: 'Conversion progress' })).toHaveValue(11)
  expect(screen.getByText('Step 1 of 5')).toBeVisible()
  expect(screen.getByText('2 of 10 source regions')).toBeVisible()
  expect(screen.getByText('12c · 32gb')).toBeVisible()
  expect(screen.getByText(/Elapsed 14s · about 28s left in this phase/)).toBeVisible()
  expect(screen.getByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    '/api/datasets/converting-slide/preview/slide.dzi?revision=conversion-configuration&preview=responsive-v2',
  )
  expect(screen.getByRole('button', { name: 'Zoom in' })).toBeDisabled()
  expect(screen.getByRole('progressbar', {
    name: 'Converting slide.vsi conversion progress',
  })).toHaveValue(11)
})

test('refreshes annotations and artifacts only for the selected active slide', async () => {
  const base: api.Dataset = {
    id: 'active-one',
    displayName: 'Active one.vsi',
    sourceBytes: 1_000,
    format: 'VSI',
    status: 'CONVERTING',
    detail: 'Converting',
    outputPath: '',
    sha256: '',
    selectedSeries: 0,
    width: 1000,
    height: 500,
    downsample: 1,
    estimatedOutputBytes: 1_000,
    projectedFileBytes: 500,
    projectedFileLowerBytes: 250,
    projectedFileUpperBytes: 1_000,
    cropX: 0,
    cropY: 0,
    cropWidth: 1000,
    cropHeight: 500,
    sourceFingerprint: 'source-one',
    configurationRevision: 'config-one',
    currentArtifactRevision: 'artifact-one',
    approvedArtifactRevision: '',
  }
  const second = {
    ...base,
    id: 'active-two',
    displayName: 'Active two.vsi',
    sourceFingerprint: 'source-two',
    configurationRevision: 'config-two',
    currentArtifactRevision: 'artifact-two',
  }
  vi.mocked(api.annotations).mockClear()
  vi.mocked(api.artifacts).mockClear()
  vi.mocked(api.bootstrap).mockResolvedValue([[base, second], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValue([base, second])
  vi.mocked(api.annotations).mockResolvedValue([])
  vi.mocked(api.artifacts).mockResolvedValue({
    currentRevision: 'artifact-one',
    approvedRevision: '',
    revisions: [],
  })

  render(<App />)

  await waitFor(() => expect(api.annotations).toHaveBeenCalledWith('active-one'))
  expect(api.annotations).not.toHaveBeenCalledWith('active-two')
  expect(api.artifacts).toHaveBeenCalledWith('active-one')
  expect(api.artifacts).not.toHaveBeenCalledWith('active-two')
})

test('keeps the original viewer visible while the upload package is still building', async () => {
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
      dziBytes: 390_000_000,
      packageBytes: 0,
      jpegQuality: 70,
      minimumWindowedSsim: 0.975,
      maximumRoiMeanDeltaE00: 2.1,
      minimumEdgeDetailRetention: 0.93,
      encoderProfile: 'compact-420-trellis',
      packageSha256: '',
      failure: '',
    }],
  })

  render(<App />)

  expect(await screen.findByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    '/api/datasets/packaging-slide/preview/slide.dzi?revision=packaging-configuration&preview=responsive-v2',
  )
  expect(screen.getAllByText('Quality passed · packaging compact DZI')).toHaveLength(2)
  expect(screen.queryByRole('button', { name: 'Approve compact DZI' })).not.toBeInTheDocument()
})

test('views, renames, downloads, and deletes saved conversions from History', async () => {
  const dataset: api.Dataset = {
    id: 'history-slide',
    displayName: 'History slide.vsi',
    sourceBytes: 1_500_000_000,
    format: 'VSI',
    status: 'READY',
    detail: 'Conversion validated',
    outputPath: '',
    sha256: 'ome-hash',
    selectedSeries: 0,
    width: 16_000,
    height: 12_000,
    downsample: 2,
    estimatedOutputBytes: 500_000_000,
    projectedFileBytes: 100_000_000,
    projectedFileLowerBytes: 80_000_000,
    projectedFileUpperBytes: 150_000_000,
    cropX: 0,
    cropY: 0,
    cropWidth: 16_000,
    cropHeight: 12_000,
    sourceFingerprint: 'history-source',
    configurationRevision: 'history-configuration',
    currentArtifactRevision: 'history-latest',
    approvedArtifactRevision: '',
  }
  const latest: api.ArtifactRevision = {
    id: 'history-latest',
    name: 'Whole slide · 2×',
    status: 'READY',
    createdAt: Date.now(),
    outputWidth: 8_000,
    outputHeight: 6_000,
    omePath: '',
    packagePath: 'C:\\exports\\latest.plslide',
    omeSha256: 'latest-ome',
    omeBytes: 110_000_000,
    dziBytes: 92_000_000,
    packageBytes: 94_000_000,
    jpegQuality: 70,
    minimumWindowedSsim: 0.976,
    maximumRoiMeanDeltaE00: 2.1,
    minimumEdgeDetailRetention: 0.94,
    encoderProfile: 'compact-420-trellis',
    packageSha256: 'latest-package',
    failure: '',
  }
  const older: api.ArtifactRevision = {
    ...latest,
    id: 'history-older',
    name: 'Tumour crop · 4×',
    createdAt: Date.now() - 60_000,
    outputWidth: 4_000,
    outputHeight: 3_000,
    packagePath: 'C:\\exports\\older.plslide',
    packageBytes: 31_000_000,
    packageSha256: 'older-package',
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[dataset], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 1.5, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValue([dataset])
  vi.mocked(api.artifacts).mockResolvedValue({
    currentRevision: latest.id,
    approvedRevision: '',
    revisions: [latest, older],
  })
  vi.mocked(api.renameArtifact).mockResolvedValue({ ...older, name: 'Review region' })
  vi.mocked(api.deleteArtifact).mockResolvedValue(dataset)

  render(<App />)

  const mainViewer = await screen.findByTestId('forge-osd')
  expect(mainViewer).toHaveAttribute(
    'data-tile-source',
    '/api/datasets/history-slide/preview/slide.dzi?revision=history-configuration&preview=responsive-v2',
  )
  fireEvent.click(await screen.findByRole('link', { name: 'View converted slide' }))
  expect(mainViewer).toHaveAttribute(
    'data-tile-source',
    '/api/datasets/history-slide/artifacts/history-latest/derivative/slide.dzi',
  )
  fireEvent.click(screen.getByRole('link', { name: 'View original slide' }))
  expect(mainViewer).toHaveAttribute(
    'data-tile-source',
    '/api/datasets/history-slide/preview/slide.dzi?revision=history-configuration&preview=responsive-v2',
  )

  fireEvent.click(await screen.findByRole('tab', { name: 'History' }))
  const history = screen.getByRole('region', { name: 'Conversion history' })
  expect(within(history).getByText('Whole slide · 2×')).toBeVisible()
  expect(within(history).getByText('Tumour crop · 4×')).toBeVisible()

  const olderCard = within(history).getByText('Tumour crop · 4×').closest('article')!
  expect(within(olderCard).getByRole('link', { name: 'Download' })).toHaveAttribute(
    'href',
    '/api/datasets/history-slide/artifacts/history-older/package',
  )
  fireEvent.click(within(olderCard).getByRole('button', { name: 'View slide' }))
  expect(screen.getByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    '/api/datasets/history-slide/artifacts/history-older/derivative/slide.dzi',
  )

  fireEvent.click(within(olderCard).getByRole('button', { name: 'Rename' }))
  fireEvent.change(within(olderCard).getByRole('textbox', { name: 'Conversion name' }), {
    target: { value: 'Review region' },
  })
  fireEvent.click(within(olderCard).getByRole('button', { name: 'Save name' }))
  await waitFor(() => expect(api.renameArtifact).toHaveBeenCalledWith(
    dataset.id,
    older.id,
    'Review region',
  ))

  fireEvent.click(within(olderCard).getByRole('button', { name: 'Delete' }))
  expect(within(olderCard).getByRole('alert')).toHaveTextContent(
    'The source slide is not deleted.',
  )
  fireEvent.click(within(olderCard).getByRole('button', { name: 'Delete conversion' }))
  await waitFor(() => expect(api.deleteArtifact).toHaveBeenCalledWith(dataset.id, older.id))
})

test('replaces the estimate with compact DZI size and quality evidence after conversion', async () => {
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
      dziBytes: 760_000,
      packageBytes: 820_224,
      jpegQuality: 70,
      minimumWindowedSsim: 0.9742,
      maximumRoiMeanDeltaE00: 2.18,
      minimumEdgeDetailRetention: 0.92,
      encoderProfile: 'compact-420-trellis',
      packageSha256: 'package-hash',
      failure: '',
    }],
  })

  render(<App />)

  const resultCard = await screen.findByRole('region', { name: 'Converted slide result' })
  expect(within(resultCard).getByText('801.0 KB')).toBeVisible()
  expect(within(resultCard).getByRole('link', { name: 'View converted slide' }))
    .toHaveAttribute('href', '#dzi-viewer')
  expect(within(resultCard).getByRole('link', { name: 'Download 801.0 KB package' }))
    .toHaveAttribute('href', '/api/datasets/measured-slide/package')

  fireEvent.click(await screen.findByRole('button', { name: 'Inspect image series' }))
  expect(await screen.findByText('Compact DZI package 801.0 KB')).toBeVisible()
  expect(screen.getByText(/Compared with 854.3 KB staging OME · 93.8% · Q70/)).toBeVisible()
  expect(screen.getByText(/Quality passed · SSIM 0.9742 · max ΔE00 2.18 · edge 92.0%/)).toBeVisible()
  expect(screen.queryByText(/Size warning:/)).not.toBeInTheDocument()
  expect(screen.queryByText(/Estimated temporary staging ≈/)).not.toBeInTheDocument()
  expect(screen.getByText('Peak conversion workspace ≤ 4.5 MB')).toBeVisible()

  fireEvent.change(screen.getByRole('combobox', { name: 'Downsample' }), {
    target: { value: '4' },
  })

  expect(await screen.findByText('Estimated temporary staging ≈ 23.8 MB')).toBeVisible()
  expect(screen.queryByText(/Compared with/)).not.toBeInTheDocument()
  expect(api.estimate).toHaveBeenCalledWith(
    'measured-slide',
    { downsample: 4, width: 8_021, height: 9_366 },
    expect.any(AbortSignal),
  )
})

test('shows one actionable size quality conflict without changing the crop', async () => {
  const failed: api.Dataset = {
    id: 'compact-conflict',
    displayName: 'Conflict.ome.tif',
    sourceBytes: 100_000_000,
    format: 'OME_TIFF',
    status: 'FAILED',
    detail: 'DZI_SIZE_QUALITY_CONFLICT: package is 1.31x and exceeds the 1.25x hard limit',
    outputPath: '',
    sha256: '',
    selectedSeries: 0,
    width: 10_000,
    height: 8_000,
    downsample: 1,
    estimatedOutputBytes: 50_000_000,
    projectedFileBytes: 50_000_000,
    projectedFileLowerBytes: 25_000_000,
    projectedFileUpperBytes: 100_000_000,
    cropX: 100,
    cropY: 200,
    cropWidth: 5_000,
    cropHeight: 4_000,
    sourceFingerprint: 'conflict-source',
    configurationRevision: 'conflict-config',
    currentArtifactRevision: '',
    approvedArtifactRevision: '',
  }
  vi.mocked(api.bootstrap).mockResolvedValue([[failed], {
    conversionRuntime: 'Bio-Formats test',
    derivativeRuntime: 'libvips test',
    vsiConversion: true,
    dziGeneration: true,
    downsamples: [1, 2, 4, 8],
  }])
  vi.mocked(api.datasets).mockResolvedValue([failed])

  render(<App />)

  const panel = await screen.findByRole('alert')
  expect(within(panel).getByText('Compact DZI could not meet the 1.25× size limit')).toBeVisible()
  expect(within(panel).getByText(/Your current crop is preserved/)).toBeVisible()
  expect(screen.getByRole('button', { name: 'Convert current revision' })).toBeVisible()
  expect(screen.getByTestId('forge-osd')).toHaveAttribute(
    'data-tile-source',
    '/api/datasets/compact-conflict/preview/slide.dzi?revision=conflict-config&preview=responsive-v2',
  )
})
