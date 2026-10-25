/**
 * Installation administration: users with their rights and subscriptions, provider accounts, and
 * workers on connected machines. The server checks every right again; nothing here holds a
 * secret or a feature flag.
 */
import { z } from '@/core/zod'
import { JOIN_URL, type AuthLink } from '@/sections/auth/links'

const id = z.string()
const text = z.string()
const maybe = z.string().nullish()

export const User = z.looseObject({
  id,
  login: maybe,
  name: text,
  status: z.enum(['active', 'disabled']),
  system_admin: z.boolean(),
  revision: z.number(),
  scopes: z.array(z.string()),
})
export type User = z.infer<typeof User>
export const Users = z.looseObject({ users: z.array(User), available_scopes: z.array(z.string()) })

export const Token = z.looseObject({ token: text })

/** What one person may use: subscriptions and shared speech connections, granted to them alone. */
export const Grant = z.looseObject({
  user_id: id,
  accounts: z.array(z.string()).default([]),
  speech_connections: z.array(z.string()).default([]),
})
export type Grant = z.infer<typeof Grant>
export const Access = z.looseObject({ grants: z.array(Grant) })

export const Audit = z.looseObject({
  events: z.array(
    z.looseObject({ id: z.number(), action: text, actor_user_id: maybe, created_at: text }),
  ),
})

/** Scope groups in the words of the product, in the order a person decides them. */
const SCOPE_GROUPS: Array<{ key: string; prefix: string[] }> = [
  { key: 'assistant', prefix: ['session.'] },
  { key: 'nutrition', prefix: ['nutrition.'] },
  { key: 'personal', prefix: ['personal.'] },
  { key: 'activity', prefix: ['activity.'] },
  { key: 'tasks', prefix: ['tasks.', 'timers.'] },
  { key: 'circle', prefix: ['challenges.', 'calls.'] },
  { key: 'research', prefix: ['research.'] },
  { key: 'weather', prefix: ['weather.'] },
  { key: 'places', prefix: ['places.'] },
]

/** Scopes grouped for choosing; a scope this build does not know stays in «other». */
export function groupScopes(available: string[]): Array<{ key: string; scopes: string[] }> {
  const groups = SCOPE_GROUPS.map((group) => ({
    key: group.key,
    scopes: available.filter((scope) => group.prefix.some((prefix) => scope.startsWith(prefix))),
  }))
  const known = new Set(groups.flatMap((group) => group.scopes))
  const other = available.filter((scope) => !known.has(scope))
  return [...groups, { key: 'other', scopes: other }].filter((group) => group.scopes.length)
}

/** The public Android invitation page; the installation and token stay in the fragment. */
export function tokenLink(origin: string, kind: AuthLink['kind'], token: string) {
  return `${JOIN_URL}#server=${encodeURIComponent(origin)}&kind=${kind}&token=${encodeURIComponent(token)}`
}

// Subscriptions (accounts).

export const Account = z.looseObject({
  id,
  provider: text,
  label: text,
  enabled: z.boolean(),
  revision: z.number(),
  reset_credits: z.number().nullish(),
  reset_credits_observed_at: maybe,
})
export type Account = z.infer<typeof Account>

export const Quota = z.looseObject({
  account_id: id,
  scope: text,
  bucket: text,
  exhausted: z.boolean().nullish(),
  remaining_percent: z.number().nullish(),
  resets_at: maybe,
  observed_at: maybe,
})
export type Quota = z.infer<typeof Quota>

export const Connection = z.looseObject({
  id,
  account_id: id,
  machine: text,
  refresh_owner: maybe,
  capabilities: z.array(z.string()).default([]),
  enabled: z.boolean(),
  auth_state: text,
  can_reconnect: z.boolean().default(false),
  quota_check: z.looseObject({ state: text, checked_at: maybe }).nullish(),
  revision: z.number(),
})
export type Connection = z.infer<typeof Connection>

export const needsReauthentication = (connection: Connection) =>
  connection.auth_state === 'auth_required' || connection.quota_check?.state === 'auth_required'

export const Policy = z.looseObject({
  id,
  name: text,
  accounts: z.array(z.string()).default([]),
  fallback: z.boolean().default(false),
  revision: z.number(),
})
export type Policy = z.infer<typeof Policy>

export const Login = z.looseObject({
  id,
  account_id: maybe,
  provider: z.string().default('codex'),
  label: text,
  state: text,
  error: maybe,
  reconnect_connection_id: maybe,
  action: z.looseObject({ url: maybe, code: maybe, input: maybe }).nullish(),
})
export type Login = z.infer<typeof Login>

export const AccountsSnapshot = z.looseObject({
  accounts: z.array(Account),
  quotas: z.array(Quota).default([]),
  connections: z.array(Connection).default([]),
  policies: z.array(Policy).default([]),
  logins: z.array(Login).default([]),
  can_connect: z.boolean().default(false),
  login_providers: z.array(z.string()).default([]),
})
export type AccountsSnapshot = z.infer<typeof AccountsSnapshot>

export const ACCOUNTS = {
  endpoint: '/v1/accounts/operations',
  lookupPrefix: '/v1/accounts/operations/by-key/',
  domain: 'accounts',
}

const STALE_MS = 10 * 60_000

/** «Ручных сбросов: 2» only when the observation is recent; otherwise a dash, never a guess. */
export function resetCredits(
  account: Account,
  now = Date.now(),
): { value: string; stale: boolean } {
  const observed = Date.parse(account.reset_credits_observed_at ?? '')
  const stale = Number.isFinite(observed) && now - observed > STALE_MS
  const known =
    Number.isFinite(observed) &&
    !stale &&
    Number.isInteger(account.reset_credits) &&
    (account.reset_credits ?? -1) >= 0
  return { value: known ? String(account.reset_credits) : '—', stale }
}

export interface QuotaView {
  scope: string
  /** Seconds of the window, or «request» for a request-rate limit. */
  period:
    | { kind: 'request' }
    | { kind: 'week' }
    | { kind: 'days' | 'hours' | 'minutes'; count: number }
    | { kind: 'raw'; value: string }
  /** 0–100, only for a fresh, unexpired observation. */
  remaining: number | null
  exhausted: boolean
  stale: boolean
  /** The reset time has passed: a new observation is awaited; it is not assumed replenished. */
  expired: boolean
}

export function quotaView(quota: Quota, now = Date.now()): QuotaView {
  const seconds = Number(quota.bucket)
  const period: QuotaView['period'] =
    quota.bucket === 'request'
      ? { kind: 'request' }
      : seconds === 604_800
        ? { kind: 'week' }
        : seconds > 0 && seconds % 86_400 === 0
          ? { kind: 'days', count: seconds / 86_400 }
          : seconds > 0 && seconds % 3600 === 0
            ? { kind: 'hours', count: seconds / 3600 }
            : seconds > 0 && seconds % 60 === 0
              ? { kind: 'minutes', count: seconds / 60 }
              : { kind: 'raw', value: quota.bucket }
  const observed = Date.parse(quota.observed_at ?? '')
  const stale = !Number.isFinite(observed) || now - observed > STALE_MS
  const expired = !!quota.resets_at && Date.parse(quota.resets_at) <= now
  const remaining =
    !stale && !expired && Number.isFinite(quota.remaining_percent ?? NaN)
      ? Math.max(0, Math.min(100, quota.remaining_percent!))
      : null
  return {
    scope: quota.scope,
    period,
    remaining,
    exhausted: !!quota.exhausted && !expired,
    stale,
    expired,
  }
}

export const ACTIVE_LOGIN = new Set(['queued', 'starting', 'waiting_user', 'verifying'])
export const TERMINAL_LOGIN = new Set(['ready', 'cancelled', 'expired', 'failed'])

export interface LoginAction {
  url: string
  /** Codex shows a one-time code to type; Claude returns a code to paste back here. */
  code: string | null
  pasteCode: boolean
}

/**
 * The provider sign-in step, only for known provider hosts over HTTPS on the default port and
 * without credentials in the address. Anything else is not shown as a link.
 */
export function loginAction(login: Login): LoginAction | null {
  if (login.state !== 'waiting_user' || !login.action?.url) return null
  let parsed: URL
  try {
    parsed = new URL(login.action.url)
  } catch {
    return null
  }
  const claude = login.provider === 'claude'
  const allowed = claude
    ? ['claude.ai', 'claude.com'].includes(parsed.hostname) &&
      ['/oauth/authorize', '/cai/oauth/authorize'].includes(parsed.pathname)
    : login.provider === 'codex' &&
      ['auth.openai.com', 'auth0.openai.com', 'chatgpt.com'].includes(parsed.hostname)
  if (
    parsed.protocol !== 'https:' ||
    !allowed ||
    parsed.username ||
    parsed.password ||
    (parsed.port && parsed.port !== '443')
  )
    return null
  if (claude)
    return login.action.input === 'authorization_code'
      ? { url: parsed.href, code: null, pasteCode: true }
      : null
  return typeof login.action.code === 'string'
    ? { url: parsed.href, code: login.action.code, pasteCode: false }
    : null
}

// Workers.

export const Machine = z.looseObject({
  id,
  label: text,
  enabled: z.boolean(),
  revision: z.number(),
  heartbeat: maybe,
  inventory: z
    .looseObject({ workspaces: z.array(z.string()).default([]) })
    .default({ workspaces: [] }),
})
export type Machine = z.infer<typeof Machine>

export const Profile = z.looseObject({
  id,
  label: text,
  harness: text,
  model: text,
  workspace: text,
  permissions: z.enum(['read_only', 'workspace_write']),
  revision: z.number(),
})
export type Profile = z.infer<typeof Profile>

export const Executor = z.looseObject({
  id,
  machine_id: id,
  profile_id: id,
  enabled: z.boolean(),
  allow_agent_dispatch: z.boolean().default(false),
  revision: z.number(),
})
export type Executor = z.infer<typeof Executor>

export const ExecutorGrant = z.looseObject({
  id,
  grantee_user_id: id,
  executor_id: id,
  profile_id: id,
  profile_revision: z.number(),
  workspace: text,
  permissions: z.enum(['read_only', 'workspace_write']),
  enabled: z.boolean(),
  revision: z.number(),
})
export type ExecutorGrant = z.infer<typeof ExecutorGrant>

export const RUN_STATES = [
  'queued',
  'starting',
  'running',
  'waiting_input',
  'paused',
  'blocked',
  'ready',
  'failed',
  'cancelled',
] as const
export const FINISHED = new Set(['ready', 'failed', 'cancelled'])

export const Run = z.looseObject({
  id,
  goal: text,
  status: z.string(),
  reason: maybe,
  deadline: text,
  workspace: maybe,
  stop_requested: z.boolean().default(false),
  revision: z.number(),
  created_at: maybe,
})
export type Run = z.infer<typeof Run>

export const Setup = z.looseObject({
  id,
  machine_id: id,
  harness: text,
  binding: text,
  step: text,
  state: text,
  revision: z.number(),
  settings: z.looseObject({ reason: maybe }).default({}),
})
export type Setup = z.infer<typeof Setup>

export const WorkersSnapshot = z.looseObject({
  machines: z.array(Machine).default([]),
  profiles: z.array(Profile).default([]),
  executors: z.array(Executor).default([]),
  executor_grants: z.array(ExecutorGrant).default([]),
  runs: z.array(Run).default([]),
  setups: z.array(Setup).default([]),
})
export type WorkersSnapshot = z.infer<typeof WorkersSnapshot>

export const RunDetail = Run.extend({
  can_send: z.boolean().default(false),
  attempts: z.array(z.looseObject({ id, state: text, account_id: maybe })).default([]),
  requests: z
    .array(
      z.looseObject({
        id,
        payload: z
          .looseObject({
            questions: z
              .array(
                z.looseObject({
                  id,
                  question: text,
                  options: z.array(z.looseObject({ label: text })).nullish(),
                }),
              )
              .default([]),
          })
          .default({ questions: [] }),
      }),
    )
    .default([]),
  events: z
    .array(z.looseObject({ kind: text, payload: z.looseObject({}).default({}), created_at: text }))
    .default([]),
})
export type RunDetail = z.infer<typeof RunDetail>

export const WORKERS = {
  endpoint: '/v1/workers/operations',
  lookupPrefix: '/v1/workers/operations/by-key/',
  domain: 'workers',
}

/** A machine is reachable when it reported within the last minute. */
export const online = (machine: Machine, now = Date.now()) =>
  !!machine.heartbeat && now - Date.parse(machine.heartbeat) < 60_000

export function harnessLabel(harness: string): string {
  const names: Record<string, string> = { codex: 'Codex', claude: 'Claude' }
  return names[harness] ?? harness
}

/** The machine, agent provider and model are visible when choosing an executor. */
export function executorLabel(snapshot: WorkersSnapshot, executor: Executor): string {
  const machine = snapshot.machines.find((item) => item.id === executor.machine_id)?.label
  const profile = snapshot.profiles.find((item) => item.id === executor.profile_id)
  return (
    [machine, profile?.label, profile && harnessLabel(profile.harness), profile?.model]
      .filter(Boolean)
      .join(' · ') || executor.id
  )
}
