import AxeBuilder from '@axe-core/playwright'
import type { APIRequestContext } from '@playwright/test'
import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'

// Rendered text against the background it really sits on, in both themes: what tokens.test.ts
// cannot see (partial opacity, text on tinted surfaces, a token used on the wrong surface).
// The palette review of 8 October measured these by hand on the phone. CI runs it on a fresh
// database (.forgejo/web-e2e.sh), so the status colours are seeded first.
const SCREENS: Array<[string, string]> = [
  ['', 'Сегодня'],
  ['assistant', 'Bro'],
  ['food', 'Питание'],
  ['tasks', 'Дела'],
  ['activity', 'Активность'],
  ['personal', 'Личное'],
  ['settings', 'Настройки'],
  ['circle', 'Круг'],
]
const MARK = 'Проверка контраста'

const shift = (day: string, days: number) =>
  new Date(Date.parse(`${day}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10)

/**
 * Today's food past its calorie goal with protein's minimum reached, a task due tomorrow and one
 * overdue: amber, teal and red on Сегодня, Питание and Дела. Missing task titles and empty day
 * parts are seeded with stable daily keys, so concurrent workers replay one receipt per part
 * and a kept local stand does not pile them up.
 */
async function seed(request: APIRequestContext, origin: string) {
  const login = await request.post(`${origin}/v1/auth/development`, {
    data: {},
    headers: { Origin: origin },
  })
  expect(login.ok(), await login.text()).toBe(true)
  async function operate(
    part: 'due' | 'overdue' | 'goal' | 'food' | 'entry',
    domain: 'nutrition' | 'tasks',
    kind: string,
    payload: object,
  ) {
    const { token } = (await (await request.get(`${origin}/v1/auth/csrf`)).json()) as {
      token: string
    }
    const response = await request.post(`${origin}/v1/${domain}/operations`, {
      data: { kind, payload, expected_revisions: {} },
      headers: {
        'X-CSRF-Token': token,
        'Idempotency-Key': `contrast-seed:${today}:${part}`,
        Origin: origin,
      },
    })
    expect(response.ok(), await response.text()).toBe(true)
    return response.json()
  }
  const tasks = (await (
    await request.get(`${origin}/v1/tasks/items?q=${encodeURIComponent(MARK)}`)
  ).json()) as { items: { title: string }[]; today: string }
  const today = tasks.today
  if (!tasks.items.some((task) => task.title === `${MARK}: срок завтра`)) {
    await operate('due', 'tasks', 'task.create', {
      title: `${MARK}: срок завтра`,
      due_on: shift(today, 1),
    })
  }
  if (!tasks.items.some((task) => task.title === `${MARK}: просрочено`)) {
    await operate('overdue', 'tasks', 'task.create', {
      title: `${MARK}: просрочено`,
      due_on: shift(today, -1),
    })
  }
  const day = (await (await request.get(`${origin}/v1/nutrition/day?date=${today}`)).json()) as {
    entries: unknown[]
    goal: unknown
  }
  if (!day.goal)
    await operate('goal', 'nutrition', 'goal.set', {
      date: today,
      calorie_target: '250',
      macro_targets: { protein_g: '20' },
    })
  if (!day.entries.length) {
    const food = (await operate('food', 'nutrition', 'food.create', {
      name: `${MARK}: творог`,
      basis_unit: 'g',
      basis_quantity: '100',
      energy_kcal: '100',
      protein_g: '10',
    })) as { snapshot_id: string }
    await operate('entry', 'nutrition', 'entry.add_many', {
      date: today,
      items: [{ label: 'Творог', snapshot_id: food.snapshot_id, quantity: '300', unit: 'g' }],
    })
  }
}

test.beforeAll(async ({ playwright, baseURL }) => {
  if (!ISOLATED) return
  const origin = new URL(baseURL!).origin
  const request = await playwright.request.newContext()
  try {
    await seed(request, origin)
  } finally {
    await request.dispose()
  }
})

for (const theme of ['light', 'dark'] as const) {
  for (const [path, heading] of SCREENS) {
    test(`text on ${heading} keeps its contrast: ${theme}`, async ({ page }) => {
      // Colours are measured at rest: a fade caught halfway reads as low contrast.
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await page.addInitScript((value) => localStorage.setItem('assistant.web.theme', value), theme)
      await page.goto(`${APP}${path}`)
      await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
      // The app's own loading states; a progress bar under its goal is reka-ui's "loading" too.
      await expect(page.locator('[role="status"][data-state="loading"]')).toHaveCount(0)
      await page.waitForFunction(() =>
        document
          .getAnimations()
          .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
          .every((animation) => animation.playState !== 'running'),
      )
      const { violations } = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
      const failures = violations.flatMap((violation) =>
        violation.nodes.map((node) => `${node.target.join(' ')}: ${node.any[0]?.message ?? ''}`),
      )
      expect(failures).toEqual([])
    })
  }
}

test.describe('public Android invitations', () => {
  test.use({ signedOut: true })

  for (const theme of ['light', 'dark'] as const) {
    for (const [fragment, heading] of [
      [
        '#server=https%3A%2F%2Ffamily.example&kind=invite&token=synthetic-invite',
        'Приглашение в Bro',
      ],
      [
        '#server=http%3A%2F%2Ffamily.example&kind=invite&token=synthetic-invite',
        'Ссылка больше не действует',
      ],
    ]) {
      test(`text on ${heading} keeps its contrast: ${theme}`, async ({ page }) => {
        await page.emulateMedia({ reducedMotion: 'reduce' })
        await page.addInitScript(
          (value) => localStorage.setItem('assistant.web.theme', value),
          theme,
        )
        await page.route('**/v1/shell/android', (route) =>
          route.fulfill({ json: { release: { version_code: 42 } } }),
        )
        await page.goto(`${APP}join${fragment}`)
        await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
        if (heading === 'Приглашение в Bro')
          await expect(
            page.getByRole('link', { name: 'Скачать приложение для Android' }),
          ).toBeVisible()
        await page.waitForFunction(() =>
          document
            .getAnimations()
            .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
            .every((animation) => animation.playState !== 'running'),
        )
        const { violations } = await new AxeBuilder({ page })
          .withRules(['color-contrast'])
          .analyze()
        const failures = violations.flatMap((violation) =>
          violation.nodes.map((node) => `${node.target.join(' ')}: ${node.any[0]?.message ?? ''}`),
        )
        expect(failures).toEqual([])
      })
    }
  }
})
