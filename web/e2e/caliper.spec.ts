import type { Locator, Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, caliperAction, expect, photograph, test } from './fixtures'

test.skip(!ISOLATED, 'caliper writes need a disposable backend (ASSISTANT_E2E_ISOLATED=1)')
test.use({ asMember: true })

/** The dashboard keeps caliper records in a journal panel and body-fat sources in its details. */
function dashboard(page: Page) {
  const journal = page.getByRole('dialog', { name: 'Замеры калипером' })
  const details = page.getByRole('dialog', { name: 'Состав тела' })
  const close = (dialog: Locator) =>
    dialog.getByRole('button', { name: 'Закрыть', exact: true }).click()
  return {
    journal,
    card: journal.getByTestId('caliper-card'),
    source: details.getByTestId('body-fat-sources'),
    async openJournal() {
      await caliperAction(page, 'caliper-history-open')
      await expect(journal).toBeVisible()
    },
    closeJournal: () => close(journal),
    /** What the details say about body-fat sources; the panel is closed again afterwards. */
    async inDetails(check: () => Promise<void>) {
      await page.getByTestId('body-details-open').click()
      await expect(details).toBeVisible()
      await check()
      await close(details)
    },
  }
}

test('caliper records preserve partial folds and expose a separate body-fat source', async ({
  page,
}) => {
  await page.goto(`${APP}personal`)
  const { journal, card, source, openJournal, closeJournal, inDetails } = dashboard(page)
  await caliperAction(page, 'caliper-add')
  await page.getByTestId('caliper-metadata-overrides').locator('summary').click()
  await page.getByLabel('Пол для расчёта').selectOption('male')
  await page.getByTestId('caliper-optional').locator('summary').click()
  await page.getByLabel('Живот, мм').fill('20,5')
  await page.getByLabel('Бок над тазом, мм').fill('15')
  await page.getByLabel('Возраст на дату замера').fill('35')
  await page.getByLabel('Заметка (необязательно)').fill('Живот и бок')
  await page.getByRole('button', { name: 'Сохранить замеры', exact: true }).click()
  await expect(page.getByRole('dialog', { name: 'Добавить замер' })).toBeHidden()
  await openJournal()
  const rows = card.getByTestId('caliper-record')
  const partial = rows.filter({ hasText: 'Живот и бок' })
  await expect(partial).toContainText('20,5 мм')
  await expect(partial).toContainText('15 мм')
  await expect(partial.getByTestId('caliper-incomplete')).toContainText('грудь, бедро')
  await closeJournal()
  await inDetails(() => expect(source).toHaveCount(0))

  await page.goto(`${APP}personal/history?kind=body&period=365`)
  const addFromHistory = page.getByRole('button', { name: 'Добавить замер калипером', exact: true })
  await expect(addFromHistory).toBeEnabled()
  await expect(page.getByTestId('body-history')).toBeVisible()
  const originalViewport = page.viewportSize()
  await photograph(page, 'caliper-history-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await photograph(page, 'caliper-history-mobile.png')
  if (originalViewport) await page.setViewportSize(originalViewport)
  await addFromHistory.click()
  await page.getByTestId('caliper-metadata-overrides').locator('summary').click()
  await page.getByLabel('Пол для расчёта').selectOption('male')
  await page.getByLabel('Грудь, мм').fill('15')
  await page.getByLabel('Живот, мм').fill('25')
  await page.getByLabel('Бедро, мм').fill('20')
  await page.getByLabel('Возраст на дату замера').fill('35')
  await page.getByLabel('Заметка (необязательно)').fill('Полный протокол')
  await page.getByRole('button', { name: 'Сохранить замеры', exact: true }).click()
  await expect(page.getByTestId('body-history')).toContainText('Калипер')
  await expect(page.getByTestId('body-history')).toContainText(/18,5\d?\s*%/)
  // The journal link opens the records on the dashboard.
  // The measurement's success toast can cover the header button on an iPhone.
  await page.getByRole('button', { name: 'Ещё', exact: true }).press('Enter')
  await page.getByRole('menuitem', { name: 'Журнал замеров', exact: true }).click()
  await expect(journal).toBeVisible()
  const full = rows.filter({ hasText: 'Полный протокол' })
  await expect(full).toContainText('Расчёт по исходным складкам при записи: 18,5 %')
  await closeJournal()
  await inDetails(async () => {
    await expect(source).toContainText('Калипер')
    await expect(source).toContainText('18,5 %')
    await expect(source).toContainText('Оценка')
  })
  await page.reload()
  await openJournal()
  await expect(rows).toHaveCount(2)
  await expect(partial).toContainText('20,5 мм')
  await closeJournal()
  await inDetails(() => expect(source).toContainText('18,5 %'))
  await openJournal()
  const method = card.getByText('Точки измерения и метод расчёта', { exact: true })
  await method.click()
  const guide = card.getByTestId('caliper-male-guide')
  await expect(guide).toContainText('Три точки мужского протокола')
  await guide.locator('img').scrollIntoViewIfNeeded()
  await expect
    .poll(() => guide.locator('img').evaluate((image: HTMLImageElement) => image.naturalWidth))
    .toBeGreaterThan(0)
  await expect(guide.getByRole('link', { name: 'Открыть схему в полном размере' })).toHaveAttribute(
    'href',
    /^\/(?!\/)/,
  )
  await photograph(page, 'caliper-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await photograph(page, 'caliper-mobile.png')
  await method.click()
  await card.getByRole('button', { name: 'Добавить замер' }).click()
  const form = page.getByRole('dialog', { name: 'Добавить замер' })
  await form.evaluate(async (dialog) => {
    await Promise.all(
      dialog
        .getAnimations({ subtree: true })
        .map((animation) => animation.finished.catch(() => undefined)),
    )
  })
  if (page.context().browser()?.browserType().name() === 'chromium')
    await page.screenshot({
      path: test.info().outputPath('caliper-form-mobile.png'),
      animations: 'disabled',
    })
  await form.getByRole('button', { name: 'Закрыть', exact: true }).click()
  await expect(form).toBeHidden()

  await full.getByRole('button', { name: /^Удалить замер:/ }).click()
  await card
    .getByTestId('caliper-delete-confirm')
    .getByRole('button', { name: 'Удалить замер', exact: true })
    .click()
  await expect(rows).toHaveCount(1)
  await expect(partial).toContainText('20,5 мм')
  await closeJournal()
  await inDetails(() => expect(source).toHaveCount(0))
})
