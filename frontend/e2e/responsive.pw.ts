import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { captureBrowserErrors } from './browserErrors'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const fixtureDzi = '<Image TileSize="256" Overlap="0" Format="png" xmlns="http://schemas.microsoft.com/deepzoom/2008"><Size Width="640" Height="320" /></Image>'
const slide = {
  id: 'synthetic-responsive-slide', displayName: 'North-east synthetic landmarks.ome.tif', sourceBytes: 100,
  format: 'OME_TIFF', status: 'READY', detail: 'Synthetic image ready', outputPath: '', sha256: 'synthetic',
  selectedSeries: 0, width: 640, height: 320, downsample: 1, estimatedOutputBytes: 0,
  projectedFileBytes: 0, projectedFileLowerBytes: 0, projectedFileUpperBytes: 0,
  cropX: 0, cropY: 0, cropWidth: 640, cropHeight: 320, sourceFingerprint: 'synthetic-source',
  configurationRevision: 'synthetic-configuration', currentArtifactRevision: '', approvedArtifactRevision: '',
}

async function installFixture(page: Page, testInfo?: TestInfo) {
  const tileState = { active: 0, maxActive: 0, requests: 0 }
  if (testInfo) {
    await page.addInitScript(() => {
      const originalAdd = EventTarget.prototype.addEventListener
      const originalRemove = EventTarget.prototype.removeEventListener
      const counts: Record<string, number> = {}
      const listeners = new WeakMap<EventTarget, Map<string, Set<EventListenerOrEventListenerObject>>>()
      EventTarget.prototype.addEventListener = function (type, listener, options) {
        if (this === window && listener) {
          const capture = typeof options === 'boolean' ? options : Boolean(options?.capture)
          const key = `${type}:${capture}`
          let target = listeners.get(this)
          if (!target) { target = new Map(); listeners.set(this, target) }
          let set = target.get(key)
          if (!set) { set = new Set(); target.set(key, set) }
          if (!set.has(listener)) { set.add(listener); counts[type] = (counts[type] || 0) + 1 }
        }
        return originalAdd.call(this, type, listener, options)
      }
      EventTarget.prototype.removeEventListener = function (type, listener, options) {
        if (this === window && listener) {
          const capture = typeof options === 'boolean' ? options : Boolean(options?.capture)
          const set = listeners.get(this)?.get(`${type}:${capture}`)
          if (set?.delete(listener)) counts[type] = Math.max(0, (counts[type] || 0) - 1)
        }
        return originalRemove.call(this, type, listener, options)
      }
      Object.assign(window, { __activeWindowListeners: () => ({ ...counts }) })
    })
  }
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/session') return route.fulfill({ json: {}, headers: { 'X-Forge-CSRF': 'fixture' } })
    if (url.pathname === '/api/datasets') return route.fulfill({ json: { datasets: [slide] }, headers: { ETag: 'synthetic-datasets' } })
    if (url.pathname === '/api/capabilities') return route.fulfill({ json: {
      conversionRuntime: 'Synthetic fixture', derivativeRuntime: 'Synthetic fixture', vsiConversion: false,
      dziGeneration: false, downsamples: [1, 2, 4], usableBytes: 1_000_000, effectiveCapacityBytes: 2_000_000,
    } })
    if (url.pathname === '/api/features') return route.fulfill({ json: { features: [] } })
    if (url.pathname === '/api/viewer/connection') return route.fulfill({ json: {
      connected: false, viewerUrl: '', deviceName: '', scopes: [], conversionMode: 'PREPARED_DZI_V2',
    } })
    if (url.pathname.endsWith('/annotations')) return route.fulfill({ json: { annotations: [] } })
    if (url.pathname.endsWith('/artifacts')) return route.fulfill({ json: { currentRevision: '', approvedRevision: '', revisions: [] } })
    if (url.pathname === '/api/analysis/runs') return route.fulfill({ json: [] })
    if (url.pathname.endsWith('/slide.dzi')) return route.fulfill({ status: 200, contentType: 'application/xml', body: fixtureDzi, headers: { 'x-pathlab-preview-mode': 'direct' } })
    if (url.pathname.endsWith('/thumbnail')) return route.fulfill({ path: path.join(here, 'fixtures', 'teaching-landmarks', 'landmarks_files', '9', '0_0.png') })
    const tile = url.pathname.match(/\/(\d+)\/(\d+)_(\d+)\.png$/)
    if (tile) {
      tileState.active++
      tileState.requests++
      tileState.maxActive = Math.max(tileState.maxActive, tileState.active)
      await new Promise((resolve) => setTimeout(resolve, 20))
      const tilePath = path.join(here, 'fixtures', 'teaching-landmarks', 'landmarks_files', tile[1], `${tile[2]}_${tile[3]}.png`)
      try { return await route.fulfill({ path: tilePath }) } finally { tileState.active-- }
    }
    return route.fulfill({ status: 404, json: { detail: `No synthetic fixture for ${url.pathname}` } })
  })
  return tileState
}

const fullChromiumViewports = [
  [320, 568], [390, 844], [568, 320], [667, 375], [768, 1024], [1024, 768],
  [1280, 720], [1440, 900], [1920, 1080], [2560, 1440], [3440, 1440], [3840, 2160],
  [599, 800], [600, 800], [601, 800], [959, 800], [960, 800], [961, 800],
  [1199, 800], [1200, 800], [1201, 800], [320, 320], [390, 480],
]
const crossBrowserViewports = [[390, 844], [568, 320], [768, 1024], [1440, 900]]

for (const [width, height] of fullChromiumViewports) {
  test(`reflows without document overflow at ${width}x${height}`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium' && !crossBrowserViewports.some(([w, h]) => w === width && h === height))
    const fixtureTiles = await installFixture(page)
    await page.setViewportSize({ width, height })
    const expectNoBrowserErrors = captureBrowserErrors(page)
    await page.goto('/assets/')
    await expect(page.locator('.forge-viewer-header').getByText('North-east synthetic landmarks.ome.tif', { exact: true })).toBeVisible()
    if (testInfo.project.name.startsWith('chromium') && [[320, 568], [1280, 720]].some(([w, h]) => w === width && h === height)) {
      await expect.poll(() => fixtureTiles.requests).toBeGreaterThan(0)
      await expect(page.locator('.forge-preview-loading')).toBeHidden({ timeout: 20_000 })
      await page.screenshot({ path: testInfo.outputPath(`forge-${width}x${height}.png`) })
    }
    const documentWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(documentWidth).toBeLessThanOrEqual(width)
    const layout = await page.evaluate(() => ({
      navigatorPosition: getComputedStyle(document.querySelector('.pathlab-local-navigator')!).position,
      inspectorPosition: getComputedStyle(document.querySelector('.pathlab-viewer-inspector')!).position,
      navigatorHidden: (document.querySelector('.pathlab-local-navigator') as HTMLElement).hidden,
      inspectorHidden: (document.querySelector('.pathlab-viewer-inspector') as HTMLElement).hidden,
    }))
    expect(layout.navigatorPosition).toBe(width <= 960 ? 'fixed' : 'static')
    expect(layout.inspectorPosition).toBe(width <= 1200 ? 'fixed' : 'relative')
    expect(layout.navigatorHidden).toBe(width <= 960)
    expect(layout.inspectorHidden).toBe(width <= 1200)
    if (width <= 600) {
      const header = page.locator('.forge-viewer-header')
      const headerBox = await header.boundingBox()
      const inspectorToggle = header.getByRole('button', { name: /slide inspector/ })
      await expect(inspectorToggle).toHaveAttribute('aria-label', /slide inspector/)
      const toggleBox = await inspectorToggle.boundingBox()
      expect(toggleBox).not.toBeNull()
      expect(toggleBox!.x + toggleBox!.width).toBeLessThanOrEqual(headerBox!.x + headerBox!.width + 1)
      expect(toggleBox!.height).toBeGreaterThanOrEqual(44)
    }
    expectNoBrowserErrors()
  })
}

test('preserves panel preference, focus, and navigator edits through breakpoint changes', async ({ page }) => {
  await installFixture(page)
  await page.setViewportSize({ width: 900, height: 700 })
  await page.goto('/assets/')
  const navigator = page.locator('.pathlab-local-navigator')
  const inspector = page.locator('.pathlab-viewer-inspector')
  const libraryTrigger = page.getByRole('button', { name: 'Local library' })
  await expect(libraryTrigger).toHaveAttribute('aria-expanded', 'false')
  await libraryTrigger.click()
  const search = navigator.getByPlaceholder('Search slides and folders')
  await expect(navigator.locator(':focus')).toHaveCount(1)
  await search.fill('landmarks')
  await expect(search).toBeFocused()
  await expect(navigator.getByRole('button', { name: 'Collapse slide library' })).toBeVisible()
  const openInspector = page.getByRole('button', { name: 'Open slide inspector' })
  await openInspector.click()
  await expect(navigator).toHaveAttribute('hidden', '')
  await expect(inspector).not.toHaveAttribute('hidden', '')
  await expect(inspector.locator(':focus')).toHaveCount(1)
  await page.keyboard.press('Escape')
  await expect(inspector).toHaveAttribute('hidden', '')
  await expect(openInspector).toBeFocused()

  await libraryTrigger.click()
  await expect(navigator).not.toHaveAttribute('hidden', '')
  await page.setViewportSize({ width: 1000, height: 700 })
  await expect(navigator).not.toHaveAttribute('hidden', '')
  await expect(inspector).toHaveAttribute('hidden', '')
  await page.getByRole('button', { name: 'Open slide inspector' }).click()
  await expect(inspector).not.toHaveAttribute('hidden', '')
  await expect(navigator.getByPlaceholder('Search slides and folders')).toHaveValue('landmarks')
  await inspector.getByRole('button', { name: 'Collapse slide inspector' }).click()
  await expect(inspector).toHaveAttribute('hidden', '')
  await page.setViewportSize({ width: 1201, height: 700 })
  await expect(inspector).not.toHaveAttribute('hidden', '')
  await page.setViewportSize({ width: 1200, height: 700 })
  await expect(inspector).toHaveAttribute('hidden', '')
  await expect(navigator).not.toHaveAttribute('hidden', '')
  await page.setViewportSize({ width: 1201, height: 700 })
  await expect(inspector).not.toHaveAttribute('hidden', '')
})

test('keeps one viewer and bounded loader settings through twenty alternating resizes', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  const tiles = await installFixture(page, testInfo)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/assets/')
  await expect.poll(() => tiles.requests).toBeGreaterThan(0)
  await expect(page.locator('.forge-preview-loading')).toBeHidden({ timeout: 20_000 })
  const viewer = page.getByTestId('forge-osd')
  const primaryViewer = viewer.locator(':scope > .openseadragon-container')
  const overviewViewer = viewer.locator('.navigator .openseadragon-container')
  await expect(primaryViewer).toHaveCount(1)
  await expect(overviewViewer).toHaveCount(1)
  await expect(viewer.locator('.openseadragon-container')).toHaveCount(2)
  await expect(viewer).toHaveAttribute('data-image-loader-limit', '1')
  await expect(viewer).toHaveAttribute('data-image-loader-budget', '2')
  await expect(viewer).toHaveAttribute('data-max-tile-cache-count', '32')
  await expect(viewer).toHaveAttribute('data-max-tile-cache-budget', '96')
  await expect(viewer).toHaveAttribute('data-effective-primary-loader-jobs', '1')
  await expect(viewer).toHaveAttribute('data-effective-overview-loader-jobs', '1')
  await expect(viewer).toHaveAttribute('data-effective-primary-tile-cache', '32')
  await expect(viewer).toHaveAttribute('data-effective-overview-tile-cache', '32')
  const viewerInstance = await viewer.getAttribute('data-viewer-instance-id')
  expect(viewerInstance).toBeTruthy()
  const before = await page.evaluate(() => (window as typeof window & { __activeWindowListeners: () => Record<string, number> }).__activeWindowListeners())
  for (let cycle = 0; cycle < 20; cycle++) {
    await page.setViewportSize({ width: cycle % 2 ? 1440 : 390, height: cycle % 2 ? 900 : 844 })
    await expect(primaryViewer).toHaveCount(1)
    await expect(overviewViewer).toHaveCount(1)
    await expect(viewer.locator('.openseadragon-container')).toHaveCount(2)
  }
  await expect(primaryViewer).toHaveCount(1)
  await expect(overviewViewer).toHaveCount(1)
  await expect(viewer).toHaveAttribute('data-viewer-instance-id', viewerInstance!)
  expect(tiles.active).toBe(0)
  expect(tiles.maxActive).toBeLessThanOrEqual(2)
  const after = await page.evaluate(() => (window as typeof window & { __activeWindowListeners: () => Record<string, number> }).__activeWindowListeners())
  expect(after).toEqual(before)
})

test('keeps dark theme, reduced motion, forced colors, root-font enlargement emulation, and keyboard focus usable', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  await installFixture(page)
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce', forcedColors: 'active' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/assets/')
  await page.addStyleTag({ content: 'html{font-size:200%!important} /* root-font enlargement emulation, not browser zoom */' })
  const settings = await page.evaluate(() => ({
    theme: document.documentElement.dataset.theme,
    reducedMotion: matchMedia('(prefers-reduced-motion: reduce)').matches,
    forcedColors: matchMedia('(forced-colors: active)').matches,
    width: document.documentElement.scrollWidth,
    viewportWidth: window.innerWidth,
    transition: getComputedStyle(document.querySelector('.pathlab-local-navigator')!).transitionDuration,
  }))
  expect(settings).toMatchObject({ theme: 'dark', reducedMotion: true, forcedColors: true })
  expect(settings.width).toBeLessThanOrEqual(settings.viewportWidth)
  expect(settings.transition).toBe('0s')
  await page.getByRole('button', { name: 'Local library' }).focus()
  await page.keyboard.press('Tab')
  const focused = await page.evaluate(() => {
    const active = document.activeElement as HTMLElement
    return { label: active.getAttribute('aria-label'), outline: getComputedStyle(active).outlineStyle, border: getComputedStyle(active).borderStyle }
  })
  expect(focused.label).toBe('Viewer library')
  expect(focused.outline).toBe('solid')
  expect(focused.border).toBe('solid')
})

test('supports touch, mouse, and keyboard while the phone navigator opens and closes', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-touch')
  await installFixture(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/assets/')
  expect(await page.evaluate(() => navigator.maxTouchPoints)).toBeGreaterThan(0)
  const trigger = page.getByRole('button', { name: 'Local library' })
  const triggerBox = await trigger.boundingBox()
  expect(triggerBox).not.toBeNull()
  await page.touchscreen.tap(triggerBox!.x + triggerBox!.width / 2, triggerBox!.y + triggerBox!.height / 2)
  const navigator = page.locator('#library-navigator')
  await expect(navigator).not.toHaveAttribute('hidden', '')
  await expect(navigator.locator(':focus')).toHaveCount(1)
  const close = navigator.getByRole('button', { name: 'Collapse slide library' })
  const closeBox = await close.boundingBox()
  expect(closeBox).not.toBeNull()
  expect(closeBox!.height).toBeGreaterThanOrEqual(44)
  await page.mouse.click(closeBox!.x + closeBox!.width / 2, closeBox!.y + closeBox!.height / 2)
  await expect(navigator).toHaveAttribute('hidden', '')
  await page.keyboard.press('Tab')
  expect(await page.evaluate(() => document.activeElement instanceof HTMLElement && document.activeElement.matches(':focus-visible'))).toBe(true)
})
