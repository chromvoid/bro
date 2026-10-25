import type { Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

test.skip(!ISOLATED, 'writes synthetic filter fixtures: requires the disposable backend')

async function setup(page: Page, baseURL: string, shared = false) {
  const origin = new URL(baseURL).origin
  await page.goto(`${APP}tasks?scope=all`)
  const { token } = await (await page.request.get(`${origin}/v1/auth/csrf`)).json()
  const headers = { 'X-CSRF-Token': token, Origin: origin }
  const send = async (kind: string, payload: object, expected: object = {}, target?: string) => {
    const response = await page.request.post(`${origin}/v1/tasks/operations`, {
      data: {
        kind,
        payload,
        expected_revisions: expected,
        ...(target ? { target_resource_id: target } : {}),
      },
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
  let space: string | undefined
  if (shared) {
    const response = await page.request.post(`${origin}/v1/spaces`, {
      data: { name: `Filter space ${crypto.randomUUID()}` },
      headers,
    })
    expect(response.ok(), await response.text()).toBe(true)
    space = (await response.json()).id
  }
  const project = async (name: string) => {
    const receipt = await send('list.create', {
      name,
      list_kind: 'project',
      ...(space ? { space_id: space } : {}),
    })
    return receipt.lists[0].id as string
  }
  const id = await project(`Filters ${crypto.randomUUID()}`)
  const create = async (payload: object, expected: object = {}) =>
    (await send('task.create', payload, expected, id)).tasks[0]
  const me = await (await page.request.get(`${origin}/v1/me`)).json()
  return { id, create, project, me }
}

/** «Доска | Список»: in the project header on wide screens, beside the chips on a phone. */
async function layout(page: Page, name: string, isMobile: boolean) {
  await (isMobile ? page : page.getByTestId('project-header'))
    .getByRole(isMobile ? 'button' : 'radio', { name, exact: true })
    .press('Space')
}

test('AND filters find a child independently, preserve URL across layouts and reset for another project', async ({
  page,
  baseURL,
  isMobile,
}) => {
  const { id, create, project, me } = await setup(page, baseURL!, true)
  const parent = await create({ title: 'Unmatched container' })
  const child = await create(
    {
      title: 'Matching child',
      description: 'needle',
      parent_id: parent.id,
      priority: 1,
      due_on: '2000-01-01',
      assigned_to: me.id,
    },
    { [parent.id]: parent.revision },
  )
  await create({ title: 'Unassigned needle', priority: 1, due_on: '2000-01-01' })
  await create({ title: 'Normal needle', priority: 2, due_on: '2000-01-01', assigned_to: me.id })
  await create({ title: 'Future needle', priority: 1, assigned_to: me.id })
  const anotherName = `Another ${crypto.randomUUID()}`
  const another = await project(anotherName)
  await page.goto(`${APP}tasks?list=${id}&q=needle&important=1&overdue=1&assignee=me`)
  const summary = page.getByTestId('task-filters-summary')
  await expect(summary).toContainText('Найдено: 1')
  const card = page.getByTestId('task-board').locator(`[data-task="${child.id}"]`)
  await expect(card).toBeVisible()
  await expect(page.getByTestId('task-board')).toContainText(
    'Родительская задача: Unmatched container',
  )
  await expect(page.getByTestId('project-header')).toContainText('Проект:')
  await page.getByTestId('task-filters-action').click()
  await expect(page.getByRole('radio', { name: 'Мои', exact: true })).toBeChecked()
  await page.keyboard.press('Escape')
  await layout(page, 'Список', isMobile)
  await expect(page).toHaveURL(/important=1/)
  await expect(page).toHaveURL(/overdue=1/)
  await expect(page).toHaveURL(/assignee=me/)
  await expect(page.getByTestId('tasks').getByRole('listitem')).toHaveCount(1)
  await expect(page.getByTestId('tasks')).toContainText('Родительская задача: Unmatched container')
  await summary.locator('[data-task-filter="assignee"]').click()
  await expect(summary).toContainText('Найдено: 2')
  await page.getByTestId('task-filters-action').click()
  await page.getByRole('radio', { name: 'Без исполнителя', exact: true }).click()
  await page.keyboard.press('Escape')
  await expect(page).toHaveURL(/assignee=unassigned/)
  await expect(summary).toContainText('Найдено: 1')
  await expect(page.getByTestId('tasks')).toContainText('Unassigned needle')
  await page
    .getByTestId('task-places')
    .getByRole('link', { name: anotherName, exact: true })
    .click()
  await expect(page).toHaveURL(new RegExp(`list=${another}$`))
  await expect(summary).toBeHidden()
  await expect(page.getByTestId('task-filters-action')).toHaveAccessibleName('Фильтры')
})

test('whole-history search widens the list and stays explicit across both layouts', async ({
  page,
  baseURL,
  isMobile,
}) => {
  const { id, create } = await setup(page, baseURL!)
  await create({ title: 'Completed needle', state: 'done' })
  await page.goto(`${APP}tasks?list=${id}&layout=list&q=needle`)
  const summary = page.getByTestId('task-filters-summary')
  await expect(summary).toContainText('Найдено: 0')
  await expect(summary).toContainText('Ничего не найдено в этом проекте.')
  await summary.getByRole('button', { name: 'Искать во всей истории', exact: true }).click()
  await expect(page).toHaveURL(/completed_scope=all/)
  await expect(page).toHaveURL(/view=all/)
  await expect(page.getByTestId('tasks')).toContainText('Completed needle')
  await expect(summary).toContainText('Вся история')
  await layout(page, 'Доска', isMobile)
  await expect(page.getByTestId('task-board')).toContainText('Completed needle')
  await layout(page, 'Список', isMobile)
  await expect(page).toHaveURL(/view=all/)
  await expect(summary).toContainText('Найдено: 1')
  await page.getByTestId('task-filters-action').click()
  await expect(page.getByRole('radio', { name: 'Мои', exact: true })).toBeHidden()
  await page.keyboard.press('Escape')
  await summary.getByRole('button', { name: 'Сбросить', exact: true }).click()
  await expect(summary).toBeHidden()
  await expect(page).not.toHaveURL(/q=|completed_scope=/)
})

test('counts all filtered matches before pagination and keeps conditions on continuation', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(90_000)
  const { id, create } = await setup(page, baseURL!)
  for (let index = 0; index < 55; index++)
    await create({ title: `Important ${index}`, priority: 1 })
  await page.goto(`${APP}tasks?list=${id}&important=1`)
  const summary = page.getByTestId('task-filters-summary')
  const column = page.getByTestId('task-board').locator('[data-state="todo"]')
  await expect(summary).toContainText('Найдено: 55')
  await expect(column.locator('[data-task]')).toHaveCount(50)
  const continuation = page.waitForRequest((request) => {
    const url = new URL(request.url())
    return url.pathname === '/v1/tasks/items' && url.searchParams.has('cursor')
  })
  await column.getByRole('button', { name: 'Показать ещё', exact: true }).click()
  const request = new URL((await continuation).url())
  expect(request.searchParams.get('important')).toBe('1')
  expect(request.searchParams.get('completed_scope')).toBe('recent')
  expect(request.searchParams.get('parent_id')).toBe('board')
  await expect(column.locator('[data-task]')).toHaveCount(55)
  await expect(summary).toContainText('Найдено: 55')
  await page.getByTestId('task-filters-action').click()
  await page.getByRole('checkbox', { name: 'Просрочены', exact: true }).click()
  await page.keyboard.press('Escape')
  await expect(summary).toContainText('Найдено: 0')
  await expect(page.getByText('Пусто. Перенесите сюда задачу')).toHaveCount(0)
})
