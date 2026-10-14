import { tmpdir } from 'node:os'
import path from 'node:path'
import { defineConfig, devices } from '@playwright/test'

const outputDir = process.env.PATHLAB_FORGE_PLAYWRIGHT_OUTPUT
  || path.join(tmpdir(), 'pathlab-forge-responsive-playwright')

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.pw.ts',
  outputDir,
  fullyParallel: true,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5175',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    viewport: { width: 1280, height: 720 },
    colorScheme: 'light',
    reducedMotion: 'no-preference',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'chromium-touch', use: { ...devices['Pixel 7'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: {
    command: 'pnpm exec vite --host 127.0.0.1 --port 5175 --strictPort',
    url: 'http://127.0.0.1:5175/assets/',
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
