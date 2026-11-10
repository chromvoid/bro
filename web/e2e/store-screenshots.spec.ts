import { expect, test, type Page } from '@playwright/test'
import { ISOLATED, freshPerson } from './backend'
import { fakeConversation } from './conversation'

/**
 * App Store screenshots of the web client the iOS shell shows (docs/app-store/README.md), taken
 * in WebKit at the two sizes App Store Connect requires: iPhone 6.9″ (1320 × 2868) and iPad 13″
 * (2064 × 2752). A new ordinary person on the disposable backend holds fictional tasks, meals and
 * weights; the chat is a scripted conversation. The images land in the run's output directory,
 * which the browser lane uploads; they are chosen and uploaded by hand.
 *
 * Built on the plain Playwright test, not ./fixtures: the shared fixture signs in as the
 * administrator, and WebKit's screenshot styles count as CSP reports there.
 */

test.skip(!ISOLATED, 'creates a person: needs the disposable backend (ASSISTANT_E2E_ISOLATED=1)')
test.skip(({ browserName }) => browserName !== 'webkit', 'the iOS app runs WebKit')

const APP = process.env.ASSISTANT_E2E_APP ?? '/'
const ZONE = 'Europe/Moscow'
const SCOPES = [
  'nutrition.read',
  'nutrition.write',
  'nutrition.profile',
  'personal.read',
  'personal.write',
  'tasks.read',
  'tasks.write',
  'timers.read',
]

const SIZES = [
  { name: 'iphone-6.9', viewport: { width: 440, height: 956 }, deviceScaleFactor: 3 },
  { name: 'ipad-13', viewport: { width: 1032, height: 1376 }, deviceScaleFactor: 2 },
] as const

/** A calendar day in the person's zone, `days` from today. */
function day(days = 0) {
  const date = new Date(Date.now() + days * 86_400_000)
  return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(date)
}

async function operate(page: Page, origin: string, domain: string, kind: string, payload: object) {
  const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
    token: string
  }
  const response = await page.request.post(`${origin}/v1/${domain}/operations`, {
    data: { kind, payload, expected_revisions: {} },
    headers: { 'X-CSRF-Token': token, 'Idempotency-Key': crypto.randomUUID(), Origin: origin },
  })
  expect(response.ok(), await response.text()).toBe(true)
  return response.json()
}

/** Fictional records for an ordinary person, in the shape the reviewer account gets. */
async function seed(page: Page, origin: string) {
  for (const [title, planned_on] of [
    ['Купить продукты на неделю', day()],
    ['Записаться к стоматологу', day()],
    ['Позвонить маме', day()],
    ['Забрать посылку в пункте выдачи', day()],
    ['Оплатить интернет', day()],
    ['Тренировка в 19:00', day()],
    ['Прочитать главу книги', day(1)],
    ['Составить меню на неделю', day(1)],
  ])
    await operate(page, origin, 'tasks', 'task.create', { title, planned_on })

  await operate(page, origin, 'nutrition', 'goal.set', {
    date: day(-30),
    calorie_target: '1800',
    macro_targets: { protein_g: '110', fat_g: '60', carbohydrate_g: '200' },
  })

  const meals = [
    ['Овсяная каша с ягодами', '110', '4', '3', '17', '250', 'breakfast'],
    ['Куриный суп с лапшой', '60', '5', '2', '6', '350', 'lunch'],
    ['Гречка с курицей', '150', '14', '4', '15', '300', 'lunch'],
    ['Творог с фруктами', '120', '12', '3', '11', '180', 'snack'],
  ]
  for (const [name, kcal, protein, fat, carbs, quantity, meal_kind] of meals) {
    const food = (await operate(page, origin, 'nutrition', 'food.create', {
      name,
      basis_unit: 'g',
      basis_quantity: '100',
      energy_kcal: kcal,
      protein_g: protein,
      fat_g: fat,
      carbohydrate_g: carbs,
    })) as { snapshot_id: string }
    await operate(page, origin, 'nutrition', 'entry.add_many', {
      date: day(),
      meal_kind,
      items: [{ label: name, snapshot_id: food.snapshot_id, quantity, unit: 'g' }],
    })
  }

  const weights = [
    73.4, 73.2, 73.3, 73.0, 72.9, 72.8, 72.9, 72.6, 72.5, 72.6, 72.4, 72.3, 72.2, 72.1,
  ]
  for (const [index, weight_kg] of weights.entries()) {
    const measured = new Date(Date.now() - (weights.length - 1 - index) * 86_400_000 - 600_000)
    await operate(page, origin, 'personal', 'personal.weight.add', {
      weight_kg: weight_kg.toFixed(1),
      measured_at: measured.toISOString(),
    })
  }
}

/** A finished exchange as the chat shows it; the conversation API is scripted in the page. */
async function conversation(page: Page) {
  const fake = await fakeConversation(page)
  const at = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()
  fake.seed({
    id: 'store-1',
    created_at: at(12),
    state: 'completed',
    text: 'Что приготовить на ужин, чтобы добрать белок? Осталось около 40 г.',
    turns: [
      {
        id: 'store-turn-1',
        reply_message_id: 'store-reply-1',
        state: 'completed',
        result: {
          reply: [
            'Три варианта примерно на 40 г белка:',
            '',
            '1. **Запечённая курица с гречкой** — 150 г филе и 150 г гречки: ~45 г белка, 520 ккал.',
            '2. **Омлет с творогом и зеленью** — 3 яйца и 100 г творога: ~38 г белка, 410 ккал.',
            '3. **Лосось с овощами** — 180 г лосося: ~40 г белка, 480 ккал.',
            '',
            'Скажите, какой выбрали, и я запишу его в дневник на ужин.',
          ].join('\n'),
        },
      },
    ],
  })
  fake.seed({
    id: 'store-2',
    created_at: at(5),
    state: 'completed',
    text: 'Напомни завтра в 9 записаться к стоматологу',
    turns: [
      {
        id: 'store-turn-2',
        reply_message_id: 'store-reply-2',
        state: 'completed',
        result: {
          reply: 'Готово: задача «Записаться к стоматологу» стоит на завтра, напомню в 9:00.',
        },
      },
    ],
  })
}

for (const size of SIZES) {
  test.describe(size.name, () => {
    test.use({
      viewport: size.viewport,
      deviceScaleFactor: size.deviceScaleFactor,
      timezoneId: ZONE,
      locale: 'ru-RU',
      colorScheme: 'light',
    })

    test(`store screenshots: ${size.name}`, async ({ page, baseURL }, testInfo) => {
      test.setTimeout(180_000)
      const origin = new URL(baseURL!).origin
      const person = await freshPerson(baseURL!, {
        prefix: 'store',
        name: 'Мария',
        timezone: ZONE,
        scopes: SCOPES,
      })
      await page.context().addCookies(person.cookies)
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await seed(page, origin)
      await conversation(page)

      const shots: Array<[string, string, (page: Page) => Promise<void>]> = [
        ['1-chat', 'assistant', (page) => expect(page.getByText('Лосось с овощами')).toBeVisible()],
        ['2-today', 'today', (page) => expect(page.getByTestId('today-food')).toBeVisible()],
        [
          '3-tasks',
          'tasks',
          (page) => expect(page.getByText('Записаться к стоматологу').first()).toBeVisible(),
        ],
        [
          '4-food',
          'food',
          (page) => expect(page.getByText('Гречка с курицей').first()).toBeVisible(),
        ],
        [
          '5-personal',
          'personal',
          async (page) => {
            // The weight chart: a phone folds it behind «Динамика за N дней», a tablet shows it.
            await expect(page.getByTestId('dashboard-weight')).toBeVisible()
            const open = page.getByRole('button', { name: /^Динамика за \d+ дней/ })
            if (await open.isVisible()) await open.click()
            // Thirty days, so two weeks of weighing fill the chart instead of its right edge.
            await page.getByRole('button', { name: '30 д', exact: true }).click()
            await expect(page.getByRole('img', { name: /^Измерения веса/ })).toBeVisible()
          },
        ],
      ]
      for (const [name, path, ready] of shots) {
        await page.goto(`${APP}${path}`)
        await ready(page)
        // Fonts, images and charts settle; toasts from the seeding are gone by now.
        await page.evaluate(() => document.fonts.ready)
        await page.waitForTimeout(1500)
        // The disposable database needs a unique login; the sidebar shows a plain one instead.
        await page.evaluate((login) => {
          const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
          for (let node = walker.nextNode(); node; node = walker.nextNode())
            if (node.textContent?.includes(login))
              node.textContent = node.textContent.replace(login, 'maria')
        }, person.login)
        const file = testInfo.outputPath(`${size.name}-${name}.png`)
        // `caret: 'initial'`: hiding the caret injects a style the page's CSP refuses.
        await page.screenshot({ path: file, caret: 'initial' })
        await testInfo.attach(`${size.name}-${name}`, { path: file, contentType: 'image/png' })
      }
    })
  })
}
