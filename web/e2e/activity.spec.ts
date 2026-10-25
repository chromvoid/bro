import type { Page } from '@playwright/test'
import { freshPerson, ISOLATED } from './backend'
import { APP, expect, overflow, photograph, test } from './fixtures'

test.skip(!ISOLATED, 'activity writes require a disposable backend')
test.use({ signedOut: true })

const PERSON = {
  prefix: 'activity',
  name: 'Синтетический дневник активности',
  timezone: 'Europe/Moscow',
  scopes: ['activity.read', 'activity.write', 'personal.read', 'personal.write'],
}

async function correctDaySteps(page: Page) {
  await page.getByTestId('day-metric-steps').click()
  await page.getByTestId('day-correct-steps').click()
  await expect(page.getByRole('dialog', { name: 'Указать шаги за день' })).toBeVisible()
}

const SYNTHETIC_DAY = '2026-10-06'
const SYNTHETIC_USER = '00000000-0000-4000-8000-000000000081'
const syntheticWalk = {
  id: '00000000-0000-4000-8000-000000000083',
  revision: 1,
  kind: 'walking',
  title: 'Синтетическая прогулка',
  date: SYNTHETIC_DAY,
  timezone: 'Europe/Moscow',
  started_at: `${SYNTHETIC_DAY}T08:00:00Z`,
  duration_minutes: 30,
  distance_km: 2,
  exercises: [],
}

type SyntheticActivityOptions = {
  steps?: 'available' | 'zero' | 'no_data' | 'conflict'
  write?: boolean
  personal?: boolean
  sessions?: (typeof syntheticWalk)[]
  device?: boolean
  failManual?: boolean
  failDevice?: boolean
}

/** Plain JSON builders stay independent of the browser runtime when Playwright collects tests. */
function syntheticWorkoutPage(from: string, to: string, includeReport: boolean) {
  const report = {
    id: 'synthetic-workout',
    revision: 1,
    source_id: 'synthetic-ring',
    source_name: 'Синтетическое кольцо',
    shape: 'workout_report',
    kind: 'running_workouts',
    local_date: SYNTHETIC_DAY,
    timezone: 'Europe/Moscow',
    start_at: `${SYNTHETIC_DAY}T07:00:00Z`,
    end_at: `${SYNTHETIC_DAY}T07:30:00Z`,
    data: { sport_type: 'running', duration_seconds: 1800, steps: 3000, distance_meters: 5000 },
  }
  return {
    from,
    to,
    timezone: 'Europe/Moscow',
    computed_at: `${SYNTHETIC_DAY}T12:00:00Z`,
    fingerprint: 'synthetic-running-page',
    daily: includeReport
      ? [
          {
            date: SYNTHETIC_DAY,
            kind: 'running_workouts',
            status: 'available',
            value: 1,
            unit: 'workouts',
            resolution: {
              status: 'available',
              fingerprint: 'synthetic-running-decision',
              resolver_version: 'synthetic-v1',
              selected: [
                {
                  id: report.id,
                  revision: report.revision,
                  source_id: report.source_id,
                  source_name: report.source_name,
                },
              ],
              alternatives: [],
              summary: { count: 1, known_duration_seconds: 1800, unknown_duration_count: 0 },
            },
          },
        ]
      : [],
    series: includeReport ? [report] : [],
    next_cursor: null,
  }
}

/** Isolated rendering states never read or write personal data, including partial API failure. */
async function syntheticActivity(page: Page, options: SyntheticActivityOptions = {}) {
  await page.clock.setFixedTime(new Date(`${SYNTHETIC_DAY}T12:00:00Z`))
  const mutations: string[] = []
  const personalReads: string[] = []
  await page.route('**/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const json = (value: unknown, status = 200) => route.fulfill({ status, json: value })
    if (request.method() !== 'GET') {
      mutations.push(path)
      return json({ error: 'synthetic_activity_does_not_write' }, 503)
    }
    if (path.startsWith('/v1/personal/')) personalReads.push(path)
    if (path === '/v1/me')
      return json({
        id: SYNTHETIC_USER,
        login: 'synthetic-activity-review',
        name: 'Синтетический пользователь',
        locale: 'ru',
        timezone: 'Europe/Moscow',
        system_admin: false,
        revision: 1,
        scopes: [
          'activity.read',
          ...(options.write === false ? [] : ['activity.write']),
          ...(options.personal === false
            ? []
            : ['personal.read', ...(options.write === false ? [] : ['personal.write'])]),
        ],
      })
    if (path === '/v1/auth/csrf') return json({ token: 'synthetic-activity-csrf' })
    // A completed fake stream reconnects and refetches every query; these states need no events.
    if (path === '/v1/me/events') return route.fulfill({ status: 503 })
    if (path === '/v1/notifications') return json({ notifications: [], unread: 0, before: null })
    if (path === '/v1/channels') return json({ channels: [] })
    if (path === '/v1/spaces') return json({ spaces: [], invitations: [] })
    if (path === '/v1/activity/sessions') {
      if (options.failManual) return json({ error: 'temporary_unavailable' }, 503)
      const kind = url.searchParams.get('kind')
      return json({
        sessions: (options.sessions ?? [syntheticWalk]).filter(
          (row) =>
            (!kind || row.kind === kind) &&
            row.date >= (url.searchParams.get('from') ?? row.date) &&
            row.date <= (url.searchParams.get('to') ?? row.date),
        ),
      })
    }
    if (path.startsWith('/v1/activity/sessions/')) {
      const id = path.slice('/v1/activity/sessions/'.length)
      const session = (options.sessions ?? [syntheticWalk]).find((row) => row.id === id)
      return session ? json({ session }) : json({ error: 'not_found' }, 404)
    }
    if (path === '/v1/activity/exercises') return json({ exercises: [] })
    if (path === '/v1/personal/steps')
      return json({ date: url.searchParams.get('date'), override: null })
    if (path === '/v1/personal/overview') {
      const date = url.searchParams.get('date') ?? SYNTHETIC_DAY
      const state = options.steps ?? 'available'
      const value = state === 'zero' ? '0' : state === 'no_data' ? null : '2400'
      const status = state === 'zero' ? 'available' : state
      const empty = (kind: string) => ({ kind, status: 'no_data', value: null, unit: 'min' })
      return json({
        date,
        timezone: 'Europe/Moscow',
        profile: { user_id: SYNTHETIC_USER, timezone: 'Europe/Moscow', revision: 1 },
        sources: [],
        preferences: {},
        metrics: {
          weight: { ...empty('weight'), unit: 'kg' },
          sleep: empty('sleep'),
          active_minutes: empty('active_minutes'),
          steps: {
            kind: 'steps',
            status,
            value,
            unit: 'steps',
            complete: false,
            resolution: {
              status:
                state === 'conflict' ? 'conflict' : state === 'no_data' ? 'no_data' : 'partial',
              fingerprint: `synthetic-steps-${date}`,
              resolver_version: 'synthetic-v1',
              selected: [],
              alternatives: [],
              ...(state === 'no_data' ? {} : { freshness: { measured_at: `${date}T08:00:00Z` } }),
              coverage: { complete: false, basis: 'daily_counter_snapshot' },
            },
          },
        },
      })
    }
    if (path === '/v1/personal/history')
      return json({
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        records: [],
        daily: [{ date: '2026-10-05', kind: 'steps', status: 'available', value: '1800' }],
      })
    if (path === '/v1/personal/resolved') {
      if (options.failDevice) return json({ error: 'temporary_unavailable' }, 503)
      const from = url.searchParams.get('from') ?? SYNTHETIC_DAY
      const to = url.searchParams.get('to') ?? SYNTHETIC_DAY
      return json(
        syntheticWorkoutPage(
          from,
          to,
          url.searchParams.get('metric') === 'running_workouts' &&
            options.device !== false &&
            SYNTHETIC_DAY >= from &&
            SYNTHETIC_DAY <= to,
        ),
      )
    }
    return json({ error: 'unmocked_activity_test_route', path }, 503)
  })
  return { mutations, personalReads }
}

test('records walks, detailed strength sets, progress and manual daily steps', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(60_000)
  await freshPerson(baseURL!, PERSON, page.request)
  await page.goto(`${APP}activity`)
  await expect(page.getByRole('heading', { level: 1, name: 'Активность' })).toBeVisible()
  await page
    .getByTestId('header-actions')
    .getByRole('button', { name: 'Добавить занятие', exact: true })
    .click()
  await page.getByLabel('Название (необязательно)').fill('Прогулка без длительности')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByRole('link', { name: /Прогулка без длительности/ })).toBeVisible()
  await page.getByRole('button', { name: 'Мои упражнения', exact: true }).click()
  await expect(page.getByRole('radio', { name: 'Упражнения', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Добавить упражнение в справочник', exact: true }).click()
  await page.getByLabel('Название упражнения').fill('Жим штанги')
  await page.getByLabel('Оборудование').fill('Штанга')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Жим штанги', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Добавить занятие', exact: true }).click()
  await page.getByLabel('Вид занятия', { exact: true }).selectOption('strength')
  await page.getByLabel('Название (необязательно)').fill('Силовая с жимом')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await page.getByRole('radio', { name: 'Журнал', exact: true }).click()
  await page.getByRole('link', { name: /Силовая с жимом/ }).click()
  await page
    .getByLabel('Выберите упражнение', { exact: true })
    .selectOption({ label: 'Жим штанги · Штанга' })
  await page.getByRole('button', { name: 'Добавить упражнение', exact: true }).click()
  await page.getByRole('button', { name: 'Добавить подход', exact: true }).click()
  await page.getByLabel('Повторения', { exact: true }).fill('8')
  await page.getByLabel('Общий вес снаряда', { exact: true }).fill('60')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByText('60 кг', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Изменить подход', exact: true }).click()
  await page.getByLabel('Общий вес снаряда', { exact: true }).fill('62,5')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByText('62,5 кг', { exact: false })).toBeVisible()
  await photograph(page, 'activity-strength-session.png')
  await page.getByRole('button', { name: 'История упражнения', exact: true }).click()
  await expect(page.getByRole('img', { name: 'История нагрузки, кг', exact: true })).toBeHidden()
  await page.getByText('Графики и все подходы', { exact: true }).click()
  await expect(page.getByRole('img', { name: 'История нагрузки, кг', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '62,5', exact: true })).toBeVisible()
  await photograph(page, 'activity-exercise-progress.png')

  await page.getByRole('radio', { name: 'Обзор', exact: true }).click()
  await correctDaySteps(page)
  await page.getByLabel('Итоговое количество шагов').fill('8000')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByTestId('day-value-steps')).toHaveText('8 000')
  await correctDaySteps(page)
  await expect(page.getByLabel('Итоговое количество шагов')).toHaveValue('8000')
  await page.getByLabel('Итоговое количество шагов').fill('8500')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await photograph(page, 'activity-overview-manual-steps.png')
  await correctDaySteps(page)
  await expect(page.getByLabel('Итоговое количество шагов')).toHaveValue('8500')
  await page.getByRole('button', { name: 'Отменить ручной итог', exact: true }).click()
  await expect(page.getByTestId('day-value-steps')).toHaveCount(0)
})

for (const delivery of ['network', '503'] as const) {
  test(`checks a lost creation response (${delivery}) before allowing a second catalog write`, async ({
    page,
    baseURL,
  }) => {
    await freshPerson(baseURL!, PERSON, page.request)
    await page.goto(`${APP}activity?tab=exercises`)
    let intercepted = false
    let allowLookup = false
    await page.route('**/v1/activity/operations/by-key/**', async (route) => {
      if (allowLookup) await route.continue()
      else
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'not_found_yet' }),
        })
    })
    await page.route('**/v1/activity/operations', async (route) => {
      if (!intercepted && route.request().postDataJSON().kind === 'activity.exercise.add') {
        intercepted = true
        const committed = await route.fetch()
        expect(committed.ok()).toBe(true)
        if (delivery === 'network') await route.abort('failed')
        else
          await route.fulfill({
            status: 503,
            contentType: 'application/json',
            body: JSON.stringify({ error: 'temporary_unavailable' }),
          })
      } else await route.continue()
    })
    await page
      .getByRole('button', { name: 'Добавить упражнение в справочник', exact: true })
      .click()
    await page.getByLabel('Название упражнения').fill('Тест потери ответа')
    await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
    await expect(page.getByTestId('unresolved-writes')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Сохранить', exact: true })).toBeDisabled()
    allowLookup = true
    await page.getByRole('button', { name: 'Проверить результат', exact: true }).click()
    await expect(page.getByTestId('unresolved-writes')).toHaveCount(0)
    await expect(page.getByLabel('Название упражнения')).toHaveCount(0)
    const response = await page.request.get(new URL('/v1/activity/exercises', baseURL!).href)
    expect(response.ok()).toBe(true)
    const data = await response.json()
    expect(
      data.exercises.filter((exercise: { name: string }) => exercise.name === 'Тест потери ответа'),
    ).toHaveLength(1)
  })
}

test('automatic receipt recovery closes a draft after a committed write returned 503', async ({
  page,
  baseURL,
}) => {
  await freshPerson(baseURL!, PERSON, page.request)
  await page.goto(`${APP}activity?tab=exercises`)
  let intercepted = false
  await page.route('**/v1/activity/operations', async (route) => {
    if (!intercepted && route.request().postDataJSON().kind === 'activity.exercise.add') {
      intercepted = true
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'temporary_unavailable' }),
      })
    } else await route.continue()
  })
  await page.getByRole('button', { name: 'Добавить упражнение в справочник', exact: true }).click()
  await page.getByLabel('Название упражнения').fill('Автоматическая проверка')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.getByLabel('Название упражнения')).toHaveCount(0)
  await expect(
    page.getByRole('heading', { name: 'Автоматическая проверка', exact: true }),
  ).toBeVisible()
  const response = await page.request.get(new URL('/v1/activity/exercises', baseURL!).href)
  expect(response.ok()).toBe(true)
  expect(
    (await response.json()).exercises.filter(
      (exercise: { name: string }) => exercise.name === 'Автоматическая проверка',
    ),
  ).toHaveLength(1)
})

test('the journal keeps its period and kind when creating a historical session', async ({
  page,
  baseURL,
}) => {
  await freshPerson(baseURL!, PERSON, page.request)
  await page.goto(`${APP}activity?tab=journal&end=2026-10-07&kind=strength`)
  await expect(page.getByTestId('activity-empty-journal')).toBeVisible()
  await expect(page.getByTestId('activity-empty-period')).toHaveCount(0)
  // «‹ 8 сент. – 7 окт. ›»: the label opens the length and the last day of the period.
  const range = page.getByTestId('activity-range')
  await expect(range).toContainText('8 сент. – 7 окт.')
  await range.click()
  await page.getByRole('radio', { name: '7 дней', exact: true }).click()
  await expect(range).toContainText('1–7 окт.')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Предыдущий период', exact: true }).click()
  await expect(range).toContainText('24–30 сент.')
  await page
    .getByRole('button', { name: 'Добавить занятие · 30 сентября', exact: true })
    .first()
    .click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('Дата', { exact: true })).toHaveValue('2026-09-30')
  await expect(dialog.getByLabel('Вид занятия', { exact: true })).toHaveValue('strength')
})

for (const state of ['zero', 'no_data'] as const) {
  test(`mobile overview distinguishes today's ${state} from yesterday's steps`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    const fake = await syntheticActivity(page, { steps: state, sessions: [], device: false })
    await page.goto(`${APP}activity`)
    const metric = page.getByTestId('day-metric-steps')
    await expect(metric).toBeInViewport({ ratio: 1 })
    if (state === 'zero') await expect(page.getByTestId('day-value-steps')).toHaveText('0')
    else {
      await expect(page.getByTestId('day-value-steps')).toHaveCount(0)
      await expect(page.getByTestId('day-status-steps')).toHaveAttribute('data-status', 'no_data')
      await expect(metric).not.toContainText('1 800')
    }
    await expect(page.getByTestId('week-day-2026-10-05')).toBeVisible()
    await expect(page.getByTestId('activity-empty-period')).toHaveCount(0)
    await expect(page.getByTestId('running-workouts-summary')).toHaveCount(0)
    expect(fake.mutations).toEqual([])
  })
}

for (const write of [true, false]) {
  test(`a conflicting decision hides the proposed step amount and respects write=${write}`, async ({
    page,
  }) => {
    const fake = await syntheticActivity(page, { steps: 'conflict', write })
    await page.goto(`${APP}activity`)
    await expect(page.getByTestId('day-value-steps')).toHaveCount(0)
    const status = page.getByTestId('day-status-steps')
    await expect(status).toHaveAttribute('data-status', 'conflict')
    await expect(status).toHaveText('Показания не согласованы')
    // A conflict inside one device is stated; the tile itself is its only control.
    const tile = page.getByTestId('day-metric-steps').locator('..')
    await expect(tile.getByRole('button')).toHaveCount(1)
    await expect(tile.getByRole('link')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Добавить занятие', exact: true })).toHaveCount(
      write ? 1 : 0,
    )
    if (!write) {
      await page.getByTestId('day-metric-steps').click()
      await expect(page.getByTestId('day-correct-steps')).toHaveCount(0)
    }
    expect(fake.mutations).toEqual([])
  })
}

test('an imported-only overview opens a read report and returns focus to its compact row', async ({
  page,
}) => {
  const fake = await syntheticActivity(page, { sessions: [] })
  await page.goto(`${APP}activity`)
  const row = page.locator('[data-testid="activity-entry-row"][data-origin="device"]')
  await expect(row).toBeVisible()
  await expect(page.getByText('За эти дни занятий нет', { exact: true })).toHaveCount(0)
  await expect(page.getByTestId('device-workout-card')).toHaveCount(0)
  await expect(page.getByTestId('running-workouts-summary')).toHaveCount(0)
  await row.click()
  const panel = page.getByRole('dialog')
  await expect(panel.getByTestId('device-workout-card')).toBeVisible()
  await expect(panel.getByRole('button', { name: 'Сохранить', exact: true })).toHaveCount(0)
  await panel.getByRole('button', { name: 'Закрыть', exact: true }).click()
  await expect(page.getByTestId('device-workout-card')).toHaveCount(0)
  await expect(row).toBeFocused()
  expect(fake.mutations).toEqual([])
})

test('a historical day is preserved when opening the step correction and session forms', async ({
  page,
}) => {
  const fake = await syntheticActivity(page)
  await page.goto(`${APP}activity?day=2026-10-03`)
  await correctDaySteps(page)
  await expect(page.getByLabel('Дата', { exact: true })).toHaveValue('2026-10-03')
  await page.getByRole('dialog').getByRole('button', { name: 'Закрыть', exact: true }).click()
  await page
    .getByTestId('header-actions')
    .getByRole('button', { name: /Добавить занятие ·/ })
    .click()
  await expect(page.getByLabel('Дата', { exact: true })).toHaveValue('2026-10-03')
  expect(fake.mutations).toEqual([])
})

test('a missing Personal permission leaves the manual journal usable without Personal reads', async ({
  page,
}) => {
  const fake = await syntheticActivity(page, { personal: false, write: false })
  await page.goto(`${APP}activity`)
  await expect(page.getByRole('link', { name: /Синтетическая прогулка/ })).toBeVisible()
  await expect(page.getByTestId('day-value-steps')).toHaveCount(0)
  await expect(page.getByTestId('activity-data-details')).toHaveCount(0)
  expect(fake.personalReads).toEqual([])
  expect(fake.mutations).toEqual([])
})

for (const unavailable of ['manual', 'device'] as const) {
  test(`a ${unavailable} API failure keeps the independent day and other journal entries visible`, async ({
    page,
  }) => {
    const fake = await syntheticActivity(page, {
      failManual: unavailable === 'manual',
      failDevice: unavailable === 'device',
    })
    await page.goto(`${APP}activity`)
    await expect(page.getByTestId('day-value-steps')).toHaveText('2 400')
    await expect(page.getByRole('alert').first()).toBeVisible()
    if (unavailable === 'manual')
      await expect(
        page.locator('[data-testid="activity-entry-row"][data-origin="device"]'),
      ).toBeVisible()
    else await expect(page.getByRole('link', { name: /Синтетическая прогулка/ })).toBeVisible()
    expect(fake.mutations).toEqual([])
  })
}

for (const viewport of [
  { width: 360, height: 800 },
  { width: 390, height: 844 },
]) {
  test(`overview at ${viewport.width}px keeps the day visible and reflows at 200% text`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport)
    const fake = await syntheticActivity(page)
    await page.goto(`${APP}activity`)
    await expect(page.locator('#boot')).toHaveCount(0)
    await expect(page.getByTestId('day-value-steps')).toHaveText('2 400')
    await expect(page.getByTestId('day-metric-steps')).toBeInViewport({ ratio: 1 })
    await expect(page.getByTestId('week-day-2026-10-05')).toHaveAttribute(
      'data-status',
      'available',
    )
    await expect.poll(() => overflow(page)).toBeLessThanOrEqual(1)
    await photograph(page, `activity-overview-${viewport.width}.png`)

    // Test CSSOM applies the full 200% size independently of the native shell's 125% cap.
    await page.evaluate(() => document.documentElement.style.setProperty('font-size', '32px'))
    await expect
      .poll(() => page.evaluate(() => getComputedStyle(document.documentElement).fontSize))
      .toBe('32px')
    await expect.poll(() => overflow(page)).toBeLessThanOrEqual(1)
    await expect(page.getByTestId('day-value-steps')).toHaveText('2 400')
    await expect(page.getByRole('radio', { name: 'Динамика', exact: true })).toBeVisible()
    await expect
      .poll(async () => {
        const bounds = await page.getByTestId(`week-day-${SYNTHETIC_DAY}`).boundingBox()
        return !!bounds && bounds.x >= 16 && bounds.x + bounds.width <= viewport.width - 16
      })
      .toBe(true)
    const dayPicker = page.getByTestId('day-label')
    const pickerBounds = await dayPicker.boundingBox()
    expect(pickerBounds).not.toBeNull()
    for (const caption of await dayPicker.locator('span').all()) {
      const bounds = await caption.boundingBox()
      expect(bounds).not.toBeNull()
      expect(bounds!.x).toBeGreaterThanOrEqual(pickerBounds!.x)
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(pickerBounds!.x + pickerBounds!.width)
    }
    for (const control of await page
      .getByTestId('activity-view')
      .locator('button:visible, [data-testid="activity-entry-row"]:visible')
      .all()) {
      const bounds = await control.boundingBox()
      expect(bounds).not.toBeNull()
      expect(Math.min(bounds!.width, bounds!.height)).toBeGreaterThanOrEqual(44)
    }
    await photograph(page, `activity-overview-${viewport.width}-text-200.png`)
    expect(fake.mutations).toEqual([])
  })
}

test('browser Back and the detail return restore the reading position from an empty overview query', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const sessions = Array.from({ length: 6 }, (_, index) => ({
    ...syntheticWalk,
    id:
      index === 3
        ? syntheticWalk.id
        : `00000000-0000-4000-8000-${String(90 + index).padStart(12, '0')}`,
    title: `Синтетическая прогулка ${index}`,
    started_at: `${SYNTHETIC_DAY}T${String(7 + index).padStart(2, '0')}:00:00Z`,
  }))
  const fake = await syntheticActivity(page, { sessions, device: false })
  await page.goto(`${APP}activity`)
  await expect(page.getByTestId('day-value-steps')).toHaveText('2 400')
  expect(new URL(page.url()).search).toBe('')
  const row = page.getByRole('link', { name: /Синтетическая прогулка 3/ })
  await row.scrollIntoViewIfNeeded()
  const offset = await row.evaluate((element) => element.getBoundingClientRect().top)
  await row.click()
  await expect(page.getByTestId('activity-session')).toBeVisible()

  await page.goBack()
  await expect(page.getByTestId('day-value-steps')).toHaveText('2 400')
  expect(new URL(page.url()).search).toBe('')
  await expect
    .poll(async () =>
      Math.abs((await row.evaluate((element) => element.getBoundingClientRect().top)) - offset),
    )
    .toBeLessThanOrEqual(8)

  await page.goForward()
  await expect(page.getByTestId('activity-session')).toBeVisible()
  await page.getByRole('link', { name: 'Назад', exact: true }).click()
  await expect(page.getByTestId('day-value-steps')).toHaveText('2 400')
  const query = new URL(page.url()).searchParams
  expect(query.get('tab')).toBe('overview')
  expect(query.get('day')).toBe(SYNTHETIC_DAY)
  await expect
    .poll(async () =>
      Math.abs((await row.evaluate((element) => element.getBoundingClientRect().top)) - offset),
    )
    .toBeLessThanOrEqual(8)
  expect(fake.mutations).toEqual([])
})

test('the movement data panel lazily exposes original reports for the selected journal period', async ({
  page,
}) => {
  const fake = await syntheticActivity(page, { write: false, sessions: [], device: false })
  const archiveRequests: {
    method: string
    from: string | null
    to: string | null
    limit: string | null
    kind: string | null
  }[] = []
  await page.route('**/v1/personal/activity-reports?**', (route) => {
    const request = route.request()
    const params = new URL(request.url()).searchParams
    archiveRequests.push({
      method: request.method(),
      from: params.get('from'),
      to: params.get('to'),
      limit: params.get('limit'),
      kind: params.get('kind'),
    })
    return route.fulfill({
      json: {
        from: params.get('from'),
        to: params.get('to'),
        items: [
          {
            id: 'synthetic-original-running-steps',
            source_id: '48d8ac71-b0a4-435c-ad84-331ea4f026bb',
            source_name: 'Synthetic ring',
            revision: 1,
            source_version: 1,
            kind: 'running_steps',
            shape: 'day_counter_snapshot',
            local_date: SYNTHETIC_DAY,
            timezone: 'Europe/Moscow',
            observed_at: `${SYNTHETIC_DAY}T08:00:00Z`,
            start_at: null,
            end_at: null,
            data: { value: '700', unit: 'steps' },
            origin: { source: 'device', record_id: 'synthetic-running-steps-snapshot' },
            quality: [],
            is_current: true,
            current_revision: 1,
            annotation_revision: 1,
            excluded: true,
            resolution_disposition: 'excluded',
          },
        ],
        next_cursor: null,
        fingerprint: 'synthetic-activity-original-reports',
      },
    })
  })
  await page.goto(`${APP}activity?tab=journal&period=7&end=${SYNTHETIC_DAY}`)
  await expect(page.getByTestId('activity-empty-journal')).toBeVisible()
  await expect(page.getByTestId('activity-report-history')).toHaveCount(0)
  // Wait for the initial reads to settle before opening the panel.
  await page.waitForLoadState('networkidle')
  expect(archiveRequests).toEqual([])

  await page.getByTestId('activity-data-details').click()
  const panel = page.getByRole('dialog', { name: 'Данные движения', exact: true })
  const archive = panel.getByTestId('activity-report-history')
  await expect(archive).toBeVisible()
  const report = archive.getByTestId('activity-report-version')
  await expect(report).toHaveCount(1)
  await expect(report.getByRole('heading', { name: 'Беговые шаги', exact: true })).toBeVisible()
  await expect(report).toContainText('Synthetic ring')
  await expect(report).toContainText('Исключено из итога')
  await expect(report).toContainText('Снимок счётчика; не складывается с предыдущими')
  await expect(report.getByRole('button', { name: 'Вернуть в итог', exact: true })).toHaveCount(0)
  expect(archiveRequests).toEqual([
    { method: 'GET', from: '2026-09-30', to: SYNTHETIC_DAY, limit: '100', kind: null },
  ])
  expect(fake.mutations).toEqual([])
})
