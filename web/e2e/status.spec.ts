import { appleShell, sentTypes } from './appleShell'
import { APP, expect, test } from './fixtures'

const RECORDING = {
  recording: true,
  queued_batches: 0,
  queued_bytes: 0,
  last_uploaded_at: null,
  error: null,
}

test.describe('the status line of the iOS app', () => {
  test.beforeEach(({ isMobile }) => {
    test.skip(!isMobile, 'the native shell runs on phones')
  })

  test('a recording and a lost connection share one line that stays while scrolling', async ({
    page,
    context,
  }) => {
    await appleShell(page, {
      capabilities: { ambient_audio: true },
      replies: {
        'ambient.account': RECORDING,
        'ambient.state': RECORDING,
        'ambient.stop': RECORDING,
      },
    })
    await page.goto(`${APP}settings`)
    const line = page.getByTestId('status-line')
    await expect(line).toHaveAttribute('data-items', 'recording')
    await context.setOffline(true)
    await expect(line).toHaveAttribute('data-items', 'recording offline')
    const top = line.getByTestId('ambient-status')
    await expect(top).toContainText('Идёт фоновая запись')
    await expect(top).toContainText('+1')

    // During a recording the line stays on screen.
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    expect((await line.boundingBox())!.y).toBeGreaterThanOrEqual(0)
    expect((await line.boundingBox())!.y).toBeLessThan(80)

    // Stop is one tap away on the line itself.
    await line.getByTestId('ambient-stop').click()
    await expect.poll(() => sentTypes(page)).toContain('ambient.stop')

    await top.click()
    const sheet = page.getByTestId('status-sheet')
    await expect(sheet.getByText('Нет соединения', { exact: true })).toBeVisible()
    await expect(sheet.getByText('Идёт фоновая запись', { exact: true })).toBeVisible()
    await page.keyboard.press('Escape')
    await context.setOffline(false)
    await expect(line).toHaveAttribute('data-items', 'recording')
  })

  test('without a recording the line scrolls away with the page', async ({ page, context }) => {
    await page.goto(`${APP}settings`)
    await expect(page.getByRole('heading', { level: 1, name: 'Настройки' })).toBeVisible()
    await context.setOffline(true)
    const line = page.getByTestId('status-line')
    await expect(line).toHaveAttribute('data-items', 'offline')
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    await expect.poll(async () => (await line.boundingBox())!.y).toBeLessThan(0)
    await context.setOffline(false)
    await expect(line).toBeHidden()
  })
})
