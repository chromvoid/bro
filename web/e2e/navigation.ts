import type { Page } from '@playwright/test'

/** Opens a section beyond the tab bar: from the sidebar on a desktop, the profile sheet on a phone. */
export async function openSection(page: Page, name: string) {
  const sidebar = page.getByRole('complementary')
  if (await sidebar.isVisible()) {
    await sidebar.getByRole('link', { name, exact: true }).click()
    return
  }
  await page.getByTestId('profile-menu').filter({ visible: true }).first().click()
  await page.getByTestId('profile-sheet').getByRole('link', { name, exact: true }).click()
}
