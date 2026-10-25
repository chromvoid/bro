import type { Page } from '@playwright/test'

/** A move of a card: an item of its menu on wide screens, a button of the move sheet on a phone. */
export function moveAction(page: Page, action: string) {
  return page
    .getByRole('menuitem', { name: action, exact: true })
    .or(page.getByTestId('task-move-sheet').getByRole('button', { name: action, exact: true }))
}

/** Opens a card's «⋯» and picks `action`; from the keyboard, as toasts may cover it on a phone. */
export async function moveCard(page: Page, title: string, action: string) {
  await page.getByRole('button', { name: `Переместить «${title}»` }).press('Enter')
  await moveAction(page, action).press('Enter')
}
