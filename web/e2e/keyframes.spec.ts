import { APP, expect, test } from './fixtures'

/**
 * Playwright's WebKit (WPE 2.55, Skia compositor) kills the page process when an animated filter
 * reaches an empty value: tw-animate-css animated blur(0) against none on every sheet and menu
 * until styles.css replaced its enter and exit (docs/agent-workflow.md, «Веб e2e»).
 */
test('no animation moves a filter', async ({ page }) => {
  await page.goto(APP)
  const animated = await page.evaluate(() => {
    const names: string[] = []
    const visit = (rules: CSSRuleList) => {
      for (const rule of rules) {
        if (rule instanceof CSSKeyframesRule) {
          const frames = [...rule.cssRules] as CSSKeyframeRule[]
          if (frames.some((frame) => frame.style.getPropertyValue('filter'))) names.push(rule.name)
        } else if (rule instanceof CSSGroupingRule) visit(rule.cssRules)
      }
    }
    for (const sheet of document.styleSheets) visit(sheet.cssRules)
    return names
  })
  expect(animated).toEqual([])
})
