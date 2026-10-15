import { expect, test, type Page, type Route } from '@playwright/test'
import { captureBrowserErrors } from './browserErrors'

type StateOptions = {
  datasets?: unknown[]
  connected?: boolean
  scopes?: string[]
  studyFailure?: boolean
  featureFailureOnOpen?: boolean
  holdFeatureLoadOnOpen?: boolean
}

async function installWorkspaceState(page: Page, options: StateOptions = {}) {
  const expectNoBrowserErrors = captureBrowserErrors(page, (status, responseUrl) => {
    if (status !== 503) return false
    const pathname = new URL(responseUrl).pathname
    return Boolean(options.studyFailure && pathname === '/api/study/drafts')
      || Boolean(options.featureFailureOnOpen && pathname === '/api/features')
  })
  let featuresCalls = 0
  let releaseFeatureLoad: (() => void) | undefined
  let signalBootstrapFeatures: (() => void) | undefined
  const bootstrapFeatures = new Promise<void>((resolve) => { signalBootstrapFeatures = resolve })
  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/session') return route.fulfill({ json: {}, headers: { 'X-Forge-CSRF': 'synthetic' } })
    if (url.pathname === '/api/datasets') return route.fulfill({ json: { datasets: options.datasets ?? [] }, headers: { ETag: 'workspace-state' } })
    if (url.pathname === '/api/capabilities') return route.fulfill({ json: {
      conversionRuntime: 'Synthetic fixture', derivativeRuntime: 'Synthetic fixture', vsiConversion: false,
      dziGeneration: false, downsamples: [1, 2, 4], usableBytes: 1_000_000, effectiveCapacityBytes: 2_000_000,
    } })
    if (url.pathname === '/api/viewer/connection') return route.fulfill({ json: {
      connected: options.connected ?? false, viewerUrl: options.connected ? 'https://viewer.synthetic.invalid' : '',
      deviceName: options.connected ? 'Synthetic Viewer' : '', scopes: options.scopes ?? [], conversionMode: 'PREPARED_DZI_V2',
    } })
    if (url.pathname === '/api/viewer/sync') return route.fulfill({ json: { items: [], folders: [], conflicts: [] } })
    if (url.pathname === '/api/features') {
      featuresCalls++
      if (featuresCalls >= 2) signalBootstrapFeatures?.()
      if (options.holdFeatureLoadOnOpen && featuresCalls === 3) {
        await new Promise<void>((resolve) => { releaseFeatureLoad = resolve })
      }
      if (options.featureFailureOnOpen && featuresCalls === 3) {
        return route.fulfill({ status: 503, json: { detail: 'Catalog temporarily unavailable.\nhttps://feature-catalog.synthetic.invalid/very/long/path/that/must-wrap-without-causing-horizontal-overflow' } })
      }
      return route.fulfill({ json: { features: [] } })
    }
    if (url.pathname === '/api/study/drafts') {
      if (options.studyFailure) return route.fulfill({ status: 503, json: { detail: 'Draft service unavailable.\nRetry later at https://study.synthetic.invalid/recovery/with/a/long/unbroken/identifier-that-must-wrap-in-a-short-dialog' } })
      return route.fulfill({ json: [] })
    }
    if (url.pathname === '/api/analysis/runs') return route.fulfill({ json: [] })
    if (url.pathname.endsWith('/annotations')) return route.fulfill({ json: { annotations: [] } })
    if (url.pathname.endsWith('/artifacts')) return route.fulfill({ json: { currentRevision: '', approvedRevision: '', revisions: [] } })
    return route.fulfill({ status: 404, json: { detail: `No workspace fixture for ${url.pathname}` } })
  })
  return {
    expectNoBrowserErrors,
    waitForBootstrapFeatures: () => bootstrapFeatures,
    releaseFeatureLoad: () => releaseFeatureLoad?.(),
  }
}

test('shows the empty local and connected Viewer library states without overflowing a phone', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  const fixture = await installWorkspaceState(page, { connected: true, scopes: ['library:read'] })
  await page.setViewportSize({ width: 390, height: 480 })
  await page.goto('/assets/')
  await page.getByRole('button', { name: 'Local library' }).click()
  await expect(page.getByText('No local slides')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('empty-local-library-phone.png') })

  await page.getByRole('button', { name: 'Viewer library' }).click()
  await expect(page.getByText('No private Viewer slides.')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('empty-viewer-library-phone.png') })
  fixture.expectNoBrowserErrors()
})

test('keeps Study authoring input, error, and close actions reachable in a short viewport without write permission', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  const fixture = await installWorkspaceState(page, { connected: true, scopes: ['library:read'] })
  await page.setViewportSize({ width: 390, height: 480 })
  await page.goto('/assets/')
  await page.getByRole('button', { name: 'Study Pack authoring' }).click()
  const dialog = page.getByRole('dialog', { name: 'Study authoring workspace' })
  await expect(dialog.getByRole('heading', { name: 'Study Pack authoring' })).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Refresh eligible teaching slides from Viewer' })).toHaveCount(0)
  const draftName = dialog.getByLabel('Draft name')
  await draftName.fill('Short viewport draft name stays in state')
  await page.setViewportSize({ width: 390, height: 320 })
  await expect(draftName).toHaveValue('Short viewport draft name stays in state')
  await expect(draftName).toBeFocused()
  const box = await dialog.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.y).toBeGreaterThanOrEqual(0)
  expect(box!.y + box!.height).toBeLessThanOrEqual(320)
  await dialog.getByRole('button', { name: 'Close Study authoring' }).scrollIntoViewIfNeeded()
  await expect(dialog.getByRole('button', { name: 'Close Study authoring' })).toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('study-authoring-short-phone.png'), fullPage: true })
  fixture.expectNoBrowserErrors()
})

test('keeps Study draft errors and close control reachable at 320px height', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  const fixture = await installWorkspaceState(page, { studyFailure: true })
  await page.setViewportSize({ width: 390, height: 320 })
  await page.goto('/assets/')
  await page.getByRole('button', { name: 'Study Pack authoring' }).click()
  const dialog = page.getByRole('dialog', { name: 'Study authoring workspace' })
  await expect(dialog.getByRole('alert')).toContainText('Draft service unavailable.')
  await expect(dialog.getByRole('button', { name: 'Retry loading drafts' })).toBeInViewport()
  await dialog.getByRole('button', { name: 'Close Study authoring' }).scrollIntoViewIfNeeded()
  await expect(dialog.getByRole('button', { name: 'Close Study authoring' })).toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  fixture.expectNoBrowserErrors()
})

test('covers Feature Center loading, empty, and multiline catalog error states', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  const fixture = await installWorkspaceState(page, { holdFeatureLoadOnOpen: true, featureFailureOnOpen: true })
  await page.setViewportSize({ width: 390, height: 480 })
  await page.goto('/assets/')
  await fixture.waitForBootstrapFeatures()
  await page.getByRole('button', { name: 'Feature Center' }).click()
  const dialog = page.getByRole('dialog', { name: 'Feature Center' })
  await expect(dialog.getByRole('status')).toContainText('Checking installed features…')
  fixture.releaseFeatureLoad()
  await expect(page.getByText(/Catalog temporarily unavailable\./)).toBeVisible()
  await expect(dialog.getByRole('status')).toContainText('No features are published.')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: testInfo.outputPath('feature-center-error-phone.png') })
  fixture.expectNoBrowserErrors()
})
