import type { Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'
import { moveCard } from './taskMove'

test.skip(!ISOLATED, 'writes synthetic tasks: needs the disposable backend')

interface Task {
  id: string
  revision: number
  title: string
  status: string
  parent_id: string | null
  checklist: Array<{ id: string; text: string; done: boolean }>
}
interface Receipt {
  lists: Array<{ id: string }>
  tasks: Task[]
}
function ledger(page: Page, baseURL: string) {
  const origin = new URL(baseURL).origin
  return async (kind: string, payload: object, expected: object = {}, target?: string) => {
    const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
      token: string
    }
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
    return (await response.json()) as Receipt
  }
}
async function read(page: Page, id: string) {
  const response = await page.request.get(`/v1/tasks/items/${id}`)
  expect(response.ok()).toBe(true)
  return (await response.json()) as Task
}

test('list and panel ask before completing a checklist and stale consent does not complete it', async ({
  page,
  baseURL,
}) => {
  const send = ledger(page, baseURL!)
  const list = (await send('list.create', { name: 'Checklist consent', list_kind: 'project' }))
    .lists[0]!.id
  const task = (
    await send(
      'task.create',
      {
        title: 'Review the route',
        checklist: [{ id: crypto.randomUUID(), text: 'Check the last train', done: false }],
      },
      {},
      list,
    )
  ).tasks[0]!
  await page.goto(`${APP}tasks?list=${list}&layout=list`)
  const row = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: task.title })
  await row.getByRole('checkbox').click()
  const warning = page.getByRole('dialog', { name: 'Состояние задачи' })
  await expect(warning).toContainText('невыполненные пункты: 1')
  await warning.getByRole('button', { name: 'Вернуться', exact: true }).click()
  await expect(warning).toBeHidden()
  expect((await read(page, task.id)).status).toBe('open')

  await row.getByRole('checkbox').click()
  await expect(warning).toBeVisible()
  // Another person changes this task while the confirmation is visible.
  const changed = (
    await send(
      'task.update',
      { task_id: task.id, changes: { description: 'Check the transfer too' } },
      { [task.id]: task.revision },
    )
  ).tasks[0]!
  await warning.getByRole('button', { name: 'Завершить всё равно' }).click()
  await expect(warning).toBeHidden()
  await expect(page.locator('[data-sonner-toast][data-type="error"]').first()).toBeVisible()
  expect((await read(page, task.id)).status).toBe('open')

  await row.getByRole('button').click()
  await expect(page.getByTestId('task-panel')).toContainText('Check the transfer too')
  await page.getByTestId('task-state').getByRole('radio', { name: 'Готово' }).press('Space')
  await expect(warning).toBeVisible()
  await warning.getByRole('button', { name: 'Завершить всё равно' }).click()
  await expect.poll(async () => (await read(page, task.id)).status).toBe('done')
  const done = await read(page, task.id)
  expect(done.revision).toBeGreaterThan(changed.revision)
  expect(done.checklist[0]!.done).toBe(false)
})

test('a board refuses to complete a parent and a child can become independent', async ({
  page,
  baseURL,
}) => {
  const send = ledger(page, baseURL!)
  const list = (await send('list.create', { name: 'Parent completion', list_kind: 'project' }))
    .lists[0]!.id
  const parent = (await send('task.create', { title: 'Book the trip' }, {}, list)).tasks[0]!
  const child = (
    await send(
      'task.create',
      { title: 'Compare the train tickets', parent_id: parent.id },
      { [parent.id]: parent.revision },
      list,
    )
  ).tasks[0]!
  await page.goto(`${APP}tasks?list=${list}&layout=board`)
  await moveCard(page, parent.title, 'Готово')
  const warning = page.getByRole('dialog', { name: 'Состояние задачи' })
  await expect(warning).toContainText('открытые подзадачи: 1')
  await expect(warning.getByRole('button', { name: 'Завершить всё равно' })).toHaveCount(0)
  await warning.getByRole('button', { name: 'Открыть задачу' }).click()
  const panel = page.getByTestId('task-panel')
  await panel.getByRole('button', { name: child.title, exact: true }).click()
  await expect(
    panel.getByRole('button', { name: `Родительская задача: ${parent.title}` }),
  ).toBeVisible()
  await panel.getByRole('button', { name: 'Сделать самостоятельной' }).click()
  await expect.poll(async () => (await read(page, child.id)).parent_id).toBeNull()
  await panel.getByRole('button', { name: 'Закрыть', exact: true }).click()

  await moveCard(page, parent.title, 'Готово')
  await expect.poll(async () => (await read(page, parent.id)).status).toBe('done')
  expect((await read(page, child.id)).status).toBe('open')
})
