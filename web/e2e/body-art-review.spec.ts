import { expect, test as base, type Page } from '@playwright/test'
import {
  bodyRendererGpuRequests,
  recordBodyRendererGpu,
  requireNativeBodyRenderer,
} from './bodyNativeGpu'

const test = base.extend<{ pageProblems: void }>({
  pageProblems: [
    async ({ page }, use, testInfo) => {
      const problems: string[] = []
      await recordBodyRendererGpu(page)
      page.on('pageerror', (error) => problems.push(error.message))
      await page.exposeFunction('reportArtCsp', (detail: string) => problems.push(detail))
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (event) => {
          const target = window as unknown as { reportArtCsp(detail: string): void }
          target.reportArtCsp(`CSP ${event.violatedDirective}: ${event.blockedURI}`)
        })
      })
      await use()
      await testInfo.attach('renderer-gpu-requests', {
        body: JSON.stringify(await bodyRendererGpuRequests(page)),
        contentType: 'application/json',
      })
      expect(problems).toEqual([])
    },
    { auto: true },
  ],
})

const candidates = [
  { key: 'male', template: 'male', character: 'sculpture' },
  { key: 'female', template: 'female', character: 'sculpture' },
  { key: 'male_spartan', template: 'male', character: 'spartan' },
] as const
const physiques = [
  { key: 'neutral', fat: 0.4, muscle: 0.5 },
  { key: 'soft', fat: 0.85, muscle: 0.2 },
  { key: 'athletic', fat: 0.2, muscle: 1 },
  { key: 'mixed', fat: 0.85, muscle: 1 },
] as const
const views = ['front', 'side', 'back', 'three-quarter'] as const

test.beforeEach(({}, testInfo) => {
  test.skip(
    testInfo.project.metadata.bodyArtReview !== true,
    'requires playwright.body-review.config.ts',
  )
})

async function rendered(page: Page) {
  const canvas = page.locator('#character')
  await expect(canvas).toHaveAttribute('data-renderer-mode', 'webgpu')
  await expect(canvas).toHaveAttribute('data-renderer-morph-count', '30')
  await expect
    .poll(async () => Number(await canvas.getAttribute('data-renderer-frames')))
    .toBeGreaterThan(0)
  await expect(page.locator('#loading')).toBeHidden()
  await requireNativeBodyRenderer(page)
  return canvas
}

for (const candidate of candidates) {
  for (const physique of physiques) {
    test(`${candidate.key}: ${physique.key} silhouette, relief and four views`, async ({
      page,
    }, testInfo) => {
      const query = new URLSearchParams({
        template: candidate.template,
        character: candidate.character,
        fat: String(physique.fat),
        muscle: String(physique.muscle),
        view: 'front',
        'reduced-motion': '1',
      })
      await page.goto(`/?${query}`)
      const canvas = await rendered(page)
      await expect(canvas).toHaveAttribute('data-character-id', candidate.character)
      await page.locator('[data-layer="form"]').click()
      await canvas.screenshot({
        path: testInfo.outputPath(`${candidate.key}-${physique.key}-silhouette.png`),
      })
      await page.locator('[data-layer="surface"]').click()
      for (const view of views) {
        await page.locator(`[data-view="${view}"]`).click()
        await expect(canvas).toHaveAttribute('data-renderer-state', 'idle')
        await canvas.screenshot({
          path: testInfo.outputPath(`${candidate.key}-${physique.key}-${view}.png`),
        })
      }
      await testInfo.attach('candidate', {
        body: JSON.stringify({
          asset: candidate.key,
          physique,
          engine: 'webgpu',
          assetSha: process.env.BODY_REVIEW_ASSET_SHA ?? null,
        }),
        contentType: 'application/json',
      })
    })
    test(`${candidate.key}: ${physique.key} source motion and shoulder/elbow poses`, async ({
      page,
    }, testInfo) => {
      const query = new URLSearchParams({
        template: candidate.template,
        character: candidate.character,
        fat: String(physique.fat),
        muscle: String(physique.muscle),
        view: 'three-quarter',
      })
      await page.goto(`/?${query}`)
      const canvas = await rendered(page)
      await expect(canvas).toHaveAttribute('data-renderer-correction-count', '4')
      await expect(canvas).toHaveAttribute('data-motion-source', 'game-idle')
      await expect
        .poll(async () => Number(await canvas.getAttribute('data-clip-elapsed')))
        .toBeGreaterThan(1)
      await canvas.screenshot({
        path: testInfo.outputPath(`${candidate.key}-${physique.key}-lookaround.png`),
      })
      await page.locator('#idle-toggle').click()
      await page.getByText('Статичные позы', { exact: true }).click()
      for (const pose of ['flex', 'open'] as const) {
        await test.step(`${candidate.key}/${physique.key}: ${pose}`, async () => {
          const control = page.locator(`[data-pose="${pose}"]`)
          await expect(control).toBeVisible()
          await control.click()
          await expect(canvas).toHaveAttribute('data-character-pose', pose)
          await page.waitForTimeout(800)
          await testInfo.attach(`${pose}-joint-state`, {
            body: JSON.stringify({
              asset: candidate.key,
              physique,
              pose: await canvas.getAttribute('data-character-pose'),
              source: await canvas.getAttribute('data-motion-source'),
              correctionMax: await canvas.getAttribute('data-renderer-correction-max'),
              clipElapsed: await canvas.getAttribute('data-clip-elapsed'),
            }),
            contentType: 'application/json',
          })
          await expect
            .poll(async () => Number(await canvas.getAttribute('data-renderer-correction-max')), {
              message: `${candidate.key}/${physique.key}/${pose}: joint correction is active`,
            })
            .toBeGreaterThan(0)
          await canvas.screenshot({
            path: testInfo.outputPath(`${candidate.key}-${physique.key}-${pose}.png`),
          })
        })
      }
    })
  }
}
