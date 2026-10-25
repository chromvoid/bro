import type { Locator, Page } from '@playwright/test'
import { APP, expect, photograph, test } from './fixtures'

/** Every API response is synthetic; this suite neither needs nor writes family data. */
const TODAY = '2026-10-05'
const USER = '00000000-0000-4000-8000-000000000071'
const SOURCE = '00000000-0000-4000-8000-000000000072'

function shift(day: string, offset: number) {
  const value = new Date(`${day}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() + offset)
  return value.toISOString().slice(0, 10)
}

function observation(kind: string, value: string | null, day = TODAY) {
  return {
    id: `${day}-${kind}`,
    source_id: SOURCE,
    source_name: 'Синтетический InBody',
    kind,
    start_at: `${day}T08:00:00Z`,
    end_at: `${day}T08:00:00Z`,
    timezone: 'UTC',
    local_date: day,
    method: 'device_report',
    data: { value },
    origin: { provider: 'DDX Fitness', device: 'InBody', record_id: `scan-${day}` },
    revision: 1,
  }
}

const scan = (day: string, weight: string, fat: string, muscle: string) => [
  observation('weight', weight, day),
  observation('body_fat_percent', fat, day),
  observation('skeletal_muscle_mass', muscle, day),
  observation('body_fat_mass', '16.5', day),
  observation('body_water', '43.8', day),
]
const records = [
  ...scan('2026-10-02', '75.4', '23.0', '32.4'),
  ...scan(TODAY, '74.2', '22.3', '32.8'),
]
const profile = {
  user_id: USER,
  timezone: 'UTC',
  birth_date: '1990-04-12',
  height_cm: '176',
  gender: 'male',
  gender_origin: 'manual',
  revision: 1,
}
const sources = [
  {
    id: SOURCE,
    name: 'Синтетический InBody',
    kinds: ['weight', 'body_fat_percent', 'skeletal_muscle_mass', 'body_water', 'body_fat_mass'],
    status: 'connected',
    last_received_at: `${TODAY}T08:00:00Z`,
    revision: 1,
  },
]
const metric = (kind: string, value: string | null, unit: string) => ({
  kind,
  status: value === null ? 'no_data' : 'available',
  value,
  unit,
  target: null,
  complete: value !== null,
  records:
    value === null
      ? []
      : records.filter((record) => record.kind === kind && record.local_date === TODAY),
})

function nutritionDay(date: string) {
  const knownProtein = date === '2026-10-03' ? '80' : '120'
  const proteinComplete = date !== '2026-10-03'
  const energy = date === '2026-10-04' ? '2050' : '2000'
  return {
    date,
    logged: true,
    energy_kcal: energy,
    energy_complete: true,
    protein_g: knownProtein,
    protein_complete: proteinComplete,
    fat_g: '65',
    carbohydrate_g: '220',
    completed: true,
    target: '2000',
    macro_targets: { protein_g: '120' },
    kcal_by_kind: { lunch: energy },
  }
}

type DashboardOptions = {
  records?: ReturnType<typeof observation>[]
  metrics?: Record<string, unknown>
  operationStatus?: number
  beforeOverview?: (day: string) => Promise<void>
}

async function dashboardSession(
  page: Page,
  write = true,
  nutrition = true,
  fresh = false,
  options: DashboardOptions = {},
) {
  await page.clock.setFixedTime(new Date(`${TODAY}T12:00:00Z`))
  const mutations: string[] = []
  const nutritionReads: string[] = []
  const writes: Array<{
    kind: string
    payload: Record<string, unknown>
    expected_revisions: Record<string, number>
  }> = []
  const fixtureRecords = options.records ?? records
  await page.route('**/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const json = (value: unknown, status = 200) => route.fulfill({ status, json: value })
    if (request.method() !== 'GET') {
      mutations.push(path)
      if (path === '/v1/personal/operations') writes.push(request.postDataJSON())
      return json({ error: 'synthetic_dashboard_does_not_write' }, options.operationStatus ?? 503)
    }
    if (path === '/v1/me')
      return json({
        id: USER,
        login: 'dashboard-layout-test',
        name: 'Синтетический пользователь',
        locale: 'ru',
        timezone: 'UTC',
        system_admin: false,
        revision: 1,
        scopes: [
          'personal.read',
          ...(write ? ['personal.write'] : []),
          ...(nutrition ? ['nutrition.read', ...(write ? ['nutrition.write'] : [])] : []),
        ],
      })
    if (path === '/v1/auth/csrf') return json({ token: 'synthetic-dashboard-csrf' })
    // A completed fake stream reconnects and refetches every query; these states need no events.
    if (path === '/v1/me/events') return route.fulfill({ status: 503 })
    if (path === '/v1/notifications') return json({ notifications: [], unread: 0, before: null })
    if (path === '/v1/channels') return json({ channels: [] })
    if (path === '/v1/spaces') return json({ spaces: [], invitations: [] })
    if (path === '/v1/personal/overview') {
      await options.beforeOverview?.(url.searchParams.get('date') ?? TODAY)
      return json({
        date: url.searchParams.get('date') ?? TODAY,
        timezone: 'UTC',
        profile: fresh
          ? { ...profile, birth_date: null, height_cm: null, gender: null, gender_origin: 'unset' }
          : profile,
        sources: fresh ? [] : sources,
        preferences: {},
        metrics: {
          weight: metric('weight', fresh ? null : '74.2', 'kg'),
          body_fat_percent: metric('body_fat_percent', fresh ? null : '22.3', '%'),
          skeletal_muscle_mass: metric('skeletal_muscle_mass', fresh ? null : '32.8', 'kg'),
          body_fat_mass: metric('body_fat_mass', fresh ? null : '16.5', 'kg'),
          body_water: metric('body_water', fresh ? null : '43.8', 'kg'),
          steps: metric('steps', null, 'steps'),
          sleep: metric('sleep', null, 'min'),
          active_minutes: metric('active_minutes', null, 'min'),
          nutrition: null,
          ...options.metrics,
        },
      })
    }
    if (path === '/v1/personal/history') {
      const kind = url.searchParams.get('kind')
      const filtered = fresh
        ? []
        : kind && kind !== 'body'
          ? fixtureRecords.filter((record) => record.kind === kind)
          : fixtureRecords
      return json({
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        records: filtered,
        sources: fresh ? [] : sources,
        daily: filtered.map((record) => ({
          date: record.local_date,
          kind: record.kind,
          status: 'available',
          value: record.data.value,
          target: null,
        })),
        truncated: false,
      })
    }
    if (path === '/v1/personal/caliper') return json({ records: [], truncated: false, limit: 100 })
    // The figure waits for the account's character before loading a model.
    if (path === '/v1/personal/body/presentation')
      return json({ character_id: 'sculpture', revision: 0 })
    // No ring among the sources: its day reports nothing, and the overview shows no ring card.
    // Unanswered, the card switched between loading and error on every resync and moved the
    // sections below it under a click.
    if (path === '/v1/personal/resolved')
      return json({
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        timezone: 'UTC',
        computed_at: `${TODAY}T12:00:00Z`,
        daily: [],
        series: [],
        next_cursor: null,
        fingerprint: 'synthetic-dashboard',
        policies: [],
      })
    if (path === '/v1/nutrition/range') {
      nutritionReads.push(path)
      const start = url.searchParams.get('start')!
      const end = url.searchParams.get('end')!
      const days = []
      for (let date = start; date <= end; date = shift(date, 1)) {
        const summary = nutritionDay(date)
        days.push(
          fresh
            ? {
                ...summary,
                logged: false,
                completed: false,
                energy_kcal: '0',
                protein_g: '0',
                fat_g: '0',
                carbohydrate_g: '0',
                target: null,
                macro_targets: null,
                kcal_by_kind: {},
              }
            : summary,
        )
      }
      return json({ days })
    }
    if (path === '/v1/nutrition/day') {
      nutritionReads.push(path)
      const date = url.searchParams.get('date') ?? TODAY
      const summary = nutritionDay(date)
      const entry = {
        id: `synthetic-meal-${date}`,
        revision: 1,
        quantity: '1',
        unit: 'serving',
        local_date: date,
        name: 'Синтетический рацион',
        nutrients: {
          energy_kcal: summary.energy_kcal,
          protein_g: summary.protein_g,
          fat_g: summary.fat_g,
          carbohydrate_g: summary.carbohydrate_g,
        },
      }
      return json({
        day_revision: 1,
        completed: !fresh,
        date,
        entries: fresh ? [] : [entry],
        items: fresh ? [] : [entry],
        totals: {
          energy_kcal: { known: fresh ? '0' : summary.energy_kcal, complete: true },
          protein_g: { known: fresh ? '0' : summary.protein_g, complete: summary.protein_complete },
          fat_g: { known: fresh ? '0' : summary.fat_g, complete: true },
          carbohydrate_g: { known: fresh ? '0' : summary.carbohydrate_g, complete: true },
        },
        goal: fresh
          ? null
          : {
              id: `goal-${date}`,
              calorie_target: '2000',
              effective_local_date: date,
              revision: 1,
              macro_targets: { protein_g: '120' },
            },
        remaining_kcal: fresh ? null : String(2000 - Number(summary.energy_kcal)),
        remaining_complete: !fresh,
        drafts: [],
      })
    }
    return json({ error: 'unmocked_dashboard_test_route', path }, 503)
  })
  return { mutations, nutritionReads, writes }
}

async function fitsPage(page: Page) {
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      ),
    )
    .toBeLessThanOrEqual(1)
}

async function focusedFieldError(field: Locator) {
  await expect(field).toBeFocused()
  await expect(field).toHaveAttribute('aria-invalid', 'true')
  expect(
    await field.evaluate((element) => {
      const ids = element.getAttribute('aria-describedby')?.split(/\s+/) ?? []
      return ids.some(
        (id) => element.ownerDocument.getElementById(id)?.getAttribute('role') === 'alert',
      )
    }),
  ).toBe(true)
}

async function scrollFormBody(panel: Locator) {
  const header = panel.locator('[data-slot="sheet-header"]')
  const footer = panel.locator('[data-slot="panel-footer"]')
  const body = panel.locator('[data-slot="panel-body"]')
  await header.scrollIntoViewIfNeeded()
  const headerBefore = (await header.boundingBox())!
  const footerBefore = (await footer.boundingBox())!
  await expect
    .poll(() => body.evaluate((element) => element.scrollHeight - element.clientHeight))
    .toBeGreaterThan(1)
  await body.evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await expect.poll(() => body.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  await expect
    .poll(async () => {
      const headerAfter = (await header.boundingBox())!
      const footerAfter = (await footer.boundingBox())!
      return Math.max(
        Math.abs(headerAfter.y - headerBefore.y),
        Math.abs(headerAfter.height - headerBefore.height),
        Math.abs(footerAfter.y - footerBefore.y),
        Math.abs(footerAfter.height - footerBefore.height),
      )
    })
    .toBeLessThanOrEqual(1)
  expect(await panel.evaluate((element) => element.scrollTop)).toBe(0)
  await expect(panel.locator('[data-slot="panel-footer"] button[type=submit]')).toBeInViewport({
    ratio: 1,
  })
}

async function fieldBetweenPanelEdges(field: Locator) {
  await expect
    .poll(() =>
      field.evaluate((element) => {
        const panel = element.closest('[role="dialog"]')!
        const header = panel.querySelector('[data-slot="sheet-header"]')!
        const footer = panel.querySelector('[data-slot="panel-footer"]')!
        const bounds = element.getBoundingClientRect()
        return Math.min(
          bounds.top - header.getBoundingClientRect().bottom,
          footer.getBoundingClientRect().top - bounds.bottom,
        )
      }),
    )
    .toBeGreaterThanOrEqual(-1)
}

test('mobile history keeps the overview day and section after changing its range', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const day = '2026-10-03'
  const sleep = {
    ...observation('sleep', null, day),
    start_at: `${day}T00:00:00Z`,
    end_at: `${day}T07:00:00Z`,
  }
  const fake = await dashboardSession(page, true, true, false, {
    records: [...records, sleep],
    metrics: {
      sleep: { ...metric('sleep', null, 'min'), status: 'available', records: [sleep] },
    },
  })
  await page.goto(`${APP}personal?day=${day}`)
  const section = page.locator('#personal-daily')
  await page.getByTestId('metric-sleep').click()
  const top = await section.evaluate((element) => element.getBoundingClientRect().top)
  await page.getByTestId('metric-detail-history').click()
  await expect(page).toHaveURL(/\/personal\/history\?/)
  let query = new URL(page.url()).searchParams
  expect(query.get('kind')).toBe('sleep')
  expect(query.get('end')).toBe(day)
  expect(query.get('returnDay')).toBe(day)
  expect(query.get('returnSection')).toBe('daily')
  const returnKey = query.get('returnKey')
  expect(returnKey).toBeTruthy()

  await page.getByLabel('Показатель', { exact: true }).selectOption('steps')
  await page
    .getByRole('navigation', { name: 'Период', exact: true })
    .getByRole('link', { name: '90 дней', exact: true })
    .click()
  await page.getByRole('button', { name: 'Предыдущий период', exact: true }).click()
  query = new URL(page.url()).searchParams
  expect(query.get('kind')).toBe('steps')
  expect(query.get('period')).toBe('90')
  expect(query.get('end')).not.toBe(day)
  expect(query.get('returnDay')).toBe(day)
  expect(query.get('returnKey')).toBe(returnKey)
  await page.getByRole('link', { name: 'Назад', exact: true }).click()
  await expect(page.getByTestId('personal-dashboard')).toBeVisible()
  expect(new URL(page.url()).searchParams.get('day')).toBe(day)
  await expect
    .poll(async () => {
      const restored = await section.evaluate((element) => element.getBoundingClientRect().top)
      return Math.abs(restored - top)
    })
    .toBeLessThanOrEqual(8)

  await page.goBack()
  await expect(page.getByRole('heading', { level: 1, name: 'История измерений' })).toBeVisible()
  await page.goForward()
  await expect(page.getByTestId('personal-dashboard')).toBeVisible()
  expect(new URL(page.url()).searchParams.get('day')).toBe(day)
  await expect
    .poll(async () => {
      const restored = await section.evaluate((element) => element.getBoundingClientRect().top)
      return Math.abs(restored - top)
    })
    .toBeLessThanOrEqual(8)
  expect(fake.mutations).toEqual([])
})

test('mobile overview menu opens all measurements for its selected day', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 })
  const fake = await dashboardSession(page)
  await page.goto(`${APP}personal?day=2026-10-03`)
  await page.getByRole('button', { name: 'Ещё', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Все измерения', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'История измерений' })).toBeVisible()
  const query = new URL(page.url()).searchParams
  expect(query.get('kind')).toBe('all')
  expect(query.get('end')).toBe('2026-10-03')
  await expect(page.getByLabel('Показатель', { exact: true })).toHaveValue('all')
  expect(fake.mutations).toEqual([])
})

test('a direct history link returns to its day after the overview finishes loading', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const day = '2026-10-03'
  let release!: () => void
  const loading = new Promise<void>((resolve) => {
    release = resolve
  })
  const fake = await dashboardSession(page, true, true, false, {
    beforeOverview: async (asked) => {
      if (asked === day) await loading
    },
  })
  await page.goto(`${APP}personal/history?kind=weight&end=${day}`)
  await expect(page.getByRole('heading', { level: 1, name: 'История измерений' })).toBeVisible()
  await page.getByRole('link', { name: 'Назад', exact: true }).click()
  await expect(page.getByTestId('personal-dashboard')).toBeVisible()
  expect(new URL(page.url()).searchParams.get('day')).toBe(day)
  await expect(page.getByTestId('dashboard-weight')).toHaveCount(0)
  release()
  await expect(page.getByTestId('dashboard-weight')).toBeVisible()
  await expect
    .poll(() =>
      page.evaluate(() => {
        const weight = document.getElementById('personal-weight')!
        const date = document.querySelector('[data-testid="personal-day-nav"]')!
        return weight.getBoundingClientRect().top - date.getBoundingClientRect().bottom
      }),
    )
    .toBeGreaterThanOrEqual(-1)
  expect(fake.mutations).toEqual([])
})

test('mobile date stays below the app top and its menu closes at the desktop breakpoint', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 })
  const fake = await dashboardSession(page)
  await page.goto(`${APP}personal`)
  const date = page.getByTestId('personal-day-nav')
  await page.getByTestId('dashboard-nutrition').scrollIntoViewIfNeeded()
  await expect
    .poll(() =>
      date.evaluate((element) => {
        const top = Number.parseFloat(getComputedStyle(element).top)
        return Math.abs(element.getBoundingClientRect().top - top)
      }),
    )
    .toBeLessThanOrEqual(1)
  for (const button of await date.getByRole('button').all()) {
    const bounds = await button.boundingBox()
    expect(bounds?.width).toBeGreaterThanOrEqual(44)
    expect(bounds?.height).toBeGreaterThanOrEqual(44)
  }
  await page.getByTestId('personal-more').click()
  await expect(page.getByRole('menu')).toBeVisible()
  await page.setViewportSize({ width: 768, height: 1024 })
  await expect(page.getByRole('menu')).toHaveCount(0)
  await expect(date).toHaveCSS('position', 'static')
  expect(fake.mutations).toEqual([])
})

test('weight correction uses the selected record ID and revision when values match', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const earlier = {
    ...observation('weight', '74.2'),
    id: 'equal-weight-earlier',
    start_at: `${TODAY}T07:00:00Z`,
    end_at: `${TODAY}T07:00:00Z`,
    revision: 7,
  }
  const later = { ...observation('weight', '74.2'), id: 'equal-weight-later', revision: 11 }
  const fake = await dashboardSession(page, true, true, false, {
    records: [...records.filter((record) => record.kind !== 'weight'), earlier, later],
    operationStatus: 409,
  })
  await page.goto(`${APP}personal`)
  await page.getByTestId('weight-details-open').click()
  await expect(page.getByTestId(`weight-record-${earlier.id}`)).toContainText('74,2')
  await expect(page.getByTestId(`weight-record-${later.id}`)).toContainText('74,2')
  await page.getByTestId(`weight-record-correct-${earlier.id}`).click()
  const panel = page.getByRole('dialog', { name: 'Исправить измерение', exact: true })
  await expect(panel.getByLabel('Когда измерено')).toHaveValue(`${TODAY}T07:00`)
  await panel.getByLabel('Вес, кг').fill('74,5')
  await panel.getByRole('button', { name: 'Сохранить поправку', exact: true }).click()
  await expect.poll(() => fake.writes.length).toBe(1)
  expect(fake.writes[0]).toMatchObject({
    kind: 'personal.record.correct',
    payload: {
      record_id: earlier.id,
      override_revision: 0,
      observation: { data: { value: '74.5' } },
    },
    expected_revisions: { [earlier.id]: 7 },
  })
  await expect(panel).toBeVisible()
  await expect(panel.getByTestId('record-server-error')).toContainText(
    'Введённые значения остались в форме.',
  )
  await expect(panel.getByLabel('Вес, кг')).toHaveValue('74,5')
})

test('sleep correction opens the source interval without inventing a duration', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 640 })
  const sleep = {
    ...observation('sleep', null),
    start_at: `${TODAY}T00:00:00Z`,
    end_at: `${TODAY}T07:00:00Z`,
  }
  const fake = await dashboardSession(page, true, true, false, {
    records: [...records, sleep],
    metrics: { sleep: { ...metric('sleep', null, 'min'), status: 'available', records: [sleep] } },
  })
  await page.goto(`${APP}personal`)
  await page.getByTestId('metric-sleep').click()
  await page.getByTestId(`metric-record-correct-${sleep.id}`).click()
  const panel = page.getByRole('dialog', { name: 'Исправить измерение', exact: true })
  await expect(panel.getByLabel('Сон, минут')).toHaveValue('')
  await expect(panel.getByLabel('Начало', { exact: true })).toHaveValue(`${TODAY}T00:00`)
  await expect(panel.getByLabel('Окончание', { exact: true })).toHaveValue(`${TODAY}T07:00`)
  const start = await panel.getByTestId('record-start').boundingBox()
  const end = await panel.getByTestId('record-end').boundingBox()
  expect(Math.abs(start!.x - end!.x)).toBeLessThanOrEqual(1)
  expect(end!.y).toBeGreaterThan(start!.y)
  await panel.getByTestId('record-end').fill(`${shift(TODAY, -1)}T23:00`)
  const submit = panel.locator('[data-slot="panel-footer"] button[type=submit]')
  await scrollFormBody(panel)
  await submit.click()
  await focusedFieldError(panel.getByTestId('record-end'))
  await fieldBetweenPanelEdges(panel.getByTestId('record-end'))
  await expect(submit).toBeInViewport({ ratio: 1 })
  expect(fake.mutations).toEqual([])
})

test('the profile footer submits once and keeps its draft with an inline server error', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 640 })
  await dashboardSession(page)
  let release!: () => void
  const response = new Promise<void>((resolve) => {
    release = resolve
  })
  const writes: unknown[] = []
  await page.route('**/v1/personal/operations', async (route) => {
    writes.push(route.request().postDataJSON())
    await response
    await route.fulfill({ status: 409, json: { error: 'synthetic_revision_conflict' } })
  })
  await page.goto(`${APP}personal`)
  await expect(page.getByTestId('dashboard-weight')).toBeVisible()
  await page.getByTestId('personal-more').click()
  await page.locator('[data-testid="profile-open"]:visible').click()
  const panel = page.getByRole('dialog', { name: 'Данные тела', exact: true })
  const height = panel.getByTestId('profile-height')
  const submit = panel.locator('[data-slot="panel-footer"] button[type=submit]')
  await height.fill('20')
  await scrollFormBody(panel)
  await submit.click()
  await focusedFieldError(height)
  await fieldBetweenPanelEdges(height)
  await expect(submit).toBeInViewport({ ratio: 1 })
  expect(writes).toHaveLength(0)
  await height.fill('180,5')
  await scrollFormBody(panel)
  expect(
    await submit.evaluate((element) => {
      const button = element as HTMLButtonElement
      return (
        !!button.form?.querySelector('[data-testid="profile-height"]') && !button.closest('form')
      )
    }),
  ).toBe(true)
  await submit.click()
  await expect(submit).toHaveText('Сохраняю…')
  await expect(submit).toBeDisabled()
  await expect(height).toBeDisabled()
  await expect.poll(() => writes.length).toBe(1)
  release()
  await expect(panel.getByTestId('profile-server-error')).toContainText(
    'Введённые значения остались в форме.',
  )
  await expect(panel).toBeVisible()
  await expect(height).toHaveValue('180,5')
  await expect(submit).toBeEnabled()
  expect(writes).toHaveLength(1)
})

test('the caliper footer reveals an invalid fold and keeps protocol changes and refused drafts', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 })
  const fake = await dashboardSession(page, true, true, false, { operationStatus: 409 })
  await page.goto(`${APP}personal`)
  await page.getByTestId('caliper-actions-toggle').click()
  await page.getByTestId('caliper-add').click()
  const panel = page.getByRole('dialog')
  const chest = panel.getByTestId('caliper-chest')
  await chest.fill('101')
  await panel.getByTestId('caliper-optional').locator('summary').click()
  await panel.getByTestId('caliper-triceps').fill('7,5')
  await panel.getByTestId('caliper-metadata-overrides').locator('summary').click()
  await panel.getByTestId('caliper-gender').selectOption('female')
  await expect(chest).toHaveValue('101')
  await expect(chest).toBeVisible()
  await expect(panel.getByTestId('caliper-triceps')).toHaveValue('7,5')
  await panel.getByTestId('caliper-note').fill('Синтетическая заметка')
  await panel.getByTestId('caliper-optional').locator('summary').click()
  await expect(chest).toBeHidden()
  const submit = panel.locator('[data-slot="panel-footer"] button[type=submit]')
  await scrollFormBody(panel)
  expect(
    await submit.evaluate((element) => {
      const button = element as HTMLButtonElement
      return (
        !!button.form?.querySelector('[data-testid="caliper-chest"]') && !button.closest('form')
      )
    }),
  ).toBe(true)
  await submit.click()
  await focusedFieldError(chest)
  await expect(panel.getByTestId('caliper-optional')).toHaveAttribute('open', '')
  await fieldBetweenPanelEdges(chest)
  await expect(submit).toBeInViewport({ ratio: 1 })
  expect(fake.writes).toHaveLength(0)
  await chest.fill('12,5')
  await submit.click()
  await expect(panel.getByTestId('caliper-server-error')).toContainText(
    'Введённые значения остались в форме.',
  )
  await expect(chest).toHaveValue('12,5')
  await expect(panel.getByTestId('caliper-triceps')).toHaveValue('7,5')
  await expect(panel.getByTestId('caliper-note')).toHaveValue('Синтетическая заметка')
  expect(fake.writes).toHaveLength(1)
  expect(fake.writes[0]).toMatchObject({
    kind: 'personal.caliper.add',
    payload: {
      folds_mm: { chest: '12.5', triceps: '7.5' },
      gender: 'female',
      note: 'Синтетическая заметка',
    },
  })
  await fitsPage(page)
})

for (const viewport of [
  { width: 320, height: 740 },
  { width: 360, height: 800 },
  { width: 390, height: 844 },
  { width: 430, height: 932 },
  { width: 844, height: 390 },
  { width: 768, height: 1024 },
  { width: 1024, height: 768 },
  { width: 1440, height: 1000 },
]) {
  test(`dashboard fits ${viewport.width}×${viewport.height} and keeps 14 days inside its chart`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport)
    const fake = await dashboardSession(page)
    await page.goto(`${APP}personal`)
    await expect(page.getByTestId('personal-dashboard')).toBeVisible()
    await expect(page.getByTestId('dashboard-body')).toBeVisible()
    // The desktop sidebar is also a narrow card: its numbers must stay on one line.
    for (const kind of ['body_fat_percent', 'skeletal_muscle_mass']) {
      const metric = page.getByTestId(`dashboard-body-${kind}`)
      await expect
        .poll(() =>
          metric
            .locator('strong')
            .evaluate(
              (element) =>
                element.getBoundingClientRect().height /
                Number.parseFloat(getComputedStyle(element).lineHeight),
            ),
        )
        .toBeLessThanOrEqual(1)
      await expect
        .poll(() =>
          metric
            .locator(':scope > span')
            .first()
            .evaluate(
              (element) =>
                element.getBoundingClientRect().height /
                Number.parseFloat(getComputedStyle(element).lineHeight),
            ),
        )
        .toBeLessThanOrEqual(3)
    }
    await expect(page.getByTestId('weight-card')).toContainText('74,2')
    await expect(page.getByTestId('dashboard-nutrition')).toBeVisible()
    const scroll = page.getByTestId('nutrition-chart-scroll')
    await expect(scroll).toBeVisible()
    for (const day of await page.locator('[data-testid^="nutrition-day-2026-"]').all()) {
      const bounds = await day.boundingBox()
      expect(bounds?.width).toBeGreaterThanOrEqual(44)
      expect(bounds?.height).toBeGreaterThanOrEqual(44)
    }
    if (await scroll.evaluate((element) => element.clientWidth < 308))
      await expect
        .poll(() => scroll.evaluate((element) => element.scrollWidth - element.clientWidth))
        .toBeGreaterThan(0)
    await page.getByTestId('nutrition-period-14').click()
    await expect(page.getByTestId(`nutrition-day-${shift(TODAY, -13)}`)).toBeAttached()
    await fitsPage(page)
    if (await scroll.evaluate((element) => element.clientWidth < 616))
      await expect
        .poll(() => scroll.evaluate((element) => element.scrollWidth - element.clientWidth))
        .toBeGreaterThan(0)
    await expect(page.getByTestId(`nutrition-day-${TODAY}`)).toHaveAttribute('data-state', 'goal')
    // A long source link must not squeeze the empty-metrics explanation into a word column.
    await expect
      .poll(() =>
        page
          .getByTestId('metrics-silent')
          .locator('p')
          .evaluate(
            (element) =>
              element.getBoundingClientRect().height /
              Number.parseFloat(getComputedStyle(element).lineHeight),
          ),
      )
      .toBeLessThanOrEqual(2)
    await page.getByRole('heading', { level: 1 }).scrollIntoViewIfNeeded()
    await photograph(page, `personal-dashboard-${viewport.width}.png`)
    if (page.context().browser()?.browserType().name() !== 'webkit')
      await page.screenshot({ path: test.info().outputPath(`above-fold-${viewport.width}.png`) })
    expect(fake.mutations).toEqual([])
  })
}

test('weight and sleep fit above the tab bar on the ordinary 360×800 overview', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 })
  const fake = await dashboardSession(page, true, true, false, {
    metrics: {
      sleep: metric('sleep', '480', 'min'),
      steps: metric('steps', '8000', 'steps'),
      active_minutes: metric('active_minutes', '30', 'min'),
    },
  })
  await page.goto(`${APP}personal`)
  const tabBar = await page.getByTestId('tab-bar').boundingBox()
  const visibleBottom = tabBar?.y ?? 800
  for (const id of ['weight-card', 'metric-sleep']) {
    const item = page.getByTestId(id)
    await expect(item).toBeVisible()
    const bounds = await item.boundingBox()
    expect(bounds?.y).toBeGreaterThanOrEqual(0)
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(visibleBottom)
  }
  await fitsPage(page)
  expect(fake.mutations).toEqual([])
})

for (const width of [360, 768, 1024, 1440]) {
  test(`the overview preserves its intended card order at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    const fake = await dashboardSession(page)
    await page.goto(`${APP}personal`)
    const weight = page.getByTestId('dashboard-weight')
    const daily = page.locator('#personal-daily')
    const body = page.getByTestId('dashboard-body')
    const nutrition = page.getByTestId('dashboard-nutrition')
    for (const card of [weight, daily, body, nutrition]) await expect(card).toBeVisible()
    await expect(page.getByTestId('nutrition-chart-scroll')).toBeVisible()
    const boxes = await Promise.all(
      [weight, daily, body, nutrition].map((card) => card.boundingBox()),
    )
    const [weightBox, dailyBox, bodyBox, nutritionBox] = boxes.map((box) => box!)
    if (width < 768) {
      expect(weightBox.y).toBeLessThan(dailyBox.y)
      expect(dailyBox.y).toBeLessThan(bodyBox.y)
      expect(bodyBox.y).toBeLessThan(nutritionBox.y)
    } else if (width < 1024) {
      expect(weightBox.y).toBeLessThan(bodyBox.y)
      expect(bodyBox.y).toBeLessThan(nutritionBox.y)
      expect(nutritionBox.y).toBeLessThan(dailyBox.y)
    } else {
      expect(bodyBox.x).toBeLessThan(weightBox.x)
      expect(Math.abs(bodyBox.y - weightBox.y)).toBeLessThanOrEqual(1)
      expect(nutritionBox.y).toBeGreaterThan(weightBox.y)
      expect(dailyBox.y).toBeGreaterThanOrEqual(bodyBox.y + bodyBox.height)
      expect(dailyBox.y).toBeGreaterThanOrEqual(nutritionBox.y + nutritionBox.height)
    }
    await fitsPage(page)
    expect(fake.mutations).toEqual([])
  })
}

for (const theme of ['light', 'dark'] as const) {
  test(`the mobile overview fits large text in the ${theme} theme`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.addInitScript((value) => localStorage.setItem('assistant.web.theme', value), theme)
    const fake = await dashboardSession(page)
    await page.goto(`${APP}personal`)
    await expect(page.getByTestId('dashboard-nutrition')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await page.evaluate(() => (document.documentElement.style.fontSize = '200%'))
    await expect(page.getByRole('heading', { level: 1, name: 'Личное' })).toBeInViewport()
    await expect
      .poll(() =>
        page.getByTestId('nutrition-energy').evaluate((element) => {
          const range = document.createRange()
          range.selectNodeContents(element)
          return (
            range.getBoundingClientRect().height /
            Number.parseFloat(getComputedStyle(element).lineHeight)
          )
        }),
      )
      .toBeLessThanOrEqual(1)
    await fitsPage(page)
    await page.getByTestId('nutrition-day-2026-10-03').click()
    const panel = page.getByTestId('nutrition-day-panel')
    await expect(panel).toContainText('80')
    await expect(panel.getByTestId('nutrition-selected-diary')).toBeVisible()
    await fitsPage(page)
    await photograph(page, `personal-mobile-large-text-${theme}.png`)
    expect(fake.mutations).toEqual([])
  })
}

for (const viewport of [
  { width: 320, height: 740 },
  { width: 1440, height: 1000 },
]) {
  test(`the GPU figure keeps a square stage at ${viewport.width}px`, async ({
    page,
    browserName,
  }) => {
    test.skip(
      browserName !== 'chromium',
      'GPU rendering is verified on Chromium; WebKit may use its supported poster fallback',
    )
    await page.setViewportSize(viewport)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await dashboardSession(page)
    await page.route('**/v1/personal/body/presentation', (route) =>
      route.fulfill({ json: { character_id: 'sculpture', revision: 1 } }),
    )
    const presentationRead = page.waitForResponse('**/v1/personal/body/presentation')
    await page.goto(`${APP}personal`)
    // This account query starts after identity settles. Wait for its saved choice
    // before scrolling a figure that would otherwise unmount during session startup.
    const presentation = await presentationRead
    expect(presentation.ok()).toBe(true)
    await presentation.finished()
    const figure = page.getByTestId('dashboard-body').getByTestId('body-figure')
    await expect(figure).toBeVisible()
    await figure.scrollIntoViewIfNeeded()
    await expect(figure).toHaveClass(/body-figure--gpu/, { timeout: 20_000 })
    await expect(page.getByTestId('dashboard-body-pause')).toHaveCount(0)
    await expect(figure.locator('canvas')).toHaveAttribute('data-character-paused', 'true')
    const bounds = await figure.boundingBox()
    expect(bounds).not.toBeNull()
    expect(Math.abs(bounds!.width - bounds!.height)).toBeLessThanOrEqual(1)
    await fitsPage(page)
    await photograph(page, `gpu-dashboard-${viewport.width}.png`)
  })
}

test('the mobile weight chart renders separate weight and InBody percentage axes', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await dashboardSession(page)
  await page.goto(`${APP}personal`)
  const weight = page.getByTestId('dashboard-weight')
  await expect(weight).toBeVisible()
  const chart = page.getByTestId('dashboard-weight-chart')
  if (!(await chart.isVisible())) await weight.locator('button[aria-expanded]').click()
  await expect(chart).toBeVisible()
  await expect(page.getByTestId('dashboard-weight-unit')).toContainText('кг')
  await expect(page.getByTestId('dashboard-fat-unit')).toContainText('%')
  await expect(page.getByTestId('dashboard-weight-point')).toHaveCount(2)
  await expect(page.getByTestId('dashboard-fat-point')).toHaveCount(2)
  await fitsPage(page)
})

test('unknown protein stays pending, an exact limit passes, and excess calories fail', async ({
  page,
}) => {
  const fake = await dashboardSession(page)
  await page.goto(`${APP}personal`)
  await expect(page.getByTestId('nutrition-day-2026-10-03')).toHaveAttribute(
    'data-state',
    'pending',
  )
  await expect(page.getByTestId('nutrition-day-2026-10-04')).toHaveAttribute('data-state', 'missed')
  await expect(page.getByTestId(`nutrition-day-${TODAY}`)).toHaveAttribute('data-state', 'goal')
  await page.getByTestId('nutrition-day-2026-10-03').click()
  await expect(page.getByTestId('nutrition-day-panel')).toContainText('80')
  await expect(page.getByTestId('nutrition-day-panel')).toContainText('120')
  await fitsPage(page)
  expect(fake.mutations).toEqual([])
})

test('chart inspection keeps the primary diary and goals on the overview day', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const fake = await dashboardSession(page)
  await page.goto(`${APP}personal`)
  const diary = page.getByTestId('nutrition-diary')
  await expect(diary).toHaveAttribute('href', `/food?day=${TODAY}`)
  await page.getByTestId('nutrition-day-2026-10-03').click()
  const selected = page.getByTestId('nutrition-day-panel')
  await expect(selected.getByTestId('nutrition-selected-diary')).toHaveAttribute(
    'href',
    '/food?day=2026-10-03',
  )
  await expect(diary).toHaveAttribute('href', `/food?day=${TODAY}`)
  await selected.getByRole('button', { name: 'Закрыть', exact: true }).click()
  await page.getByTestId('nutrition-details-toggle').click()
  await page.getByTestId('nutrition-goals').click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.locator('#goal-from')).toHaveValue(TODAY)
  await expect(page.locator('#goal-protein_g')).toHaveValue('120')
  expect(fake.mutations).toEqual([])
})

test('chart focus announces a day, keyboard activation opens its panel and restores focus', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 740 })
  const fake = await dashboardSession(page)
  await page.goto(`${APP}personal`)
  const panel = page.getByTestId('nutrition-day-panel')
  for (const [date, key, status] of [
    ['2026-10-03', 'Enter', 'Минимум белка не подтверждён'],
    ['2026-10-04', 'Space', 'Лимит калорий превышен'],
  ] as const) {
    const day = page.getByTestId(`nutrition-day-${date}`)
    await day.focus()
    await expect(page.getByTestId('nutrition-readout')).toContainText(status)
    await expect(panel).toHaveCount(0)
    await day.press(key)
    await expect(panel).toBeVisible()
    await expect(panel).toContainText(status)
    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()
    await expect(day).toBeFocused()
  }
  await page.getByTestId('nutrition-period-14').click()
  await expect(page.locator('[data-testid^="nutrition-day-"][aria-pressed=true]')).toHaveCount(0)
  await expect(page.getByTestId('nutrition-diary')).toHaveAttribute('href', `/food?day=${TODAY}`)
  await fitsPage(page)
  expect(fake.mutations).toEqual([])
})

for (const [id, label] of [
  ['body-details-open', 'body details'],
  ['caliper-history-open', 'caliper history'],
  ['caliper-add', 'caliper form'],
  ['profile-open', 'profile form'],
] as const) {
  test(`mobile ${label} closes without navigating away or writing data`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 740 })
    const fake = await dashboardSession(page)
    await page.goto(`${APP}personal`)
    if (id === 'profile-open') await page.getByTestId('personal-more').click()
    if (id.startsWith('caliper-') && !(await page.getByTestId(id).isVisible()))
      await page.getByTestId('caliper-actions-toggle').click()
    await page.locator(`[data-testid="${id}"]:visible`).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await fitsPage(page)
    const fields = dialog.locator('input, select, textarea, a')
    if (await fields.count()) await fields.last().scrollIntoViewIfNeeded()
    const close = dialog.getByRole('button', { name: 'Закрыть', exact: true })
    await expect(close).toBeVisible()
    const bounds = await close.boundingBox()
    expect(bounds?.width).toBeGreaterThanOrEqual(44)
    expect(bounds?.height).toBeGreaterThanOrEqual(44)
    const smallInputs = await dialog
      .locator('input:not([type=hidden]), textarea')
      .evaluateAll((elements) =>
        elements
          .filter((element) => Number.parseFloat(getComputedStyle(element).fontSize) < 16)
          .map((element) => element.id),
      )
    expect(smallInputs).toEqual([])
    await close.click()
    await expect(dialog).toBeHidden()
    await expect(page).toHaveURL(new RegExp(`${APP}personal$`))
    expect(fake.mutations).toEqual([])
  })
}

test('a read-only account keeps charts but has no measurement or goal write controls', async ({
  page,
}) => {
  const fake = await dashboardSession(page, false)
  await page.goto(`${APP}personal`)
  await expect(page.getByTestId('dashboard-nutrition')).toBeVisible()
  await expect(page.getByTestId('caliper-add')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Записать вес', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /^(Изменить|Задать) цели$/ })).toHaveCount(0)
  await page.getByTestId('body-details-open').click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expect(fake.mutations).toEqual([])
})

test('without nutrition access the dashboard does not request the diary', async ({ page }) => {
  const fake = await dashboardSession(page, false, false)
  await page.goto(`${APP}personal`)
  await expect(page.getByTestId('personal-dashboard')).toBeVisible()
  await expect(page.getByTestId('dashboard-body')).toBeVisible()
  await expect(page.getByTestId('nutrition-chart-scroll')).toHaveCount(0)
  expect(fake.nutritionReads).toEqual([])
  expect(fake.mutations).toEqual([])
})

test('an empty mobile dashboard keeps caliper actions without inventing a body analysis', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 740 })
  const fake = await dashboardSession(page, true, true, true)
  await page.goto(`${APP}personal`)
  await expect(page.getByTestId('dashboard-body')).toBeVisible()
  await expect(page.getByTestId('dashboard-body')).toContainText('Нет анализа состава тела')
  await expect(page.locator('.body-figure')).toHaveCount(0)
  await expect(page.getByTestId('caliper-actions-toggle')).toBeVisible()
  await page.getByTestId('caliper-actions-toggle').click()
  await expect(page.getByTestId('caliper-add')).toBeVisible()
  await expect(page.getByTestId('caliper-history-open')).toBeVisible()
  await expect(page.getByTestId('nutrition-chart-scroll')).toHaveCount(0)
  await expect(page.getByTestId('dashboard-nutrition')).toContainText('Нет записей')
  await fitsPage(page)
  expect(fake.mutations).toEqual([])
})
