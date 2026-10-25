import type { Locator, Page, Route } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test, undo } from './fixtures'
import { moveCard } from './taskMove'

test.skip(
  !ISOLATED,
  'writes tasks and timers: needs the disposable backend (ASSISTANT_E2E_ISOLATED=1)',
)

const unique = (prefix: string) =>
  `${prefix} ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`

// The menus open from the keyboard: on a phone the toasts of the steps before lie over the top
// rows for a few seconds, and what is tested here is the menu, not that wait.

async function trashTask(page: Page) {
  await page
    .getByTestId('task-panel')
    .getByRole('button', { name: 'Действия задачи', exact: true })
    .press('Enter')
  await page.getByRole('menuitem', { name: 'В корзину', exact: true }).press('Enter')
}

/**
 * Opens a task made through the API from «Все», found by its title. In a full run the account
 * holds every spec's tasks (tasks-filters.spec.ts alone adds 55 important ones), and a new task of
 * ordinary priority is past the first page of «Все».
 */
async function openFromAll(page: Page, title: string) {
  await page.goto(`${APP}tasks?view=open&q=${encodeURIComponent(title)}`)
  await page
    .getByTestId('tasks')
    .getByRole('listitem')
    .filter({ hasText: title })
    .getByRole('button')
    .click()
}

/** A view outside «Сегодня · Скоро · Все» opens from the menu at the end of that row. */
async function showView(page: Page, menu: string, name: string) {
  await page.getByRole('button', { name: menu, exact: true }).press('Enter')
  await page.getByRole('menuitemradio', { name }).press('Enter')
}

/** «+ Проект»: a chip above the tasks on a phone, «Новый проект» in the sidebar on wide screens. */
async function newProject(page: Page, isMobile: boolean, name: string) {
  await page
    .getByTestId('task-places')
    .getByRole('link', { name: isMobile ? 'Проект' : 'Новый проект', exact: true })
    .click()
  await page.getByLabel('Название проекта').fill(name)
}

function ledger(page: Page, baseURL: string, domain: 'tasks' | 'timers') {
  const origin = new URL(baseURL).origin
  return async (kind: string, payload: object, expected: object = {}, target?: string) => {
    const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
      token: string
    }
    const response = await page.request.post(`${origin}/v1/${domain}/operations`, {
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
}

// Synthetic pictures: a yellow block on teal (48×32) and a white disc on plum (32×48).
const PICTURE =
  'iVBORw0KGgoAAAANSUhEUgAAADAAAAAgCAIAAADbtmxLAAAATklEQVR42mMUqehhGEyAiWGQgVEHjTpo1EG0Biy4JO4EzKG13SobUkajbNRBow4addCog0YdNOqgUQeNOojoBhrW1tNolI06aNRBw8BBAAPzBZQCMUXRAAAAAElFTkSuQmCC'
const PHOTO =
  'iVBORw0KGgoAAAANSUhEUgAAACAAAAAwCAIAAAD/zu84AAAAkElEQVR42u2XsQ2AMAwEkxdjUTEGFQUzUVEwCbMxACF+xVhB6FNG778kkt9K3sY5RS6k4CWAAAII8AXAQOrW87hv7tNiFmYzrovWPAZOd1MDp7uphN+9rscr7pWqHn3QdvynWkWFACEAJiP54Ov0RG2XKFahLeV5PZonCamEZ1oxmkz+cAJnsjpZAAEE+AHgAm17LA/etr9/AAAAAElFTkSuQmCC'

/** Pastes [picture] into [field] as a screenshot on the clipboard would arrive. */
async function pastePicture(field: Locator, picture: string) {
  await field.evaluate((input, base64) => {
    const data = new DataTransfer()
    const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0))
    data.items.add(new File([bytes], 'screenshot.png', { type: 'image/png' }))
    input.dispatchEvent(
      new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }),
    )
  }, picture)
}

/** Every picture inside [scope] has loaded: the browser decoded it, nothing answered 404. */
async function loaded(scope: Locator) {
  const pictures = scope.locator('img')
  await expect(pictures.first()).toBeVisible()
  await expect
    .poll(() =>
      pictures.evaluateAll((all) => all.every((img) => (img as HTMLImageElement).naturalWidth > 0)),
    )
    .toBe(true)
}

test('a picture pasted under the title and one picked under the description stay with the task', async ({
  page,
}) => {
  const title = unique('Скриншот ошибки')
  await page.goto(`${APP}tasks`)
  await page.getByPlaceholder('Новая задача').fill(title)
  await page.getByRole('button', { name: 'Добавить' }).click()
  const row = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })
  await row.getByRole('button').click()

  await page.getByRole('button', { name: 'Изменить: Что сделать', exact: true }).click()
  await pastePicture(page.getByRole('textbox', { name: 'Что сделать' }), PICTURE)
  const titlePictures = page.getByRole('list', { name: 'Изображения: Что сделать' })
  await loaded(titlePictures)
  await expect(page.getByRole('textbox', { name: 'Что сделать' })).toHaveValue(title)

  // The same screenshot again under the description is the same picture: the task keeps it once.
  await page.getByRole('button', { name: 'Изменить: Подробности', exact: true }).click()
  await pastePicture(page.getByRole('textbox', { name: 'Подробности' }), PICTURE)
  await expect(page.getByText('Это изображение уже прикреплено к задаче')).toBeVisible()
  const chooser = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: 'Прикрепить изображение: Подробности' }).click()
  await (
    await chooser
  ).setFiles({ name: 'photo.png', mimeType: 'image/png', buffer: Buffer.from(PHOTO, 'base64') })
  await loaded(page.getByRole('list', { name: 'Изображения: Подробности' }))
  await expect(
    page.getByRole('list', { name: 'Изображения: Подробности' }).locator('img'),
  ).toHaveCount(1)
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByText('Изменения сохранены')).toBeVisible()

  // The list shows the title's picture beside it; the card shows both fields' pictures again.
  await loaded(row)
  await row.getByRole('button').first().click()
  await loaded(titlePictures)
  await loaded(page.getByRole('list', { name: 'Изображения: Подробности' }))
  await titlePictures.getByRole('button', { name: 'Открыть изображение 1' }).click()
  await loaded(page.getByTestId('image-viewer'))
})

test('a task is added for today, edited, completed and restored from the trash', async ({
  page,
}) => {
  const title = unique('Забрать посылку')
  await page.goto(`${APP}tasks`)
  await page.getByPlaceholder('Новая задача').fill(title)
  await page.getByRole('button', { name: 'Добавить' }).click()
  const row = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })
  await expect(row).toBeVisible()

  await row.getByRole('button').click()
  await page.getByLabel('Крайний срок', { exact: true }).fill('2031-05-20')
  await page.getByRole('button', { name: 'Изменить: Подробности', exact: true }).click()
  await page.getByRole('textbox', { name: 'Подробности' }).fill('На почте у дома')
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByText('Изменения сохранены')).toBeVisible()
  await expect(row).toContainText('Срок: 20 мая 2031 г.')

  await row.getByRole('checkbox').click()
  await expect(page.getByText('Выполнено').first()).toBeVisible()
  await showView(page, 'Ещё', 'Выполненные')
  const done = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })
  // Open the row only once the list shows it completed, not a copy from before.
  await expect(done.getByRole('checkbox')).toBeChecked()
  await done.getByRole('button').click()
  await trashTask(page)
  await showView(page, 'Выполненные', 'Корзина')
  const trashed = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })
  await trashed.getByRole('button').click()
  await expect(page.getByText('Задача в корзине — её можно только восстановить.')).toBeVisible()
  await page.getByRole('button', { name: 'Восстановить' }).click()
  await expect(page.getByText('Задача восстановлена')).toBeVisible()
  await expect(trashed).toBeHidden()
})

test('a conflicting edit keeps the draft and applies only the fields the user changed', async ({
  page,
  baseURL,
}) => {
  const operate = ledger(page, baseURL!, 'tasks')
  const title = unique('Позвонить')
  const created = (await operate('task.create', { title })) as {
    tasks: Array<{ id: string; revision: number }>
  }
  const [task] = created.tasks
  await openFromAll(page, title)
  await page.getByLabel('Крайний срок', { exact: true }).fill('2031-06-01')

  // Someone renames the task meanwhile.
  await operate(
    'task.update',
    { task_id: task!.id, changes: { title: `${title} маме` } },
    { [task!.id]: task!.revision },
  )
  await page.getByRole('button', { name: 'Сохранить' }).click()
  const conflict = page.getByTestId('task-conflict')
  await expect(conflict).toContainText('Задача уже изменилась')
  await expect(conflict).toContainText('1 июня 2031 г.')
  await conflict.getByRole('button', { name: 'Применить мои изменения' }).click()
  await expect(page.getByText('Изменения сохранены')).toBeVisible()
  // Both edits survive: the other rename and our due date.
  const row = page
    .getByTestId('tasks')
    .getByRole('listitem')
    .filter({ hasText: `${title} маме` })
  await expect(row).toContainText('Срок: 1 июня 2031 г.')
})

test('a conflict whose newer version cannot be read still keeps the draft', async ({
  page,
  baseURL,
}) => {
  const operate = ledger(page, baseURL!, 'tasks')
  const title = unique('Купить')
  const created = (await operate('task.create', { title })) as {
    tasks: Array<{ id: string; revision: number }>
  }
  const [task] = created.tasks
  await openFromAll(page, title)
  await page.getByLabel('Крайний срок', { exact: true }).fill('2031-06-02')
  await operate(
    'task.update',
    { task_id: task!.id, changes: { title: `${title} хлеб` } },
    { [task!.id]: task!.revision },
  )
  // The save conflicts, and reading the newer version fails too.
  await page.route(`**/v1/tasks/items/${task!.id}`, (route) => route.abort('connectionreset'))
  await page.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByText(/^Задача уже изменилась\./)).toBeVisible()
  await expect(page.getByLabel('Крайний срок', { exact: true })).toHaveValue('2031-06-02')
})

test('a task reopened before its own read returns is trashed at its newest revision', async ({
  page,
}) => {
  const title = unique('Отнести книги')
  await page.goto(`${APP}tasks`)
  await page.getByPlaceholder('Новая задача').fill(title)
  await page.getByRole('button', { name: 'Добавить' }).click()
  const row = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })
  const read = (url: URL) => /^\/v1\/tasks\/items\/[^/]+$/.test(url.pathname)
  // Opened once, the panel keeps its read of the task in the cache.
  const first = page.waitForResponse((response) => read(new URL(response.url())))
  await row.getByRole('button').click()
  await first
  await page.getByTestId('task-panel').getByRole('button', { name: 'Закрыть' }).click()
  // Completed from the list, the task is now a revision past that read.
  await row.getByRole('checkbox').click()
  await expect(page.getByText('Выполнено').first()).toBeVisible()
  await showView(page, 'Ещё', 'Выполненные')
  const done = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })
  await expect(done.getByRole('checkbox')).toBeChecked()
  // Reopened while its own read is slow, the panel acts on the list's newer copy.
  await page.route(read, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 3000))
    await route.continue()
  })
  await done.getByRole('button').click()
  await trashTask(page)
  await expect(page.getByText('Задача в корзине')).toBeVisible()
  await expect(done).toBeHidden()
})

test('a timer counts down, shows on Today and can be cancelled', async ({ page }) => {
  const label = unique('Паста').slice(0, 40)
  await page.goto(`${APP}timers`)
  await page.getByLabel('Название').fill(label)
  await page.getByLabel('Минут').fill('12')
  await page.getByRole('button', { name: 'Запустить' }).click()
  const card = page.getByTestId('timers').getByRole('listitem').filter({ hasText: label })
  await expect(card.getByRole('timer')).toHaveText(/^1[12]:\d\d$/)
  const first = await card.getByRole('timer').textContent()
  await expect
    .poll(async () => card.getByRole('timer').textContent(), { timeout: 5000 })
    .not.toBe(first)

  await page.goto(APP)
  await expect(page.getByTestId('today-timers')).toContainText(label)
  await page.goto(`${APP}timers`)
  await card.getByRole('button', { name: 'Отменить таймер' }).click()
  await expect(page.getByText('Таймер отменён')).toBeVisible()
  await expect(card).toBeHidden()
  await expect(page.getByText('Недавние')).toBeVisible()

  // Undo starts the same timer again for the time that was left.
  await undo(page, 'Таймер отменён')
  await expect(page.getByText('Отменено', { exact: true })).toBeVisible()
  await expect(card.getByRole('timer')).toHaveText(/^1[01]:\d\d$/)
  await card.getByRole('button', { name: 'Отменить таймер' }).click()
  await expect(card).toBeHidden()
})

test('completing and trashing a task can be taken back from the confirmation', async ({ page }) => {
  const title = unique('Полить цветы')
  await page.goto(`${APP}tasks`)
  await page.getByPlaceholder('Новая задача').fill(title)
  await page.getByRole('button', { name: 'Добавить' }).click()
  const row = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })

  await row.getByRole('checkbox').click()
  await expect(page.getByText('Выполнено').first()).toBeVisible()
  await undo(page, 'Выполнено')
  await expect(page.getByText('Отменено', { exact: true })).toBeVisible()
  await expect(row.getByRole('checkbox')).not.toBeChecked()

  await row.getByRole('button').click()
  await trashTask(page)
  await expect(row).toBeHidden()
  await undo(page, 'Задача в корзине')
  await expect(row).toBeVisible()
  await expect(row.getByRole('checkbox')).not.toBeChecked()
})

test('a task trashed right after an undo is sent with the revision the undo returned', async ({
  page,
}) => {
  const title = unique('Вынести мусор')
  await page.goto(`${APP}tasks`)
  await page.getByPlaceholder('Новая задача').fill(title)
  await page.getByRole('button', { name: 'Добавить' }).click()
  const row = page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title })
  await expect(row).toBeVisible()

  // Tasks are read again only after the next step: the screen has the receipts to go by.
  await page.route('**/v1/tasks/items**', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 2000))
    // A read the client has cancelled meanwhile is already gone.
    await route.continue().catch(() => undefined)
  })
  await row.getByRole('checkbox').click()
  await expect(page.getByText('Выполнено').first()).toBeVisible()
  await expect(row.getByRole('checkbox')).toBeChecked({ timeout: 1000 })
  await undo(page, 'Выполнено')
  await expect(page.getByText('Отменено', { exact: true })).toBeVisible()
  await expect(row.getByRole('checkbox')).not.toBeChecked({ timeout: 1000 })

  await row.getByRole('button').click()
  await trashTask(page)
  await expect(page.getByText('Задача в корзине')).toBeVisible()
  await expect(row).toBeHidden()
})

test('a task changed elsewhere is shown as it is now when trashing it conflicts', async ({
  page,
  baseURL,
}) => {
  const operate = ledger(page, baseURL!, 'tasks')
  const title = unique('Купить хлеб')
  const created = (await operate('task.create', { title })) as {
    tasks: Array<{ id: string; revision: number }>
  }
  const [task] = created.tasks
  let pauseReads = false
  let resumeReads!: () => void
  const resumed = new Promise<void>((resolve) => {
    resumeReads = resolve
  })
  const taskReads: Promise<void>[] = []
  const readTask = (url: URL) =>
    url.pathname === '/v1/tasks/items' || url.pathname === `/v1/tasks/items/${task!.id}`
  const holdTaskRead = (route: Route) => {
    const read = (async () => {
      const response = await route.fetch()
      if (pauseReads) await resumed
      await route.fulfill({ response }).catch(() => undefined)
    })()
    taskReads.push(read)
    return read
  }
  // Install before navigation: an already-started detail read must not slip past the barrier.
  await page.route(readTask, holdTaskRead)
  await openFromAll(page, title)
  const panel = page.getByTestId('task-panel')
  await expect(panel.getByText(title, { exact: true }).last()).toBeVisible()

  // Someone renames the task. Hold refreshes until the stale write is actually refused.
  pauseReads = true
  const renamed = `${title} с отрубями`
  await operate(
    'task.update',
    { task_id: task!.id, changes: { title: renamed } },
    { [task!.id]: task!.revision },
  )
  const refused = page.waitForResponse(
    (response) => response.url().endsWith('/v1/tasks/operations') && response.status() === 409,
  )
  try {
    await trashTask(page)
    await refused
  } finally {
    pauseReads = false
    resumeReads()
  }
  // The refusal comes with the task as it is now, and a second try goes through.
  await expect(page.getByText('Задача уже изменена')).toBeVisible()
  await expect(panel.getByText(renamed, { exact: true }).last()).toBeVisible({ timeout: 1000 })
  await trashTask(page)
  await expect(page.getByText('Задача в корзине')).toBeVisible()
  // Wait for our finite reads, including responses the app stopped awaiting after deletion.
  await page.unroute(readTask, holdTaskRead)
  await Promise.all(taskReads)
})

test('a timer that goes off is told by the assistant: a toast, Today, the conversation and the bell', async ({
  page,
  baseURL,
}) => {
  const label = unique('Чай').slice(0, 40)
  await page.goto(APP)
  await expect(page.getByTestId('notifications-bell')).toBeVisible()
  await ledger(page, baseURL!, 'timers')('timer.start', { duration_seconds: 2, label })
  const title = new RegExp(`Таймер «${label}» на .+ сработал`)
  await expect(page.locator('[data-sonner-toast]').filter({ hasText: title })).toBeVisible({
    timeout: 20_000,
  })
  await expect(page.getByTestId('today-notifications')).toContainText(title)
  await expect(page.getByTestId('notifications-unread')).toBeVisible()

  await page.goto(`${APP}assistant`)
  await expect(
    page.getByTestId('conversation').getByTestId('notification').filter({ hasText: title }),
  ).toBeVisible()

  await page.goto(`${APP}notifications`)
  const card = page.getByTestId('notification').filter({ hasText: title })
  await card.getByTestId('notification-read').click()
  await expect(card.getByTestId('notification-read')).toBeHidden()
  await expect(card).not.toContainText('новое')
})

test('a reminder is set and cancelled on the timers screen; one that comes is told', async ({
  page,
  baseURL,
}) => {
  const text = unique('Позвонить маме').slice(0, 60)
  await page.goto(`${APP}timers`)
  await page.getByLabel('О чём напомнить').fill(text)
  await page.getByRole('button', { name: 'Напомнить' }).click()
  const row = page.getByTestId('reminders').getByRole('listitem').filter({ hasText: text })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Отменить напоминание' }).click()
  await expect(page.getByText('Напоминание отменено')).toBeVisible()
  await expect(row).toBeHidden()

  const soon = unique('Выключить плиту').slice(0, 60)
  await ledger(
    page,
    baseURL!,
    'timers',
  )('reminder.create', {
    // A few seconds ahead: under load the request itself may take a while to arrive.
    at: new Date(Date.now() + 8000).toISOString(),
    text: soon,
  })
  await expect(page.locator('[data-sonner-toast]').filter({ hasText: soon })).toBeVisible({
    timeout: 30_000,
  })
})

test('a reminder is set from a task, belongs to it and is cancelled there', async ({
  page,
  baseURL,
}) => {
  const operate = ledger(page, baseURL!, 'tasks')
  const title = unique('Позвонить врачу')
  await operate('task.create', { title })
  await openFromAll(page, title)
  const block = page.getByTestId('task-reminder')
  await block.getByTestId('task-remind').click()
  await block.getByRole('button', { name: 'Завтра в 9:00' }).click()
  await expect(page.getByText('Напомню: Завтра, 09:00')).toBeVisible()
  await expect(block.getByRole('listitem')).toHaveText('Завтра, 09:00')
  // On the timers screen it leads back to its task.
  await page.goto(`${APP}timers`)
  const item = page.getByTestId('reminders').getByRole('listitem').filter({ hasText: title })
  await item.getByRole('link', { name: 'К задаче' }).click()
  await expect(block.getByRole('listitem')).toHaveText('Завтра, 09:00')
  await block.getByRole('button', { name: 'Отменить напоминание' }).click()
  await expect(page.getByText('Напоминание отменено')).toBeVisible()
  await expect(block.getByRole('listitem')).toHaveCount(0)
})

test('tasks for today appear on Today and can be completed there', async ({ page }) => {
  const title = unique('Купить хлеб')
  await page.goto(`${APP}tasks`)
  await page.getByPlaceholder('Новая задача').fill(title)
  await page.getByRole('button', { name: 'Добавить' }).click()
  await expect(page.getByTestId('tasks')).toContainText(title)
  await page.goto(APP)
  const card = page.getByTestId('today-tasks')
  // The card shows the five newest; ours is the newest.
  await expect(card).toContainText(title)
  await card.getByRole('listitem').filter({ hasText: title }).getByRole('checkbox').click()
  await expect(card).not.toContainText(title)
})

test('a project opens as its board and keeps workflow, checklist, subtasks and discussion', async ({
  page,
  isMobile,
}) => {
  const project = unique('Поездка')
  const title = unique('Выбрать отель')
  await page.goto(`${APP}tasks`)
  await newProject(page, isMobile, project)
  await page.getByLabel('Ожидаемый результат').fill('Подготовить поездку')
  await page.getByRole('dialog').getByRole('button', { name: 'Создать проект' }).click()
  await expect(page).toHaveURL(/list=/)
  const header = page.getByTestId('project-header')
  await expect(header.getByRole('heading')).toHaveText(project)
  await expect(header).toContainText('Подготовить поездку')
  const board = page.getByTestId('task-board')
  const column = (state: string) => board.locator(`[data-state="${state}"]`)
  const card = board.getByTestId('task-card').filter({ hasText: title })
  // The board has no date views: a task added in a column gets its state and no date.
  await expect(page.getByRole('navigation', { name: 'Какие задачи показать' })).toBeHidden()
  await page.getByRole('button', { name: 'Добавить задачу в «К выполнению»' }).click()
  await page.getByRole('textbox', { name: 'Новая задача в колонке «К выполнению»' }).fill(title)
  await page.getByRole('textbox', { name: 'Новая задача в колонке «К выполнению»' }).press('Enter')
  await expect(page.getByTestId('board-composer').getByRole('status')).toContainText(title)
  await page.getByRole('dialog').getByRole('button', { name: 'Закрыть' }).click()
  await expect(column('todo').getByTestId('task-card').filter({ hasText: title })).toBeVisible()
  await expect(card).not.toContainText('План')
  // On a phone the columns scroll sideways inside the board, never the page.
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBe(0)
  if (isMobile) {
    await page
      .getByRole('navigation', { name: 'Колонки доски' })
      .getByRole('button', { name: /^Ждёт/ })
      .click()
    await expect(column('waiting')).toBeInViewport({ ratio: 0.8 })
  }
  // «⋯» moves it; «Ожидание» asks what for. From the keyboard: toasts may cover it on a phone.
  await moveCard(page, title, 'Ожидание')
  await page.getByRole('dialog').getByLabel('Чего ждём').fill('Ответ владельца отеля')
  await page.getByRole('button', { name: 'Перенести в «Ожидание»' }).click()
  await expect(column('waiting').getByTestId('task-card').filter({ hasText: title })).toContainText(
    'Ответ владельца отеля',
  )
  await card.click()
  await page.getByLabel('Приоритет', { exact: true }).selectOption('1')
  await page
    .getByTestId('task-panel')
    .getByRole('button', { name: 'Сохранить', exact: true })
    .click()
  await expect(card).toContainText('Важно')
  await card.click()
  // «Готово» in the state switch completes the task; the card has no button of its own for it.
  await page.getByTestId('task-state').getByRole('radio', { name: 'Готово' }).click()
  await expect(column('done').getByTestId('task-card').filter({ hasText: title })).toBeVisible()
  await undo(page, 'Перенесено в «Готово»')
  await expect(column('waiting').getByTestId('task-card').filter({ hasText: title })).toContainText(
    'Ответ владельца отеля',
  )
  // The state changes in place: the card stays open until it is closed. Closed from the keyboard:
  // another test's timer can go off meanwhile, and its alert lies over the panel's top.
  await page.getByTestId('task-panel').getByRole('button', { name: 'Закрыть' }).press('Enter')
  await card.click()
  await expect(
    page.getByTestId('task-panel').getByText('Ответ владельца отеля', { exact: true }),
  ).toBeVisible()
  const step = page.getByPlaceholder('Следующий шаг')
  await step.fill('Сравнить расположение')
  await page.locator('form').filter({ has: step }).getByRole('button', { name: 'Добавить' }).click()
  await expect(page.getByRole('checkbox', { name: 'Сравнить расположение' })).toBeVisible()
  await page.getByRole('checkbox', { name: 'Сравнить расположение' }).click()
  await expect(page.getByRole('checkbox', { name: 'Сравнить расположение' })).toBeChecked()
  const child = page.getByPlaceholder('Новая подзадача')
  await child.fill('Запросить условия отмены')
  await page
    .locator('form')
    .filter({ has: child })
    .getByRole('button', { name: 'Добавить' })
    .click()
  await expect(
    page.getByTestId('task-panel').getByRole('button', { name: 'Запросить условия отмены' }),
  ).toBeVisible()
  await page.getByRole('tab', { name: 'Обсуждение', exact: true }).click()
  await page.getByLabel('Сообщение', { exact: true }).fill('Смотрим варианты рядом с вокзалом')
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect(page.getByTestId('task-panel')).toContainText('Смотрим варианты рядом с вокзалом')
  await page.getByTestId('task-panel').getByRole('button', { name: 'Закрыть' }).press('Enter')
  // The subtask stays inside its task; the card counts it and the checklist.
  await expect(card).toContainText('1/1')
  await expect(card).toContainText('0/1')
  await expect(
    board.getByTestId('task-card').filter({ hasText: 'Запросить условия отмены' }),
  ).toBeHidden()

  // The project's progress counts its tasks, not the subtask inside one.
  await expect(header).toContainText('0 из 1 готово')
  // On a phone the switch stands beside the chips; wide screens have it in the header.
  const layout = (name: string) =>
    isMobile
      ? page.getByRole('button', { name, exact: true })
      : header.getByRole('radio', { name, exact: true })
  if (isMobile) {
    // The chip names the project and the board's «Готово» carries its progress: no row of its own.
    // The name stays for screen readers.
    const tabs = page.getByRole('navigation', { name: 'Колонки доски' })
    await expect(tabs.getByRole('button', { name: /^Готово/ })).toContainText('0/1')
    const [chips, button] = [
      (await page.getByTestId('task-places').filter({ visible: true }).boundingBox())!,
      (await layout('Список').boundingBox())!,
    ]
    expect(Math.abs(chips.y + chips.height / 2 - (button.y + button.height / 2))).toBeLessThan(12)
    await expect(header.getByRole('heading', { name: project })).toHaveCount(1)
  }
  // From the keyboard: on a phone the toasts of the steps before lie over the header.
  await layout('Список').press('Space')
  const row = page
    .getByTestId('tasks')
    .getByRole('listitem')
    .filter({
      has: page.getByRole('button', { name: title, exact: true }),
    })
  await expect(row).toContainText('Ожидание')
  // «Дела» comes back to this project, still as its list, on this device.
  await page.goto(`${APP}tasks`)
  await expect(page).toHaveURL(/list=/)
  await expect(row).toContainText('Ожидание')
  await layout('Доска').press('Space')
  await card.click()
  await expect(page.getByRole('checkbox', { name: 'Сравнить расположение' })).toBeChecked()
  await expect(
    page.getByTestId('task-panel').getByText('Ответ владельца отеля', { exact: true }),
  ).toBeVisible()
})

test('a card is dragged to another column and moved back from the keyboard', async ({
  page,
  baseURL,
  isMobile,
}) => {
  test.skip(isMobile, 'dragging is for wide screens; a phone moves cards from their menu')
  const send = ledger(page, baseURL!, 'tasks')
  await page.goto(`${APP}tasks?scope=all`)
  const created = (await send('list.create', { name: unique('Сад'), list_kind: 'project' })) as {
    lists: Array<{ id: string }>
  }
  const list = created.lists[0]!.id
  const title = unique('Посадить яблоню')
  await send('task.create', { title }, {}, list)
  await page.goto(`${APP}tasks?list=${list}`)
  const board = page.getByTestId('task-board')
  const inColumn = (state: string) =>
    board.locator(`[data-state="${state}"]`).getByTestId('task-card').filter({ hasText: title })
  await expect(inColumn('todo')).toBeVisible()
  await inColumn('todo').dragTo(board.locator('[data-drop="in_progress"]'))
  await expect(inColumn('in_progress')).toBeVisible()
  await expect(page.getByText('Перенесено в «В работе»').first()).toBeVisible()
  // The moved card takes the focus once the columns are read again.
  await expect(inColumn('in_progress').locator('[data-task]')).toBeFocused()
  await page.keyboard.press('Shift+ArrowLeft')
  await expect(inColumn('todo')).toBeVisible()
  await expect(inColumn('todo').locator('[data-task]')).toBeFocused()
})

test('the open task changes its state at once', async ({ page, baseURL }) => {
  const send = ledger(page, baseURL!, 'tasks')
  await page.goto(`${APP}tasks?scope=all`)
  const created = (await send('list.create', { name: unique('Гараж'), list_kind: 'project' })) as {
    lists: Array<{ id: string }>
  }
  const list = created.lists[0]!.id
  const title = unique('Поменять масло')
  await send('task.create', { title }, {}, list)
  await page.goto(`${APP}tasks?list=${list}`)
  const board = page.getByTestId('task-board')
  await board.getByTestId('task-card').filter({ hasText: title }).click()
  // From the keyboard: on a phone the toasts of the steps before lie over the panel's top.
  await page.getByTestId('task-state').getByRole('radio', { name: 'В работе' }).press('Space')
  await expect(page.getByText('Перенесено в «В работе»').first()).toBeVisible()
  await page.getByTestId('task-panel').getByRole('button', { name: 'Закрыть' }).click()
  await expect(
    board.locator('[data-state="in_progress"]').getByTestId('task-card').filter({ hasText: title }),
  ).toBeVisible()
})

test('timers are a section of the navigation, not a button of the tasks header', async ({
  page,
  isMobile,
}) => {
  await page.goto(`${APP}tasks?scope=all`)
  await expect(page.getByRole('heading', { name: 'Дела', level: 1 })).toBeVisible()
  await expect(
    page.getByTestId('header-actions').getByRole('link', { name: 'Таймеры' }),
  ).toHaveCount(0)
  if (isMobile) {
    await page.getByTestId('profile-menu').filter({ visible: true }).click()
    await page.getByTestId('profile-sheet').getByRole('link', { name: 'Таймеры' }).click()
  } else {
    await page
      .getByRole('navigation', { name: 'Основная навигация' })
      .filter({ visible: true })
      .getByRole('link', { name: 'Таймеры', exact: true })
      .click()
  }
  await expect(page).toHaveURL(/\/timers/)
  await expect(page.getByRole('heading', { name: 'Таймеры и напоминания', level: 1 })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Назад' })).toHaveCount(0)
})

test('an empty date view says where the other open tasks are', async ({ page, baseURL }) => {
  const send = ledger(page, baseURL!, 'tasks')
  await page.goto(`${APP}tasks`)
  const project = unique('Дача')
  const created = (await send('list.create', { name: project, list_kind: 'project' })) as {
    lists: Array<{ id: string }>
  }
  const list = created.lists[0]!.id
  const title = unique('Покрасить забор')
  await send('task.create', { title }, {}, list)
  await page.goto(`${APP}tasks?list=${list}&layout=list&view=today`)
  await expect(page.getByText('На сегодня дел нет')).toBeVisible()
  await expect(page.getByText('Остальные открытые задачи — во «Все».')).toBeVisible()
  await page.getByRole('link', { name: 'Показать все' }).click()
  await expect(
    page.getByTestId('tasks').getByRole('listitem').filter({ hasText: title }),
  ).toBeVisible()
})

test('the board stays horizontal and keeps creation within reach at every width', async ({
  page,
  baseURL,
  isMobile,
}) => {
  const operate = ledger(page, baseURL!, 'tasks')
  await page.goto(`${APP}tasks`)
  await newProject(page, isMobile, unique('Доска'))
  await page.getByRole('dialog').getByRole('button', { name: 'Создать проект' }).click()
  await expect(page).toHaveURL(/list=/)
  const listId = new URL(page.url()).searchParams.get('list')!
  for (let index = 0; index < 18; index++)
    await operate('task.create', { title: `Задача ${index + 1}`, state: 'todo' }, {}, listId)
  await page.reload()
  const board = page.getByTestId('task-board')
  await expect(board.getByTestId('task-card')).toHaveCount(18)

  for (const width of [390, 768, 1024, 1279, 1440]) {
    await page.setViewportSize({ width, height: 844 })
    await expect
      .poll(() =>
        board.locator(':scope > section').evaluateAll((columns) => {
          const tops = columns.map((column) => column.getBoundingClientRect().top)
          return Math.max(...tops) - Math.min(...tops)
        }),
      )
      .toBeLessThan(1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBe(0)
    if (width === 390) {
      const first = await board.getByTestId('task-card').first().boundingBox()
      expect(first!.y).toBeLessThanOrEqual(260)
    }
    if (width < 1280) {
      const jumps = page.getByRole('navigation', { name: 'Колонки доски' })
      const done = jumps.getByRole('button', { name: /^Готово/ })
      await done.click()
      await expect(done).toHaveAttribute('aria-current', 'true')
      await expect(done).toBeInViewport({ ratio: 0.99 })
      await expect(board.locator('[data-state="done"]')).toBeInViewport({ ratio: 0.8 })
      await jumps.getByRole('button', { name: /^Сделать/ }).click()
    }
  }

  for (const width of [1024, 1440]) {
    await page.setViewportSize({ width, height: 844 })
    await board.getByTestId('task-card').last().scrollIntoViewIfNeeded()
    const floating = page.getByTestId('board-floating-add')
    await expect(floating).toBeInViewport({ ratio: 1 })
    await floating.getByRole('button', { name: 'Добавить задачу', exact: true }).click()
    await expect(page.getByRole('menuitem')).toHaveText(['К выполнению', 'В работе', 'Ожидание'])
    await page.getByRole('menuitem', { name: 'Ожидание', exact: true }).click()
    await expect(
      page.getByRole('textbox', { name: 'Новая задача в колонке «Ожидание»' }),
    ).toBeVisible()
    await expect(page.getByTestId('board-composer').getByLabel('Чего ждём')).toBeVisible()
    await page.getByRole('dialog').getByRole('button', { name: 'Закрыть' }).click()
  }

  await page.setViewportSize({ width: 390, height: 844 })
  await board.getByTestId('task-card').last().scrollIntoViewIfNeeded()
  const add = page.getByRole('button', { name: 'Добавить задачу в «К выполнению»' })
  await expect(add).toBeInViewport({ ratio: 1 })
  await add.click()
  const form = page.getByTestId('board-composer')
  await expect(form).toHaveCount(1)
  await form.getByLabel('Новая задача в колонке «К выполнению»').fill('Новая в очереди')
  await form.getByRole('button', { name: 'Добавить', exact: true }).click()
  await expect(form.getByRole('status')).toContainText('Новая в очереди')
  await expect(form.getByLabel('Новая задача в колонке «К выполнению»')).toBeEmpty()
  await expect(form.getByLabel('Срок', { exact: true })).toBeEmpty()
  await form.getByRole('button', { name: 'В начало', exact: true }).click()
  await expect(page).toHaveURL(/sort=manual/)
  await form.getByRole('button', { name: 'Открыть', exact: true }).click()
  await expect(page.getByTestId('task-panel')).toContainText('Новая в очереди')
  await expect(page.getByTestId('board-composer')).toBeHidden()
})
