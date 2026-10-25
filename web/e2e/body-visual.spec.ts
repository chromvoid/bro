import { expect, test as base, type Page } from '@playwright/test'
import { History } from '../src/sections/personal/api'
import {
  bodyRendererGpuRequests,
  recordBodyRendererGpu,
  requireNativeBodyRenderer,
} from './bodyNativeGpu'

// This suite deliberately does not import the database/session fixture. All responses come
// from serve_personal.py and its synthetic JSON, including when run for manual visual review.
const test = base.extend<{ pageProblems: void }>({
  pageProblems: [
    async ({ page }, use, testInfo) => {
      const problems: string[] = []
      const writes: string[] = []
      if (requireNativeWebGpu) await recordBodyRendererGpu(page)
      page.on('pageerror', (error) => problems.push(error.message))
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith('/v1/') && request.method() !== 'GET')
          writes.push(`${request.method()} ${new URL(request.url()).pathname}`)
      })
      page.on('response', (response) => {
        if (/\.glb$/.test(new URL(response.url()).pathname) && !response.ok())
          problems.push(`Model resource ${response.status()}: ${new URL(response.url()).pathname}`)
      })
      await page.exposeFunction('reportBodyCspViolation', (value: string) => problems.push(value))
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (event) => {
          const target = window as unknown as { reportBodyCspViolation(value: string): void }
          target.reportBodyCspViolation(`CSP ${event.violatedDirective}: ${event.blockedURI}`)
        })
      })
      await use()
      if (requireNativeWebGpu) {
        await testInfo.attach('renderer-gpu-requests', {
          body: JSON.stringify(await bodyRendererGpuRequests(page)),
          contentType: 'application/json',
        })
      }
      expect(problems, 'no uncaught exception or production CSP violation').toEqual([])
      expect(writes, 'viewing and comparing must never write Personal data').toEqual([])
    },
    { auto: true },
  ],
})

test.beforeEach(({}, testInfo) => {
  test.skip(
    testInfo.project.metadata.syntheticBody !== true,
    'requires the dedicated read-only synthetic stand (playwright.body.config.ts)',
  )
})

const atlas = (page: Page) => page.getByTestId('body-atlas')
const canvas = (page: Page) => page.getByTestId('body-figure').locator('canvas')
const scanId = (id: string, source = 'synthetic-a') => JSON.stringify([source, id])
const bodyResource = (path: string, file: string) =>
  /^\/assets\/body-[0-9a-f]{64}-/.test(path) && path.endsWith(`-${file}`)

async function openBody(
  page: Page,
  gender: 'male' | 'female' | 'unset' = 'male',
  character: 'sculpture' | 'spartan' = 'sculpture',
) {
  await page.goto(
    `/personal/history?kind=body&preview_gender=${gender}&preview_character=${character}`,
  )
  await expect(atlas(page)).toBeVisible()
  await page.getByTestId('body-figure').scrollIntoViewIfNeeded()
}

async function rendered(
  page: Page,
  gender: 'male' | 'female' = 'male',
  expectedMode: 'webgpu' | 'webgl' | null = requireNativeWebGpu ? 'webgpu' : null,
) {
  await expect(canvas(page)).toBeVisible()
  await expect(canvas(page)).toHaveAttribute('data-character-template', gender)
  await expect(canvas(page)).toHaveAttribute('data-renderer-morph-count', '30')
  await expect
    .poll(async () => Number(await canvas(page).getAttribute('data-renderer-frames')))
    .toBeGreaterThan(0)
  if (expectedMode) await expect(canvas(page)).toHaveAttribute('data-renderer-mode', expectedMode)
  if (requireNativeWebGpu && expectedMode === 'webgpu') await requireNativeBodyRenderer(page)
}

async function forceWebGl(page: Page) {
  await page.addInitScript(() => Object.defineProperty(navigator, 'gpu', { value: undefined }))
}

const requireNativeWebGpu = process.env.BODY_REQUIRE_NATIVE_WEBGPU === '1'

async function prepareCharacterSwaps(page: Page) {
  if (!requireNativeWebGpu) await forceWebGl(page)
}

async function renderedCharacter(page: Page, character: 'sculpture' | 'spartan') {
  await rendered(page)
  await expect(canvas(page)).toHaveAttribute('data-character-id', character)
  await expect(canvas(page)).toHaveAttribute(
    'data-renderer-mode',
    requireNativeWebGpu ? 'webgpu' : 'webgl',
  )
  if (requireNativeWebGpu) await requireNativeBodyRenderer(page)
}

/** Simulate an account change using the real read query and account visibility resync. */
async function changingPresentation(page: Page) {
  let character: 'sculpture' | 'spartan' = 'sculpture'
  let revision = 0
  let reads = 0
  await page.route('**/v1/personal/body/presentation', async (route) => {
    reads++
    await route.fulfill({ json: { character_id: character, revision } })
  })
  return {
    get character() {
      return character
    },
    async select(next: 'sculpture' | 'spartan') {
      const previousReads = reads
      character = next
      revision++
      // Query focus refetch is disabled. The account stream explicitly resyncs
      // visible tabs, invalidating the same queries as a personal.changed event.
      await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
      await expect.poll(() => reads).toBeGreaterThan(previousReads)
      await expect(page.getByTestId(`atlas-character-${next}`)).toHaveAttribute(
        'aria-pressed',
        'true',
      )
    },
  }
}

const angleDistance = (actual: number, expected: number) =>
  Math.abs(Math.atan2(Math.sin(actual - expected), Math.cos(actual - expected)))

for (const gender of ['male', 'female'] as const) {
  test(`${gender}: real 30-target model, effective metrics and automatic idle motion`, async ({
    page,
  }, testInfo) => {
    const resources: string[] = []
    page.on('request', (request) => {
      if (request.url().endsWith('.glb')) resources.push(new URL(request.url()).pathname)
    })
    await openBody(page, gender)
    await rendered(page, gender)
    await expect(canvas(page)).toHaveAttribute('data-renderer-mode', 'webgpu')
    await expect(canvas(page)).toHaveAttribute('data-motion-source', 'game-idle')
    await expect(canvas(page)).toHaveAttribute('data-character-scenario', 'idle')
    await expect(canvas(page)).toHaveAttribute('data-character-paused', 'false')
    await expect(
      atlas(page).locator('[data-testid="atlas-motion-toggle"], [data-testid^="atlas-scenario-"]'),
    ).toHaveCount(0)
    await expect(atlas(page).getByTestId('atlas-body_fat_percent')).toContainText('14 %')
    await expect(atlas(page).getByTestId('atlas-body_fat_percent')).toContainText('Ваша поправка')
    expect(resources).toHaveLength(2)
    expect(resources.some((path) => bodyResource(path, `${gender}.glb`))).toBe(true)
    expect(resources.some((path) => bodyResource(path, `${gender}-lookaround.glb`))).toBe(true)
    const initialFrames = Number(await canvas(page).getAttribute('data-renderer-frames'))
    await expect
      .poll(async () => Number(await canvas(page).getAttribute('data-renderer-frames')))
      .toBeGreaterThan(initialFrames + 1)

    await page.emulateMedia({ reducedMotion: 'reduce' })
    await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
    await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')
    const frames = await canvas(page).getAttribute('data-renderer-frames')
    await page.waitForTimeout(300)
    await expect(canvas(page)).toHaveAttribute('data-renderer-frames', frames!)
    await atlas(page).screenshot({ path: testInfo.outputPath(`${gender}-composition.png`) })

    await page.emulateMedia({ reducedMotion: 'no-preference' })
    await expect(canvas(page)).toHaveAttribute('data-character-paused', 'false')
    await page.getByTestId('body-figure').scrollIntoViewIfNeeded()
    await expect
      .poll(async () => Number(await canvas(page).getAttribute('data-renderer-frames')))
      .toBeGreaterThan(Number(frames) + 1)
  })
}

test('same-day history stays separate and comparison shows real endpoints in a relaxed pose', async ({
  page,
}, testInfo) => {
  await openBody(page)
  await rendered(page)
  const select = page.locator('#body-analysis-select')
  await expect(select.locator('option')).toHaveCount(3)
  await expect(select).toHaveValue(scanId('current'))
  await expect(select.locator('option')).toContainText(['12:00', '08:00', '08:00'])
  await atlas(page).getByRole('button', { name: 'Сравнить анализы', exact: true }).click()
  const previous = page.locator('#body-compare-analysis')
  await expect(previous).toHaveValue(scanId('same-day'))
  await expect(previous.locator('option')).toHaveCount(2)
  await page.getByTestId('body-figure').scrollIntoViewIfNeeded()
  await expect(canvas(page)).toHaveAttribute('data-character-comparison', 'true')
  await expect(canvas(page)).toHaveAttribute('data-character-pose', 'relaxed')
  await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
  const metrics = atlas(page).locator('.atlas-metrics')
  const initialMetrics = await metrics.innerText()
  const endpoints = await page.getByTestId('atlas-comparison-endpoints').innerText()
  await page.locator('#body-compare-progress').fill('47')
  await expect(metrics).toHaveText(initialMetrics, { useInnerText: true })
  await expect(page.getByTestId('atlas-comparison-endpoints')).toHaveText(endpoints, {
    useInnerText: true,
  })
  await expect(atlas(page).getByTestId('atlas-body_fat_percent')).toContainText('18 %')
  await expect(atlas(page).getByTestId('atlas-body_fat_percent')).toContainText('−4 п. п.')
  await atlas(page).screenshot({ path: testInfo.outputPath('comparison-same-day.png') })

  await previous.selectOption(scanId('previous-incomplete'))
  await expect(atlas(page)).toContainText('Нет данных для перехода: мышцы')
  await atlas(page).getByRole('button', { name: 'Закрыть сравнение', exact: true }).click()
  await expect(canvas(page)).toHaveAttribute('data-character-comparison', 'false')
  await expect(canvas(page)).toHaveAttribute('data-character-scenario', 'idle')
  await expect(canvas(page)).toHaveAttribute('data-character-paused', 'false')
  await page.locator('#body-analysis-source').selectOption('synthetic-b')
  await expect(select.locator('option')).toHaveCount(1)
  await expect(select).toHaveValue(scanId('other-source', 'synthetic-b'))
  await expect(
    atlas(page).getByRole('button', { name: 'Сравнить анализы', exact: true }),
  ).toBeDisabled()
})

test('WebGL2 and reduced motion remain usable at 390px', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await forceWebGl(page)
  await openBody(page, 'female')
  await rendered(page, 'female', 'webgl')
  await expect(canvas(page)).toHaveAttribute('data-renderer-mode', 'webgl')
  await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
  await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  const frames = await canvas(page).getAttribute('data-renderer-frames')
  await page.waitForTimeout(300)
  await expect(canvas(page)).toHaveAttribute('data-renderer-frames', frames!)
  await atlas(page).screenshot({ path: testInfo.outputPath('female-mobile-webgl-reduced.png') })
})

test('without GPU APIs the neutral poster keeps exact data and gender fallback never writes', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'gpu', { value: undefined })
    const original = HTMLCanvasElement.prototype.getContext
    HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, ...args) {
      if (/^(webgl2?|experimental-webgl|webgpu)$/.test(String(args[0]))) return null
      return original.apply(this, args)
    } as typeof original
  })
  await openBody(page, 'unset')
  await expect(atlas(page)).toContainText('Статичная нейтральная иллюстрация')
  await expect(page.getByTestId('body-figure').locator('img')).toBeVisible()
  await expect(page.getByTestId('body-figure').locator('img')).toHaveAttribute(
    'src',
    /male.*\.webp/,
  )
  await expect(atlas(page).getByTestId('atlas-skeletal_muscle_mass')).toContainText('38,8 кг')
  await expect(page.getByRole('button', { name: 'Указать гендер' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await atlas(page).screenshot({ path: testInfo.outputPath('neutral-poster-mobile.png') })
})

test('offscreen rendering stops, context loss preserves data and reopening recovers', async ({
  page,
}) => {
  await forceWebGl(page)
  await openBody(page)
  await rendered(page, 'male', 'webgl')
  await expect(canvas(page)).toHaveAttribute('data-renderer-mode', 'webgl')
  await page.getByTestId('body-history').locator('li').last().scrollIntoViewIfNeeded()
  await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'inactive')
  const frames = await canvas(page).getAttribute('data-renderer-frames')
  await page.waitForTimeout(300)
  await expect(canvas(page)).toHaveAttribute('data-renderer-frames', frames!)
  await page.getByTestId('body-figure').scrollIntoViewIfNeeded()
  await expect
    .poll(async () => Number(await canvas(page).getAttribute('data-renderer-frames')))
    .toBeGreaterThan(Number(frames))

  expect(
    await canvas(page).evaluate((element) => {
      if (!(element instanceof HTMLCanvasElement)) return false
      const gl = element.getContext('webgl2')
      const extension = gl?.getExtension('WEBGL_lose_context')
      extension?.loseContext()
      return !!extension
    }),
  ).toBe(true)
  await expect(page.getByTestId('body-figure').locator('img')).toBeVisible()
  await expect(atlas(page)).toContainText('Статичная нейтральная иллюстрация')
  await expect(atlas(page).getByTestId('atlas-body_fat_percent')).toContainText('14 %')
  await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'disposed')
  await page.reload()
  await page.getByTestId('body-figure').scrollIntoViewIfNeeded()
  await rendered(page, 'male', 'webgl')
})

test('contained history cards remain keyboard-accessible and resize across pages on mobile', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await forceWebGl(page)
  await page.route('**/v1/personal/history?*', async (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    const response = await route.fetch()
    const history = History.parse(await response.json())
    const current = history.records.filter((record) => record.origin?.record_id === 'current')
    expect(current).toHaveLength(6)
    const records = Array.from({ length: 31 }, (_, index) => {
      const id = `paged-${index}`
      const at = new Date(Date.UTC(2026, 8, index + 1, 12)).toISOString()
      return current.map((record) => ({
        ...record,
        id: `${id}-${record.kind}`,
        start_at: at,
        end_at: at,
        local_date: at.slice(0, 10),
        origin: { ...record.origin, record_id: id },
        original: record.original
          ? {
              ...record.original,
              start_at: at,
              origin: { ...record.original.origin, record_id: id },
            }
          : record.original,
      }))
    }).flat()
    await route.fulfill({ response, json: History.parse({ ...history, records }) })
  })

  await openBody(page)
  const history = page.getByTestId('body-history')
  const cards = history.getByTestId('body-analysis')
  const slots = history.locator(':scope > ul > li')
  const select = page.locator('#body-analysis-select')
  const nav = history.getByRole('navigation', { name: 'Страницы анализов состава тела' })
  const heading = history.getByRole('heading', { name: '31 анализ', exact: true })
  await expect(cards).toHaveCount(12)
  await expect(select.locator('option')).toHaveCount(31)
  await expect(nav.getByRole('status')).toHaveText('1 из 3')
  await expect(cards.first().locator('time')).toHaveAttribute(
    'datetime',
    '2026-10-01T12:00:00.000Z',
  )

  const lastCard = cards.last()
  const lastSlot = slots.last()
  const summary = lastCard.locator('summary')
  await expect(summary).not.toBeInViewport()
  await summary.focus()
  await expect(summary).toBeFocused()
  await expect(summary).toBeInViewport()
  await expect(lastCard.locator('details > ul')).toHaveCount(0)
  const closedHeight = (await lastSlot.boundingBox())!.height
  await summary.press('Enter')
  await expect(lastCard.locator('details')).toHaveAttribute('open', '')
  await expect(lastCard.locator('details > ul > li')).toHaveCount(6)
  const openHeight = (await lastSlot.boundingBox())!.height
  expect(openHeight).toBeGreaterThan(closedHeight)

  const correction = lastCard.getByRole('button', { name: 'Исправить: Вес', exact: true })
  await correction.click()
  const dialog = page.getByRole('dialog', { name: 'Исправить измерение' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('Вес, кг', { exact: true })).toHaveValue('86,4')
  await expect(dialog.getByLabel('Когда измерено', { exact: true })).toHaveValue('2026-09-20T12:00')
  await dialog.press('Escape')
  await expect(dialog).not.toBeVisible()
  await summary.focus()
  await summary.press('Enter')
  await expect(lastCard.locator('details > ul')).toHaveCount(0)
  const collapsedHeight = (await lastSlot.boundingBox())!.height
  expect(collapsedHeight).toBeCloseTo(closedHeight, 0)

  // A remembered intrinsic size must update after closing details, not leave their tall box.
  await page.getByTestId('body-figure').scrollIntoViewIfNeeded()
  const layerControl = atlas(page).locator('.atlas-layers button').first()
  await layerControl.focus()
  await expect(layerControl).toBeFocused()
  await expect(summary).not.toBeInViewport()
  await summary.focus()
  await expect(summary).toBeInViewport()
  const revisitedHeight = (await lastSlot.boundingBox())!.height
  expect(revisitedHeight).toBeCloseTo(closedHeight, 0)

  const pageOffsets: number[] = []
  for (const [number, count, firstDate] of [
    [2, 12, '2026-09-19T12:00:00.000Z'],
    [3, 7, '2026-09-07T12:00:00.000Z'],
  ] as const) {
    await nav.getByRole('button', { name: 'Далее', exact: true }).click()
    await expect(nav.getByRole('status')).toHaveText(`${number} из 3`)
    await expect(cards).toHaveCount(count)
    await expect(cards.first().locator('time')).toHaveAttribute('datetime', firstDate)
    await expect(heading).toBeInViewport()
    const titleBox = (await heading.boundingBox())!
    const firstBox = (await slots.first().boundingBox())!
    const offset = firstBox.y - (titleBox.y + titleBox.height)
    pageOffsets.push(offset)
    expect(offset).toBeGreaterThanOrEqual(0)
    expect(offset).toBeLessThan(128)
    expect(firstBox.height).toBeLessThan(844)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  }
  await expect(nav.getByRole('button', { name: 'Далее', exact: true })).toBeDisabled()
  await expect(cards.last().locator('time')).toHaveAttribute('datetime', '2026-09-01T12:00:00.000Z')
  await expect(select.locator('option')).toHaveCount(31)
  await testInfo.attach('contained-history-layout', {
    body: JSON.stringify({
      closedHeight,
      openHeight,
      collapsedHeight,
      revisitedHeight,
      pageOffsets,
    }),
    contentType: 'application/json',
  })
})

test('saved Spartan uses its own model and supports all viewpoints without a write', async ({
  page,
}, testInfo) => {
  const resources: string[] = []
  page.on('request', (request) => {
    if (request.url().endsWith('.glb')) resources.push(new URL(request.url()).pathname)
  })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await openBody(page, 'male', 'spartan')
  await rendered(page)
  await expect(canvas(page)).toHaveAttribute('data-character-id', 'spartan')
  await expect(canvas(page)).toHaveAttribute('data-renderer-mode', 'webgpu')
  await expect(page.getByTestId('atlas-character-spartan')).toHaveAttribute('aria-pressed', 'true')
  expect(resources).toHaveLength(2)
  expect(resources.some((path) => bodyResource(path, 'male_spartan.glb'))).toBe(true)
  expect(resources.some((path) => bodyResource(path, 'male_spartan-lookaround.glb'))).toBe(true)
  for (const [view, target] of [
    ['front', 0],
    ['side', Math.PI / 2],
    ['back', Math.PI],
    ['three-quarter', 0.48],
  ] as const) {
    await page.getByTestId(`atlas-view-${view}`).click()
    await expect
      .poll(async () => {
        const yaw = Number(await canvas(page).getAttribute('data-character-yaw'))
        return Math.abs(Math.atan2(Math.sin(yaw - target), Math.cos(yaw - target)))
      })
      .toBeLessThan(0.015)
    await atlas(page).screenshot({ path: testInfo.outputPath(`spartan-${view}.png`) })
  }
  await atlas(page).getByRole('button', { name: 'Сравнить анализы', exact: true }).click()
  await expect(canvas(page)).toHaveAttribute('data-character-comparison', 'true')
  await expect(canvas(page)).toHaveAttribute('data-character-id', 'spartan')
  await expect(canvas(page)).toHaveAttribute('data-character-pose', 'relaxed')
})

for (const character of ['sculpture', 'spartan'] as const) {
  test(`${character}: complete pointer orbits stay continuous in both directions`, async ({
    page,
  }, testInfo) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await prepareCharacterSwaps(page)
    await openBody(page, 'male', character)
    await renderedCharacter(page, character)
    await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
    await page.getByTestId('atlas-view-front').click()

    const cameraSample = async () => {
      const sample = await canvas(page).evaluate((element) => ({
        yaw: element.getAttribute('data-character-yaw'),
        frames: element.getAttribute('data-renderer-frames'),
      }))
      expect(sample.yaw).not.toBeNull()
      expect(sample.frames).not.toBeNull()
      const yaw = Number(sample.yaw)
      const frames = Number(sample.frames)
      expect(Number.isFinite(yaw)).toBe(true)
      expect(Number.isInteger(frames) && frames > 0).toBe(true)
      return { yaw, frames }
    }
    await expect.poll(async () => angleDistance((await cameraSample()).yaw, 0)).toBeLessThan(0.015)
    await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')

    const bounds = (await canvas(page).boundingBox())!
    const x = bounds.x + bounds.width / 2
    const y = bounds.y + bounds.height / 2
    const turn = 2 * Math.PI
    const maxPixels = Math.min(100, bounds.width / 4)
    const segments = Math.max(16, Math.ceil(turn / (maxPixels * 0.006 * 4)) * 4)
    const step = turn / segments
    const pixels = step / 0.006
    expect(pixels).toBeGreaterThan(8) // Every gesture passes horizontal intent classification.

    for (const direction of [1, -1] as const) {
      let previous = await cameraSample()
      const initial = previous.yaw
      let travelled = 0
      let crossedWrap = false
      const quadrants = new Set<number>()
      const samples = [{ ...previous, travelled }]
      for (let segment = 0; segment < segments; segment++) {
        await page.mouse.move(x, y)
        await page.mouse.down()
        await page.mouse.move(x + direction * pixels, y, { steps: 4 })
        await page.mouse.up()
        const expected = previous.yaw + direction * step
        await expect
          .poll(async () => {
            const sample = await cameraSample()
            return sample.frames > previous.frames && angleDistance(sample.yaw, expected) < 0.015
          })
          .toBe(true)

        const sample = await cameraSample()
        expect(sample.frames).toBeGreaterThan(previous.frames)
        const rawDelta = sample.yaw - previous.yaw
        // The real camera exposes canonical azimuth: unwrap its ±π crossing, not a UI counter.
        const delta = Math.atan2(Math.sin(rawDelta), Math.cos(rawDelta))
        expect(direction * delta).toBeGreaterThan(0)
        expect(Math.abs(delta - direction * step)).toBeLessThan(0.015)
        travelled += delta
        expect(Math.abs(travelled - direction * step * (segment + 1))).toBeLessThan(0.03)
        crossedWrap ||= Math.abs(rawDelta) > Math.PI
        const positiveYaw = ((sample.yaw % turn) + turn) % turn
        quadrants.add(Math.floor(positiveYaw / (Math.PI / 2)))
        samples.push({ ...sample, travelled })
        previous = sample
      }
      expect(crossedWrap).toBe(true)
      expect([...quadrants].sort()).toEqual([0, 1, 2, 3])
      expect(Math.abs(travelled - direction * turn)).toBeLessThan(0.03)
      expect(angleDistance(previous.yaw, initial)).toBeLessThan(0.015)
      const directionName = direction === 1 ? 'clockwise' : 'counterclockwise'
      await testInfo.attach(`${character}-pointer-orbit-${directionName}`, {
        body: JSON.stringify({
          character,
          direction,
          segments,
          travelled,
          samples,
        }),
        contentType: 'application/json',
      })
    }
  })
}

test('preset transition drag starts at the actual angle and passes the former orbit limit', async ({
  page,
}) => {
  await openBody(page)
  await rendered(page)
  await atlas(page).getByRole('button', { name: 'Сравнить анализы', exact: true }).click()
  await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
  await page.getByTestId('atlas-view-side').click()
  // Start a real pointer gesture during the smooth transition rather than waiting for its target.
  const bounds = (await canvas(page).boundingBox())!
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
  await canvas(page).evaluate((element) => {
    element.addEventListener(
      'pointermove',
      () => {
        element.setAttribute(
          'data-test-drag-yaw',
          element.getAttribute('data-character-yaw') ?? '0',
        )
      },
      { once: true },
    )
  })
  await page.mouse.down()
  await page.mouse.move(bounds.x + bounds.width / 2 + 50, bounds.y + bounds.height / 2, {
    steps: 1,
  })
  const prior = Number(await canvas(page).getAttribute('data-test-drag-yaw'))
  await page.mouse.up()
  await expect
    .poll(async () => {
      const actual = Number(await canvas(page).getAttribute('data-character-yaw'))
      return Math.abs(Math.atan2(Math.sin(actual - prior - 0.3), Math.cos(actual - prior - 0.3)))
    })
    .toBeLessThan(0.15)
  const yaw = Number(await canvas(page).getAttribute('data-character-yaw'))
  const expected = prior + 0.3
  expect(Math.abs(Math.atan2(Math.sin(yaw - expected), Math.cos(yaw - expected)))).toBeLessThan(
    0.15,
  )
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
  await page.mouse.down()
  await page.mouse.move(bounds.x + bounds.width / 2 + 300, bounds.y + bounds.height / 2, {
    steps: 4,
  })
  await page.mouse.up()
  await expect
    .poll(async () => Math.abs(Number(await canvas(page).getAttribute('data-character-yaw'))))
    .toBeGreaterThan(0.7)
})

test('catalog swaps preserve the rendered camera, reduced motion and comparison values', async ({
  page,
}, testInfo) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await prepareCharacterSwaps(page)
  const presentation = await changingPresentation(page)
  await openBody(page)
  await renderedCharacter(page, 'sculpture')
  await page.getByTestId('atlas-view-side').click()
  await expect
    .poll(async () =>
      angleDistance(Number(await canvas(page).getAttribute('data-character-yaw')), Math.PI / 2),
    )
    .toBeLessThan(0.015)
  await atlas(page).getByRole('button', { name: 'Сравнить анализы', exact: true }).click()
  await page.locator('#body-compare-progress').fill('47')
  await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')
  const yaw = Number(await canvas(page).getAttribute('data-character-yaw'))
  const metrics = await atlas(page).locator('.atlas-metrics').innerText()
  const endpoints = await page.getByTestId('atlas-comparison-endpoints').innerText()
  const analysis = await page.locator('#body-analysis-select').inputValue()
  const previous = await page.locator('#body-compare-analysis').inputValue()

  for (const character of ['spartan', 'sculpture'] as const) {
    const replaced = await canvas(page).elementHandle()
    expect(replaced).not.toBeNull()
    await presentation.select(character)
    await expect.poll(() => replaced!.evaluate((element) => element.isConnected)).toBe(false)
    expect(await replaced!.getAttribute('data-renderer-state')).toBe('disposed')
    await renderedCharacter(page, character)
    await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
    await expect(canvas(page)).toHaveAttribute('data-character-scenario', 'idle')
    await expect(canvas(page)).toHaveAttribute('data-character-comparison', 'true')
    await expect(canvas(page)).toHaveAttribute('data-character-pose', 'relaxed')
    await expect
      .poll(async () =>
        angleDistance(Number(await canvas(page).getAttribute('data-character-yaw')), yaw),
      )
      .toBeLessThan(0.005)
    await expect(page.locator('#body-compare-progress')).toHaveValue('47')
    await expect(page.locator('#body-analysis-select')).toHaveValue(analysis)
    await expect(page.locator('#body-compare-analysis')).toHaveValue(previous)
    await expect(atlas(page).locator('.atlas-metrics')).toHaveText(metrics, {
      useInnerText: true,
    })
    await expect(page.getByTestId('atlas-comparison-endpoints')).toHaveText(endpoints, {
      useInnerText: true,
    })
    await atlas(page).screenshot({ path: testInfo.outputPath(`swap-${character}-comparison.png`) })
  }

  await atlas(page).getByRole('button', { name: 'Закрыть сравнение', exact: true }).click()
  await expect(canvas(page)).toHaveAttribute('data-character-comparison', 'false')
  await expect(canvas(page)).toHaveAttribute('data-character-scenario', 'idle')
  await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
  await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')
  const frames = await canvas(page).getAttribute('data-renderer-frames')
  await page.waitForTimeout(300)
  await expect(canvas(page)).toHaveAttribute('data-renderer-frames', frames!)
  expect(
    angleDistance(Number(await canvas(page).getAttribute('data-character-yaw')), yaw),
  ).toBeLessThan(0.005)
})

test('a superseded GLB download is aborted and its late response cannot replace the active catalog model', async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await prepareCharacterSwaps(page)
  const presentation = await changingPresentation(page)
  const resources: Array<{ path: string; selected: 'sculpture' | 'spartan' }> = []
  const failures: Array<{ url: string; error: string }> = []
  const rendererErrors: string[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path.endsWith('.glb')) resources.push({ path, selected: presentation.character })
  })
  page.on('requestfailed', (request) => {
    failures.push({ url: request.url(), error: request.failure()?.errorText ?? '' })
  })
  page.on('console', (message) => {
    if (
      message.type() === 'error' &&
      /InBody renderer failed|Source animation loading failed/.test(message.text())
    )
      rendererErrors.push(message.text())
  })
  let releaseLate!: () => void
  const lateGate = new Promise<void>((resolve) => {
    releaseLate = resolve
  })
  let delayedUrl = ''
  let downloaded = false
  let delivered = false
  await page.route('**/*male_spartan*.glb', async (route) => {
    if (new URL(route.request().url()).pathname.includes('lookaround')) return route.continue()
    delayedUrl = route.request().url()
    // Download genuine bytes now, then withhold them from the browser until a
    // newer selection has fully rendered. The source animation remains a real request.
    const response = await route.fetch()
    expect(response.ok(), `delayed model response: ${response.status()}`).toBe(true)
    expect((await response.body()).subarray(0, 4).toString('utf8')).toBe('glTF')
    downloaded = true
    await lateGate
    try {
      await route.fulfill({ response })
    } catch {
      // Fulfilling an already cancelled request can be rejected by Playwright.
    } finally {
      delivered = true
    }
  })

  try {
    await openBody(page)
    await renderedCharacter(page, 'sculpture')
    expect(resources).toHaveLength(2)
    expect(
      resources.every(
        ({ path }) => bodyResource(path, 'male.glb') || bodyResource(path, 'male-lookaround.glb'),
      ),
    ).toBe(true)
    await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')
    await presentation.select('spartan')
    await expect.poll(() => downloaded).toBe(true)
    await expect(canvas(page)).toHaveAttribute('data-character-id', 'spartan')
    const superseded = await canvas(page).elementHandle()
    expect(superseded).not.toBeNull()

    await presentation.select('sculpture')
    await expect.poll(() => superseded!.evaluate((element) => element.isConnected)).toBe(false)
    expect(await superseded!.getAttribute('data-renderer-state')).toBe('disposed')
    await expect
      .poll(() =>
        failures.some(({ url, error }) => url === delayedUrl && /abort|cancel/i.test(error)),
      )
      .toBe(true)
    await renderedCharacter(page, 'sculpture')
    await expect(canvas(page)).toHaveAttribute('data-character-paused', 'true')
    await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')
    const recovered = await canvas(page).elementHandle()
    expect(recovered).not.toBeNull()
    const frames = await recovered!.getAttribute('data-renderer-frames')
    const expectedModel = await recovered!.getAttribute('data-character-id')
    await atlas(page).evaluate((element) => {
      const target = window as Window & {
        bodySwapSamples?: Array<{ character: string | null; enhanced: boolean }>
        bodySwapObserver?: MutationObserver
      }
      const sample = () => {
        const figure = element.querySelector('[data-testid="body-figure"]')
        target.bodySwapSamples!.push({
          character: figure?.getAttribute('data-character-id') ?? null,
          enhanced: figure?.classList.contains('body-figure--gpu') ?? false,
        })
      }
      target.bodySwapSamples = []
      target.bodySwapObserver = new MutationObserver(sample)
      target.bodySwapObserver.observe(element, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ['class', 'data-character-id'],
      })
      sample()
    })
    releaseLate()
    await expect.poll(() => delivered).toBe(true)
    // Include queued promise continuations and subsequent paint opportunities.
    await page.waitForTimeout(350)
    expect(await recovered!.evaluate((element) => element.isConnected)).toBe(true)
    expect(await recovered!.getAttribute('data-renderer-frames')).toBe(frames)
    await expect(canvas(page)).toHaveAttribute('data-character-id', expectedModel!)
    await expect(canvas(page)).toHaveAttribute('data-renderer-state', 'idle')
    const samples = await page.evaluate(() => {
      const target = window as Window & {
        bodySwapSamples?: Array<{ character: string | null; enhanced: boolean }>
        bodySwapObserver?: MutationObserver
      }
      target.bodySwapObserver?.disconnect()
      return target.bodySwapSamples ?? []
    })
    expect(samples.length).toBeGreaterThan(0)
    expect(samples.every(({ character, enhanced }) => character === 'sculpture' && enhanced)).toBe(
      true,
    )
    expect(resources.some(({ path }) => bodyResource(path, 'male_spartan.glb'))).toBe(true)
    expect(
      resources.every(({ path, selected }) => {
        const model = selected === 'spartan' ? 'male_spartan' : 'male'
        return bodyResource(path, `${model}.glb`) || bodyResource(path, `${model}-lookaround.glb`)
      }),
    ).toBe(true)
    expect(rendererErrors, 'aborted loaders must not report a failed replacement').toEqual([])
  } finally {
    releaseLate()
  }
})

test('an incompatible saved Spartan shows the female poster and keeps all view controls disabled without GPU', async ({
  page,
}) => {
  await forceWebGl(page)
  await page.addInitScript(() => {
    const original = HTMLCanvasElement.prototype.getContext
    HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, ...args) {
      if (/^(webgl2?|experimental-webgl|webgpu)$/.test(String(args[0]))) return null
      return original.apply(this, args)
    } as typeof original
  })
  await openBody(page, 'female', 'spartan')
  await expect(atlas(page)).toContainText('Статичная нейтральная иллюстрация')
  await expect(page.getByTestId('atlas-character-spartan')).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('atlas-character-spartan')).toBeDisabled()
  await expect(page.getByTestId('atlas-character-unavailable')).toContainText(
    'выбор образа сохранён',
  )
  await expect(page.getByTestId('body-figure').locator('img')).toHaveAttribute(
    'src',
    /female.*\.webp/,
  )
  await expect(canvas(page)).toHaveAttribute('data-character-id', 'sculpture')
  for (const view of ['front', 'side', 'back', 'three-quarter']) {
    await expect(page.getByTestId(`atlas-view-${view}`)).toBeDisabled()
  }
})
