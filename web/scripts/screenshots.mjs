// Captures app screens on a phone and a desktop, in both themes, for design review.
// Usage: node scripts/screenshots.mjs <origin+base, e.g. http://127.0.0.1:5173/> <out dir> 'path[|name[|viewport]]' ...
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { chromium } from '@playwright/test'

const [base, out, ...targets] = process.argv.slice(2)
if (!base || !out || !targets.length) {
  console.error(
    'usage: node scripts/screenshots.mjs <base url> <out dir> <path[|name[|viewport]]>...',
  )
  process.exit(2)
}
mkdirSync(out, { recursive: true })
const viewports = { phone: { width: 390, height: 844 }, desktop: { width: 1280, height: 860 } }
const browser = await chromium.launch()
for (const scheme of ['light', 'dark']) {
  for (const [device, viewport] of Object.entries(viewports)) {
    const context = await browser.newContext({
      viewport,
      colorScheme: scheme,
      deviceScaleFactor: 2,
    })
    const page = await context.newPage()
    for (const target of targets) {
      const [
        path = '/',
        name = path.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'home',
        mode,
      ] = target.split('|')
      // The change stream keeps a request open, so wait for the render instead of network idle.
      await page.goto(new URL(path.replace(/^\//, ''), base).href, { waitUntil: 'load' })
      await page.locator('main').first().waitFor()
      await page.evaluate(() => document.fonts.ready)
      await page.waitForTimeout(600)
      // Screens with their own scrolling (conversations) are captured as the viewport shows them.
      await page.screenshot({
        path: join(out, `${name}-${device}-${scheme}.png`),
        fullPage: mode !== 'viewport',
      })
    }
    await context.close()
  }
}
await browser.close()
console.log(`saved to ${out}`)
