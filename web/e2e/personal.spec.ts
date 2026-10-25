import type { Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.skip(
  !ISOLATED,
  'writes personal data: needs the disposable backend (ASSISTANT_E2E_ISOLATED=1)',
)
// Each browser acts as its own synthetic member: profile and preferences are per account.
test.use({ asMember: true })

const unique = (prefix: string) => `${prefix} ${Date.now().toString(36)}`

/** A weight with one decimal that is never whole, so its display is predictable («73,4 кг»). */
function weight(): { typed: string; shown: string; value: number } {
  const value = 60 + Math.floor(Math.random() * 30) + (1 + Math.floor(Math.random() * 9)) / 10
  const typed = value.toFixed(1).replace('.', ',')
  return { typed, shown: `${typed} кг`, value }
}

function personal(page: Page, baseURL: string) {
  const origin = new URL(baseURL).origin
  return async (kind: string, payload: object, expected: object = {}) => {
    const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
      token: string
    }
    const response = await page.request.post(`${origin}/v1/personal/operations`, {
      data: { kind: `personal.${kind}`, payload, expected_revisions: expected },
      headers: { 'X-CSRF-Token': token, 'Idempotency-Key': crypto.randomUUID(), Origin: origin },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
}

async function openProfile(page: Page) {
  await expect(page.getByTestId('dashboard-weight')).toBeVisible()
  if (page.viewportSize()!.width < 768) await page.getByTestId('personal-more').click()
  await page.locator('[data-testid="profile-open"]:visible').click()
}

async function openAllMeasurements(page: Page) {
  await expect(page.getByTestId('dashboard-weight')).toBeVisible()
  if (page.viewportSize()!.width < 768) {
    await page.getByTestId('personal-more').click()
    await page.getByRole('menuitem', { name: 'Все измерения', exact: true }).click()
  } else await page.getByRole('link', { name: 'Все измерения', exact: true }).click()
}

test('a weighing is recorded, corrected with its origin kept, excluded and restored', async ({
  page,
}) => {
  const first = weight()
  // Same whole kilograms, another non-zero tenth: never shown as a whole number.
  const tenth = Math.round((first.value % 1) * 10)
  const fixedValue = Math.floor(first.value) + ((tenth % 9) + 1) / 10
  const fixed = { value: fixedValue, typed: fixedValue.toFixed(1).replace('.', ','), shown: '' }
  fixed.shown = `${fixed.typed} кг`

  await page.goto(`${APP}personal`)
  // Nothing reports sleep for a new account; movement lives in «Активность».
  await expect(page.getByTestId('metrics-silent')).toContainText('Сон · Нет данных')
  await expect(page.getByTestId('metrics-silent')).not.toContainText('шаги')
  await page.getByRole('button', { name: 'Записать вес' }).click()
  await expect(page.getByText('Europe/Belgrade · ваше местное время')).toBeVisible()
  await page.getByLabel('Вес, кг').fill('12')
  await page.getByRole('button', { name: 'Сохранить измерение' }).click()
  await expect(page.getByText('Укажите вес от 20 до 500 кг.')).toBeVisible()
  await page.getByLabel('Вес, кг').fill(first.typed)
  await page.getByRole('button', { name: 'Сохранить измерение' }).click()
  await expect(page.getByText('Вес записан')).toBeVisible()
  await expect(page.getByTestId('weight-card')).toContainText(first.shown)
  await expect(page.getByTestId('weight-card')).toContainText('Последний вес · сегодня')

  await openAllMeasurements(page)
  await expect(page.getByRole('heading', { level: 1, name: 'История измерений' })).toBeVisible()
  // The overview opens all kinds; this lifecycle follows the resolved weight result.
  await page.getByLabel('Показатель', { exact: true }).selectOption('weight')
  const mode = page.getByRole('group', { name: 'Как показывать данные' })
  await expect(mode).toBeVisible()
  const rows = page.getByTestId('history').getByRole('listitem')
  await rows.filter({ hasText: first.shown }).first().getByRole('button').click()
  const correction = page.getByRole('dialog', { name: 'Исправить измерение', exact: true })
  const correctionSubmit = correction
    .locator('[data-slot="panel-footer"]')
    .getByRole('button', { name: 'Сохранить поправку', exact: true })
  await expect(correctionSubmit).toBeInViewport()
  await correction.getByLabel('Вес, кг').fill('')
  await correctionSubmit.click()
  await expect(correction.getByTestId('record-value')).toBeFocused()
  await expect(correction.getByTestId('record-value')).toHaveAttribute('aria-invalid', 'true')
  await expect(correction.getByRole('alert')).toContainText('Укажите значение числом.')
  await page.getByLabel('Вес, кг').fill(fixed.typed)
  await page.getByRole('button', { name: 'Сохранить поправку' }).click()
  await expect(page.getByText('Поправка сохранена')).toBeVisible()
  const corrected = rows.filter({ hasText: `было ${first.shown}` }).first()
  await expect(corrected).toContainText(fixed.shown)
  await expect(corrected).toContainText('Ваша поправка')

  await corrected.getByRole('button').click()
  await page.getByRole('button', { name: 'Не учитывать в расчётах' }).click()
  await expect(page.getByText('Измерение не учитывается')).toBeVisible()
  // Excluding the weighing removes its selected value; an empty day need not have a card.
  await expect(
    page.getByTestId('daily-resolution').locator(':scope > p').filter({ hasText: fixed.shown }),
  ).toHaveCount(0)
  await expect(corrected).toBeHidden()
  await mode.getByRole('button', { name: 'Все источники' }).click()
  await expect(corrected).toContainText('Не учитывается')

  await corrected.getByRole('button').click()
  await page.getByRole('button', { name: 'Сбросить поправку' }).click()
  await expect(page.getByText('Поправка сброшена')).toBeVisible()
  const original = rows.filter({ hasText: first.shown }).filter({ hasText: 'Не учитывается' })
  await expect(original.first()).toContainText('Введено вами')
  await original.first().getByRole('button').click()
  await page.getByRole('button', { name: 'Вернуть в расчёты' }).click()
  await expect(page.getByText('Измерение снова учитывается')).toBeVisible()

  // Restored, the original weighing is the day's result again.
  await mode.getByRole('button', { name: 'Итог' }).click()
  await expect(page.getByTestId('daily-resolution').filter({ hasText: first.shown })).toHaveCount(1)
  const counted = rows.filter({ hasText: first.shown })
  await expect(counted).toHaveCount(1)
  await expect(counted).not.toContainText('Не учитывается')
})

test('the profile keeps only what the personal model uses', async ({ page }) => {
  await page.goto(`${APP}personal`)
  // The dashboard edits the profile in a panel; /personal/profile is for links from elsewhere.
  const panel = page.getByRole('dialog', { name: 'Данные тела' })
  await openProfile(page)
  await panel.getByLabel('Рост, см').fill('300')
  await panel.getByRole('button', { name: 'Сохранить данные тела' }).click()
  await expect(panel.getByText(/Укажите рост от 50 до 260 см/)).toBeVisible()
  await expect(panel.getByTestId('profile-height')).toBeFocused()
  await expect(panel.getByTestId('profile-height')).toHaveAttribute('aria-invalid', 'true')
  await expect(
    panel.locator('[data-slot="panel-footer"]').getByRole('button', {
      name: 'Сохранить данные тела',
      exact: true,
    }),
  ).toBeInViewport()
  const height = `${170 + Math.floor(Math.random() * 20)},5`
  await panel.getByLabel('Рост, см').fill(height)
  await panel.getByLabel('Дата рождения').fill('1990-04-12')
  await panel.getByRole('button', { name: 'Сохранить данные тела' }).click()
  await expect(page.getByText('Данные тела сохранены')).toBeVisible()
  await expect(panel).toBeHidden()

  await page.reload()
  await openProfile(page)
  await expect(panel.getByTestId('profile-age')).not.toHaveText('—')
  await expect(panel.getByLabel('Рост, см')).toHaveValue(height.replace(',', '.'))
  await expect(panel.getByLabel('Дата рождения')).toHaveValue('1990-04-12')
  await expect(panel.getByLabel('Часовой пояс')).toHaveValue('Europe/Belgrade')
})

test('a registered source waits for data, switches off and on, and can be pinned', async ({
  page,
  baseURL,
}) => {
  const name = unique('Часы')
  const { source } = (await personal(page, baseURL!)('source.create', {
    name,
    kinds: ['sleep'],
  })) as { source: { id: string } }

  // The device page: nothing delivered yet, switched off and on again.
  await page.goto(`${APP}personal/sources/${source.id}`)
  await expect(page.getByTestId('source-summary')).toContainText('данных ещё нет')
  await page.getByRole('button', { name: 'Отключить', exact: true }).click()
  await expect(page.getByText('Источник отключён')).toBeVisible()
  await expect(page.getByTestId('source-summary')).toContainText('Отключён')
  await page.getByRole('button', { name: 'Включить', exact: true }).click()
  await expect(page.getByText('Источник включён')).toBeVisible()
  await expect(page.getByTestId('source-summary')).toContainText('данных ещё нет')

  // The sources screen lists it and pins it for sleep from today.
  await page.goto(`${APP}personal/sources`)
  await expect(page.getByTestId('source-devices')).toContainText(name)
  await page.getByTestId('source-row-sleep').click()
  const sheet = page.getByTestId('metric-sources-panel')
  const pin = sheet.getByRole('radio', { name: new RegExp(`Всегда ${name}`) })
  await pin.click()
  await sheet.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByText('Источник сохранён')).toBeVisible()
  await expect(page.getByTestId('source-row-sleep')).toContainText(`Всегда ${name}`)
  await page.reload()
  await page.getByTestId('source-row-sleep').click()
  await expect(sheet.getByRole('radio', { name: new RegExp(`Всегда ${name}`) })).toBeChecked()

  // Targets live apart from sources.
  await page.goto(`${APP}personal/goals`)
  const sleep = page
    .getByRole('region', { name: 'Цели' })
    .getByRole('listitem')
    .filter({ hasText: /^\s*Сон/ })
  await sleep.getByRole('button', { name: 'Изменить' }).click()
  await page.getByLabel('Цель, мин').fill('480')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByText('Цель сохранена')).toBeVisible()
  // Entered in minutes, shown in hours and minutes like every sleep duration.
  await expect(sleep).toContainText('Цель: 8 ч 0 мин')
  await page.reload()
  await expect(sleep).toContainText('Цель: 8 ч 0 мин')
})

test('a value names the devices it came from, and pinning one saves the metric order', async ({
  page,
  baseURL,
}) => {
  const operate = personal(page, baseURL!)
  const watch = (await operate('source.create', { name: unique('Браслет'), kinds: ['steps'] })) as {
    source: { id: string; name: string }
  }
  const phone = (await operate('source.create', { name: unique('Телефон'), kinds: ['steps'] })) as {
    source: { id: string; name: string }
  }
  // Two sources reporting the same day cannot be produced without import keys: the overview is
  // answered with the resolver's split between the real sources, and the pin goes to the server.
  const share = (source: { id: string; name: string }, value: number) => ({
    source_id: source.id,
    source_name: source.name,
    count: 1,
    value: String(value),
    spans: [],
  })
  await page.route('**/v1/personal/overview**', async (route) => {
    // A refetch may cancel an earlier request while it is being answered.
    const response = await route.fetch().catch(() => null)
    if (!response) return
    const body = (await response.json()) as {
      metrics: Record<string, Record<string, unknown> & { resolution?: object }>
    }
    body.metrics.steps = {
      kind: 'steps',
      status: 'available',
      value: '8000',
      unit: 'steps',
      complete: false,
      target: null,
      records: [],
      source_unavailable: false,
      resolution: {
        ...body.metrics.steps?.resolution,
        status: 'available',
        selected: [],
        alternatives: [],
        contributions: [share(phone.source, 2600), share(watch.source, 5400)],
      },
    }
    await route.fulfill({ response, json: body }).catch(() => undefined)
  })
  // Steps are a tile of the day in «Активность», not of «Личное».
  await page.goto(`${APP}activity`)
  await page.getByTestId('day-metric-steps').click()
  const line = page.getByRole('dialog', { name: 'Шаги' }).getByTestId('source-line')
  // The larger share first.
  await expect(line).toHaveText(`Источник: ${watch.source.name}, ${phone.source.name}`)
  await line.click()
  const sheet = page.getByTestId('metric-sources-panel')
  await sheet.getByRole('radio', { name: `Всегда ${phone.source.name}` }).click()
  await sheet.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByText('Источник сохранён')).toBeVisible()

  await page.unroute('**/v1/personal/overview**')
  const overview = (await (
    await page.request.get(`${new URL(baseURL!).origin}/v1/personal/overview`)
  ).json()) as {
    date: string
    resolution_policies: Array<{ metric: string; source_ids: string[]; effective_from: string }>
  }
  // The pin is saved as the metric's source order, in force from today.
  expect(overview.resolution_policies.filter((policy) => policy.metric === 'steps')).toEqual([
    expect.objectContaining({ source_ids: [phone.source.id], effective_from: overview.date }),
  ])
})
