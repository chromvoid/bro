import { describe, expect, it } from 'vitest'
import {
  Display,
  agentSetup,
  cleanSettings,
  moved,
  pairingCode,
  toggledTicker,
  uptimeParts,
  withLayout,
} from './api'

describe('devices', () => {
  it('reads a pairing code the way the screen shows it', () => {
    expect(pairingCode('4k7q 2mxa')).toBe('4K7Q-2MXA')
    expect(pairingCode('4K7Q-2MXA')).toBe('4K7Q-2MXA')
    // Letters that look like digits are not in the alphabet, so a typo is caught here.
    expect(pairingCode('4K7Q-2MX0')).toBeNull()
    expect(pairingCode('4K7Q')).toBeNull()
  })

  it('keeps cards when a page changes its layout', () => {
    const page = { layout: 'split' as const, cards: ['subscriptions', 'device'] }
    expect(withLayout(page, 'grid', 'clock').cards).toEqual([
      'subscriptions',
      'device',
      'clock',
      'clock',
    ])
    expect(withLayout(page, 'single', 'clock')).toEqual({
      layout: 'single',
      cards: ['subscriptions'],
    })
  })

  it('drops empty titles and moves pages within bounds', () => {
    expect(
      cleanSettings({
        home_after_s: 300,
        pages: [
          { layout: 'single', cards: ['clock'], title: '  ' },
          { layout: 'single', cards: ['device'], title: ' Экран ' },
        ],
      }).pages,
    ).toEqual([
      { layout: 'single', cards: ['clock'] },
      { layout: 'single', cards: ['device'], title: 'Экран' },
    ])
    expect(moved(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b'])
    expect(moved(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
  })

  it('keeps a strip only with cards in it', () => {
    const page = { layout: 'single' as const, cards: ['subscriptions'] }
    const withStrip = toggledTicker(toggledTicker(page, 'portfolio', true), 'clock', true)
    expect(withStrip.ticker).toEqual(['portfolio', 'clock'])
    expect(toggledTicker(withStrip, 'portfolio', false).ticker).toEqual(['clock'])
    expect(cleanSettings({ home_after_s: 300, pages: [withStrip] }).pages[0]).toEqual({
      layout: 'single',
      cards: ['subscriptions'],
      ticker: ['portfolio', 'clock'],
      ticker_s: 12,
    })
    expect(
      cleanSettings({ home_after_s: 300, pages: [{ ...page, ticker: [], ticker_s: 30 }] }).pages[0],
    ).toEqual(page)
  })

  it('parses a display description', () => {
    const display = Display.parse({
      device: {
        id: 'd',
        kind: 'display',
        name: 'Кухня',
        created_at: '2026-09-26T10:00:00Z',
        last_seen_at: null,
        status: { rssi: -60, firmware: '0.1.0' },
        revision: 2,
      },
      settings: { pages: [{ layout: 'single', cards: ['clock'] }], home_after_s: 300 },
      default: { pages: [{ layout: 'single', cards: ['subscriptions'] }], home_after_s: 300 },
      online: false,
      cards: [{ id: 'clock', title: 'Время', available: true }],
      layouts: { single: 1 },
    })
    expect(display.device.status?.rssi).toBe(-60)
    expect(uptimeParts(93784)).toEqual({ days: 1, hours: 2, minutes: 3 })
  })
})

describe('agentSetup', () => {
  it('scopes Claude Code to the repository and leaves Codex disabled outside it', () => {
    const setup = agentSetup('https://assistant.example', 'tok')
    expect(setup.claude).toBe(
      'claude mcp add --scope local --transport http assistant https://assistant.example/v1/mcp --header "Authorization: Bearer tok"',
    )
    expect(setup.codex).toContain('url = "https://assistant.example/v1/mcp"')
    expect(setup.codex).toContain('enabled = false')
    expect(setup.codex).toContain('bearer_token_env_var = "ASSISTANT_AGENT_TOKEN"')
    expect(setup.codex).toContain('export ASSISTANT_AGENT_TOKEN=tok')
  })
})
