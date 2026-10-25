import { defineConfig, devices } from '@playwright/test'

const port = process.env.BODY_APP_PREVIEW_PORT ?? '5191'
const origin = `http://127.0.0.1:${port}`

/** Built app + production CSP + synthetic read-only API, without the shared development stand. */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'body-visual.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 20_000 },
  outputDir: '../runtime/inbody-character/browser-results',
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: origin,
    viewport: { width: 1440, height: 1100 },
    // Headless Chromium otherwise hides its WebGPU adapter. On macOS test the
    // native Metal path; the headless default ANGLE backend rejects the canvas presentation texture.
    launchOptions: {
      args: [
        '--enable-unsafe-webgpu',
        ...(process.platform === 'darwin' ? ['--use-angle=metal'] : []),
      ],
    },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', metadata: { syntheticBody: true } }],
  webServer:
    process.env.BODY_CI_MANAGES_PREVIEW === '1'
      ? undefined
      : {
          command: `${process.env.BODY_PREVIEW_PYTHON ?? '../.venv/bin/python'} -B ../tools/inbody_character_preview/serve_personal.py --port ${port}`,
          url: `${origin}/health`,
          reuseExistingServer: !process.env.CI,
          timeout: 15_000,
        },
})
