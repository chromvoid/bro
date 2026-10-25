import type { Page } from '@playwright/test'
import { freshPerson, ISOLATED } from './backend'
import { APP, caliperAction, expect, photograph, test } from './fixtures'

test.skip(!ISOLATED, 'caliper profile writes need a disposable backend (ASSISTANT_E2E_ISOLATED=1)')
test.use({ signedOut: true })

/** Profile changes must not race with other tests that edit the project's shared member. */
const PERSON = {
  prefix: 'caliper-profile',
  name: 'Калипер: синтетический профиль',
  timezone: 'Europe/Moscow',
  scopes: ['personal.read', 'personal.write'],
}

/** On the dashboard a measurement is added from the body card; records are kept in a journal panel. */
async function addMeasurement(page: Page) {
  await caliperAction(page, 'caliper-add')
}

/** The calendar day in a time zone, as the app writes it. */
function isoDay(zone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  return ['year', 'month', 'day']
    .map((type) => parts.find((part) => part.type === type)!.value)
    .join('-')
}

async function openJournal(page: Page) {
  await expect(page.getByRole('dialog', { name: 'Добавить замер' })).toBeHidden()
  await caliperAction(page, 'caliper-history-open')
  await expect(page.getByRole('dialog', { name: 'Замеры калипером' })).toBeVisible()
}

test('an incomplete historical measurement is calculated only after explicit profile completion', async ({
  page,
  baseURL,
}) => {
  await freshPerson(baseURL!, PERSON, page.request)
  const completionRequests: Array<{
    payload: { measurement_id: string }
    expected_revisions: Record<string, number>
  }> = []
  page.on('request', (request) => {
    if (request.method() !== 'POST' || !request.url().endsWith('/v1/personal/operations')) return
    const body = request.postDataJSON()
    if (body.kind === 'personal.caliper.complete') completionRequests.push(body)
  })
  await page.goto(`${APP}personal`)
  const card = page.getByTestId('caliper-card')
  await addMeasurement(page)
  await page.getByTestId('caliper-metadata-overrides').locator('summary').click()
  await page.getByLabel('Пол для расчёта', { exact: true }).selectOption('male')
  await page.getByLabel('Грудь, мм').fill('15')
  await page.getByLabel('Живот, мм').fill('25')
  await page.getByLabel('Бедро, мм').fill('20')
  await page.getByLabel('Когда измерено').fill('2025-01-01T12:00')
  await page.getByLabel('Заметка (необязательно)').fill('Возраст из исторического профиля')
  await page.getByRole('button', { name: 'Сохранить замеры', exact: true }).click()
  await openJournal(page)
  const row = card.getByTestId('caliper-record')
  await expect(row.getByTestId('caliper-incomplete')).toContainText('возраст на дату замера')
  await expect(row).not.toContainText('%')
  await expect(row.getByRole('link', { name: 'Пол в профиле' })).toHaveCount(0)
  await photograph(page, 'caliper-profile-incomplete.png')

  const readRaw = async () => {
    const response = await page.request.get(
      new URL('/v1/personal/caliper?from=2025-01-01&to=2025-01-01', baseURL!).href,
    )
    expect(response.ok(), await response.text()).toBe(true)
    return (await response.json()).records as Array<{
      id: string
      revision: number
      age_years: number | null
      measured_at: string
      timezone: string
      body_fat_percent: string | null
      folds_mm: Record<string, string>
    }>
  }
  const original = (await readRaw())[0]!
  expect(original.age_years).toBeNull()
  await page.goto(`${APP}personal/history?kind=body&period=365`)
  await expect(page.getByTestId('caliper-history-hint')).toContainText('Неполные замеры')
  await expect(page.getByTestId('body-history')).not.toContainText(
    'Возраст из исторического профиля',
  )
  await page.getByRole('button', { name: 'Ещё', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Журнал замеров', exact: true }).click()
  await row.getByRole('link', { name: 'Дата рождения в профиле', exact: true }).click()
  // The link keeps the way back to the body section of the day it was opened from (fa699aee).
  await expect(page).toHaveURL(/\/personal\/profile\?/)
  const back = new URL(page.url()).searchParams
  expect(back.get('field')).toBe('birth')
  expect(back.get('returnDay')).toBe(isoDay('Europe/Moscow'))
  expect(back.get('returnSection')).toBe('body')
  expect(back.get('returnKey')).toBeTruthy()
  await expect(page.getByLabel('Дата рождения', { exact: true })).toBeFocused()
  await page.getByLabel('Дата рождения', { exact: true }).fill('1990-06-15')
  await expect(page.getByTestId('profile-age')).not.toHaveText('—')
  await photograph(page, 'caliper-profile-page-desktop.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await photograph(page, 'caliper-profile-page-mobile.png')
  await page.getByRole('button', { name: 'Сохранить данные тела', exact: true }).click()
  await expect(page.getByText('Данные тела сохранены', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Вернуться в «Личное»', exact: true }).click()
  await openJournal(page)
  await expect(
    row.getByRole('button', { name: 'Рассчитать с данными профиля', exact: true }),
  ).toBeVisible()
  await expect(row).not.toContainText('%')
  expect(completionRequests).toEqual([])
  expect((await readRaw())[0]!.id).toBe(original.id)
  await row.getByRole('button', { name: 'Рассчитать с данными профиля', exact: true }).click()
  await expect(row).toContainText('Расчёт по исходным складкам при записи:')
  await expect(row).toContainText('%')
  await expect(row).toContainText('возраст на дату замера: 34')
  await expect(row.getByTestId('caliper-incomplete')).toHaveCount(0)
  expect(completionRequests).toHaveLength(1)
  expect(completionRequests[0]!.payload).toEqual({ measurement_id: original.id })
  expect(completionRequests[0]!.expected_revisions[original.id]).toBe(original.revision)
  const replacement = (await readRaw())[0]!
  expect(replacement.id).not.toBe(original.id)
  expect(replacement.age_years).toBe(34)
  expect(replacement.measured_at).toBe(original.measured_at)
  expect(replacement.timezone).toBe(original.timezone)
  expect(replacement.folds_mm).toEqual(original.folds_mm)
  await page.setViewportSize({ width: 390, height: 844 })
  await photograph(page, 'caliper-profile-completed-mobile.png')
})

test('editing a missing profile inside the measurement form preserves the fold draft', async ({
  page,
  baseURL,
}) => {
  await freshPerson(baseURL!, PERSON, page.request)
  const oldDay = isoDay('Europe/Moscow')
  const newZone = ['Pacific/Kiritimati', 'Pacific/Pago_Pago'].find(
    (zone) => isoDay(zone) !== oldDay,
  )!
  const newDay = isoDay(newZone)
  const overviewDays: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === '/v1/personal/overview') overviewDays.push(url.searchParams.get('date')!)
  })
  await page.goto(`${APP}personal`)
  const card = page.getByTestId('caliper-card')
  await addMeasurement(page)
  await page.getByLabel('Грудь, мм').fill('15')
  await page.getByLabel('Живот, мм').fill('25')
  await page.getByLabel('Бедро, мм').fill('20')
  await page.getByLabel('Когда измерено').fill('2025-01-01T12:00')
  await page.getByLabel('Заметка (необязательно)').fill('Складки сохраняются в черновике')
  await page
    .getByTestId('caliper-profile-notice')
    .getByRole('button', { name: 'Дата рождения в профиле', exact: true })
    .click()
  await expect(page).toHaveURL(new RegExp(`${APP}personal$`))
  await expect(page.getByLabel('Дата рождения', { exact: true })).toBeFocused()
  await page.getByLabel('Дата рождения', { exact: true }).fill('1990-06-15')
  await page.getByLabel('Пол', { exact: true }).selectOption('male')
  await page.getByLabel('Часовой пояс', { exact: true }).fill(newZone)
  await page.setViewportSize({ width: 390, height: 844 })
  await photograph(page, 'caliper-profile-nested-mobile.png')
  let releaseRefresh!: () => void
  const refreshGate = new Promise<void>((resolve) => {
    releaseRefresh = resolve
  })
  await page.route('**/v1/personal/overview?**', async (route) => {
    await refreshGate
    await route.continue()
  })
  await page.getByRole('button', { name: 'Сохранить данные тела', exact: true }).click()
  await expect(page.getByLabel('Дата рождения', { exact: true })).toHaveCount(0)
  expect(overviewDays).toContain(oldDay)
  await expect.poll(() => overviewDays).toContain(newDay)
  await expect(page.getByLabel('Грудь, мм')).toHaveValue('15')
  await expect(page.getByLabel('Живот, мм')).toHaveValue('25')
  await expect(page.getByLabel('Бедро, мм')).toHaveValue('20')
  await expect(page.getByLabel('Когда измерено')).toHaveValue('2025-01-01T12:00')
  await expect(page.getByLabel('Заметка (необязательно)')).toHaveValue(
    'Складки сохраняются в черновике',
  )
  // The query refresh is deliberately delayed: saving must still use the profile receipt's
  // fresh defaults on the server, without sending the form's stale default gender:null.
  await expect(page.getByTestId('caliper-primary').locator('input')).toHaveCount(5)
  await photograph(page, 'caliper-profile-draft-mobile.png')
  const added = page.waitForResponse(
    (response) =>
      response.url().endsWith('/v1/personal/operations') &&
      response.request().postDataJSON()?.kind === 'personal.caliper.add',
  )
  await page.getByRole('button', { name: 'Сохранить замеры', exact: true }).click()
  expect((await added).ok()).toBe(true)
  releaseRefresh()
  await openJournal(page)
  await expect(card.getByTestId('caliper-record')).toContainText(
    'Расчёт по исходным складкам при записи:',
  )
  await expect(card.getByTestId('caliper-record')).toContainText('возраст на дату замера: 34')
  const saved = await page.request.get(
    new URL('/v1/personal/caliper?from=2024-12-31&to=2025-01-02', baseURL!).href,
  )
  expect(saved.ok(), await saved.text()).toBe(true)
  const record = (await saved.json()).records[0]
  expect(record.timezone).toBe(newZone)
  expect(new Date(record.measured_at).toISOString()).toBe('2025-01-01T09:00:00.000Z')
  expect(record.gender).toBe('male')
  expect(record.age_years).toBe(34)
})
