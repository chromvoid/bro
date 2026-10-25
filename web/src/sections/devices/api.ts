/**
 * The user's client devices and display screens. A screen signs in by a code it shows; the
 * server draws its frames, so here the owner only chooses pages and watches the same frame.
 */
import { z } from '@/core/zod'

export const LAYOUTS = { single: 1, split: 2, grid: 4 } as const
export type Layout = keyof typeof LAYOUTS
const CODE = /^[A-HJ-NP-Z2-9]{8}$/

export const Status = z.looseObject({
  rssi: z.number().nullish(),
  firmware: z.string().nullish(),
  uptime: z.number().nullish(),
  heap: z.number().nullish(),
})
export type Status = z.infer<typeof Status>

/** What a development agent may do besides reading like its person. */
export const Grants = z.looseObject({
  writes: z.array(z.string()).default([]),
  diagnostics: z.boolean().default(false),
})
export type Grants = z.infer<typeof Grants>

export const Device = z.looseObject({
  id: z.string(),
  kind: z.string(),
  name: z.string(),
  created_at: z.string(),
  last_seen_at: z.string().nullish(),
  status: Status.nullish(),
  current: z.boolean().optional(),
  revision: z.number().optional(),
  grants: Grants.nullish(),
})
export type Device = z.infer<typeof Device>

export const Devices = z.looseObject({ devices: z.array(Device) })

export const TICKER_PERIODS = [6, 12, 20, 30, 60] as const
export const MAX_TICKER = 6

export const Page = z.object({
  layout: z.enum(['single', 'split', 'grid']),
  cards: z.array(z.string()),
  title: z.string().optional(),
  /** Cards shown one at a time as a line at the bottom, turning every `ticker_s` seconds. */
  ticker: z.array(z.string()).optional(),
  ticker_s: z.number().optional(),
})
export type Page = z.infer<typeof Page>

export const Settings = z.object({ pages: z.array(Page), home_after_s: z.number() })
export type Settings = z.infer<typeof Settings>

export const Card = z.looseObject({
  id: z.string(),
  title: z.string(),
  available: z.boolean(),
  /** Has a brief form for the rotating strip. */
  brief: z.boolean().default(false),
})
export type Card = z.infer<typeof Card>

export const Display = z.looseObject({
  device: Device,
  settings: Settings,
  default: Settings,
  online: z.boolean(),
  cards: z.array(Card),
})
export type Display = z.infer<typeof Display>

/** What the user typed, as the screen shows it: «4K7Q-2MXA»; null until it can be a code. */
export function pairingCode(input: string): string | null {
  const code = input.toUpperCase().replace(/[\s-]/g, '')
  return CODE.test(code) ? `${code.slice(0, 4)}-${code.slice(4)}` : null
}

/** A page switched to another layout keeps its cards in order and fills new slots. */
export function withLayout(page: Page, layout: Layout, fallback: string): Page {
  const slots = LAYOUTS[layout]
  const cards = Array.from({ length: slots }, (_, index) => page.cards[index] ?? fallback)
  return { ...page, layout, cards }
}

/** Pages as the server accepts them: no empty titles. */
export function cleanSettings(settings: Settings): Settings {
  return {
    home_after_s: settings.home_after_s,
    pages: settings.pages.map(({ layout, cards, title, ticker, ticker_s }) => ({
      layout,
      cards,
      ...(title?.trim() ? { title: title.trim() } : {}),
      ...(ticker?.length ? { ticker, ticker_s: ticker_s ?? 12 } : {}),
    })),
  }
}

/** Adds or removes one card of a page's strip, keeping the order the cards were chosen in. */
export function toggledTicker(page: Page, card: string, on: boolean): Page {
  const current = page.ticker ?? []
  const ticker = on
    ? [...current.filter((item) => item !== card), card]
    : current.filter((item) => item !== card)
  return { ...page, ticker: ticker.slice(0, MAX_TICKER) }
}

export function moved<T>(items: readonly T[], from: number, to: number): T[] {
  const copy = [...items]
  if (to < 0 || to >= copy.length) return copy
  const [item] = copy.splice(from, 1)
  copy.splice(to, 0, item as T)
  return copy
}

export function uptimeParts(seconds: number): { days: number; hours: number; minutes: number } {
  const minutes = Math.floor(seconds / 60)
  return {
    days: Math.floor(minutes / 1440),
    hours: Math.floor(minutes / 60) % 24,
    minutes: minutes % 60,
  }
}

export const AgentOptions = z.object({
  writes: z.array(z.string()),
  diagnostics: z.boolean(),
})
export const ConnectedAgent = z.looseObject({
  token: z.string(),
  device: z.looseObject({ id: z.string(), name: z.string() }),
})

/**
 * How Claude Code and Codex reach this app's MCP endpoint with an agent's token, in this
 * repository only (docs/agent-access.md): Claude Code in the main checkout's local scope, Codex
 * disabled globally and enabled by the repository's .codex/config.toml.
 */
export function agentSetup(origin: string, token: string): { claude: string; codex: string } {
  const url = `${origin}/v1/mcp`
  return {
    claude: `claude mcp add --scope local --transport http assistant ${url} --header "Authorization: Bearer ${token}"`,
    codex: [
      `# ~/.codex/config.toml; включает сервер .codex/config.toml репозитория`,
      `[mcp_servers.assistant]`,
      `url = "${url}"`,
      `enabled = false`,
      `bearer_token_env_var = "ASSISTANT_AGENT_TOKEN"`,
      ``,
      `# в окружении, где запускается codex:`,
      `export ASSISTANT_AGENT_TOKEN=${token}`,
    ].join('\n'),
  }
}
