import { expect, test } from '@playwright/test'
import { captureBrowserErrors } from './browserErrors'

const dzi = '<Image TileSize="256" Overlap="0" Format="png" xmlns="http://schemas.microsoft.com/deepzoom/2008"><Size Width="640" Height="320" /></Image>'

test('renders non-square synthetic landmarks and records image coordinates through gutters, zoom, and resize', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')
  const expectNoBrowserErrors = captureBrowserErrors(page)
  const browserErrors: string[] = []
  page.on('pageerror', (error) => browserErrors.push(error.stack || error.message))
  page.on('console', (message) => { if (message.type() === 'error') browserErrors.push(message.text()) })
  let tileRequests = 0
  await page.route('**/api/datasets/synthetic-dataset/artifacts/synthetic-revision/teaching-preview/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/slide.dzi')) return route.fulfill({ status: 200, contentType: 'application/xml', body: dzi })
    const tile = url.pathname.match(/\/(\d+)\/(\d+)_(\d+)\.png$/)
    if (!tile) return route.fulfill({ status: 404 })
    tileRequests++
    return route.fulfill({ path: `e2e/fixtures/teaching-landmarks/landmarks_files/${tile[1]}/${tile[2]}_${tile[3]}.png` })
  })
  await page.setViewportSize({ width: 1000, height: 900 })
  const response = await page.goto('/assets/e2e/fixtures/teaching-preview.html')
  try { await expect(page.getByTestId('teaching-ready')).toHaveText('Image ready', { timeout: 20_000 }) }
  catch (error) {
    const diagnostics = { status: response?.status(), url: page.url(), browserErrors, tileRequests, body: await page.locator('body').innerText() }
    console.log('TEACHING FIXTURE DIAGNOSTICS', JSON.stringify(diagnostics))
    await testInfo.attach('teaching-preview-diagnostics.json', { body: JSON.stringify(diagnostics, null, 2), contentType: 'application/json' })
    throw error
  }
  const viewport = page.locator('.teaching-slide-viewport')
  await expect(viewport.locator('.openseadragon-container')).toHaveCount(1)
  await expect.poll(() => tileRequests).toBeGreaterThan(0)

  const imageBounds = await page.evaluate(() => {
    const viewer = window.__teachingViewer!
    const area = document.querySelector('.teaching-slide-viewport')!.getBoundingClientRect()
    const topLeft = viewer.viewport.pixelFromPoint(viewer.viewport.imageToViewportCoordinates(0, 0), true)
    const bottomRight = viewer.viewport.pixelFromPoint(viewer.viewport.imageToViewportCoordinates(640, 320), true)
    return { area: { left: area.left, top: area.top, right: area.right, bottom: area.bottom, width: area.width, height: area.height }, topLeft, bottomRight }
  })
  const hasLetterbox = imageBounds.topLeft.y > imageBounds.area.top + 2 || imageBounds.bottomRight.y < imageBounds.area.bottom - 2
    || imageBounds.topLeft.x > imageBounds.area.left + 2 || imageBounds.bottomRight.x < imageBounds.area.right - 2
  expect(hasLetterbox).toBe(true)
  const gutterPoint = imageBounds.topLeft.y > imageBounds.area.top + 2
    ? { x: (imageBounds.topLeft.x + imageBounds.bottomRight.x) / 2, y: imageBounds.area.top + 1 }
    : { x: imageBounds.area.left + 1, y: (imageBounds.topLeft.y + imageBounds.bottomRight.y) / 2 }
  await page.mouse.click(gutterPoint.x, gutterPoint.y)
  await expect(page.getByTestId('teaching-location')).toHaveText('No location selected')
  const expectLocationWithinOneSourcePixel = async (x: number, y: number) => {
    const [actualX, actualY] = (await page.getByTestId('teaching-location').innerText()).split(',').map(Number)
    expect(Math.abs(actualX - x)).toBeLessThanOrEqual(1 / 640)
    expect(Math.abs(actualY - y)).toBeLessThanOrEqual(1 / 320)
  }

  const clickImagePoint = async (x: number, y: number) => {
    const point = await page.evaluate(({ x: imageX, y: imageY }) => {
      const viewer = window.__teachingViewer!
      const imagePoint = viewer.viewport.pixelFromPoint(viewer.viewport.imageToViewportCoordinates(imageX, imageY), true)
      const rect = document.querySelector('.teaching-slide-viewport')!.getBoundingClientRect()
      return { x: rect.left + imagePoint.x, y: rect.top + imagePoint.y }
    }, { x, y })
    await page.mouse.click(point.x, point.y)
  }
  await clickImagePoint(160, 80)
  await expectLocationWithinOneSourcePixel(0.25, 0.25)
  const marker = page.getByTestId('teaching-location-marker')
  await expect(marker).toBeVisible()
  const markerBox = await marker.boundingBox()
  const redLandmark = await page.evaluate(() => {
    const viewer = window.__teachingViewer!
    const p = viewer.viewport.pixelFromPoint(viewer.viewport.imageToViewportCoordinates(160, 80), true)
    const rect = document.querySelector('.teaching-slide-viewport')!.getBoundingClientRect()
    return { x: rect.left + p.x, y: rect.top + p.y }
  })
  expect(Math.abs((markerBox!.x + markerBox!.width / 2) - redLandmark.x)).toBeLessThanOrEqual(1)
  expect(Math.abs((markerBox!.y + markerBox!.height / 2) - redLandmark.y)).toBeLessThanOrEqual(1)

  await page.getByRole('button', { name: 'Zoom teaching slide in' }).click()
  await page.waitForFunction(() => {
    const viewport = window.__teachingViewer?.viewport
    return Boolean(viewport && Math.abs(viewport.getZoom(true) - viewport.getZoom(false)) < 0.001)
  })
  await clickImagePoint(480, 240)
  await expectLocationWithinOneSourcePixel(0.75, 0.75)
  await page.screenshot({ path: testInfo.outputPath('synthetic-landmarks-zoomed.png') })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForFunction(() => {
    const viewer = window.__teachingViewer
    const element = document.querySelector('.teaching-slide-viewport')
    const size = viewer?.viewport.getContainerSize()
    return Boolean(element && size && size.x === element.clientWidth && size.y === element.clientHeight)
  })
  await clickImagePoint(320, 160)
  await expectLocationWithinOneSourcePixel(0.5, 0.5)
  await page.setViewportSize({ width: 568, height: 320 })
  await page.waitForFunction(() => {
    const viewer = window.__teachingViewer
    const element = document.querySelector('.teaching-slide-viewport')
    const size = viewer?.viewport.getContainerSize()
    return Boolean(element && size && size.x === element.clientWidth && size.y === element.clientHeight)
  })
  await clickImagePoint(160, 80)
  await expectLocationWithinOneSourcePixel(0.25, 0.25)
  expectNoBrowserErrors()
})

test('keeps synthetic landmark coordinates correct at DPR 1, 2, and 3', async ({ browser }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium')

  for (const deviceScaleFactor of [1, 2, 3]) {
    const context = await browser.newContext({
      baseURL: testInfo.project.use.baseURL as string,
      viewport: { width: 1000, height: 900 },
      deviceScaleFactor,
    })
    try {
      const page = await context.newPage()
      const expectNoBrowserErrors = captureBrowserErrors(page)
      let tileRequests = 0
      await page.route('**/api/datasets/synthetic-dataset/artifacts/synthetic-revision/teaching-preview/**', async (route) => {
        const url = new URL(route.request().url())
        if (url.pathname.endsWith('/slide.dzi')) return route.fulfill({ status: 200, contentType: 'application/xml', body: dzi })
        const tile = url.pathname.match(/\/(\d+)\/(\d+)_(\d+)\.png$/)
        if (!tile) return route.fulfill({ status: 404 })
        tileRequests++
        return route.fulfill({ path: `e2e/fixtures/teaching-landmarks/landmarks_files/${tile[1]}/${tile[2]}_${tile[3]}.png` })
      })

      await page.goto('/assets/e2e/fixtures/teaching-preview.html')
      await expect(page.getByTestId('teaching-ready')).toHaveText('Image ready', { timeout: 20_000 })
      await expect.poll(() => tileRequests).toBeGreaterThan(0)
      await expect.poll(() => page.evaluate(() => window.devicePixelRatio)).toBe(deviceScaleFactor)

      const point = await page.evaluate(() => {
        const viewer = window.__teachingViewer!
        const imagePoint = viewer.viewport.pixelFromPoint(
          viewer.viewport.imageToViewportCoordinates(320, 160),
          true,
        )
        const rect = document.querySelector('.teaching-slide-viewport')!.getBoundingClientRect()
        return { x: rect.left + imagePoint.x, y: rect.top + imagePoint.y }
      })
      await page.mouse.click(point.x, point.y)
      const [actualX, actualY] = (await page.getByTestId('teaching-location').innerText()).split(',').map(Number)
      expect(Math.abs(actualX - 0.5)).toBeLessThanOrEqual(1 / 640)
      expect(Math.abs(actualY - 0.5)).toBeLessThanOrEqual(1 / 320)
      await page.screenshot({ path: testInfo.outputPath(`synthetic-landmarks-dpr-${deviceScaleFactor}.png`) })
      expectNoBrowserErrors()
    } finally {
      await context.close()
    }
  }
})
