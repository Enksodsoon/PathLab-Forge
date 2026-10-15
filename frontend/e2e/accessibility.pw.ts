import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { captureBrowserErrors } from './browserErrors'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const dzi = '<Image TileSize="256" Overlap="0" Format="png" xmlns="http://schemas.microsoft.com/deepzoom/2008"><Size Width="640" Height="320" /></Image>'
const slide = {
  id: 'synthetic-responsive-slide', displayName: 'North-east synthetic landmarks.ome.tif', sourceBytes: 100,
  format: 'OME_TIFF', status: 'READY', detail: 'Synthetic image ready', outputPath: '', sha256: 'synthetic',
  selectedSeries: 0, width: 640, height: 320, downsample: 1, estimatedOutputBytes: 0,
  projectedFileBytes: 0, projectedFileLowerBytes: 0, projectedFileUpperBytes: 0,
  cropX: 0, cropY: 0, cropWidth: 640, cropHeight: 320, sourceFingerprint: 'synthetic-source',
  configurationRevision: 'synthetic-configuration', currentArtifactRevision: '', approvedArtifactRevision: '',
}

async function installFixture(page: Page) {
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
    if (url.pathname.endsWith('/slide.dzi')) return route.fulfill({ status: 200, contentType: 'application/xml', body: dzi, headers: { 'x-pathlab-preview-mode': 'direct' } })
    if (url.pathname.endsWith('/thumbnail')) return route.fulfill({ path: path.join(here, 'fixtures', 'teaching-landmarks', 'landmarks_files', '9', '0_0.png') })
    const tile = url.pathname.match(/\/(\d+)\/(\d+)_(\d+)\.png$/)
    if (tile) return route.fulfill({ path: path.join(here, 'fixtures', 'teaching-landmarks', 'landmarks_files', tile[1], `${tile[2]}_${tile[3]}.png`) })
    return route.fulfill({ status: 404, json: { detail: `No synthetic fixture for ${url.pathname}` } })
  })
}

for (const viewport of [{ width: 1440, height: 900, label: 'desktop' }, { width: 390, height: 844, label: 'phone' }]) {
  test(`axe scans the populated Forge workspace at ${viewport.label} size`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium')
    const expectNoBrowserErrors = captureBrowserErrors(page)
    await installFixture(page)
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto('/assets/')
    await expect(page.locator('.forge-viewer-header').getByText(slide.displayName, { exact: true })).toBeVisible()
    if (viewport.label === 'phone') {
      const header = page.locator('.forge-viewer-header')
      const headerBox = await header.boundingBox()
      const buttons = header.getByRole('button')
      for (const button of await buttons.all()) {
        await expect(button).toHaveAccessibleName(/.+/)
        const box = await button.boundingBox()
        expect(box).not.toBeNull()
        expect(box!.x + box!.width).toBeLessThanOrEqual(headerBox!.x + headerBox!.width + 1)
        expect(box!.height).toBeGreaterThanOrEqual(44)
      }
    }
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
    const report = results.violations.map((violation) => ({
      id: violation.id, impact: violation.impact, description: violation.description,
      help: violation.help, nodes: violation.nodes.map((node) => ({ target: node.target, summary: node.failureSummary })),
    }))
    await testInfo.attach(`axe-${viewport.label}.json`, { body: JSON.stringify(report, null, 2), contentType: 'application/json' })
    const severe = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    expect(severe, JSON.stringify(report, null, 2)).toEqual([])
    expectNoBrowserErrors()
  })
}
