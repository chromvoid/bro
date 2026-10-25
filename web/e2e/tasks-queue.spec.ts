import type { Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test, undo } from './fixtures'
import { moveAction, moveCard } from './taskMove'

test.skip(!ISOLATED, 'writes synthetic queues: requires the disposable backend')

type Item = { id: string; title: string; revision: number; state: string }
async function project(page: Page, baseURL: string, count: number) {
  const origin = new URL(baseURL).origin
  await page.goto(`${APP}tasks?scope=all`)
  const { token } = await (await page.request.get(`${origin}/v1/auth/csrf`)).json()
  const send = async (kind: string, payload: object, expected: object = {}, target?: string) => {
    const response = await page.request.post(`${origin}/v1/tasks/operations`, {
      data: {
        kind,
        payload,
        expected_revisions: expected,
        ...(target ? { target_resource_id: target } : {}),
      },
      headers: { 'X-CSRF-Token': token, 'Idempotency-Key': crypto.randomUUID(), Origin: origin },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
  const created = await send('list.create', { name: 'Очередь', list_kind: 'project' })
  const id = created.lists[0].id as string
  const tasks: Item[] = []
  for (let index = 0; index < count; index++) {
    const receipt = await send(
      'task.create',
      { title: `Queue ${String(index + 1).padStart(3, '0')}` },
      {},
      id,
    )
    tasks.push(receipt.tasks[0])
  }
  await page.goto(`${APP}tasks?list=${id}`)
  return { id, tasks, send }
}

const column = (page: Page, state = 'todo') =>
  page.getByTestId('task-board').locator(`[data-drop="${state}"]`)
const titles = (page: Page, state = 'todo') => column(page, state).locator('[data-task]')
const menu = (page: Page, task: Item, action: string) => moveCard(page, task.title, action)

test('manual order supports menus, keyboard, waiting and receipt undo, and remembers project choice', async ({
  page,
  baseURL,
}) => {
  const { id, tasks } = await project(page, baseURL!, 3)
  const [a, b, c] = tasks as [Item, Item, Item]
  await expect
    .poll(() =>
      titles(page).evaluateAll((items) => items.map((item) => item.getAttribute('aria-label'))),
    )
    .toEqual([a.title, b.title, c.title])
  await menu(page, b, 'В начало')
  await expect(titles(page).first()).toHaveAttribute('aria-label', b.title)
  await undo(page, 'Порядок изменён')
  await expect(titles(page).first()).toHaveAttribute('aria-label', a.title)
  await column(page).locator(`[data-task="${a.id}"]`).press('Alt+ArrowDown')
  await expect(titles(page).first()).toHaveAttribute('aria-label', b.title)
  await menu(page, c, 'Ожидание')
  await page.getByRole('dialog').getByLabel('Чего ждём').fill('Ответ')
  await page.getByRole('button', { name: 'Перенести в «Ожидание»' }).click()
  await expect(column(page, 'waiting').locator(`[data-task="${c.id}"]`)).toHaveCount(1)
  await undo(page, 'Перенесено в «Ожидание»')
  await expect(titles(page).last()).toHaveAttribute('aria-label', c.title)

  await page.getByRole('button', { name: 'Действия со списком' }).press('Enter')
  await page.getByRole('menuitemradio', { name: 'По сроку' }).press('Enter')
  await expect(page).toHaveURL(/sort=due/)
  await page.goto(`${APP}tasks?list=${id}`)
  // A sorted board keeps its height: the way back to one's own order is in the card's moves.
  await menu(page, a, 'Перейти в мой порядок')
  await expect(page).toHaveURL(/sort=manual/)
  await page.goto(`${APP}tasks?list=${id}&q=Queue`)
  await expect(
    page
      .getByText(
        'При поиске или фильтрах порядок изменить нельзя. Перенос между состояниями доступен.',
      )
      .first(),
  ).toBeVisible()
  await page.getByRole('button', { name: `Переместить «${a.title}»` }).press('Enter')
  await expect(moveAction(page, 'В начало')).toBeHidden()
  await moveAction(page, 'В работе').press('Enter')
  await expect(column(page, 'in_progress').locator(`[data-task="${a.id}"]`)).toHaveCount(1)
})

test('drag saves state and position together and a nonmanual drop only changes state', async ({
  page,
  baseURL,
  isMobile,
}) => {
  test.skip(isMobile, 'touch uses the same queue commands through its card menu')
  const { id, tasks } = await project(page, baseURL!, 3)
  const [a, b, c] = tasks as [Item, Item, Item]
  const writes: Array<{ kind: string; payload: Record<string, unknown> }> = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/v1/tasks/operations')
      writes.push(request.postDataJSON())
  })
  await column(page)
    .locator(`[data-task="${c.id}"]`)
    .dragTo(column(page).locator(`[data-task="${a.id}"]`), { targetPosition: { x: 20, y: 2 } })
  await expect(titles(page).first()).toHaveAttribute('aria-label', c.title)
  expect(writes.at(-1)).toMatchObject({
    kind: 'task.set_state',
    payload: { task_id: c.id, state: 'todo', before_id: a.id },
  })
  await menu(page, b, 'В работе')
  const moved = column(page, 'in_progress').locator(`[data-task="${b.id}"]`)
  await expect(moved).toBeVisible()
  // The completed move restores focus after its board refresh and unlocks dragging.
  await expect(moved).toBeFocused()
  await column(page)
    .locator(`[data-task="${c.id}"]`)
    .dragTo(column(page, 'in_progress').locator(`[data-task="${b.id}"]`), {
      targetPosition: { x: 20, y: 2 },
    })
  await expect(titles(page, 'in_progress').first()).toHaveAttribute('aria-label', c.title)
  expect(writes.at(-1)).toMatchObject({
    payload: { task_id: c.id, state: 'in_progress', before_id: b.id },
  })
  await page.goto(`${APP}tasks?list=${id}&sort=priority`)
  await expect(page.locator('#boot')).toHaveCount(0)
  const source = column(page, 'in_progress').locator(`[data-task="${b.id}"]`)
  await source.hover()
  const from = await source.boundingBox()
  const to = await column(page).boundingBox()
  expect(from).not.toBeNull()
  expect(to).not.toBeNull()
  // Activate the native drag before crossing the column; deliver dragover before releasing.
  await page.mouse.down()
  await page.mouse.move(from!.x + from!.width / 2 + 8, from!.y + from!.height / 2, { steps: 3 })
  await page.mouse.move(to!.x + to!.width / 2, to!.y + to!.height / 2, { steps: 12 })
  await page.mouse.move(to!.x + to!.width / 2 + 1, to!.y + to!.height / 2)
  await page.mouse.up()
  await expect(column(page).locator(`[data-task="${b.id}"]`)).toHaveCount(1)
  expect(writes.at(-1)?.payload).not.toHaveProperty('before_id')
})

test('130 tasks keep loaded depth and a surviving visible anchor after external edits and deletion', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(90_000)
  const { tasks, send } = await project(page, baseURL!, 130)
  await expect(titles(page)).toHaveCount(50)
  const more = page
    .getByTestId('task-board')
    .locator('[data-state="todo"]')
    .getByRole('button', { name: 'Показать ещё', exact: true })
  await more.click()
  await expect(titles(page)).toHaveCount(100)
  await more.click()
  await expect(titles(page)).toHaveCount(130)
  const middle = column(page).locator(`[data-task="${tasks[80]!.id}"]`)
  await middle.scrollIntoViewIfNeeded()
  await send(
    'task.update',
    { task_id: tasks[0]!.id, changes: { title: 'Updated externally' } },
    { [tasks[0]!.id]: 1 },
  )
  await expect(column(page).locator(`[data-task="${tasks[0]!.id}"]`)).toHaveAttribute(
    'aria-label',
    'Updated externally',
  )
  await expect(titles(page)).toHaveCount(130)
  const visible = await titles(page).evaluateAll((buttons) =>
    buttons
      .filter((button) => {
        const rect = button.getBoundingClientRect()
        return rect.bottom > 0 && rect.top < innerHeight && rect.right > 0 && rect.left < innerWidth
      })
      .map((button) => ({
        id: button.getAttribute('data-task')!,
        top: button.getBoundingClientRect().top,
      })),
  )
  expect(visible.length).toBeGreaterThan(1)
  const [anchor, next] = visible
  await send('task.delete', { task_id: anchor!.id }, { [anchor!.id]: 1 })
  await expect(titles(page)).toHaveCount(129)
  await expect
    .poll(async () => (await column(page).locator(`[data-task="${next!.id}"]`).boundingBox())!.y)
    .toBeCloseTo(next!.top, 0)
  const ids = await titles(page).evaluateAll((buttons) =>
    buttons.map((button) => button.getAttribute('data-task')),
  )
  expect(new Set(ids).size).toBe(129)
})

test('a page arriving after a search change cannot append its old unfiltered tasks', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(60_000)
  await project(page, baseURL!, 55)
  await expect(titles(page)).toHaveCount(50)
  let release!: () => void
  let entered!: () => void
  let finished!: () => void
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  const requested = new Promise<void>((resolve) => {
    entered = resolve
  })
  const delivered = new Promise<void>((resolve) => {
    finished = resolve
  })
  await page.route('**/v1/tasks/items?**', async (route) => {
    if (!new URL(route.request().url()).searchParams.has('cursor')) return route.continue()
    const response = await route.fetch()
    entered()
    await held
    await route.fulfill({ response })
    finished()
  })
  await page
    .getByTestId('task-board')
    .getByRole('button', { name: 'Показать ещё', exact: true })
    .click()
  await requested
  await page.getByRole('button', { name: 'Найти задачу', exact: true }).click()
  await page.getByRole('searchbox', { name: 'Найти задачу', exact: true }).fill('Queue 055')
  await expect(titles(page)).toHaveCount(1)
  await expect(titles(page)).toHaveAttribute('aria-label', 'Queue 055')
  release()
  await delivered
  await expect(titles(page)).toHaveCount(1)
  await expect(titles(page)).toHaveAttribute('aria-label', 'Queue 055')
})
