import type { Page, Route } from '@playwright/test'
import { freshPerson, ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

const json = (route: Route, value: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(value) })
const now = () => new Date().toISOString()

test.describe('on the disposable backend', () => {
  test.skip(!ISOLATED, 'creates users: needs the disposable backend')

  test('an invitation link is shown once to hand over, and opens the sign-up', async ({
    page,
    browser,
    baseURL,
  }) => {
    await page.goto(`${APP}admin/users`)
    await page.getByRole('button', { name: 'Новое приглашение' }).first().click()
    await expect(page.getByRole('checkbox', { name: 'Разговор с ассистентом' })).toBeChecked()
    await page.getByRole('button', { name: 'Создать ссылку на 7 дней' }).click()
    const link = page.getByTestId('invite-link').getByRole('textbox', { name: 'Ссылка' })
    await expect(link).toHaveValue(/:\d+\/login#kind=invite&token=/)
    await expect(page.getByTestId('invite-link')).toContainText(
      'Передайте её выбранному человеку лично',
    )

    const visitor = await browser.newContext({ baseURL })
    try {
      const signUp = await visitor.newPage()
      await signUp.goto(await link.inputValue())
      await expect(signUp.getByRole('heading', { name: 'Добро пожаловать' })).toBeVisible()
    } finally {
      await visitor.close()
    }
  })

  test('a person’s limits, reset link and disabling are separate steps', async ({
    page,
    baseURL,
  }) => {
    const { login } = await freshPerson(baseURL!, {
      prefix: 'olga',
      name: 'Ольга Пример',
      timezone: 'UTC',
      scopes: ['tasks.read'],
    })

    await page.goto(`${APP}admin/users`)
    const row = page.getByTestId('users').getByRole('listitem').filter({ hasText: login })
    await row.getByRole('button').click()
    await expect(page.getByRole('checkbox', { name: 'Дела: просмотр' })).toBeChecked()
    await page.getByLabel('Обращений к ассистенту в день').fill('7')
    await page.getByRole('button', { name: 'Сохранить лимиты' }).click()
    await expect(page.getByText('Лимиты сохранены')).toBeVisible()
    await page.getByRole('button', { name: 'Выдать ссылку сброса пароля' }).click()
    await expect(page.getByTestId('reset-link')).toContainText('Ольга Пример')
    await expect(page.getByTestId('reset-link').getByRole('textbox')).toHaveValue(
      /#kind=reset&token=/,
    )

    await page.getByRole('button', { name: 'Отключить доступ' }).click()
    await expect(page.getByRole('dialog', { name: /Отключить/ })).toContainText('Данные сохранятся')
    await page
      .getByRole('dialog', { name: /Отключить/ })
      .getByRole('button', { name: 'Отключить доступ' })
      .click()
    await expect(row).toContainText('Отключён')
    await row.getByRole('button').click()
    await expect(page.getByLabel('Обращений к ассистенту в день')).toHaveValue('7')
    await page.getByRole('button', { name: 'Вернуть доступ' }).click()
    await expect(row).not.toContainText('Отключён')
  })

  test('a person’s card holds their subscriptions; absent services say so', async ({ page }) => {
    await page.goto(`${APP}admin/users`)
    const row = page
      .getByTestId('users')
      .getByRole('listitem')
      .filter({ hasText: 'Тестовый владелец' })
    await expect(row).toContainText('Без подписок')
    await row.getByRole('button').click()
    const subscriptions = page.getByTestId('user-subscriptions')
    await expect(subscriptions).toContainText('Подключений нет')
    await subscriptions.getByRole('button', { name: 'Сохранить подписки' }).click()
    await expect(page.getByText('Подписки сохранены')).toBeVisible()

    await page.goto(`${APP}accounts`)
    await expect(page.getByTestId('connect-unavailable')).toBeVisible()
    await expect(page.getByTestId('no-subscriptions')).toBeVisible()
    await page.goto(`${APP}workers`)
    await expect(page.getByTestId('workers-unavailable')).toContainText('Исполнители не подключены')
  })
})

async function fakeAccounts(page: Page) {
  let state = 'none'
  const codes: string[] = []
  const operations: Array<{ kind: string; payload: unknown }> = []
  const login = () => ({
    id: 'login-1',
    account_id: state === 'ready' ? 'acc-1' : null,
    provider: 'claude',
    label: 'Семейная',
    state,
    error: null,
    action:
      state === 'waiting_user'
        ? { url: 'https://claude.ai/oauth/authorize?code=true', input: 'authorization_code' }
        : null,
  })
  await page.route('**/v1/accounts/snapshot', (route) =>
    json(route, {
      accounts: [
        {
          id: 'acc-1',
          provider: 'codex',
          label: 'Codex · основное',
          enabled: true,
          revision: 1,
          reset_credits: 2,
          reset_credits_observed_at: now(),
        },
      ],
      quotas: [
        {
          account_id: 'acc-1',
          scope: '*',
          bucket: '18000',
          exhausted: false,
          remaining_percent: 68,
          resets_at: new Date(Date.now() + 3_600_000).toISOString(),
          observed_at: now(),
          source: 'x',
        },
      ],
      connections: [],
      policies: [],
      logins: state === 'none' ? [] : [login()],
      can_connect: true,
      login_providers: ['claude'],
    }),
  )
  await page.route('**/v1/accounts/operations', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}') as {
      kind: string
      payload: unknown
    }
    operations.push(body)
    if (body.kind === 'account.connect') state = 'waiting_user'
    return json(route, { operation_id: crypto.randomUUID(), kind: body.kind }, 201)
  })
  await page.route('**/v1/accounts/logins/login-1', (route) => json(route, login()))
  await page.route('**/v1/accounts/logins/login-1/code', async (route) => {
    codes.push((JSON.parse(route.request().postData() ?? '{}') as { code: string }).code)
    state = 'ready'
    return json(route, { accepted: true })
  })
  return { operations, codes }
}

test('a subscription shows only fresh quota and is signed in at the provider', async ({ page }) => {
  const fake = await fakeAccounts(page)
  await page.goto(`${APP}accounts`)
  const subscription = page.getByTestId('subscriptions').getByRole('listitem').first()
  await expect(subscription).toContainText('Codex · основное')
  await expect(subscription).toContainText('Общий лимит · 5 ч')
  await expect(subscription).toContainText('Осталось 68%')
  await expect(subscription).toContainText('ручных сбросов: 2')

  await page.getByRole('button', { name: 'Подключить подписку' }).first().click()
  await page.getByLabel('Название подписки').fill('Семейная')
  await page.getByRole('button', { name: 'Начать вход' }).click()
  const card = page.getByTestId('login-card')
  await expect(card).toContainText('Войдите в аккаунт Claude')
  await expect(card.getByRole('link', { name: 'Открыть страницу входа' })).toHaveAttribute(
    'href',
    'https://claude.ai/oauth/authorize?code=true',
  )
  await card.getByLabel('Код из Claude').fill('synthetic-code')
  await card.getByRole('button', { name: 'Завершить' }).click()
  await expect(card.getByLabel('Код из Claude')).toHaveCount(0)
  await expect(card).toContainText('Подписка подключена')
  await expect(
    page.getByTestId('login-ready').getByRole('button', { name: 'Завершить подключение' }),
  ).toBeVisible()
  expect(fake.codes).toEqual(['synthetic-code'])
  expect(fake.operations[0]).toMatchObject({
    kind: 'account.connect',
    payload: { provider: 'claude', label: 'Семейная' },
  })
})

async function fakeReconnect(page: Page, outcome: 'ready' | 'failed' = 'ready') {
  let state = 'none'
  let attempt = 0
  const operations: Array<{ kind: string; payload: unknown; expected_revisions: unknown }> = []
  const login = () => ({
    id: `reconnect-${attempt}`,
    provider: 'claude',
    label: 'Claude · семейная',
    state,
    reconnect_connection_id: 'connection-1',
    error: state === 'failed' ? 'account_mismatch' : null,
    action:
      state === 'waiting_user'
        ? { url: 'https://claude.ai/oauth/authorize?state=synthetic', input: 'authorization_code' }
        : null,
  })
  await page.route('**/v1/accounts/snapshot', (route) =>
    json(route, {
      accounts: [
        { id: 'acc-1', provider: 'claude', label: 'Claude · семейная', enabled: true, revision: 1 },
      ],
      connections: [
        {
          id: 'connection-1',
          account_id: 'acc-1',
          machine: 'server',
          refresh_owner: 'server',
          enabled: true,
          auth_state: 'ready',
          revision: state === 'ready' ? 8 : 7,
          can_reconnect: true,
          quota_check: { state: state === 'ready' ? 'ok' : 'auth_required', checked_at: now() },
        },
      ],
      quotas: [
        {
          account_id: 'acc-1',
          scope: '*',
          bucket: '18000',
          remaining_percent: state === 'ready' ? 79 : 98,
          observed_at:
            state === 'ready' ? now() : new Date(Date.now() - 2 * 86400_000).toISOString(),
        },
      ],
      logins: state === 'none' ? [] : [login()],
      can_connect: true,
      login_providers: ['claude'],
    }),
  )
  await page.route('**/v1/accounts/operations', async (route) => {
    const body = route.request().postDataJSON() as (typeof operations)[number]
    operations.push(body)
    if (body.kind === 'connection.reconnect') {
      attempt++
      state = 'waiting_user'
    }
    if (body.kind === 'account.connect.cancel') state = 'cancelled'
    return json(route, { operation_id: crypto.randomUUID(), kind: body.kind }, 201)
  })
  await page.route('**/v1/accounts/logins/reconnect-*', (route) => json(route, login()))
  await page.route('**/v1/accounts/logins/reconnect-*/code', (route) => {
    state = outcome
    return json(route, { accepted: true })
  })
  return operations
}

test('reauthentication replaces access to the same subscription and refreshes its quota', async ({
  page,
}) => {
  const operations = await fakeReconnect(page)
  await page.goto(`${APP}accounts`)
  const subscriptions = page.getByTestId('subscriptions')
  await expect(subscriptions).toContainText('Нужно войти снова')
  await expect(subscriptions).not.toContainText('98%')
  await subscriptions.getByRole('button', { name: 'Войти снова' }).click()
  const card = page.getByTestId('login-card')
  await expect(card).toContainText('Войдите в тот же аккаунт провайдера')
  expect(operations[0]).toMatchObject({
    kind: 'connection.reconnect',
    payload: { connection_id: 'connection-1' },
    expected_revisions: { 'connection-1': 7 },
  })
  await expect(subscriptions.getByRole('button', { name: 'Войти снова' })).toBeDisabled()
  await card.getByLabel('Код из Claude').fill('synthetic-code#state')
  await card.getByRole('button', { name: 'Завершить' }).click()
  await expect(card).toContainText('Доступ к подписке обновлён')
  await expect(card).toContainText('Настройки подписки и доступ участников сохранены')
  await expect(subscriptions.getByRole('listitem')).toHaveCount(1)
  await expect(subscriptions).toContainText('Осталось 79%')
  await expect(subscriptions).not.toContainText('Нужно войти снова')
})

test('a wrong provider account explains the failure and allows retry and cancellation', async ({
  page,
}) => {
  const operations = await fakeReconnect(page, 'failed')
  await page.goto(`${APP}accounts`)
  const button = page.getByTestId('subscriptions').getByRole('button', { name: 'Войти снова' })
  await button.click()
  let card = page.getByTestId('login-card')
  await card.getByLabel('Код из Claude').fill('synthetic-wrong#state')
  await card.getByRole('button', { name: 'Завершить' }).click()
  await expect(card).toContainText('Вы вошли в другой аккаунт провайдера. Подписка не изменена.')
  await expect(button).toBeEnabled()
  await button.click()
  card = page.getByTestId('login-card')
  await expect(card).toContainText('Войдите в аккаунт Claude')
  await card.getByRole('button', { name: 'Отменить вход' }).click()
  await expect(card).toContainText('Вход отменён')
  await expect(button).toBeEnabled()
  await expect(page.getByTestId('subscriptions').getByRole('listitem')).toHaveCount(1)
  expect(operations.map((item) => item.kind)).toEqual([
    'connection.reconnect',
    'connection.reconnect',
    'account.connect.cancel',
  ])
})

async function fakeWorkers(page: Page) {
  const operations: Array<{ kind: string; payload: Record<string, unknown> }> = []
  const base = {
    deadline: new Date(Date.now() + 3_600_000).toISOString(),
    workspace: 'Документы',
    stop_requested: false,
    revision: 3,
    created_at: now(),
  }
  const runs = [
    {
      id: 'run-question',
      goal: 'Подготовить список покупок',
      status: 'waiting_input',
      reason: null,
      ...base,
    },
    {
      id: 'run-unknown',
      goal: 'Проверить таблицу',
      status: 'blocked',
      reason: 'outcome_unknown',
      ...base,
    },
    { id: 'run-ready', goal: 'Собрать обзор материалов', status: 'ready', reason: null, ...base },
  ]
  await page.route('**/v1/workers/snapshot', (route) =>
    json(route, {
      machines: [
        {
          id: 'm1',
          label: 'Домашний сервер',
          enabled: true,
          revision: 1,
          heartbeat: now(),
          inventory: { workspaces: ['Документы'] },
        },
      ],
      profiles: [
        {
          id: 'p1',
          label: 'Чтение',
          harness: 'codex',
          model: 'gpt-x',
          workspace: 'Документы',
          permissions: 'read_only',
          revision: 1,
        },
      ],
      executors: [
        {
          id: 'e1',
          machine_id: 'm1',
          profile_id: 'p1',
          connection_id: 'c1',
          enabled: true,
          allow_agent_dispatch: false,
          revision: 1,
        },
      ],
      runs,
      setups: [],
    }),
  )
  await page.route('**/v1/workers/runs/*', (route) => {
    const run = runs.find((item) => route.request().url().endsWith(item.id))!
    return json(route, {
      ...run,
      attempts: [],
      requests:
        run.id === 'run-question'
          ? [
              {
                id: 'req-1',
                payload: {
                  questions: [
                    {
                      id: 'q1',
                      question: 'На сколько человек считать?',
                      options: [{ label: '2' }, { label: '4' }],
                    },
                  ],
                },
              },
            ]
          : [],
      events: [
        { kind: 'progress', payload: { text: 'Рабочая область подготовлена' }, created_at: now() },
        ...(run.id === 'run-ready'
          ? [{ kind: 'artifact', payload: { path: 'out/overview.md' }, created_at: now() }]
          : []),
      ],
    })
  })
  await page.route('**/v1/workers/operations', (route) => {
    operations.push(JSON.parse(route.request().postData() ?? '{}'))
    return json(route, { operation_id: crypto.randomUUID() }, 201)
  })
  return operations
}

test('workers: a question is answered in place, an interrupted run waits for a review', async ({
  page,
}) => {
  const operations = await fakeWorkers(page)
  await page.goto(`${APP}workers`)
  const runs = page.getByTestId('runs')
  await expect(runs).toContainText('Нужен ответ')
  await expect(runs).toContainText('Нужно проверить результат прерванных действий')

  await runs
    .getByRole('listitem')
    .filter({ hasText: 'Подготовить список покупок' })
    .getByRole('button')
    .click()
  await expect(page.getByTestId('run-progress')).toContainText('Рабочая область подготовлена')
  const question = page.getByTestId('run-question')
  await question.getByRole('button', { name: '4' }).click()
  await question.getByRole('button', { name: 'Ответить и продолжить' }).click()
  await expect(page.getByText('Ответ передан')).toBeVisible()
  expect(operations.at(-1)).toMatchObject({
    kind: 'input.answer',
    payload: { request_id: 'req-1', answer: { answers: { q1: { answers: ['4'] } } } },
  })

  await page.goto(`${APP}workers/runs/run-unknown`)
  const recover = page.getByTestId('run-recover')
  await expect(recover.getByRole('button', { name: 'Подготовить продолжение' })).toBeDisabled()
  await recover.getByRole('checkbox').click()
  await recover.getByRole('button', { name: 'Подготовить продолжение' }).click()
  await expect.poll(() => operations.at(-1)?.kind).toBe('run.recover')
  expect(operations.at(-1)?.payload).toMatchObject({ effects_reviewed: true, resolution: 'pause' })

  await page.goto(`${APP}workers/runs/run-ready`)
  await expect(page.getByTestId('run-ready')).toContainText('Исходная задача остаётся открытой')
  await expect(page.getByText('out/overview.md')).toBeVisible()

  await page.goto(`${APP}workers`)
  await page.getByRole('button', { name: 'Поручить работу' }).first().click()
  await page.getByLabel('Что нужно получить').fill('Сверить счета')
  await page.getByLabel('Лимит времени, часов (0,1–24)').fill('1,5')
  await page.getByRole('button', { name: 'Запустить' }).click()
  await expect.poll(() => operations.at(-1)?.kind).toBe('run.create')
  expect(operations.at(-1)?.payload).toMatchObject({
    goal: 'Сверить счета',
    executor_ids: ['e1'],
    hours: 1.5,
    rotation: false,
  })
})

test('the development backend shows its real subscriptions read-only', async ({ page }) => {
  test.skip(ISOLATED, 'the disposable backend has no subscriptions')
  await page.goto(`${APP}accounts`)
  await expect(
    page.getByTestId('subscriptions').or(page.getByTestId('no-subscriptions')),
  ).toBeVisible()
})

test('subscription setup defaults to only the signed-in person and requires an explicit save', async ({
  page,
}) => {
  await fakeAccounts(page)
  const grants: Array<{ url: string; users: string[] }> = []
  await page.route('**/v1/admin/access', (route) => json(route, { grants: [] }))
  await page.route('**/v1/admin/accounts/*/users', async (route) => {
    const body = route.request().postDataJSON() as { users: string[] }
    grants.push({ url: route.request().url(), users: body.users })
    return json(route, { updated: true })
  })
  await page.goto(`${APP}accounts`)
  await page
    .getByTestId('subscriptions')
    .getByRole('button', { name: 'Завершить подключение' })
    .click()
  const dialog = page.getByRole('dialog')
  await expect(
    dialog.getByRole('checkbox', { name: 'Тестовый владелец', exact: true }),
  ).toBeChecked()
  await expect(dialog.getByRole('checkbox', { checked: true })).toHaveCount(1)
  expect(grants).toHaveLength(0)
  await dialog.getByRole('button', { name: 'Завершить подключение', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(grants).toHaveLength(1)
  expect(grants[0]!.url).toContain('/v1/admin/accounts/acc-1/users')
  expect(grants[0]!.users).toHaveLength(1)
})

test('the food catalog shows a running refresh, cancels it and switches a release once confirmed', async ({
  page,
}) => {
  let job = {
    id: 'job-1',
    state: 'running',
    stage: 'download',
    progress: { bytes: 1024 ** 3, bytes_total: 4 * 1024 ** 3, lines: 120000, kept: 340 },
    error: null,
    import_version_id: null,
    created_at: now(),
    updated_at: now(),
    finished_at: null as string | null,
  }
  const operations: Array<{ kind: string; payload: Record<string, unknown> }> = []
  await page.route('**/v1/nutrition/catalog', (route) =>
    json(route, {
      source: 'openfoodfacts-ru',
      active: {
        import_version_id: 'version-2',
        release: '2026-10-10',
        imported_at: now(),
        products: 13035,
        ml: 2210,
        with_package: 9120,
        units: { declared: 5074, from_quantity_text: 5633, read_by_model: 133, undeclared: 2195 },
      },
      releases: [
        {
          import_version_id: 'version-1',
          release: '2026-09-19',
          status: 'superseded',
          imported_at: now(),
          products: 13105,
        },
      ],
      job,
    }),
  )
  await page.route('**/v1/nutrition/catalog/operations', async (route) => {
    const body = route.request().postDataJSON() as (typeof operations)[number]
    operations.push(body)
    if (body.kind === 'catalog.cancel') job = { ...job, state: 'cancelled', finished_at: now() }
    return json(route, { operation_id: crypto.randomUUID(), kind: body.kind }, 201)
  })
  await page.goto(`${APP}admin/catalog`)
  await expect(page.getByTestId('catalog-active')).toContainText('Выпуск от 10 октября 2026')
  const refresh = page.getByTestId('catalog-job')
  await expect(refresh.getByRole('progressbar', { name: 'Скачивание выгрузки' })).toBeVisible()
  await expect(refresh).toContainText('Скачано 1 ГБ из 4 ГБ')
  await refresh.getByRole('button', { name: 'Отменить' }).click()
  await expect(refresh).toContainText('Обновление отменено')
  await expect(refresh.getByRole('button', { name: 'Обновить из Open Food Facts' })).toBeVisible()

  const releases = page.getByTestId('catalog-releases')
  await releases.getByRole('button', { name: 'Сделать активным' }).click()
  const dialog = page.getByRole('dialog', { name: /Сделать активным выпуск от 19 сентября 2026/ })
  await expect(dialog).toContainText('Записи в дневнике не изменятся')
  expect(operations.map((operation) => operation.kind)).toEqual(['catalog.cancel'])
  await dialog.getByRole('button', { name: 'Сделать активным' }).click()
  await expect(dialog).toBeHidden()
  expect(operations.map((operation) => operation.kind)).toEqual([
    'catalog.cancel',
    'catalog.activate',
  ])
  expect(operations[1]!.payload).toEqual({ import_version_id: 'version-1' })
})
