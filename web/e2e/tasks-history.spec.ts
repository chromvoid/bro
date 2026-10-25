import type { Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.skip(!ISOLATED, 'writes synthetic task history: requires the disposable backend')

interface Task {
  id: string
  list_id: string
  revision: number
  state: string
  deleted_at: string | null
}
interface Event {
  id: string
  kind: string
  created_at: string
  actor: { id: string; name: string }
  changes: Record<string, unknown>
}
interface History {
  items: Event[]
  people: Array<{ id: string; name: string }>
  next_cursor: string | null
}
interface Receipt {
  tasks: Task[]
  lists: Array<{ id: string }>
}

async function backend(page: Page, baseURL: string) {
  const origin = new URL(baseURL).origin
  const { token } = await (await page.request.get(`${origin}/v1/auth/csrf`)).json()
  const send = async (
    kind: string,
    payload: object,
    expected: object = {},
    target?: string,
    key = crypto.randomUUID(),
  ) => {
    const response = await page.request.post(`${origin}/v1/tasks/operations`, {
      data: {
        kind,
        payload,
        expected_revisions: expected,
        ...(target ? { target_resource_id: target } : {}),
      },
      headers: { 'X-CSRF-Token': token, 'Idempotency-Key': key, Origin: origin },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return (await response.json()) as Receipt
  }
  async function read<T>(path: string): Promise<T> {
    const response = await page.request.get(`${origin}/v1/${path}`)
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
  const project = async (name = 'Synthetic history project') =>
    (await send('list.create', { name, list_kind: 'project' })).lists[0]!.id
  const create = async (list: string, fields: object = {}) =>
    (await send('task.create', { title: 'Synthetic history task', ...fields }, {}, list)).tasks[0]!
  const task = (id: string) => read<Task>(`tasks/items/${id}`)
  const history = (id: string) => read<History>(`tasks/items/${id}/history`)
  return { send, read, project, create, task, history }
}

async function openHistory(page: Page, task: Task, view = 'open') {
  await page.goto(`${APP}tasks?list=${task.list_id}&layout=list&view=${view}&task=${task.id}`)
  await page.getByTestId('task-panel').getByRole('tab', { name: 'История', exact: true }).click()
  await expect(
    page.getByTestId('task-history').locator('[data-history-event]').first(),
  ).toBeVisible()
  return page.getByTestId('task-history')
}

test('history describes UI changes with author, time and scalar differences without retaining authored text', async ({
  page,
  baseURL,
}) => {
  const api = await backend(page, baseURL!)
  const list = await api.project()
  const secret = `Synthetic previous text ${crypto.randomUUID()}`
  const replacement = `Synthetic replacement ${crypto.randomUUID()}`
  const task = await api.create(list, {
    title: secret,
    description: `${secret} description`,
    checklist: [{ id: crypto.randomUUID(), text: `${secret} checklist`, done: true }],
    state: 'waiting',
    waiting_reason: `${secret} waiting`,
  })
  const discussionRequests: string[] = []
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === `/v1/tasks/items/${task.id}/discussion`)
      discussionRequests.push(request.url())
  })
  await page.goto(`${APP}tasks?list=${list}&layout=list&view=open&task=${task.id}`)
  const panel = page.getByTestId('task-panel')
  await panel.getByRole('button', { name: 'Изменить: Что сделать', exact: true }).click()
  await panel.getByRole('textbox', { name: 'Что сделать', exact: true }).fill(replacement)
  await panel.getByRole('button', { name: 'Изменить: Подробности', exact: true }).click()
  await panel.getByRole('textbox', { name: 'Подробности', exact: true }).fill(`${replacement} body`)
  await panel.locator('#task-priority').selectOption('1')
  await panel.locator('#task-planned').fill('2031-05-20')
  await panel.locator('#task-due').fill('2031-05-21')
  await panel.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(panel).toBeHidden()

  const history = await openHistory(page, task)
  const response = await api.history(task.id)
  expect(response.items.map((item) => item.kind)).toEqual(['updated', 'created'])
  const latest = response.items[0]!
  expect(latest.changes).toMatchObject({
    title: {},
    description: {},
    priority: { before: 2, after: 1 },
    planned_on: { before: null, after: '2031-05-20' },
    due_on: { before: null, after: '2031-05-21' },
  })
  const row = history.locator(`[data-history-event="${latest.id}"]`)
  await expect(row).toContainText(latest.actor.name)
  await expect(row.getByText('Задача изменена', { exact: true })).toBeVisible()
  await expect(row.getByText('Изменено название', { exact: true })).toBeVisible()
  await expect(row.getByText('Изменено описание', { exact: true })).toBeVisible()
  await expect(row).toContainText('Обычный')
  await expect(row).toContainText('Высокий')
  await expect(row).toContainText('Крайний срок')
  await expect(row).toContainText('2031')
  await expect(row.locator('time')).toHaveAttribute('datetime', latest.created_at)
  const me = await api.read<{ timezone: string }>('me')
  const stamp = await row.locator('time').textContent()
  const expectedStamp = await page.evaluate(
    ({ instant, zone }) => new Date(instant).toLocaleString('ru-RU', { timeZone: zone }),
    { instant: latest.created_at, zone: me.timezone },
  )
  expect(stamp).toBe(expectedStamp)
  expect(JSON.stringify(response)).not.toContain(secret)
  expect(JSON.stringify(response)).not.toContain(replacement)
  expect(JSON.stringify(response)).not.toContain('operation_id')
  await expect(history).not.toContainText(secret)
  await expect(history).not.toContainText(replacement)
  expect(discussionRequests).toEqual([])

  // Four complete tab labels remain usable at phone width, including the new history tab.
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(panel.locator('[data-slot="sheet-header"]')).toBeVisible()
  const tabs = panel.getByRole('tablist')
  await expect(tabs.getByRole('tab')).toHaveCount(4)
  for (const name of ['Задача', 'Ассистент', 'Обсуждение', 'История']) {
    const tab = tabs.getByRole('tab', { name, exact: true })
    await tab.scrollIntoViewIfNeeded()
    await expect(tab).toBeInViewport()
    expect(await tab.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBe(0)
  await page.evaluate(() => {
    document.documentElement.style.fontSize = '200%'
  })
  for (const name of ['Задача', 'Ассистент', 'Обсуждение', 'История']) {
    const tab = tabs.getByRole('tab', { name, exact: true })
    await tab.scrollIntoViewIfNeeded()
    await expect(tab).toBeInViewport({ ratio: 0.99 })
    expect(await tab.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
    const box = (await tab.boundingBox())!
    expect(box.width).toBeGreaterThanOrEqual(44)
    expect(box.height).toBeGreaterThanOrEqual(44)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBe(0)
  const heading = panel.getByRole('heading', { name: replacement, exact: true })
  await expect(heading).toHaveText(replacement)
  expect(await heading.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true)
  const header = panel.locator('[data-slot="sheet-header"]')
  expect((await header.boundingBox())!.height).toBeLessThan(844 * 0.6)
  const action = row.getByText('Задача изменена', { exact: true })
  await action.evaluate((element) => element.scrollIntoView({ block: 'end' }))
  await expect(action).toBeInViewport({ ratio: 0.99 })
  expect((await action.boundingBox())!.y).toBeGreaterThanOrEqual(
    (await header.boundingBox())!.y + (await header.boundingBox())!.height,
  )
  expect(
    await action.evaluate((element) => {
      const box = element.getBoundingClientRect()
      const topmost = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2)
      return topmost === element || (topmost !== null && element.contains(topmost))
    }),
  ).toBe(true)
})

test('completion, deletion and restoration stay in history, including the trash history tab', async ({
  page,
  baseURL,
}) => {
  const api = await backend(page, baseURL!)
  const list = await api.project()
  let task = await api.create(list)
  await page.goto(`${APP}tasks?list=${list}&layout=list&view=open&task=${task.id}`)
  const panel = page.getByTestId('task-panel')
  await panel
    .getByTestId('task-state')
    .getByRole('radio', { name: 'Готово', exact: true })
    .press('Space')
  await expect.poll(async () => (await api.task(task.id)).state).toBe('done')
  task = await api.task(task.id)
  const completeHistory = await openHistory(page, task, 'done')
  await expect(completeHistory.locator('[data-history-event]').first()).toContainText(
    'Состояние изменено',
  )
  await expect(completeHistory.locator('[data-history-event]').first()).toContainText(
    'К выполнению',
  )
  await expect(completeHistory.locator('[data-history-event]').first()).toContainText('Готово')
  await panel.getByRole('button', { name: 'Действия задачи', exact: true }).press('Enter')
  await page.getByRole('menuitem', { name: 'В корзину', exact: true }).press('Enter')
  await expect(panel).toBeHidden()
  task = await api.task(task.id)
  expect(task.deleted_at).not.toBeNull()

  const trashHistory = await openHistory(page, task, 'trash')
  await expect(panel.getByRole('tab')).toHaveText(['Задача', 'История'])
  await expect(trashHistory.locator('[data-history-event]').first()).toContainText('Задача удалена')
  await expect(panel.getByRole('button', { name: 'Восстановить', exact: true })).toBeHidden()
  await panel.getByRole('tab', { name: 'Задача', exact: true }).click()
  await expect(trashHistory).toBeHidden()
  await panel.getByRole('button', { name: 'Восстановить', exact: true }).click()
  await expect(panel).toBeHidden()
  task = await api.task(task.id)
  expect(task.deleted_at).toBeNull()
  const restoredHistory = await openHistory(page, task, 'done')
  await expect(restoredHistory.locator('[data-history-event]').first()).toContainText(
    'Задача восстановлена',
  )
  expect((await api.history(task.id)).items.map((item) => item.kind)).toEqual([
    'restored',
    'deleted',
    'state_changed',
    'created',
  ])
})

test('moving a task records the fact without private list names, and replay adds no history row', async ({
  page,
  baseURL,
}) => {
  const api = await backend(page, baseURL!)
  const formerName = `Synthetic private list ${crypto.randomUUID()}`
  const destinationName = `Synthetic next list ${crypto.randomUUID()}`
  const former = await api.project(formerName)
  const destination = await api.project(destinationName)
  let task = await api.create(former)
  const key = crypto.randomUUID()
  const move = { task_id: task.id, list_id: destination }
  const revisions = { [task.id]: task.revision }
  const first = await api.send('task.move', move, revisions, undefined, key)
  const replay = await api.send('task.move', move, revisions, undefined, key)
  expect(replay.tasks).toEqual(first.tasks)
  task = first.tasks[0]!
  const history = await openHistory(page, task)
  const response = await api.history(task.id)
  expect(response.items.map((item) => item.kind)).toEqual(['moved', 'created'])
  expect(response.items[0]!.changes.list_id).toEqual({})
  await expect(history.locator('[data-history-event]').first()).toContainText('Задача перенесена')
  await expect(history.locator('[data-history-event]').first()).toContainText('Изменён список')
  for (const privateValue of [formerName, destinationName, former, destination]) {
    expect(JSON.stringify(response)).not.toContain(privateValue)
    await expect(history).not.toContainText(privateValue)
  }
})

test('history loads earlier pages without duplicate events and refreshes after an external change', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(90_000)
  const api = await backend(page, baseURL!)
  const list = await api.project()
  let task = await api.create(list)
  for (let index = 0; index < 55; index++) {
    const changed = await api.send(
      'task.update',
      { task_id: task.id, changes: { priority: index % 2 === 0 ? 1 : 2 } },
      { [task.id]: task.revision },
    )
    task = changed.tasks[0]!
  }
  const history = await openHistory(page, task)
  const events = history.locator('[data-history-event]')
  await expect(events).toHaveCount(50)
  const earlier = history.getByRole('button', { name: 'Показать более ранние', exact: true })
  await earlier.click()
  await expect(events).toHaveCount(56)
  await expect(earlier).toBeHidden()
  const identities = await events.evaluateAll((items) =>
    items.map((item) => item.getAttribute('data-history-event')),
  )
  expect(new Set(identities).size).toBe(56)

  await api.send(
    'task.update',
    { task_id: task.id, changes: { priority: 3 } },
    { [task.id]: task.revision },
  )
  await expect(events).toHaveCount(57)
  await expect(events.first()).toContainText('Низкий')
  const refreshed = await events.evaluateAll((items) =>
    items.map((item) => item.getAttribute('data-history-event')),
  )
  expect(new Set(refreshed).size).toBe(57)
  const current = await api.history(task.id)
  expect(refreshed.slice(0, 50)).toEqual(current.items.map((item) => item.id))
})
