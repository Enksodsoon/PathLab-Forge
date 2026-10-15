import { expect, type Page } from '@playwright/test'

export function captureBrowserErrors(page: Page, isExpectedFailedResponse: (status: number, url: string) => boolean = () => false) {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(`uncaught: ${error.stack || error.message}`))
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().startsWith('Failed to load resource:')) {
      errors.push(`console.error: ${message.text()}`)
    }
  })
  page.on('response', (response) => {
    if (response.status() >= 400 && !isExpectedFailedResponse(response.status(), response.url())) {
      errors.push(`HTTP ${response.status()}: ${response.url()}`)
    }
  })
  return () => expect(errors, errors.join('\n')).toEqual([])
}
