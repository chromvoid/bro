import type { Browser, BrowserContext, Page } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'
import { memberFor } from './members'

test.skip(!ISOLATED, 'two people write to the same spaces: needs the disposable backend')
// Each browser acts as its own member; the backend owner (e2e-owner) is the other person.
test.use({ asMember: true })

const unique = (prefix: string) => `${prefix} ${Date.now().toString(36)}`

/** The owner of the disposable backend in a separate browser session. */
async function owner(
  browser: Browser,
  baseURL: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const origin = new URL(baseURL).origin
  const context = await browser.newContext({ baseURL: origin })
  await context.request.post('/v1/auth/development', { data: {}, headers: { Origin: origin } })
  return { context, page: await context.newPage() }
}

async function createSpace(page: Page, name: string) {
  await page.goto(`${APP}circle`)
  await page.getByRole('button', { name: 'Создать пространство' }).first().click()
  await page.getByLabel('Название').fill(name)
  await page.getByRole('dialog').getByRole('button', { name: 'Создать пространство' }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
}

test('a space: invitation after a check, a shared chat, the assistant only on request, leaving and archive', async ({
  page,
  browser,
  baseURL,
}, info) => {
  const member = memberFor(info.project.name)
  const name = unique('Семья')
  await createSpace(page, name)
  await page.getByRole('link', { name: /Участники и доступ/ }).click()
  await page.getByRole('button', { name: 'Пригласить человека' }).click()
  await page.getByLabel('Логин получателя').fill('e2e-owner')
  await page.getByRole('button', { name: 'Продолжить' }).click()
  // G12: the recipient and what they will see, before anything is sent.
  await expect(page.getByTestId('invite-check')).toContainText('Тестовый владелец · @e2e-owner')
  await expect(page.getByTestId('invite-check')).toContainText('всю историю общего чата')
  await page.getByRole('button', { name: 'Отправить приглашение' }).click()
  await expect(page.getByText('Приглашение отправлено: Тестовый владелец')).toBeVisible()

  const other = await owner(browser, baseURL!)
  try {
    await other.page.goto(`${APP}circle`)
    const invitation = other.page
      .getByTestId('invitations')
      .getByRole('listitem')
      .filter({ hasText: name })
    await expect(invitation).toContainText(`Пригласил(а) ${member.name}`)
    await invitation.getByRole('button').click()
    await expect(other.page.getByText('Вам будет доступна вся история общего чата')).toBeVisible()
    await other.page.getByRole('button', { name: 'Присоединиться' }).click()
    await expect(other.page.getByRole('heading', { level: 1, name })).toBeVisible()
    await other.page.getByRole('link', { name: /Общий чат/ }).click()
    await expect(
      other.page.getByText(`Видят все участники «${name}», включая будущих`),
    ).toBeVisible()
    const chat = other.page.url()
    await other.page.getByRole('textbox', { name: /Сообщение/ }).fill('Добавим рис в покупки?')
    await other.page.getByRole('button', { name: 'Отправить', exact: true }).click()

    // The member sees it through the change stream.
    await page.getByRole('link', { name: 'Назад' }).click()
    await page.getByRole('link', { name: /Общий чат/ }).click()
    await expect(
      page.getByTestId('message').filter({ hasText: 'Добавим рис в покупки?' }),
    ).toContainText('Тестовый владелец')
    // G09: asking the assistant says where the answer appears; this backend has no assistant.
    await page.getByRole('textbox', { name: /Сообщение/ }).fill('Что приготовить с рисом?')
    await page.getByRole('button', { name: 'Позвать ассистента' }).click()
    await expect(page.getByTestId('ask-audience')).toContainText(
      `Ответ увидят все участники «${name}»`,
    )
    await page.getByRole('button', { name: 'Спросить в общем чате' }).click()
    await expect(page.getByText('Ассистент не подключён')).toBeVisible()
    await expect(page.getByRole('textbox', { name: /Сообщение/ })).toHaveValue(
      'Что приготовить с рисом?',
    )

    // G20: the other person leaves; their personal data stays theirs.
    await other.page.getByRole('link', { name: 'Назад' }).click()
    await other.page.getByRole('link', { name: /Участники и доступ/ }).click()
    await other.page.getByRole('button', { name: 'Выйти из пространства' }).click()
    await expect(other.page.getByRole('dialog')).toContainText(
      'Личные данные останутся в вашем аккаунте',
    )
    await other.page
      .getByRole('dialog')
      .getByRole('button', { name: 'Выйти из пространства' })
      .click()
    await expect(other.page.getByRole('heading', { level: 1, name: 'Круг' })).toBeVisible()
    // G18: the chat they had open is gone, not a network error.
    await other.page.goto(chat)
    await expect(other.page.getByTestId('gone')).toContainText('Этот раздел больше недоступен')
  } finally {
    await other.context.close()
  }

  // G14: the only owner cannot just leave; archiving keeps the history.
  await page.getByRole('link', { name: 'Назад' }).click()
  await page.getByRole('link', { name: /Участники и доступ/ }).click()
  await expect(page.getByTestId('sole-owner')).toContainText('Вы единственный владелец')
  await page.getByRole('button', { name: 'Архивировать пространство' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Архивировать пространство' }).click()
  await expect(page.getByText(`«${name}» архивировано. История сохранена.`)).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: 'Круг' })).toBeVisible()
  await expect(
    page.getByTestId('spaces').getByRole('listitem').filter({ hasText: name }),
  ).toHaveCount(0)
})

test('friends: a request by exact login, a direct chat, blocking and removing', async ({
  page,
  browser,
  baseURL,
}, info) => {
  const member = memberFor(info.project.name)
  const origin = new URL(baseURL!).origin
  const other = await owner(browser, baseURL!)
  try {
    const ownerId = ((await (await other.context.request.get('/v1/me')).json()) as { id: string })
      .id
    // Leave no relationship from an earlier run of this member.
    const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
      token: string
    }
    for (const action of ['unblock', 'remove'])
      await page.request.post(`${origin}/v1/relationships`, {
        data: { user_id: ownerId, action },
        headers: { 'X-CSRF-Token': token, Origin: origin },
      })

    await page.goto(`${APP}circle/friends`)
    await page.getByRole('textbox', { name: 'Точный логин' }).fill('@e2e-owner')
    await page.getByRole('button', { name: 'Найти' }).click()
    await expect(page.getByTestId('relation-state')).toContainText('Вы пока не связаны')
    await page.getByRole('button', { name: 'Добавить в друзья' }).click()
    await expect(page.getByTestId('relation-state')).toContainText('Запрос отправлен')
    await page.keyboard.press('Escape')

    await other.page.goto(`${APP}circle/friends`)
    const request = other.page
      .getByTestId('people-incoming')
      .getByRole('listitem')
      .filter({ hasText: member.login })
    await request.getByRole('button').click()
    await other.page.getByRole('button', { name: 'Принять' }).click()
    await expect(other.page.getByTestId('relation-state')).toContainText('Друг')
    await other.page.getByRole('button', { name: 'Написать' }).click()
    await expect(other.page.getByText(`Видите только вы и ${member.name}`)).toBeVisible()
    await other.page.getByRole('textbox', { name: /Сообщение/ }).fill('Привет, это личное')
    await other.page.getByRole('button', { name: 'Отправить', exact: true }).click()
    await expect(
      other.page.getByTestId('message').filter({ hasText: 'Привет, это личное' }),
    ).toBeVisible()

    // A message waits in Circle: a count beside it in the sidebar, a dot on the phone's avatar
    // (top-level screens show the avatar; nested ones show «Назад»).
    await page.goto(APP)
    await expect(page.getByTestId(/^circle-(count|dot)$/).filter({ visible: true })).toBeVisible()
    await page.goto(`${APP}circle/friends`)
    await page
      .getByTestId('people-friends')
      .getByRole('listitem')
      .filter({ hasText: 'e2e-owner' })
      .getByRole('button')
      .click()
    await page.getByRole('button', { name: 'Написать' }).click()
    await expect(
      page.getByTestId('message').filter({ hasText: 'Привет, это личное' }),
    ).toBeVisible()

    // G16: blocking closes the chat; data rights are managed elsewhere.
    await page.getByRole('link', { name: 'Назад' }).click()
    await page
      .getByTestId('people-friends')
      .getByRole('listitem')
      .filter({ hasText: 'e2e-owner' })
      .getByRole('button')
      .click()
    await page.getByRole('button', { name: 'Заблокировать' }).click()
    await expect(page.getByTestId('blocked')).toContainText(
      'Отдельные разрешения на ваши данные блокировка не отзывает',
    )
    await page.getByRole('button', { name: 'Разблокировать' }).click()
    await expect(page.getByTestId('relation-state')).toContainText('Вы пока не связаны')
  } finally {
    await other.context.close()
  }
})

test('a shared list: tasks with an assignee, and its access set apart from roles', async ({
  page,
}, info) => {
  const member = memberFor(info.project.name)
  const name = unique('Дом')
  await createSpace(page, name)
  await page.getByRole('link', { name: /Общие задачи/ }).click()
  await page.getByRole('textbox', { name: 'Новая задача' }).fill('Купить рис')
  await page.getByRole('button', { name: 'Добавить' }).click()
  const task = page
    .getByTestId('list-tasks')
    .getByRole('listitem')
    .filter({ hasText: 'Купить рис' })
  await expect(task).toContainText('Без исполнителя')
  await task.getByRole('button').click()
  await page.getByLabel('Исполнитель').selectOption({ label: member.name })
  await page.getByRole('dialog').getByRole('button', { name: 'Сохранить' }).click()
  await expect(task).toContainText(member.name)
  await task.getByRole('checkbox').click()
  await expect(task).toBeHidden()

  await page.getByRole('button', { name: 'Доступ к списку' }).click()
  await page.getByRole('radio', { name: /Только просмотр/ }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.getByText('Права сохранены')).toBeVisible()
})

test('a challenge: rules first, joining before the start, leaving with a warning', async ({
  page,
}) => {
  const name = unique('Клуб')
  await createSpace(page, name)
  await page.getByRole('link', { name: /Испытания/ }).click()
  await page.getByRole('button', { name: 'Новое испытание' }).first().click()
  await page.getByLabel('Название').fill('Дни в своём диапазоне')
  await page.getByRole('dialog').getByRole('button', { name: 'Создать испытание' }).click()
  await expect(page.getByTestId('challenge-rules')).toContainText('в ваш собственный диапазон')
  await expect(page.getByTestId('challenge-rules')).toContainText('Успешные дни и серия')
  await page.getByRole('button', { name: 'Участвовать' }).click()
  await expect(page.getByTestId('participation')).toContainText(
    'Цель и часовой пояс зафиксируются в момент старта',
  )
  await page.getByRole('button', { name: 'Выйти из рейтинга' }).click()
  await expect(page.getByRole('dialog')).toContainText('Записи питания сохранятся')
  await page.getByRole('dialog').getByRole('button', { name: 'Выйти из рейтинга' }).click()
  await expect(page.getByRole('button', { name: 'Участвовать' })).toBeVisible()
})
