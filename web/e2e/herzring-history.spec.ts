import {
  producerArchiveItem,
  producerHomeArchiveItem,
  producerRhrScore,
  producerSummary,
  producerSummaryPage,
} from '../src/sections/personal/derivedScores.fixtures'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.skip(!ISOLATED, 'synthetic ring history fixtures require the disposable backend')
test.use({ asMember: true })

const source = '48d8ac71-b0a4-435c-ad84-331ea4f026bb'
const workout = 'dba98120-9cdd-4b65-b7af-75a747d72a88'
const report = (revision: number) => ({
  id: 'synthetic-workout-report',
  source_id: source,
  source_name: 'Synthetic ring',
  revision,
  source_version: revision,
  kind: 'running_workouts',
  shape: 'workout_report',
  local_date: '2026-10-09',
  timezone: 'UTC',
  observed_at: '2026-10-09T08:02:00Z',
  start_at: '2026-10-09T08:00:00Z',
  end_at: '2026-10-09T08:02:00Z',
  data: { sport_type: 'running', duration_seconds: 120, steps: null, distance_meters: null },
  origin: {
    source: 'phone_recorded',
    record_id: workout,
    details: {
      status: 'interrupted',
      trigger: 'manual',
      stop_reason: 'link_lost',
      recovery_end_at: '2026-10-09T08:03:00Z',
    },
  },
  quality: ['sample_gap'],
  is_current: revision === 2,
  current_revision: 2,
  annotation_revision: 1,
  excluded: true,
  resolution_disposition: 'excluded',
})

test('read-only history exposes old and excluded workouts and separate GPS segments under CSP', async ({
  page,
  baseURL,
}) => {
  const me = await (await page.request.get(`${new URL(baseURL!).origin}/v1/me`)).json()
  await page.route('**/v1/me', (route) =>
    route.fulfill({
      json: { ...me, scopes: me.scopes.filter((scope: string) => scope !== 'personal.write') },
    }),
  )
  await page.route('**/v1/personal/activity-reports?**', (route) => {
    const params = new URL(route.request().url()).searchParams
    return route.fulfill({
      json: {
        from: params.get('from'),
        to: params.get('to'),
        items: [report(1), report(2)],
        next_cursor: null,
        fingerprint: 'synthetic-archive',
      },
    })
  })
  const point = (at: string, longitude: number) => ({
    at,
    latitude: 10,
    longitude,
    accuracy_meters: 5,
    quality: 'valid',
  })
  const context = {
    distance_meters: 20,
    usable_points: 4,
    reported_points: 6,
    gap_count: 1,
    coverage_seconds: 60,
    quality: ['sample_gap'],
  }
  await page.route('**/v1/personal/rings/workouts/*/track?**', (route) =>
    route.fulfill({
      json: {
        workout_id: workout,
        source_id: source,
        workout: {
          start_at: '2026-10-09T08:00:00Z',
          end_at: '2026-10-09T08:02:00Z',
          timezone: 'UTC',
          status: 'interrupted',
          trigger: 'manual',
          recovery_end_at: '2026-10-09T08:03:00Z',
          stop_reason: 'link_lost',
        },
        chunks: [],
        segments: [
          {
            context: 'running',
            quality: 'valid',
            points: [
              point('2026-10-09T08:00:00Z', 179.999),
              point('2026-10-09T08:00:10Z', -179.999),
            ],
          },
          {
            context: 'running',
            quality: 'valid',
            points: [point('2026-10-09T08:01:00Z', -179.998)],
          },
          {
            context: 'recovery',
            quality: 'valid',
            points: [point('2026-10-09T08:02:10Z', -179.997)],
          },
        ],
        summary: {
          algorithm_version: 'synthetic-distance-v1',
          value_meters: 40,
          unit: 'm',
          contexts: { running: context, recovery: context },
          input_refs: [],
          fingerprint: 'synthetic-track',
        },
        fingerprint: 'synthetic-track-inputs',
        next_cursor: null,
      },
    }),
  )
  await page.route('**/v1/maps/osm/**', (route) => route.fulfill({ status: 204 }))
  await page.goto(`${APP}personal/history?kind=running_workouts&period=7&end=2026-10-09`)
  const versions = page.getByTestId('activity-report-version')
  await expect(versions).toHaveCount(2)
  await expect(versions.first()).toContainText('Предыдущая версия')
  await expect(versions.first()).toContainText('Исключено из итога')
  await expect(versions.first().getByRole('button', { name: 'Вернуть в итог' })).toHaveCount(0)
  await versions.first().getByRole('button', { name: 'Маршрут GPS', exact: true }).click()
  const map = versions
    .first()
    .getByRole('img', { name: 'Маршрут тренировки, отдельные участки активности и восстановления' })
  await expect(map).toBeVisible()
  await expect(map.locator('polyline')).toHaveCount(3)
  await expect(versions.first().getByRole('link', { name: 'OpenStreetMap' })).toBeVisible()
  await expect(versions.first().getByTestId('workout-track-history')).toContainText('Пропуски: 1')
})

test('unqualified temperature packets stay visible as source reports without a fabricated measured value', async ({
  page,
  baseURL,
}) => {
  const me = await (await page.request.get(`${new URL(baseURL!).origin}/v1/me`)).json()
  await page.route('**/v1/personal/resolved?**', (route) => {
    const params = new URL(route.request().url()).searchParams
    return route.fulfill({
      json: {
        from: params.get('from'),
        to: params.get('to'),
        timezone: me.timezone,
        computed_at: '2026-10-09T08:00:00Z',
        daily: [],
        series: [],
        next_cursor: null,
        fingerprint: 'no-normalized-temperature',
      },
    })
  })
  await page.route('**/v1/personal/raw-reports?**', (route) => {
    const params = new URL(route.request().url()).searchParams
    return route.fulfill({
      json: {
        from: params.get('from'),
        to: params.get('to'),
        count: 1,
        next_cursor: null,
        fingerprint: 'raw',
        items: [
          {
            id: 'synthetic-raw',
            user_id: me.id,
            source_id: source,
            source_name: 'Synthetic ring',
            source_status: 'connected',
            external_id: 'bulk25',
            source_version: 1,
            revision: 1,
            source_hash: 'synthetic-hash',
            received_at: '2026-10-09T08:00:00Z',
            metric: 'temperature',
            local_date: '2026-10-08',
            timezone: 'UTC',
            observed_at: '2026-10-08T23:59:00Z',
            namespace: 'bulk',
            opcode: 37,
            layout: 'unqualified',
            payload_hex: 'bc2501',
            details: {},
            origin: {},
            quality: ['layout_unconfirmed'],
            is_current: true,
            current_revision: 1,
            qualification: 'source_only',
            reason: 'ambiguous_layout',
            interpretation: null,
          },
        ],
      },
    })
  })
  await page.goto(`${APP}personal/history?kind=temperature&period=7&end=2026-10-09`)
  const raw = page.getByTestId('raw-temperature-history')
  await expect(raw).toContainText('Получено отчётов и версий за период: 1')
  await expect(raw).toContainText('не участвуют в измеренной температуре')
  await raw.getByTestId('raw-temperature-version').locator(':scope > summary').click()
  await expect(raw).toContainText('Дата кольца: 2026-10-08')
  await raw.getByText('Пакет и разбор источника', { exact: true }).click()
  await expect(raw).toContainText('bc2501')
  await expect(page.getByTestId('sensor-samples')).toHaveCount(0)
})

test('generic phone workouts keep requested mode, pauses and recovery distinct from running', async ({
  page,
  baseURL,
}) => {
  const me = await (await page.request.get(`${new URL(baseURL!).origin}/v1/me`)).json()
  await page.route('**/v1/me', (route) =>
    route.fulfill({
      json: { ...me, scopes: me.scopes.filter((scope: string) => scope !== 'personal.write') },
    }),
  )
  const generic = {
    ...report(2),
    kind: 'workouts',
    data: {
      sport_type: 'cycling',
      duration_seconds: 120,
      active_duration_seconds: 90,
      sport_plan: {
        requested_id: 2,
        name: 'cycling',
        family: 'cycling',
        candidate_wire_mode: 2,
        actual_wire_mode: null,
        qualification: 'protocol_unverified',
        evidence_ref: 'synthetic-catalog',
      },
      pauses: [{ start_at: '2026-10-09T08:00:30Z', end_at: '2026-10-09T08:01:00Z' }],
    },
  }
  await page.route('**/v1/personal/activity-reports?**', (route) =>
    route.fulfill({
      json: {
        from: '2026-10-03',
        to: '2026-10-09',
        items: [generic],
        next_cursor: null,
        fingerprint: 'generic-archive',
      },
    }),
  )
  await page.goto(`${APP}personal/history?kind=workouts&period=7&end=2026-10-09`)
  const card = page.getByTestId('device-workout-card')
  await expect(card).toContainText('Тренировка · cycling')
  await expect(card).toContainText('Время активности без пауз: 1 мин 30 с')
  await card.getByTestId('reported-sport-plan').locator(':scope > summary').click()
  await expect(card.getByTestId('reported-sport-plan')).toContainText(
    'Фактический режим: Не подтверждён',
  )
  await card.getByTestId('reported-workout-pauses').locator(':scope > summary').click()
  await expect(card.getByTestId('reported-workout-pauses')).toContainText('08:00 — 08:01')
  await expect(card.getByRole('button', { name: 'Маршрут GPS', exact: true })).toBeVisible()
})

test('long-period saved scores page compact summaries and lazily read immutable inputs under CSP', async ({
  page,
  baseURL,
}) => {
  const me = await (await page.request.get(`${new URL(baseURL!).origin}/v1/me`)).json()
  await page.route('**/v1/me', (route) =>
    route.fulfill({
      json: { ...me, scopes: me.scopes.filter((scope: string) => scope !== 'personal.write') },
    }),
  )
  const score = producerRhrScore(source)
  score.context.time_zone = me.timezone
  const first = producerSummary(score, 'Сохранённое определение RHR')
  const other = producerRhrScore('fbca20c0-d307-4cb9-a5d1-a1b9223bf390')
  other.context.time_zone = me.timezone
  const second = {
    ...producerSummary(other, 'Сохранённое определение RHR'),
    result_id: 'b'.repeat(64),
  }
  const saved = {
    ...producerArchiveItem(),
    source_scope: source,
    time_zone: me.timezone,
    score,
    normalized_inputs: {
      snapshot: { source_id: source, context_revisions: score.input_revisions },
      facts: { hr_7d: { value: [55, 60], refs: score.input_revisions, baseline: score.baseline } },
      requested_inputs: ['hr_7d'],
    },
  }
  const summaryRequests: URL[] = []
  const detailRequests: string[] = []
  let writes = 0
  await page.route('**/v1/personal/operations', (route) => {
    writes += 1
    return route.fulfill({ status: 403 })
  })
  await page.route('**/v1/personal/derived-scores?**', (route) => {
    const url = new URL(route.request().url())
    summaryRequests.push(url)
    const params = url.searchParams
    const cursor = params.get('cursor')
    if (cursor !== null && cursor !== first.result_id)
      return route.fulfill({ status: 400, json: { error: 'invalid_synthetic_summary_cursor' } })
    const next = cursor === first.result_id
    return route.fulfill({
      json: {
        ...producerSummaryPage([next ? second : first], next ? null : first.result_id),
        from: params.get('from'),
        to: params.get('to'),
        time_zone: params.get('time_zone'),
        total_summaries: 2,
      },
    })
  })
  await page.route('**/v1/personal/derived-result-versions/*', (route) => {
    detailRequests.push(new URL(route.request().url()).pathname)
    return route.fulfill({ json: saved })
  })
  await page.goto(`${APP}personal/history?kind=derived_scores&period=365&end=2026-10-09`)
  const history = page.getByTestId('derived-scores')
  await expect(history.getByTestId('derived-score')).toHaveCount(1)
  await expect(history).toContainText('Сохранённое определение RHR')
  await expect(history.getByTestId('derived-summary-count')).toContainText('1 / 2')
  expect(detailRequests).toEqual([])
  expect(summaryRequests[0]!.searchParams.get('from')).toBe('2025-10-10')
  expect(summaryRequests[0]!.searchParams.get('limit')).toBe('250')
  await expect(
    history.getByRole('button', { name: 'Пересчитать и сохранить историю' }),
  ).toHaveCount(0)
  // Account-stream resync may repeat page 1; the pagination contract is its explicit cursor.
  const nextPage = page.waitForRequest((request) => {
    const url = new URL(request.url())
    return (
      request.method() === 'GET' &&
      url.pathname === '/v1/personal/derived-scores' &&
      url.searchParams.has('cursor')
    )
  })
  await history.getByRole('button', { name: 'Загрузить ещё расчёты' }).click()
  const nextPageParams = new URL((await nextPage).url()).searchParams
  expect(nextPageParams.get('cursor')).toBe(first.result_id)
  expect(nextPageParams.get('from')).toBe('2025-10-10')
  expect(nextPageParams.get('to')).toBe('2026-10-09')
  expect(nextPageParams.get('time_zone')).toBe(me.timezone)
  expect(nextPageParams.get('limit')).toBe('250')
  await expect(history.getByTestId('derived-score')).toHaveCount(2)
  await expect(history.getByTestId('derived-summary-count')).toContainText('2 / 2')
  expect(detailRequests).toEqual([])
  const card = history.getByTestId('derived-score').first()
  await card.getByRole('button', { name: 'Открыть сохранённые исходные данные' }).click()
  await expect(card.getByTestId('derived-score-details')).toBeVisible()
  await card.getByText('Определение и исходные данные', { exact: true }).click()
  await expect(card.getByTestId('derived-baseline')).toContainText('2 показаний')
  await expect(card.getByTestId('derived-baseline')).toContainText('lower_fifth_percentile')
  await card.getByText('Версии исходных записей и контекста', { exact: true }).click()
  await expect(card.getByTestId('derived-score-details')).toContainText(
    'Состояние выбора источников',
  )
  await expect(card.getByTestId('derived-score-details')).toContainText('Момент расчёта')
  expect(detailRequests).toEqual([`/v1/personal/derived-result-versions/${first.result_id}`])
  expect(writes).toBe(0)
})

test('saved Home inputs preserve three producer baselines and missing current without a fresh neutral score', async ({
  page,
  baseURL,
}) => {
  const me = await (await page.request.get(`${new URL(baseURL!).origin}/v1/me`)).json()
  await page.route('**/v1/me', (route) =>
    route.fulfill({
      json: { ...me, scopes: me.scopes.filter((scope: string) => scope !== 'personal.write') },
    }),
  )
  const saved = producerHomeArchiveItem()
  const original = JSON.stringify(saved)
  const summary = producerSummary(saved.score, saved.definition_snapshot.label)
  const details: string[] = []
  let writes = 0
  await page.route('**/v1/personal/operations', (route) => {
    writes += 1
    return route.fulfill({ status: 403 })
  })
  await page.route('**/v1/personal/derived-scores?**', (route) => {
    const params = new URL(route.request().url()).searchParams
    return route.fulfill({
      json: {
        ...producerSummaryPage([summary]),
        from: params.get('from'),
        to: params.get('to'),
        time_zone: params.get('time_zone'),
      },
    })
  })
  await page.route('**/v1/personal/derived-result-versions/*', (route) => {
    details.push(new URL(route.request().url()).pathname)
    return route.fulfill({ json: saved })
  })
  await page.goto(`${APP}personal/history?kind=derived_scores&period=7&end=2026-10-09`)
  const card = page.getByTestId('derived-score')
  await expect(card).toHaveCount(1)
  await expect(card).toContainText('Готовность · главный экран Soul')
  await expect(card).toContainText('Недостаточно данных')
  await expect(card.locator('.text-xl')).toHaveCount(0)
  expect(details).toEqual([])
  await card.getByRole('button', { name: 'Открыть сохранённые исходные данные' }).click()
  await expect(card.getByTestId('derived-score-details')).toBeVisible()
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(page.viewportSize()!.width)
  await card.getByText('Определение и исходные данные', { exact: true }).click()
  const components = card.getByTestId('derived-component')
  await expect(components).toContainText([
    'Шаги во время сна, вычитаемые из активности: 80',
    'Текущие шаги в контексте главного экрана Soul: Нет данных',
    'Шаги за день по сохранённой истории: 80',
    'Цель шагов: Нет данных',
  ])
  await expect(card).toContainText(
    'Не удалось подтвердить текущий счётчик шагов и дату его обновления.',
  )
  const baselines = card.getByTestId('derived-baseline').locator(':scope > dd > ul > li')
  await expect(baselines).toHaveCount(3)
  for (const baseline of await baselines.all()) {
    await baseline
      .getByText('Окно, выбор и исходные версии компонента базы', { exact: true })
      .click()
  }
  const renderedBaselines = await baselines.locator('pre').allTextContents()
  expect(renderedBaselines.map((value) => JSON.parse(value))).toEqual(
    saved.score.baseline.components,
  )
  await card.getByText('Версии исходных записей и контекста', { exact: true }).click()
  await expect(card.getByTestId('derived-score-details')).toContainText('Отчёт активности')
  const captured = card.getByText('Сохранённые входы, контекст и реализация', { exact: true })
  await captured.click()
  const { is_current: isCurrent, ...immutable } = saved
  void isCurrent
  expect(JSON.parse((await captured.locator('..').locator('pre').textContent())!)).toEqual(
    immutable,
  )
  expect(details).toEqual([`/v1/personal/derived-result-versions/${saved.result_id}`])
  await expect(card.locator('.text-xl')).toHaveCount(0)
  expect(JSON.stringify(saved)).toBe(original)
  expect(writes).toBe(0)
})
