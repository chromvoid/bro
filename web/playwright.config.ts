import { defineConfig, devices } from '@playwright/test'
import { join } from 'node:path'

// Tests that write data run only against a disposable backend (tools/web_e2e.py): the shared stand
// on :8799, or another stand named by WEB_E2E_NAME on its own WEB_E2E_PORT (one per worktree).
const isolated = process.env.ASSISTANT_E2E_ISOLATED === '1'
const stand = process.env.WEB_E2E_NAME ?? 'web-e2e'
const port = process.env.WEB_E2E_PORT ?? (stand === 'web-e2e' ? '8799' : '')
if (isolated && stand !== 'web-e2e' && (!port || port === '8799')) {
  throw new Error(`Stand ${stand} needs WEB_E2E_PORT: a free port of its own`)
}
const backend = `http://127.0.0.1:${port}`
// Otherwise the built app served by the development backend, so the real CSP and cookies apply.
const baseURL = process.env.ASSISTANT_E2E_ORIGIN ?? (isolated ? backend : 'http://127.0.0.1:8766')
// CI's browser lane (.forgejo/web-e2e.sh) runs in an image without the checkout's .venv and keeps its
// evidence where it can copy it out.
const python = process.env.WEB_E2E_PYTHON ?? '../.venv/bin/python'
const output = process.env.WEB_E2E_OUTPUT

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/members.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: output && join(output, 'report') }],
    // The nightly suite lists its failed tests from this (.forgejo/web-e2e-issue.mjs).
    ...(output ? [['json', { outputFile: join(output, 'results.json') }] as const] : []),
  ],
  outputDir: output ? join(output, 'results') : undefined,
  use: { baseURL, trace: 'retain-on-failure' },
  // A real backend under a busy machine: allow assertions more than the default five seconds.
  expect: { timeout: 10_000 },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'webkit', use: { ...devices['iPhone 15'] } },
    // The narrowest phone the family uses (OnePlus 13: 360 CSS px at 3x): the frame and screen
    // fit checks, chat/dictation, contrast, accessibility and Personal interactions run here.
    {
      name: 'phone',
      testMatch:
        /(frame|phone|status|contrast|a11y|activity|assistant(?:-presentation)?|herzring-history|personal(?:-dashboard)?)\.spec\.ts$/,
      use: { ...devices['Pixel 7'], viewport: { width: 360, height: 792 }, deviceScaleFactor: 3 },
    },
  ],
  webServer: isolated
    ? {
        // Serves the stand the WEB_E2E_* variables name: the command inherits the environment.
        command: `${python} -B ../tools/web_e2e.py serve`,
        url: `${backend}/health`,
        reuseExistingServer: true,
        timeout: 60_000,
      }
    : undefined,
})
