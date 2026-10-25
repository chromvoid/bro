import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, ref } from 'vue'
import type { InvocationOutcome, PluginHost } from '@/plugins/api'
import { PositionsList, TOOLS } from '../contracts'
import { inUnit } from '../format'
import { Trading } from '../model'
import PnlScreen from './PnlScreen.vue'

const original = PositionsList.parse(
  JSON.parse(
    readFileSync(join(__dirname, '../../package/examples/positions.list/all.json'), 'utf8'),
  ).data,
)
const money = (value: string) => inUnit(value, 'USD', true)
const cleanups: Array<() => void> = []
afterEach(() => cleanups.splice(0).forEach((cleanup) => cleanup()))

function screen(empty = false) {
  const invoke = vi.fn(
    async (tool: string, args: Record<string, unknown> = {}): Promise<InvocationOutcome> => {
      expect(tool).toBe(TOOLS.positions)
      const period = (args.range as { period: string }).period
      const realized = empty
        ? '0'
        : period === '24h'
          ? '-25'
          : period === 'all'
            ? '109950'
            : '1520.4'
      const income = period === '24h' ? '2' : period === 'all' ? original.totals.income : '0'
      return {
        kind: 'completed',
        receipts: [],
        clientKey: null,
        data: {
          ...original,
          items: original.items.map((item, index) => ({
            ...item,
            realized_in_range: empty
              ? '0'
              : period === 'all'
                ? item.realized
                : index === 0
                  ? realized
                  : '0',
          })),
          totals: {
            ...original.totals,
            realized_in_range: realized,
            income_in_range: income,
            realized_by_month: empty ? [] : [{ month: '2026-10', realized }],
          },
        },
      }
    },
  )
  const host = {
    invoke,
    navigate: vi.fn(),
    capabilities: ref({ tools: {}, configurationWritable: false }),
  } as unknown as PluginHost
  const trading = new Trading(host, () => Date.parse(original.freshness.as_of!))
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(PnlScreen, { host, trading })
  app.mount(root)
  cleanups.push(() => {
    app.unmount()
    root.remove()
  })
  const get = (selector: string) => {
    const element = root.querySelector<HTMLElement>(selector)
    if (!element) throw new Error(`Missing element: ${selector}`)
    return element
  }
  return { root, get, trading, invoke }
}

describe('PnL period on the screen', () => {
  it('changes realized results, coin bars and months while keeping current positions clearly separate', async () => {
    const { root, get, trading, invoke } = screen()
    await trading.pnl.refresh()
    await nextTick()
    const initialPositions = get('[data-testid="positions"]').textContent!.trim()
    const initialLifetime = get('[data-testid="pnl-lifetime"]').textContent!.trim()
    expect(get('[data-testid="pnl-period-result"]').textContent!.trim()).toBe(money('1520.4'))
    expect(get('[data-testid="pnl-totals"]').textContent!.trim()).toContain('Реализовано за период')
    expect(get('[data-testid="results-by-coin"]').textContent!.trim()).toContain(money('1520.4'))
    expect(get('[data-testid="results-by-coin"]').textContent!.trim()).not.toContain('GRAM')
    expect(get('[data-testid="pnl-current"]').textContent!.trim()).toContain(
      'выбор периода их не меняет',
    )
    expect(initialLifetime).toContain(money(original.totals.result!))
    expect(initialLifetime).toContain('Итог торговли за всё время')

    for (const [label, period, value, income] of [
      ['24 часа', '24h', '-25', '2'],
      ['Всё время', 'all', '109950', original.totals.income],
    ]) {
      const button = [...root.querySelectorAll<HTMLButtonElement>('[role="radio"]')].find(
        (item) => item.textContent!.trim() === label,
      )!
      button.click()
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(button.getAttribute('aria-checked')).toBe('true')
      expect(invoke).toHaveBeenLastCalledWith(TOOLS.positions, {
        range: { period },
        include_closed: true,
        include_stables: true,
      })
      expect(get('[data-testid="pnl-period-result"]').textContent!.trim()).toBe(money(value!))
      expect(get('[data-testid="pnl-totals"] dd').textContent!.trim()).toBe(money(income!))
      expect(get('[data-testid="results-by-coin"]').getAttribute('aria-label')).toBe(
        'Реализовано по монетам',
      )
      expect(get('[data-testid="results-by-coin"]').textContent!.trim()).toContain(
        money(period === 'all' ? '113500' : value!),
      )
      expect(get('[data-testid="months"] td').textContent!.trim()).toBe(money(value!))
      expect(get('[data-testid="positions"]').textContent!.trim()).toBe(initialPositions)
      expect(get('[data-testid="pnl-lifetime"]').textContent!.trim()).toBe(initialLifetime)
    }
    expect(get('[data-testid="results-by-coin"]').textContent!.trim()).toContain(money('-3550'))
  })

  it('distinguishes a period without realized gains or losses from having no open positions', async () => {
    const { root, get, trading } = screen(true)
    await trading.pnl.refresh()
    await nextTick()
    expect(get('[data-testid="pnl-period-result"]').textContent!.trim()).toBe(money('0'))
    expect(root.textContent).toContain('За выбранный период нет реализованной прибыли или убытка.')
    expect(root.querySelector('[data-testid="results-by-coin"]')).toBeNull()
    expect(get('[data-testid="positions"]').textContent!.trim()).toContain('LTC')
    expect(root.textContent).not.toContain('Открытых позиций нет.')
  })
})
