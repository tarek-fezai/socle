// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import { defineConfig, devices } from '@playwright/test'

/**
 * Config dédiée aux specs de parité visuelle (`e2e-visual/`, docs/visual-parity.md).
 * Aucun retry : un écart instable doit échouer, pas passer au second essai.
 */
export default defineConfig({
  testDir: './e2e-visual',
  testMatch: '**/*.spec.mjs',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  timeout: 60_000,
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.01,
      animations: 'disabled',
    },
  },
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
    locale: 'fr-FR',
    colorScheme: 'light',
    deviceScaleFactor: 1,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        deviceScaleFactor: 1,
        colorScheme: 'light',
      },
    },
  ],
  webServer: [
    {
      command: 'node e2e-visual/serve-mockups.mjs',
      url: 'http://127.0.0.1:4174/Login.dc.html',
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
    },
    {
      command: 'pnpm preview --host 127.0.0.1 --port 4173',
      url: 'http://127.0.0.1:4173',
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
})
