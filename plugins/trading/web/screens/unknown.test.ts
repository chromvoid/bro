import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, ref, type Component } from 'vue'
import type { PluginHost } from '@/plugins/api'
import { PortfolioOverview, PositionChart, PositionSummary, PositionsList } from '../contracts'
import { Trading, type ReadModel } from '../model'
import CoinScreen from './CoinScreen.vue'
import PnlScreen from './PnlScreen.vue'
import PortfolioScreen from './PortfolioScreen.vue'

const example = (tool: string, name: string) =>
  JSON.parse(readFileSync(join(__dirname, '../../package/examples', tool, `${name}.json`), 'utf8'))
    .data
const issue = {
  code: 'p2p_cancellation_unlinked',
  coin: 'LTC',
  occurred_at: '2026-09-24T00:00:00Z',
  entry_id: 'synthetic-cancellation',
}
const known = example('positions.list', 'all')
const unknown = {
  ...known,
  items: known.items.map((item: { coin: string }) =>
    item.coin === 'LTC'
      ? {
          ...item,
          realized: null,
          realized_in_range: null,
          income: null,
          income_in_range: null,
          fees: null,
          result: null,
          unrealized: null,
          issues: [issue],
        }
      : item,
  ),
  totals: {
    ...known.totals,
    realized: null,
    realized_in_range: null,
    unrealized: null,
    income: null,
    income_in_range: null,
    fees: null,
    result: null,
    issues: [issue],
    realized_by_month: [{ month: '2026-09', realized: null }],
  },
}
const ready = <T>(value: T): ReadModel<T> => ({
  kind: 'ready',
  value,
  empty: false,
  stale: false,
  problem: null,
})
const cleanup: Array<() => void> = []
afterEach(() => cleanup.splice(0).forEach((close) => close()))

function screen(component: Component, prepare: (trading: Trading) => void) {
  const host = {
    navigate: vi.fn(),
    capabilities: ref({ tools: {}, configurationWritable: false }),
  } as unknown as PluginHost
  const trading = new Trading(host)
  prepare(trading)
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(component, { host, trading })
  app.config.warnHandler = (message) => {
    // ReadStatus renders no wrapper when a successful read has no warning.
    if (!message.startsWith('Extraneous non-props attributes (class)')) throw new Error(message)
  }
  app.mount(root)
  cleanup.push(() => {
    app.unmount()
    root.remove()
  })
  return root
}

describe('unknown position results', () => {
  it('keeps unknown period results and months distinct from zero, showing their cause', () => {
    const root = screen(PnlScreen, (trading) => {
      trading.pnl.positions.value = ready(PositionsList.parse(unknown))
    })
    expect(root.querySelector('[data-testid=pnl-period-result]')!.textContent!.trim()).toBe(
      'не определено',
    )
    expect(root.textContent).toContain('отменённая P2P-сделка')
    expect(root.textContent).toContain('пока не считаются')
    const ranking = root.querySelector('[data-testid=results-by-coin]')!
    expect(ranking.textContent).toContain('LTC')
    expect(ranking.textContent).toContain('не определено')
    expect(ranking.querySelector('rect')).toBeNull()
    expect(root.textContent).not.toContain(
      'За выбранный период нет реализованной прибыли или убытка.',
    )
    const month = root.querySelector('[data-testid=months]')!
    expect(month.querySelector('td')!.textContent).toBe('не определено')
    expect(month.querySelector('[data-testid=chart-unknown]')!.textContent).toContain(
      'не определено',
    )
  })

  it('does not draw unknown portfolio results as a zero share', () => {
    const root = screen(PortfolioScreen, (trading) => {
      trading.portfolio.overview.value = ready(
        PortfolioOverview.parse(example('portfolio.get', 'overview')),
      )
      trading.portfolio.result.value = ready(
        PositionsList.parse({
          ...unknown,
          totals: { ...unknown.totals, realized: '500' },
        }),
      )
    })
    const card = root.querySelector('[data-testid=trading-result]')!
    expect(card.textContent).toContain('не определено')
    expect(card.textContent).toContain('отменённая P2P-сделка')
    expect(card.querySelector('svg[role=img]')).toBeNull()
  })

  it('shows an uncertain core without claiming a known allocation of lots', () => {
    const summary = example('position.summary', 'ltc')
    const root = screen(CoinScreen, (trading) => {
      trading.pnl.positions.value = ready(PositionsList.parse(unknown))
      trading.coin.coin.value = 'LTC'
      trading.coin.summary.value = ready(
        PositionSummary.parse({
          ...summary,
          realized: null,
          realized_in_range: null,
          income: null,
          income_in_range: null,
          fees: null,
          result: null,
          issues: [issue],
          realized_by_month: [{ month: '2026-09', realized: null }],
          core: {
            asked: '1500',
            since: '2026-01-01T00:00:00Z',
            qty: null,
            entry: null,
            unrealized: null,
            sold_qty: null,
            sold_price: null,
            sold_unrealized: null,
          },
          lots: [{ qty: '1500', unit_cost: '72', core: true }],
          lots_total: 1,
        }),
      )
      trading.coin.chart.value = ready(
        PositionChart.parse({
          ...example('position.chart', 'ltc'),
          issues: [issue],
        }),
      )
    })
    expect(root.textContent).toContain('отменённая P2P-сделка')
    expect(root.querySelector('[data-testid=core]')!.textContent).toContain('не определено')
    expect(root.querySelector('[data-testid=coin-months] td')!.textContent).toBe('не определено')
    expect(root.textContent).toContain('Доход Earn и бонусы')
    expect(root.querySelector('[data-testid=position-lots]')).toBeNull()
    expect(root.textContent).toContain(
      'Распределение монет между основой и остальными покупками не определено',
    )
  })

  it('shows a new known core even when a past cancellation leaves lifetime profit unknown', () => {
    const root = screen(CoinScreen, (trading) => {
      trading.pnl.positions.value = ready(PositionsList.parse(unknown))
      trading.coin.coin.value = 'LTC'
      trading.coin.summary.value = ready(
        PositionSummary.parse({
          ...example('position.summary', 'ltc'),
          realized: null,
          result: null,
          issues: [issue],
          core: {
            asked: '1',
            since: '2026-09-25T00:00:00Z',
            qty: '1',
            entry: '140',
            unrealized: '20',
            sold_qty: '0',
            sold_price: null,
            sold_unrealized: null,
          },
          lots: [{ qty: '1', unit_cost: '140', core: true }],
          lots_total: 1,
        }),
      )
    })
    expect(root.textContent).toContain('отменённая P2P-сделка')
    expect(root.querySelector('[data-testid=position-lots]')).not.toBeNull()
    expect(root.textContent).not.toContain(
      'Распределение монет между основой и остальными покупками не определено',
    )
  })
})
