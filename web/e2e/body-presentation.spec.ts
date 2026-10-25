import type { APIResponse, Page } from '@playwright/test'
import { freshPerson, ISOLATED } from './backend'
import { APP, expect, streamCursor, test } from './fixtures'

test.skip(
  !ISOLATED,
  'body appearance writes need the disposable backend (ASSISTANT_E2E_ISOLATED=1)',
)
test.use({ asMember: true })

type Presentation = { character_id: 'sculpture' | 'spartan'; revision: number }
type Profile = { user_id: string; revision: number }
type Receipt = {
  operation_id: string
  event_seq: number
  kind: string
  body_presentation: Presentation
}

const canvas = (page: Page) => page.getByTestId('body-figure').locator('canvas')
const character = (page: Page, id: Presentation['character_id']) =>
  page.getByTestId(`atlas-character-${id}`)

/** Account-wide appearance/profile changes must not race with the shared project's member. */
const PERSON = {
  prefix: 'body-presentation',
  name: 'Синтетический персонаж',
  timezone: 'Europe/Belgrade',
  scopes: ['personal.read', 'personal.write'],
}

async function operation(
  page: Page,
  baseURL: string,
  kind: string,
  payload: object,
  key = crypto.randomUUID(),
  expected: Record<string, number> = {},
): Promise<APIResponse> {
  const origin = new URL(baseURL).origin
  const { token } = (await (await page.request.get(`${origin}/v1/auth/csrf`)).json()) as {
    token: string
  }
  return page.request.post(`${origin}/v1/personal/operations`, {
    data: { kind: `personal.${kind}`, payload, expected_revisions: expected },
    headers: { Origin: origin, 'X-CSRF-Token': token, 'Idempotency-Key': key },
  })
}

async function presentation(page: Page, baseURL: string): Promise<Presentation> {
  const response = await page.request.get(new URL('/v1/personal/body/presentation', baseURL).href)
  expect(response.ok(), await response.text()).toBe(true)
  return response.json()
}

async function profile(page: Page, baseURL: string): Promise<Profile> {
  const response = await page.request.get(new URL('/v1/personal/overview', baseURL).href)
  expect(response.ok(), await response.text()).toBe(true)
  return ((await response.json()) as { profile: Profile }).profile
}

async function setGender(page: Page, baseURL: string, gender: 'male' | 'female') {
  const old = await profile(page, baseURL)
  const response = await operation(
    page,
    baseURL,
    'profile.update',
    { gender, height_cm: 180, birth_date: '1990-06-15' },
    crypto.randomUUID(),
    { [old.user_id]: old.revision },
  )
  expect(response.status(), await response.text()).toBe(201)
}

/** Real observations through the ledger: no direct SQL and no fabricated history responses. */
async function seedBody(page: Page, baseURL: string) {
  await setGender(page, baseURL, 'male')
  const measured = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString()
  const weighed = await operation(page, baseURL, 'weight.add', {
    weight_kg: '78.4',
    measured_at: measured,
  })
  expect(weighed.status(), await weighed.text()).toBe(201)
  const caliper = await operation(page, baseURL, 'caliper.add', {
    measured_at: measured,
    folds_mm: { chest: '15', abdomen: '25', thigh: '20' },
  })
  expect(caliper.status(), await caliper.text()).toBe(201)
}

/** Appearance saving also works on devices that can only show a neutral poster. */
async function withoutGpu(page: Page) {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'gpu', { value: undefined })
    const original = HTMLCanvasElement.prototype.getContext
    HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, ...args) {
      if (/^(webgl2?|experimental-webgl|webgpu)$/.test(String(args[0]))) return null
      return original.apply(this, args)
    } as typeof original
  })
}

async function openBody(page: Page) {
  await page.goto(`${APP}personal/history?kind=body&period=30`)
  await expect(page.getByTestId('body-atlas')).toBeVisible()
  await page.getByTestId('body-figure').scrollIntoViewIfNeeded()
  await expect(page.getByTestId('body-figure').locator('img')).toBeVisible()
  await expect(character(page, 'sculpture')).toBeEnabled()
}

/** A matching URL alone also passes for a visible broken image after a missing asset request. */
async function neutralPoster(page: Page, asset: 'male' | 'female' | 'male_spartan') {
  const figure = page.getByTestId('body-figure')
  await figure.scrollIntoViewIfNeeded()
  const poster = figure.locator('img')
  await expect(poster).toBeVisible()
  // Served content-addressed, as web/tooling/bodyAssets.ts names it: /assets/body-<sha256>-<file>.
  await expect(poster).toHaveAttribute(
    'src',
    new RegExp(`/assets/body-[0-9a-f]{64}-${asset}\\.webp(?:\\?.*)?$`),
  )
  await expect
    .poll(() =>
      poster.evaluate(
        (image: HTMLImageElement) =>
          image.complete && image.naturalWidth > 0 && image.naturalHeight > 0,
      ),
    )
    .toBe(true)
  await poster.evaluate((image: HTMLImageElement) => image.decode())
  await expect(
    page
      .getByTestId('body-atlas')
      .getByText('Статичная нейтральная иллюстрация. Точные значения доступны рядом.', {
        exact: true,
      }),
  ).toBeVisible()
  for (const view of ['front', 'side', 'back', 'three-quarter']) {
    await expect(page.getByTestId(`atlas-view-${view}`)).toBeDisabled()
  }
}

test('character saving survives reload and changes reach another browser session without GPU', async ({
  page,
  browser,
  baseURL,
}) => {
  await freshPerson(baseURL!, PERSON, page.request)
  await seedBody(page, baseURL!)
  expect(await presentation(page, baseURL!)).toEqual({ character_id: 'sculpture', revision: 0 })
  await withoutGpu(page)
  const otherContext = await browser.newContext({
    baseURL,
    storageState: await page.context().storageState(),
  })
  const other = await otherContext.newPage()
  const otherProblems: string[] = []
  other.on('pageerror', (error) => {
    if (!/ due to access control checks\.$/.test(error.message)) otherProblems.push(error.message)
  })
  await other.exposeFunction('reportBodyPresentationCsp', (detail: string) =>
    otherProblems.push(detail),
  )
  await other.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      const target = window as unknown as { reportBodyPresentationCsp(detail: string): void }
      target.reportBodyPresentationCsp(`CSP ${event.violatedDirective}: ${event.blockedURI}`)
    })
  })
  try {
    await withoutGpu(other)
    await openBody(page)
    await openBody(other)
    await expect(character(page, 'sculpture')).toHaveAttribute('aria-pressed', 'true')
    await expect(character(other, 'sculpture')).toHaveAttribute('aria-pressed', 'true')
    await neutralPoster(page, 'male')
    await neutralPoster(other, 'male')
    await expect.poll(() => streamCursor(other)).toEqual(expect.any(Number))
    await character(page, 'spartan').click()
    await expect(character(page, 'spartan')).toHaveAttribute('aria-pressed', 'true')
    await expect(character(other, 'spartan')).toHaveAttribute('aria-pressed', 'true')
    await expect(canvas(other)).toHaveAttribute('data-character-id', 'spartan')
    await neutralPoster(page, 'male_spartan')
    await neutralPoster(other, 'male_spartan')
    expect(await presentation(page, baseURL!)).toEqual({ character_id: 'spartan', revision: 1 })

    await page.reload()
    await expect(character(page, 'spartan')).toHaveAttribute('aria-pressed', 'true')
    await expect(canvas(page)).toHaveAttribute('data-character-id', 'spartan')
    await neutralPoster(page, 'male_spartan')
    await expect.poll(() => streamCursor(page)).toEqual(expect.any(Number))
    await character(other, 'sculpture').click()
    await expect(character(page, 'sculpture')).toHaveAttribute('aria-pressed', 'true')
    await expect(canvas(page)).toHaveAttribute('data-character-id', 'sculpture')
    await neutralPoster(page, 'male')
    await neutralPoster(other, 'male')
    expect(await presentation(page, baseURL!)).toEqual({ character_id: 'sculpture', revision: 2 })
    expect(otherProblems).toEqual([])
  } finally {
    await otherContext.close()
  }
})

test('ledger receipts are idempotent, stale revisions conflict and accounts keep their own choice', async ({
  page,
  browser,
  baseURL,
}) => {
  await freshPerson(baseURL!, PERSON, page.request)
  const key = crypto.randomUUID()
  const payload = { character_id: 'spartan', revision: 0 }
  const first = await operation(page, baseURL!, 'body.presentation.set', payload, key)
  expect(first.status(), await first.text()).toBe(201)
  const receipt = (await first.json()) as Receipt
  expect(receipt.kind).toBe('personal.body.presentation.set')
  expect(receipt.body_presentation).toEqual({ character_id: 'spartan', revision: 1 })

  const repeated = await operation(page, baseURL!, 'body.presentation.set', payload, key)
  expect(repeated.status(), await repeated.text()).toBe(200)
  expect(await repeated.json()).toEqual(receipt)
  const lookup = await page.request.get(
    new URL(`/v1/personal/operations/by-key/${key}`, baseURL!).href,
  )
  expect(lookup.ok(), await lookup.text()).toBe(true)
  expect(await lookup.json()).toEqual(receipt)
  const stale = await operation(page, baseURL!, 'body.presentation.set', {
    character_id: 'sculpture',
    revision: 0,
  })
  expect(stale.status(), await stale.text()).toBe(409)
  const reused = await operation(
    page,
    baseURL!,
    'body.presentation.set',
    { character_id: 'sculpture', revision: 1 },
    key,
  )
  expect(reused.status(), await reused.text()).toBe(409)
  expect(await presentation(page, baseURL!)).toEqual(receipt.body_presentation)

  const otherContext = await browser.newContext({ baseURL })
  try {
    const other = await otherContext.newPage()
    await freshPerson(baseURL!, PERSON, other.request)
    expect(await presentation(other, baseURL!)).toEqual({ character_id: 'sculpture', revision: 0 })
    const foreign = await other.request.get(
      new URL(`/v1/personal/operations/by-key/${key}`, baseURL!).href,
    )
    expect(foreign.status(), await foreign.text()).toBe(404)
    const own = await operation(
      other,
      baseURL!,
      'body.presentation.set',
      { character_id: 'sculpture', revision: 0 },
      key,
    )
    expect(own.status(), await own.text()).toBe(201)
    const ownReceipt = (await own.json()) as Receipt
    expect(ownReceipt.operation_id).not.toBe(receipt.operation_id)
    expect(ownReceipt.body_presentation).toEqual({ character_id: 'sculpture', revision: 1 })
    expect(await presentation(page, baseURL!)).toEqual(receipt.body_presentation)

    await freshPerson(baseURL!, { ...PERSON, scopes: ['personal.read'] }, other.request)
    expect(await presentation(other, baseURL!)).toEqual({ character_id: 'sculpture', revision: 0 })
    const forbidden = await operation(other, baseURL!, 'body.presentation.set', payload)
    expect(forbidden.status(), await forbidden.text()).toBe(403)
    expect(await presentation(other, baseURL!)).toEqual({ character_id: 'sculpture', revision: 0 })
  } finally {
    await otherContext.close()
  }
})

test('a female profile shows a sculpture while retaining the previously saved Spartan choice', async ({
  page,
  baseURL,
}) => {
  await freshPerson(baseURL!, PERSON, page.request)
  await seedBody(page, baseURL!)
  const saved = await operation(page, baseURL!, 'body.presentation.set', {
    character_id: 'spartan',
    revision: 0,
  })
  expect(saved.status(), await saved.text()).toBe(201)
  await withoutGpu(page)
  await openBody(page)
  await expect(canvas(page)).toHaveAttribute('data-character-id', 'spartan')
  await neutralPoster(page, 'male_spartan')
  await expect.poll(() => streamCursor(page)).toEqual(expect.any(Number))

  await setGender(page, baseURL!, 'female')
  await expect(character(page, 'spartan')).toBeDisabled()
  await expect(page.getByTestId('atlas-character-unavailable')).toContainText(
    'Сейчас показана женская скульптура; выбор образа сохранён.',
  )
  await expect(canvas(page)).toHaveAttribute('data-character-id', 'sculpture')
  await neutralPoster(page, 'female')
  expect(await presentation(page, baseURL!)).toEqual({ character_id: 'spartan', revision: 1 })
  await page.reload()
  await expect(character(page, 'spartan')).toBeDisabled()
  await expect(character(page, 'spartan')).toHaveAttribute('aria-pressed', 'true')
  await expect(canvas(page)).toHaveAttribute('data-character-id', 'sculpture')
  await neutralPoster(page, 'female')
  expect(await presentation(page, baseURL!)).toEqual({ character_id: 'spartan', revision: 1 })

  const rejected = await operation(page, baseURL!, 'body.presentation.set', {
    character_id: 'spartan',
    revision: 1,
  })
  expect(rejected.status(), await rejected.text()).toBe(422)
  expect(await presentation(page, baseURL!)).toEqual({ character_id: 'spartan', revision: 1 })
})
