import { defineConfig, devices } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const output = process.env.AGENT_E2E_OUTPUT
const connection = process.env.AGENT_E2E_CONNECTION
if (!output || !connection) throw new Error('Use tools/agent_e2e.py run to start an isolated run')
const backend = JSON.parse(readFileSync(connection, 'utf8')) as { origin: string }

export default defineConfig({
  testDir: './e2e-agent',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  workers: Number(process.env.AGENT_E2E_WORKERS ?? 1),
  retries: 0,
  timeout: 600_000,
  expect: { timeout: 15_000 },
  outputDir: join(output, 'browser'),
  reporter: [
    ['list'],
    ['html', { outputFolder: join(output, 'playwright'), open: 'never' }],
    ['json', { outputFile: join(output, 'playwright.json') }],
  ],
  use: {
    baseURL: backend.origin,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'ru-RU',
    timezoneId: 'Europe/Moscow',
  },
  projects: [{ name: 'agent-chromium', use: { ...devices['Desktop Chrome'] } }],
})
