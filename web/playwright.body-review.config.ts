import { defineConfig, devices } from '@playwright/test'

const port = process.env.BODY_ART_REVIEW_PORT ?? '5186'
const origin = `http://127.0.0.1:${port}`

/** Actual candidate resources, rendered by the production core for artistic review. */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'body-art-review.spec.ts',
  fullyParallel: false,
  workers: 1,
  maxFailures: 1,
  timeout: 90_000,
  expect: { timeout: 30_000 },
  outputDir: '../runtime/inbody-character/art-review-results',
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    actionTimeout: 10_000,
    baseURL: origin,
    viewport: { width: 1440, height: 1100 },
    launchOptions: {
      args: [
        '--enable-unsafe-webgpu',
        ...(process.platform === 'darwin' ? ['--use-angle=metal'] : []),
      ],
    },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'on',
  },
  projects: [{ name: 'chromium', metadata: { bodyArtReview: true } }],
  webServer:
    process.env.BODY_CI_MANAGES_PREVIEW === '1'
      ? undefined
      : {
          command: `${process.env.BODY_PREVIEW_PYTHON ?? '../.venv/bin/python'} -B ../tools/inbody_character_preview/serve.py --port ${port}`,
          url: origin,
          reuseExistingServer: !process.env.CI,
          timeout: 15_000,
        },
})
