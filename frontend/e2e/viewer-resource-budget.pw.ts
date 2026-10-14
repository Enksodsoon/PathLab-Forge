import { expect, test, type Page, type Route } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { captureBrowserErrors } from './browserErrors'

const here = path.dirname(fileURLToPath(import.meta.url))
const datasetId = 'ca38d59a-08ce-44a2-aaf2-cb96bd147bdf'
const replacementDatasetId = 'b293c4f0-a2ba-4242-a81f-5a208a364cbb'
const artifactRevision = 'fcafc4bf-2350-470c-aa3a-ad5f5a0d9734'
const packageSha256 = 'a'.repeat(64)
const slideId = `local:${datasetId}`
const fixtureDzi = '<Image TileSize="256" Overlap="0" Format="png" xmlns="http://schemas.microsoft.com/deepzoom/2008"><Size Width="640" Height="320" /></Image>'

const slide = {
  id: datasetId, displayName: 'Concurrent synthetic landmarks.ome.tif', sourceBytes: 100,
  format: 'OME_TIFF', status: 'READY', detail: 'Synthetic image ready', outputPath: '', sha256: packageSha256,
  selectedSeries: 0, width: 640, height: 320, downsample: 1, estimatedOutputBytes: 0,
  projectedFileBytes: 0, projectedFileLowerBytes: 0, projectedFileUpperBytes: 0,
  cropX: 0, cropY: 0, cropWidth: 640, cropHeight: 320, sourceFingerprint: 'synthetic-source',
  configurationRevision: 'synthetic-configuration', currentArtifactRevision: '', approvedArtifactRevision: '',
}
const replacementSlide = {
  ...slide,
  id: replacementDatasetId,
  displayName: 'Replacement synthetic landmarks.ome.tif',
  sourceFingerprint: 'replacement-synthetic-source',
  configurationRevision: 'replacement-synthetic-configuration',
}

const studyDraft = {
  id: 'concurrent-resource-draft', name: 'Concurrent resource draft', revision: 1,
  associations: { teachingSlides: { [slideId]: {
    referenceId: slideId, viewerSlideId: slideId, datasetId, artifactRevision, packageSha256,
    configurationRevision: 'synthetic-configuration', sourceFingerprint: 'synthetic-source', outputWidth: 640, outputHeight: 320,
    provenance: { series: 0, viewRevision: 'synthetic-view', crop: { x: 0, y: 0, width: 640, height: 320 }, downsample: 1 },
  } } },
  issues: [], previewChecksum: 'exact-synthetic-checksum', reviewedTaskIds: [], approvedChecksum: '', updatedAt: 1,
  definition: {
    schema: 'pathlab.study-pack/1', packKey: 'concurrent-resource', version: 1, title: 'Resource test', author: 'Synthetic fixture',
    license: 'CC-BY-4.0', provenance: 'Synthetic Playwright fixture', revision: 'synthetic-r1', languages: ['en'],
    slides: [{ viewerSlideId: slideId, sha256: packageSha256, displayName: 'Concurrent synthetic landmarks' }],
    tasks: [{ id: 'spatial-landmark', type: 'spatial', slideId, prompt: 'Select the synthetic landmark.', targetX: .45, targetY: .4,
      targetWidth: .1, targetHeight: .2, tolerance: .05, options: [], answerKey: '', hints: ['Synthetic hint'], explanation: 'Synthetic explanation.', sources: [] }],
  },
}

type ResourceSnapshot = {
  viewerCount: number; trackedViewerCount: number; activeLoaderJobs: number; queuedLoaderJobs: number
  activeLoaders: number; scheduledLoaders: number; waitingLoaders: number; maxLoaderJobs: number; maxCacheTiles: number; cachedTiles: number
  peaks: { activeLoaderJobs: number; queuedLoaderJobs: number; cachedTiles: number; viewers: number }
  viewers: Array<{ id: string; role: string; retired: boolean; jobsInProgress: number; queuedJobs: number; runtimeJobLimit: number
    cacheLimit: number; cachedTiles: number; bounds: { x: number; y: number; width: number; height: number } | null
    center: { x: number; y: number } | null; zoom: number | null; container: { width: number; height: number } }>
}

declare global {
  interface Window { __forgeViewerResourceSnapshot?: () => ResourceSnapshot }
}

async function installOverlapFixture(page: Page) {
  const state = { activeTiles: 0, maxActiveTiles: 0, tileRequests: 0, teachingTileRequests: 0, replacementTileRequests: 0, releaseTiles: () => {} }
  let release!: () => void
  const tileGate = new Promise<void>((resolve) => { release = resolve })
  state.releaseTiles = release
  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/session') return route.fulfill({ json: {}, headers: { 'X-Forge-CSRF': 'synthetic' } })
    if (url.pathname === '/api/datasets') return route.fulfill({ json: { datasets: [slide, replacementSlide] }, headers: { ETag: 'resource-budget-fixture' } })
    if (url.pathname === '/api/capabilities') return route.fulfill({ json: {
      conversionRuntime: 'Synthetic fixture', derivativeRuntime: 'Synthetic fixture', vsiConversion: false,
      dziGeneration: false, downsamples: [1, 2, 4], usableBytes: 1_000_000, effectiveCapacityBytes: 2_000_000,
    } })
    if (url.pathname === '/api/features') return route.fulfill({ json: { features: [] } })
    if (url.pathname === '/api/viewer/connection') return route.fulfill({ json: {
      connected: false, viewerUrl: '', deviceName: '', scopes: [], conversionMode: 'PREPARED_DZI_V2',
    } })
    if (url.pathname === '/api/viewer/sync') return route.fulfill({ json: { items: [], folders: [], conflicts: [] } })
    if (url.pathname === '/api/study/drafts') return route.fulfill({ json: [studyDraft] })
    if (url.pathname === '/api/analysis/runs') return route.fulfill({ json: [] })
    if (url.pathname.endsWith('/annotations')) return route.fulfill({ json: { annotations: [] } })
    if (url.pathname.endsWith('/artifacts')) return route.fulfill({ json: { currentRevision: '', approvedRevision: '', revisions: [] } })
    if (url.pathname.endsWith('/slide.dzi')) {
      return route.fulfill({ status: 200, contentType: 'application/xml', body: fixtureDzi, headers: { 'x-pathlab-preview-mode': 'direct' } })
    }
    if (url.pathname.endsWith('/thumbnail')) return route.fulfill({ path: path.join(here, 'fixtures', 'teaching-landmarks', 'landmarks_files', '9', '0_0.png') })
    const tile = url.pathname.match(/\/(\d+)\/(\d+)_(\d+)\.png$/)
    if (tile) {
      if (url.pathname.includes('/teaching-preview/')) state.teachingTileRequests++
      if (url.pathname.includes(replacementDatasetId)) state.replacementTileRequests++
      state.activeTiles++
      state.tileRequests++
      state.maxActiveTiles = Math.max(state.maxActiveTiles, state.activeTiles)
      try {
        await tileGate
        const tilePath = path.join(here, 'fixtures', 'teaching-landmarks', 'landmarks_files', tile[1], `${tile[2]}_${tile[3]}.png`)
        return await route.fulfill({ path: tilePath })
      } finally {
        state.activeTiles--
      }
    }
    return route.fulfill({ status: 404, json: { detail: `No resource fixture for ${url.pathname}` } })
  })
  return state
}

async function resourceSnapshot(page: Page) {
  return page.evaluate(() => window.__forgeViewerResourceSnapshot?.() || null)
}

function expectResourceBounds(snapshot: ResourceSnapshot) {
  expect(snapshot.viewerCount).toBeLessThanOrEqual(3)
  expect(snapshot.activeLoaderJobs).toBeLessThanOrEqual(2)
  expect(snapshot.activeLoaders).toBeLessThanOrEqual(2)
  expect(snapshot.queuedLoaderJobs).toBeGreaterThanOrEqual(0)
  expect(snapshot.maxLoaderJobs).toBe(2)
  expect(snapshot.maxCacheTiles).toBe(96)
  // OSD's maxImageCacheCount is an eviction target that can be exceeded while
  // tiles are loading/drawing. Assert configured limits and report actual
  // counts separately instead of treating that soft cap as a byte ceiling.
  expect(snapshot.cachedTiles).toBeGreaterThanOrEqual(0)
  const activeCacheOwners = snapshot.viewers.filter((viewer) => !viewer.retired)
  expect(activeCacheOwners.reduce((total, viewer) => total + viewer.cacheLimit, 0)).toBeLessThanOrEqual(96)
  expect(activeCacheOwners.every((viewer) => viewer.cacheLimit <= 32 && viewer.cachedTiles >= 0)).toBe(true)
  expect(snapshot.peaks.activeLoaderJobs).toBeLessThanOrEqual(2)
  expect(snapshot.peaks.cachedTiles).toBeGreaterThanOrEqual(0)
}

test('bounds all concurrent workspace and Study preview viewers while resize preserves main slide view', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  test.setTimeout(90_000)
  const fixture = await installOverlapFixture(page)
  const expectNoBrowserErrors = captureBrowserErrors(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/assets/')

  const workspace = page.getByTestId('forge-osd')
  const dialog = page.getByRole('dialog', { name: 'Study authoring workspace' })
  const teachingViewport = dialog.getByTestId('teaching-slide-viewport')
  const mainCanvas = workspace.locator('.openseadragon-canvas').first()
  await expect(page.locator('.forge-viewer-header').getByText(slide.displayName, { exact: true })).toBeVisible()
  await expect.poll(() => fixture.activeTiles, { timeout: 15_000 }).toBe(2)
  await expect.poll(async () => (await resourceSnapshot(page))?.viewerCount ?? 0).toBe(2)

  const canvasBox = await mainCanvas.boundingBox()
  expect(canvasBox).not.toBeNull()
  await page.mouse.move(canvasBox!.x + canvasBox!.width / 2, canvasBox!.y + canvasBox!.height / 2)
  await page.mouse.wheel(0, -360)
  await page.mouse.move(canvasBox!.x + canvasBox!.width / 2, canvasBox!.y + canvasBox!.height / 2)
  await page.mouse.down()
  await page.mouse.move(canvasBox!.x + canvasBox!.width / 2 + 54, canvasBox!.y + canvasBox!.height / 2 + 28, { steps: 5 })
  await page.mouse.up()
  await page.waitForTimeout(250)

  const viewerInstance = await workspace.getAttribute('data-viewer-instance-id')
  await page.getByRole('button', { name: 'Zoom in' }).click()
  await expect.poll(async () => {
    const snapshot = await resourceSnapshot(page)
    return snapshot?.viewers.find((viewer) => viewer.role === 'workspace-primary')?.zoom || 0
  }).toBeGreaterThan(1.01)
  const zoomedCanvasBox = await mainCanvas.boundingBox()
  expect(zoomedCanvasBox).not.toBeNull()
  await page.mouse.move(zoomedCanvasBox!.x + zoomedCanvasBox!.width / 2, zoomedCanvasBox!.y + zoomedCanvasBox!.height / 2)
  await page.mouse.down()
  await page.mouse.move(zoomedCanvasBox!.x + zoomedCanvasBox!.width / 2 + 54, zoomedCanvasBox!.y + zoomedCanvasBox!.height / 2 + 28, { steps: 5 })
  await page.mouse.up()
  await page.waitForTimeout(250)
  const before = await resourceSnapshot(page) as ResourceSnapshot | null
  expect(before).not.toBeNull()
  expectResourceBounds(before!)
  const mainBefore = before!.viewers.find((viewer) => viewer.role === 'workspace-primary')
  expect(mainBefore?.center).not.toBeNull()
  expect(mainBefore?.zoom).toBeGreaterThan(0)

  try {
    await page.getByRole('button', { name: 'Study Pack authoring' }).click()
    const preview = dialog.getByRole('article', { name: 'Exact faculty preview' })
    await expect(preview).toBeVisible()
    await preview.scrollIntoViewIfNeeded()
    await expect(teachingViewport).toBeVisible()
    await expect.poll(async () => {
      const snapshot = await resourceSnapshot(page)
      return snapshot?.viewers.find((viewer) => viewer.role === 'teaching-preview')?.center ? 1 : 0
    }).toBe(1)
    const teachingZoomBefore = ((await resourceSnapshot(page)) as ResourceSnapshot).viewers.find((viewer) => viewer.role === 'teaching-preview')!.zoom!
    await preview.getByRole('button', { name: 'Zoom teaching slide in' }).click()
    await expect.poll(async () => (await resourceSnapshot(page))?.viewerCount ?? 0).toBe(3)
    await expect.poll(async () => {
      const snapshot = await resourceSnapshot(page)
      return snapshot?.viewers.find((viewer) => viewer.role === 'teaching-preview')?.zoom ?? 0
    }).toBeGreaterThan(teachingZoomBefore + .05)
    await expect.poll(async () => (await resourceSnapshot(page))?.waitingLoaders ?? 0).toBeGreaterThan(0)
    const contended = await resourceSnapshot(page) as ResourceSnapshot
    expectResourceBounds(contended)
    expect(contended.activeLoaderJobs).toBe(2)
    expect(contended.waitingLoaders).toBeGreaterThan(0)
    expect(fixture.activeTiles).toBe(2)
    expect(contended.viewers.map((viewer) => viewer.role).sort()).toEqual(['teaching-preview', 'workspace-overview', 'workspace-primary'])
    expect(contended.viewers.reduce((total, viewer) => total + viewer.cacheLimit, 0)).toBe(96)
    expect(contended.viewers.every((viewer) => viewer.cacheLimit === 32)).toBe(true)
    expect(await page.locator('.openseadragon-container').count()).toBe(3)

    for (const viewport of [{ width: 390, height: 844 }, { width: 1440, height: 900 }]) {
      await page.setViewportSize(viewport)
      await expect(dialog).toBeVisible()
      await expect(workspace).toHaveAttribute('data-viewer-instance-id', viewerInstance!)
      const resized = await resourceSnapshot(page) as ResourceSnapshot
      expectResourceBounds(resized)
      const main = resized.viewers.find((viewer) => viewer.role === 'workspace-primary')
      expect(main?.id).toBe(mainBefore!.id)
      expect(main?.center?.x).toBeCloseTo(mainBefore!.center!.x, 3)
      expect(main?.center?.y).toBeCloseTo(mainBefore!.center!.y, 3)
      expect(main?.zoom).toBeGreaterThan(1)
      expect(main?.bounds?.width).toBeGreaterThan(0)
      expect(main?.bounds?.height).toBeGreaterThan(0)
      const documentWidths = await page.evaluate(() => ({
        client: document.documentElement.clientWidth,
        document: document.documentElement.scrollWidth,
        body: document.body.scrollWidth,
      }))
      expect(documentWidths.document).toBeLessThanOrEqual(documentWidths.client)
      expect(documentWidths.body).toBeLessThanOrEqual(documentWidths.client)
      const previewWidth = await teachingViewport.evaluate((element) => element.getBoundingClientRect().width)
      expect(previewWidth).toBeLessThanOrEqual(viewport.width)
      if (viewport.width === 1440) {
        expect(main?.zoom).toBeCloseTo(mainBefore!.zoom!, 3)
        expect(main?.bounds?.x).toBeCloseTo(mainBefore!.bounds!.x, 3)
        expect(main?.bounds?.y).toBeCloseTo(mainBefore!.bounds!.y, 3)
        expect(main?.bounds?.width).toBeCloseTo(mainBefore!.bounds!.width, 3)
        expect(main?.bounds?.height).toBeCloseTo(mainBefore!.bounds!.height, 3)
      }
      expect(fixture.maxActiveTiles).toBeLessThanOrEqual(2)
    }
  } finally {
    fixture.releaseTiles()
  }

  await expect.poll(() => fixture.activeTiles, { timeout: 20_000 }).toBe(0)
  await expect.poll(() => fixture.teachingTileRequests, { timeout: 20_000 }).toBeGreaterThan(0)
  await expect.poll(async () => {
    const snapshot = await resourceSnapshot(page)
    return snapshot && snapshot.queuedLoaderJobs === 0 && snapshot.waitingLoaders === 0 && snapshot.scheduledLoaders === 0
      ? 0 : (snapshot?.queuedLoaderJobs || 1)
  }, { timeout: 20_000 }).toBe(0)
  await expect(dialog.getByText('Exact associated teaching pixels loaded. Inspect every question location before reviewing.')).toBeVisible()
  const settled = await resourceSnapshot(page) as ResourceSnapshot
  expectResourceBounds(settled)
  expect(settled.activeLoaderJobs).toBe(0)
  expect(settled.queuedLoaderJobs).toBe(0)
  expect(settled.waitingLoaders).toBe(0)
  expect(settled.scheduledLoaders).toBe(0)
  expect(settled.peaks.activeLoaderJobs).toBeLessThanOrEqual(2)
  console.log('OSD cache configuration and observed counts', JSON.stringify({
    configured: settled.viewers.map(({ role, cacheLimit }) => ({ role, cacheLimit })),
    actualLoadedTiles: settled.viewers.map(({ role, cachedTiles }) => ({ role, cachedTiles })),
    aggregateActualLoadedTiles: settled.cachedTiles,
    peakAggregateActualLoadedTiles: settled.peaks.cachedTiles,
  }))
  expect(fixture.maxActiveTiles).toBeLessThanOrEqual(2)
  expect(await page.locator('.openseadragon-container').count()).toBe(3)
  expect(await workspace.getAttribute('data-viewer-instance-id')).toBe(viewerInstance)
  await page.screenshot({ path: testInfo.outputPath('study-preview-loaded-landmarks-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(dialog).toBeVisible()
  const phoneWidths = await page.evaluate(() => ({ client: document.documentElement.clientWidth, document: document.documentElement.scrollWidth, body: document.body.scrollWidth }))
  expect(phoneWidths.document).toBeLessThanOrEqual(phoneWidths.client)
  expect(phoneWidths.body).toBeLessThanOrEqual(phoneWidths.client)
  expect(await teachingViewport.evaluate((element) => element.getBoundingClientRect().width)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('study-preview-loaded-landmarks-phone.png') })
  expectNoBrowserErrors()
})

test('replaces the main slide while source tile requests are held and retires the old viewers cleanly', async ({ page }) => {
  test.skip(test.info().project.name !== 'chromium')
  test.setTimeout(60_000)
  const fixture = await installOverlapFixture(page)
  const expectNoBrowserErrors = captureBrowserErrors(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/assets/')

  const workspace = page.getByTestId('forge-osd')
  await expect(page.locator('.forge-viewer-header').getByText(slide.displayName, { exact: true })).toBeVisible()
  await expect.poll(() => fixture.activeTiles, { timeout: 15_000 }).toBe(2)
  const initialInstanceId = await workspace.getAttribute('data-viewer-instance-id')
  const initial = await resourceSnapshot(page) as ResourceSnapshot
  expectResourceBounds(initial)
  expect(initial.activeLoaderJobs).toBe(2)
  expect(initial.viewers.map((viewer) => viewer.role).sort()).toEqual(['workspace-overview', 'workspace-primary'])

  await page.getByRole('button', { name: /Replacement synthetic landmarks\.ome\.tif/ }).click()
  await expect(page.locator('.forge-viewer-header').getByText(replacementSlide.displayName, { exact: true })).toBeVisible()
  await expect.poll(() => workspace.getAttribute('data-viewer-instance-id')).not.toBe(initialInstanceId)
  const replacement = await resourceSnapshot(page) as ResourceSnapshot
  expectResourceBounds(replacement)
  expect(replacement.viewerCount).toBe(2)
  expect(replacement.activeLoaderJobs).toBeLessThanOrEqual(2)
  expect(replacement.viewers.filter((viewer) => !viewer.retired).map((viewer) => viewer.role).sort()).toEqual(['workspace-overview', 'workspace-primary'])
  const retiringOldPair = replacement.viewers.filter((viewer) => viewer.retired)
  expect(retiringOldPair).toHaveLength(2)
  expect(retiringOldPair.every((viewer) => viewer.jobsInProgress > 0)).toBe(true)
  expect(replacement.trackedViewerCount).toBe(4)
  expect(await page.locator('.openseadragon-container').count()).toBe(2)

  fixture.releaseTiles()
  await expect.poll(() => fixture.activeTiles, { timeout: 20_000 }).toBe(0)
  await expect.poll(() => fixture.replacementTileRequests, { timeout: 20_000 }).toBeGreaterThan(0)
  await expect.poll(async () => {
    const snapshot = await resourceSnapshot(page)
    return snapshot?.viewers.find((viewer) => viewer.role === 'workspace-primary')?.jobsInProgress ?? -1
  }, { timeout: 20_000 }).toBe(0)
  await expect.poll(async () => (await resourceSnapshot(page))?.trackedViewerCount ?? -1, { timeout: 20_000 }).toBe(2)
  const settled = await resourceSnapshot(page) as ResourceSnapshot
  expectResourceBounds(settled)
  expect(settled.activeLoaderJobs).toBe(0)
  expect(settled.queuedLoaderJobs).toBe(0)
  expect(settled.waitingLoaders).toBe(0)
  expect(settled.scheduledLoaders).toBe(0)
  expect(settled.viewers.every((viewer) => !viewer.retired)).toBe(true)
  expect(await workspace.getAttribute('data-viewer-instance-id')).not.toBe(initialInstanceId)
  expect(await page.locator('.openseadragon-container').count()).toBe(2)
  expectNoBrowserErrors()
})
