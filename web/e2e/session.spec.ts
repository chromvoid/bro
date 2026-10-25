import { APP, expect, streamCursor, test } from './fixtures'

// Needs the development backend (npm run dev): it signs the owner in automatically.
test('signs in and follows the account change stream', async ({ page }) => {
  await page.goto(APP)
  await expect(page.getByTestId('profile-menu').filter({ visible: true })).toBeVisible()
  // The first message on a fresh stream is a resync carrying the current position.
  await expect.poll(() => streamCursor(page)).toEqual(expect.any(Number))
})
