import type { Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test, undo } from './fixtures'

test.skip(!ISOLATED, 'writes to the diary: needs the disposable backend (ASSISTANT_E2E_ISOLATED=1)')

/**
 * A day of its own for each test. The disposable database keeps earlier runs, so days are drawn
 * from half a century to make a collision with another run's day unlikely.
 */
function isolatedDay(): string {
  const offset = Math.floor(Math.random() * 18_000) * 86_400_000
  return new Date(Date.UTC(1971, 0, 2) + offset).toISOString().slice(0, 10)
}

const previousDay = (day: string) =>
  new Date(Date.parse(`${day}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10)

/** Ledger writes through the owner's session, to arrange data a test does not exercise. */
function diary(page: Page, baseURL: string) {
  const origin = new URL(baseURL).origin
  async function operate(kind: string, payload: object, expected: object = {}) {
    const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
      token: string
    }
    const response = await page.request.post(`${origin}/v1/nutrition/operations`, {
      data: { kind, payload, expected_revisions: expected },
      headers: { 'X-CSRF-Token': token, 'Idempotency-Key': crypto.randomUUID(), Origin: origin },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
  async function product(name: string, kcal = '120') {
    return (await operate('food.create', {
      name,
      basis_unit: 'g',
      basis_quantity: '100',
      energy_kcal: kcal,
      protein_g: '10',
    })) as { snapshot_id: string; food_id: string }
  }
  return { operate, product }
}

const entries = (page: Page) => page.getByTestId('entries')

/**
 * Taps «Добавить еду». On a phone the floating button steps aside while the day is read down
 * (FoodView), and tapping an entry below the fold scrolls the page down: a person scrolls back
 * up to bring the button back, and so does the test, once no sheet is closing: a scroll made
 * while one closes does not stay.
 */
async function addFood(page: Page) {
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.getByTestId('add-food').click()
}

test('a label product is created, logged with a preview, and undone', async ({ page }) => {
  const day = isolatedDay()
  const name = `Творог ${Date.now().toString(36)}`
  await page.goto(`${APP}food?day=${day}`)
  await expect(page.getByText('Пока нет записей')).toBeVisible()
  await addFood(page)
  await page.getByRole('button', { name: 'Этикетка' }).click()
  await page.getByLabel('Название', { exact: true }).fill(name)
  await page.getByLabel('Энергия, ккал').fill('121')
  await page.getByLabel('Белки, г').fill('18')
  await page.getByRole('button', { name: 'Сохранить продукт' }).click()

  await expect(page.getByRole('heading', { name })).toBeVisible()
  await page.getByLabel(/Количество/).fill('150')
  await expect(page.getByTestId('estimate')).toContainText('182')
  await page.getByRole('button', { name: 'Записать' }).click()
  const recorded = page.getByText(`Записано: ${name} — 182 ккал`)
  await expect(recorded).toBeVisible()
  await expect(entries(page)).toContainText(name)
  await expect(page.getByTestId('day-summary')).toContainText('182')

  await undo(page, `Записано: ${name}`)
  await expect(page.getByText('Отменено')).toBeVisible()
  await expect(page.getByText('Пока нет записей')).toBeVisible()
})

test('an entry is corrected; a stale edit keeps the typed value and asks to recheck', async ({
  page,
  baseURL,
}) => {
  const { operate, product } = diary(page, baseURL!)
  const day = isolatedDay()
  const food = await product(`Кефир ${Date.now().toString(36)}`, '50')
  const logged = (await operate('entry.add_many', {
    date: day,
    items: [{ label: 'Кефир', snapshot_id: food.snapshot_id, quantity: '200', unit: 'g' }],
  })) as { applied_entries: Array<{ entry_id: string; revision: number }> }
  const [applied] = logged.applied_entries
  await page.goto(`${APP}food?day=${day}`)
  await entries(page).getByRole('button', { name: /Кефир/ }).click()
  await page.getByLabel(/Количество/).fill('250')

  // Someone else changes the entry first.
  await operate(
    'entry.update',
    { entry_id: applied!.entry_id, quantity: '300' },
    { [applied!.entry_id]: applied!.revision },
  )
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByText(/Запись уже изменилась/)).toBeVisible()
  await expect(page.getByLabel(/Количество/)).toHaveValue('250')

  await page.keyboard.press('Escape')
  await entries(page).getByRole('button', { name: /Кефир/ }).click()
  await page.getByLabel(/Количество/).fill('250')
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByText('Изменено')).toBeVisible()
  await expect(entries(page)).toContainText('250 г')
  await expect(entries(page)).toContainText('125')
})

test('goal, remaining calories, completion and moving between days', async ({ page, baseURL }) => {
  const { operate, product } = diary(page, baseURL!)
  const day = isolatedDay()
  const food = await product(`Гречка ${Date.now().toString(36)}`, '100')
  await operate('entry.add_many', {
    date: day,
    items: [{ label: 'Гречка', snapshot_id: food.snapshot_id, quantity: '300', unit: 'g' }],
  })
  await page.goto(`${APP}food?day=${day}`)
  // Goals carry forward to later days, so another run's goal may already apply here.
  await page.getByRole('button', { name: 'Ещё', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Цель на день' }).click()
  await page.getByLabel('Лимит калорий, ккал').fill('2000')
  await page.getByLabel('Белки, г').fill('20')
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByTestId('day-summary')).toContainText('из 2 000 ккал')
  await expect(page.getByTestId('calorie-balance')).toHaveText('До лимита 1 700 ккал')
  // A nutrient goal is shown next to what was eaten. Protein's goal is a minimum (as on «Личное»):
  // reaching it is a success, while a limit passed (calories below) is a warning.
  const protein = page.getByTestId('macro-protein_g')
  await expect(protein).toContainText('30 из 20 г')
  await expect(protein).toContainText('цель достигнута')
  await expect(protein.locator('[data-tone="success"]')).toHaveCount(1)
  await expect(page.getByTestId('macro-fat_g').getByRole('progressbar')).toHaveCount(0)
  // «Задать цель» is small text, but a finger 21 px above or below it still reaches it.
  const setFatGoal = page.getByRole('button', { name: 'Задать цель: Жиры' })
  const reach = await setFatGoal.evaluate((button) => {
    button.scrollIntoView({ block: 'center' })
    const box = button.getBoundingClientRect()
    const x = box.left + box.width / 2
    const middle = box.top + box.height / 2
    return [-21, 21].map((dy) => button.contains(document.elementFromPoint(x, middle + dy)))
  })
  expect(reach).toEqual([true, true])

  await page.getByRole('button', { name: 'Ещё', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Цель на день' }).click()
  await page.getByLabel('Лимит калорий, ккал').fill('250')
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByTestId('calorie-balance')).toHaveText('Сверх лимита 50 ккал')
  await expect(page.getByTestId('calorie-balance')).toHaveClass(/text-warning/)
  // The week strip shows the same day against its goal.
  await expect(
    page.getByTestId('week-strip').getByRole('button', { pressed: true }),
  ).toHaveAccessibleName(/300 из 250 ккал, сверх лимита$/)

  await page.getByRole('button', { name: 'Отметить день заполненным' }).click()
  await page.getByRole('button', { name: 'Да, всё записано' }).click()
  await expect(page.getByText('День отмечен как заполненный').first()).toBeVisible()

  await page.getByRole('button', { name: 'Предыдущий день' }).click()
  await expect(page).toHaveURL(new RegExp(`day=${previousDay(day)}`))
  await expect(page.getByText('День отмечен как заполненный')).toHaveCount(0)
})

test('a dish is scaled, its ingredient edited, saved and logged again', async ({
  page,
  baseURL,
}) => {
  const { operate, product } = diary(page, baseURL!)
  const day = isolatedDay()
  const oats = await product(`Овсянка ${Date.now().toString(36)}`, '100')
  const banana = await product(`Банан ${Date.now().toString(36)}`, '90')
  const logged = (await operate('entry.add_many', {
    date: day,
    items: [
      { label: 'Овсянка', snapshot_id: oats.snapshot_id, quantity: '200', unit: 'g' },
      { label: 'Банан', snapshot_id: banana.snapshot_id, quantity: '100', unit: 'g' },
    ],
  })) as { applied_entries: Array<{ entry_id: string; revision: number }> }
  const dishName = `Завтрак ${Date.now().toString(36)}`
  await operate(
    'dish.group',
    {
      name: dishName,
      quantity: '300',
      unit: 'g',
      entry_ids: logged.applied_entries.map((entry) => entry.entry_id),
    },
    Object.fromEntries(logged.applied_entries.map((entry) => [entry.entry_id, entry.revision])),
  )
  await page.goto(`${APP}food?day=${day}`)
  await entries(page)
    .getByRole('button', { name: new RegExp(dishName) })
    .click()
  await page.getByLabel(/Объём блюда/).fill('600')
  await page.getByRole('button', { name: 'Сохранить' }).first().click()
  await expect(entries(page)).toContainText('600 г')
  await expect(page.getByTestId('day-summary')).toContainText('580')

  await entries(page)
    .getByRole('button', { name: new RegExp(dishName) })
    .click()
  await page.getByRole('button', { name: 'Запомнить состав' }).click()
  await page.getByRole('button', { name: 'Запомнить состав' }).click()
  await expect(page.getByText(`Состав сохранён: ${dishName}`)).toBeVisible()
  await page.keyboard.press('Escape')

  // The add sheet's search finds saved dishes next to products.
  await addFood(page)
  await page.getByTestId('add-search').fill(dishName)
  await page
    .getByTestId('add-dishes')
    .getByRole('button', { name: new RegExp(`^${dishName}`) })
    .click()
  await page.getByRole('button', { name: 'Записать блюдо' }).click()
  await expect(page.getByText(new RegExp(`Записано: ${dishName}`))).toBeVisible()
})

test('a meal is logged as lunch, read under its kind and moved to dinner with an undo', async ({
  page,
  baseURL,
}) => {
  const { product } = diary(page, baseURL!)
  const day = isolatedDay()
  const name = `Борщ ${Date.now().toString(36)}`
  await product(name, '60')
  await page.goto(`${APP}food?day=${day}`)
  await addFood(page)
  await page.getByTestId('add-search').fill(name)
  await page
    .getByTestId('food-results')
    .getByRole('button', { name: new RegExp(name) })
    .click()
  // Another day than today: no kind is suggested, the person chooses one.
  const kinds = page.getByRole('radiogroup', { name: 'Приём пищи' })
  await expect(kinds.getByRole('radio', { checked: true })).toHaveCount(0)
  await kinds.getByRole('radio', { name: 'Обед' }).click()
  await page.getByRole('button', { name: 'Записать' }).click()
  const lunch = page.getByTestId('meal-kind-lunch')
  await expect(lunch.getByRole('heading', { name: 'Обед' })).toBeVisible()
  await expect(lunch).toContainText(name)

  await lunch.getByRole('button', { name: 'Сменить приём пищи' }).click()
  await page.getByRole('menuitemradio', { name: 'Ужин' }).click()
  await expect(page.getByTestId('meal-kind-dinner')).toContainText(name)
  await expect(lunch).toHaveCount(0)
  await undo(page, 'Приём пищи: Ужин')
  await expect(page.getByTestId('meal-kind-lunch')).toContainText(name)
})

test('the week of a day adds up its calories, macros and meals in the statistics', async ({
  page,
  baseURL,
}) => {
  const { operate, product } = diary(page, baseURL!)
  const day = isolatedDay()
  const food = await product(`Плов ${Date.now().toString(36)}`, '150')
  await operate('entry.add_many', {
    date: day,
    meal_kind: 'lunch',
    items: [{ label: 'Плов', snapshot_id: food.snapshot_id, quantity: '400', unit: 'g' }],
  })
  await page.goto(`${APP}food?day=${day}`)
  await page.getByRole('button', { name: 'Ещё', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Статистика' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Статистика' })).toBeVisible()
  // The statistics open on the current week; the day of this test is in another one.
  await page.goto(`${APP}food/stats?day=${day}`)
  await expect(page.getByTestId('stats-kcal')).toContainText('600')
  await expect(page.getByText('Считаются дни с записями: 1 из 7')).toBeVisible()
  await expect(page.getByTestId('stats-kinds')).toContainText('Обед')
  await page.getByRole('radio', { name: 'Месяц' }).click()
  await expect(page).toHaveURL(/period=month/)
  await expect(page.getByTestId('stats-kcal')).toContainText('600')
})

test('a draft is resolved by picking a product; remembered foods can be starred', async ({
  page,
  baseURL,
}) => {
  const { operate, product } = diary(page, baseURL!)
  const day = isolatedDay()
  const name = `Сырник ${Date.now().toString(36)}`
  await product(name, '220')
  await operate('entry.add_many', {
    date: day,
    items: [{ label: name, missing_fields: ['snapshot_id', 'quantity'] }],
  })
  await page.goto(`${APP}food?day=${day}`)
  await expect(page.getByText('Нужно уточнить')).toBeVisible()
  await page.getByRole('button', { name: 'Уточнить' }).click()
  await page
    .getByTestId('food-results')
    .getByRole('button', { name: new RegExp(name) })
    .click()
  await page.getByLabel(/Количество/).fill('150')
  await page.getByRole('button', { name: 'Записать' }).click()
  await expect(page.getByText('Нужно уточнить')).toBeHidden()
  await expect(entries(page)).toContainText(name)
  // The repeat below confirms in the same words: this confirmation goes first, so that one is
  // not mistaken for it (and its undo taken back instead).
  await expect(
    page.locator('[data-sonner-toast]').filter({ hasText: `Записано: ${name}` }),
  ).toHaveCount(0)

  // What was eaten is in «Недавнее»; «+» logs the last portion again, with an undo.
  await addFood(page)
  const recent = page.getByTestId('add-memory').getByRole('listitem').filter({ hasText: name })
  await expect(recent).toContainText('прошлый раз 150 г · 330 ккал')
  await recent.getByRole('button', { name: `Записать ещё раз: ${name}` }).click()
  await expect(page.getByText(`Записано: ${name} — 330 ккал`)).toBeVisible()
  await undo(page, `Записано: ${name} — 330 ккал`)
  await expect(page.getByText('Отменено', { exact: true })).toBeVisible()
  const star = page
    .getByRole('listitem')
    .filter({ hasText: name })
    .getByRole('button', { name: 'В избранное' })
  await star.click()
  const unstar = page
    .getByRole('listitem')
    .filter({ hasText: name })
    .getByRole('button', { name: 'Убрать из избранного' })
  await expect(unstar).toBeVisible()
  // Favourites come first in a list of 30: leave none behind for later runs.
  await unstar.click()
  await expect(star).toBeVisible()
})

test('the add button steps aside while the day is read and covers no calories at its end', async ({
  page,
  baseURL,
  isMobile,
}) => {
  test.skip(!isMobile, 'the floating button is for phones')
  const { operate, product } = diary(page, baseURL!)
  const day = isolatedDay()
  const food = await product(`Рис ${Date.now().toString(36)}`, '130')
  await operate('entry.add_many', {
    date: day,
    items: Array.from({ length: 8 }, () => ({
      label: 'Рис',
      snapshot_id: food.snapshot_id,
      quantity: '100',
      unit: 'g',
    })),
  })
  await page.goto(`${APP}food?day=${day}`)
  const dock = page.getByTestId('add-food-dock')
  const add = page.getByTestId('add-food')
  await expect(add).toBeInViewport()
  await expect(add).toHaveText('Добавить еду')
  // Reading down past the day's summary: the button steps aside.
  await page.evaluate(() => {
    const summary = document.querySelector('[data-testid="day-summary"]')!.getBoundingClientRect()
    window.scrollTo(0, window.scrollY + summary.bottom + 80)
  })
  await expect(dock).toHaveAttribute('data-hidden', 'true')
  // Back up a little: the button returns, round now that the summary is out of view.
  await page.evaluate(() => window.scrollBy(0, -30))
  await expect(dock).not.toHaveAttribute('data-hidden')
  await expect(add).toHaveAccessibleName('Добавить еду')
  await expect(add).toHaveText('')
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  await expect(dock).not.toHaveAttribute('data-hidden')
  const button = (await add.boundingBox())!
  for (const row of await entries(page).getByRole('button').all()) {
    const box = (await row.boundingBox())!
    const apart =
      box.y + box.height <= button.y ||
      button.y + button.height <= box.y ||
      box.x + box.width <= button.x ||
      button.x + button.width <= box.x
    expect(apart).toBe(true)
  }
})
