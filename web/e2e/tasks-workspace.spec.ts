import type { Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.skip(!ISOLATED, 'writes synthetic tasks: needs the disposable backend')

interface Task {
  id: string
  title: string
  list_id: string
}
interface Receipt {
  lists: Array<{ id: string }>
  tasks: Task[]
}
function ledger(page: Page, baseURL: string) {
  const origin = new URL(baseURL).origin
  return async (kind: string, payload: object, target?: string) => {
    const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
      token: string
    }
    const response = await page.request.post(`${origin}/v1/tasks/operations`, {
      data: {
        kind,
        payload,
        expected_revisions: {},
        ...(target ? { target_resource_id: target } : {}),
      },
      headers: { 'X-CSRF-Token': token, 'Idempotency-Key': crypto.randomUUID(), Origin: origin },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return (await response.json()) as Receipt
  }
}

async function project(page: Page, baseURL: string, long = false) {
  const send = ledger(page, baseURL)
  const list = (await send('list.create', { name: 'Workspace', list_kind: 'project' })).lists[0]!.id
  const first = (
    await send(
      'task.create',
      {
        title: 'Plan the route',
        description: 'Read the route and check the steps before choosing dates.',
        checklist: Array.from({ length: long ? 30 : 1 }, (_, index) => ({
          id: crypto.randomUUID(),
          text: `Route step ${index + 1}`,
          done: false,
        })),
      },
      list,
    )
  ).tasks[0]!
  const second = (await send('task.create', { title: 'Book the tickets' }, list)).tasks[0]!
  await send(
    'task.create',
    { title: 'Wait for confirmation', state: 'waiting', waiting_reason: 'Reply from the hotel' },
    list,
  )
  if (long) {
    await Promise.all(
      Array.from({ length: 16 }, (_, index) =>
        send('task.create', { title: `Prepare route detail ${index + 1}` }, list),
      ),
    )
  }
  return { list, first, second }
}

test('a wide board remains usable beside the card and owns its floating add action', async ({
  page,
  baseURL,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const { list, first, second } = await project(page, baseURL!, true)
  await page.goto(`${APP}tasks?list=${list}&layout=board&sort=manual`)
  const board = page.getByTestId('task-board')
  await board.getByRole('button', { name: first.title, exact: true }).click()
  const panel = page.locator('aside[data-testid="task-panel"]')
  await expect(panel).toBeVisible()
  await expect(page.locator('[data-slot="sheet-overlay"]')).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: 'Колонки доски' })).toBeVisible()
  const boardBox = (await board.boundingBox())!
  const panelBox = (await panel.boundingBox())!
  expect(boardBox.x + boardBox.width).toBeLessThanOrEqual(panelBox.x)
  expect(
    (await board.locator('section[data-state="todo"]').boundingBox())!.width,
  ).toBeGreaterThanOrEqual(300)
  await panel.evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await expect.poll(() => panel.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  const windowTop = await page.evaluate(() => window.scrollY)
  await board.getByRole('button', { name: second.title, exact: true }).click()
  await expect(panel.getByTestId('task-title-text')).toHaveText(second.title)
  await expect.poll(() => panel.evaluate((element) => element.scrollTop)).toBe(0)
  expect(await page.evaluate(() => window.scrollY)).toBe(windowTop)
  const lower = board
    .locator('section[data-state="todo"]')
    .getByTestId('task-card')
    .nth(13)
    .getByRole('button')
    .first()
  const lowerTitle = await lower.getAttribute('aria-label')
  await lower.scrollIntoViewIfNeeded()
  const scrolledTop = await page.evaluate(() => window.scrollY)
  expect(scrolledTop).toBeGreaterThan(0)
  await lower.click()
  await expect(panel.getByTestId('task-title-text')).toHaveText(lowerTitle!)
  expect(await page.evaluate(() => window.scrollY)).toBe(scrolledTop)
  const add = page.getByTestId('board-floating-add').getByRole('button')
  await expect(add).toBeInViewport()
  const addBox = (await add.boundingBox())!
  const dockedBox = (await panel.boundingBox())!
  expect(addBox.x + addBox.width).toBeLessThanOrEqual(dockedBox.x)
  // Text scaling changes the room needed by a card: use the modal when two columns no longer fit.
  await page.evaluate(() => {
    document.documentElement.style.fontSize = '200%'
  })
  await expect(page.getByTestId('task-panel')).toHaveAttribute('role', 'dialog')
  await expect(
    page.getByTestId('task-panel').getByRole('navigation', { name: 'Задачи этой колонки' }),
  ).toBeVisible()
})

for (const width of [390, 768, 1024, 1279]) {
  test(`a ${width}px modal card navigates its loaded column and keeps unsaved edits`, async ({
    page,
    baseURL,
  }) => {
    await page.setViewportSize({ width, height: 844 })
    const { list, first, second } = await project(page, baseURL!)
    await page.goto(`${APP}tasks?list=${list}&layout=board&sort=manual`)
    await page
      .getByTestId('task-board')
      .getByRole('button', { name: first.title, exact: true })
      .click()
    const panel = page.getByTestId('task-panel')
    await expect(panel).toHaveAttribute('role', 'dialog')
    const previous = panel.getByRole('button', { name: 'Предыдущая', exact: true })
    const next = panel.getByRole('button', { name: 'Следующая', exact: true })
    await expect(previous).toBeDisabled()
    await expect(next).toBeEnabled()
    const steps = panel.getByRole('region', { name: 'Чек-лист', exact: true })
    const positions = await panel.evaluate((element) => {
      const steps = element.querySelector('section[aria-label="Чек-лист"]')!
      const properties = element.querySelector('[data-testid="task-properties"]')!
      return Boolean(steps.compareDocumentPosition(properties) & Node.DOCUMENT_POSITION_FOLLOWING)
    })
    expect(positions).toBe(true)
    await expect(steps).toBeAttached()
    await panel.getByRole('button', { name: 'Изменить: Что сделать', exact: true }).click()
    await panel.getByRole('textbox', { name: 'Что сделать', exact: true }).fill('Edited route')
    await expect(next).toBeDisabled()
    await expect(panel).toContainText('Сохраните или отмените изменения перед переходом')
    await panel.getByRole('button', { name: 'Отмена', exact: true }).click()
    await next.click()
    await expect(panel.getByTestId('task-title-text')).toHaveText(second.title)
    await expect(next).toBeDisabled()
    await expect(previous).toBeEnabled()
    await previous.click()
    await expect(panel.getByTestId('task-title-text')).toHaveText(first.title)
  })
}

test('moving a card stays in the action menu and asks about its history', async ({
  page,
  baseURL,
}) => {
  await page.setViewportSize({ width: 1279, height: 844 })
  const { list, first } = await project(page, baseURL!)
  const send = ledger(page, baseURL!)
  const destination = (
    await send('list.create', { name: 'Workspace destination', list_kind: 'project' })
  ).lists[0]!.id
  await page.goto(`${APP}tasks?list=${list}&layout=board`)
  await page
    .getByTestId('task-board')
    .getByRole('button', { name: first.title, exact: true })
    .click()
  const panel = page.getByTestId('task-panel')
  await expect(panel.getByRole('button', { name: 'В корзину', exact: true })).toHaveCount(0)
  await panel.getByRole('button', { name: 'Действия задачи', exact: true }).press('Enter')
  await page
    .getByRole('menuitem', { name: 'Перенести в другой список', exact: true })
    .press('Enter')
  const transfer = page.getByTestId('task-transfer')
  await transfer.getByRole('combobox').selectOption(destination)
  const move = transfer.getByRole('button', { name: 'Перенести в другой список', exact: true })
  await expect(move).toBeDisabled()
  await transfer.getByRole('checkbox').check()
  await move.click()
  await expect(transfer).toBeHidden()
  await expect
    .poll(
      async () => (await (await page.request.get(`/v1/tasks/items/${first.id}`)).json()).list_id,
    )
    .toBe(destination)
})
