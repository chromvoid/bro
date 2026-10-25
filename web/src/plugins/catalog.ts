/**
 * Wire formats of the client plugin API (`/v1/plugins`): the catalog, results, lookups, refusals
 * and events, and the choice of a client module for an installation.
 */
import { ApiError } from '@/core/http'
import { z } from '@/core/zod'
import {
  PLUGIN_API_VERSION,
  type InstallationStatus,
  type InvocationOutcome,
  type PluginCapabilities,
  type PluginError,
  type PluginUi,
  type Recovery,
} from './api'

const Tool = z.looseObject({
  id: z.string(),
  version: z.string(),
  effect: z.string(),
  scopes: z.array(z.string()).nullish(),
  surfaces: z.array(z.string()).nullish(),
})

const Contribution = z.looseObject({
  id: z.string(),
  kind: z.string(),
  title: z.string(),
  capability: z.string().nullish(),
  entry: z.string().nullish(),
  client_api: z.number().nullish(),
  scopes: z.array(z.string()).nullish(),
  fallback: z.string().nullish(),
  requires_tools: z.record(z.string(), z.number()).nullish(),
})

export const Installation = z.looseObject({
  installation_id: z.string().regex(/^[A-Za-z0-9._~-]{1,200}$/),
  package: z.looseObject({ id: z.string(), version: z.string().nullish() }),
  title: z.string(),
  description: z.string().nullish(),
  status: z.string(),
  note: z.string().nullish(),
  granted_scopes: z.array(z.string()).nullish(),
  tools: z.array(Tool).default([]),
  ui: z.array(Contribution).default([]),
  configuration: z.looseObject({ writable: z.boolean() }).nullish(),
  events: z.array(z.looseObject({ type: z.string() })).default([]),
})
export type Installation = z.infer<typeof Installation>

export const Catalog = z.looseObject({
  installations: z.array(z.unknown()).transform((items) =>
    // One malformed entry never hides the others.
    items.flatMap((item) => {
      const parsed = Installation.safeParse(item)
      return parsed.success ? [parsed.data] : []
    }),
  ),
})

const STATUSES: InstallationStatus[] = [
  'starting',
  'ready',
  'degraded',
  'unavailable',
  'incompatible',
  'rejected',
  'disabled',
]
export function statusOf(value: string): InstallationStatus {
  return (STATUSES as string[]).includes(value) ? (value as InstallationStatus) : 'unknown'
}
/** Tools may be called; an unavailable installation may recover by itself. */
export function dispatchable(status: InstallationStatus): boolean {
  return ['starting', 'ready', 'degraded', 'unavailable'].includes(status)
}

export function capabilitiesOf(installation: Installation): PluginCapabilities {
  return {
    status: statusOf(installation.status),
    note: installation.note ?? null,
    grantedScopes: installation.granted_scopes ?? [],
    tools: Object.fromEntries(
      installation.tools.map((tool) => [
        tool.id,
        {
          id: tool.id,
          version: tool.version,
          effect: tool.effect,
          scopes: tool.scopes ?? [],
          surfaces: tool.surfaces ?? [],
        },
      ]),
    ),
    configurationWritable: installation.configuration ? installation.configuration.writable : null,
    events: installation.events.map((event) => event.type),
  }
}

export type Fallback = 'no_ui' | 'client_api' | 'missing_tools' | 'unavailable'

/**
 * The module for an installation: same package and contribution id, `client_api` equal to this
 * shell's, every required tool at the declared major. Anything else is a fallback with a reason;
 * the plugin's tools stay available to the assistant.
 */
export function matchUi(
  installation: Installation,
  registry: readonly PluginUi[],
): { ui: PluginUi; fallback: null } | { ui: null; fallback: Fallback } {
  if (!dispatchable(statusOf(installation.status))) return { ui: null, fallback: 'unavailable' }
  const majors = new Map(
    installation.tools.map((tool) => [tool.id, Number(tool.version.split('.')[0] ?? NaN)]),
  )
  let fallback: Fallback = 'no_ui'
  for (const contribution of installation.ui.filter((item) => item.kind === 'native')) {
    const ui = registry.find(
      (item) =>
        item.packageId === installation.package.id && item.contributionId === contribution.id,
    )
    if (!ui) continue
    if (contribution.client_api !== PLUGIN_API_VERSION || ui.pluginApi !== PLUGIN_API_VERSION) {
      fallback = 'client_api'
      continue
    }
    const required = [
      ...Object.entries(contribution.requires_tools ?? {}),
      ...Object.entries(ui.requiredTools),
    ]
    if (required.some(([tool, major]) => majors.get(tool) !== major)) {
      fallback = 'missing_tools'
      continue
    }
    return { ui, fallback: null }
  }
  return { ui: null, fallback }
}

const WireError = z.looseObject({
  code: z.string(),
  message: z.string().nullish(),
  recovery: z.string().nullish(),
  field_errors: z.array(z.looseObject({ path: z.string(), code: z.string() })).nullish(),
  retry_after_ms: z.number().nullish(),
})

const RECOVERIES: Recovery[] = [
  'fix_arguments',
  'reauthenticate',
  'refresh_state',
  'lookup',
  'none',
]

function pluginError(wire: z.infer<typeof WireError>): PluginError {
  const recovery = (RECOVERIES as string[]).includes(wire.recovery ?? '')
    ? (wire.recovery as Recovery)
    : 'none'
  return {
    code: wire.code.slice(0, 64),
    message: (wire.message || wire.code).slice(0, 500),
    recovery,
    fieldErrors: (wire.field_errors ?? []).slice(0, 50).map(({ path, code }) => ({ path, code })),
    retryAfterMs: wire.retry_after_ms ?? null,
  }
}

export function localError(code: string, message = code): PluginError {
  return { code, message, recovery: 'none', fieldErrors: [], retryAfterMs: null }
}
export const NETWORK = localError('network_unavailable', 'Нет связи с сервером')

/** The structured refusal (`assistant.rejected/v1`); unreadable bodies keep only HTTP facts. */
export function errorOf(error: ApiError): PluginError {
  const detail = WireError.safeParse(error.detail)
  const retry =
    error.retryAfter && /^\d+$/.test(error.retryAfter) ? Number(error.retryAfter) * 1000 : null
  if (detail.success) {
    const parsed = pluginError(detail.data)
    return parsed.retryAfterMs === null ? { ...parsed, retryAfterMs: retry } : parsed
  }
  return {
    ...localError(error.code ?? `http_${error.status}`),
    recovery: error.status === 504 ? 'lookup' : 'none',
    retryAfterMs: retry,
  }
}

const Result = z.looseObject({
  state: z.string(),
  data: z.unknown().optional(),
  error: WireError.nullish(),
  receipts: z.array(z.unknown()).nullish(),
  progress: z.unknown().optional(),
})
type Result = z.infer<typeof Result>

export const TERMINAL = new Set(['completed', 'failed', 'cancelled'])

/** The result inside an invocation response or a lookup (`assistant.lookup/v1`). */
export function resultOf(body: unknown): Result | null {
  const record = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {}
  const parsed = Result.safeParse(record.contract === 'assistant.lookup/v1' ? record.result : body)
  return parsed.success ? parsed.data : null
}

export function outcomeOf(result: Result | null, clientKey: string | null): InvocationOutcome {
  if (!result)
    return { kind: 'unknown', clientKey, notFound: false, error: localError('unexpected_state') }
  const receipts = result.receipts ?? []
  switch (result.state) {
    case 'completed':
      return { kind: 'completed', data: result.data ?? null, receipts, clientKey }
    case 'failed':
    case 'cancelled':
      return {
        kind: 'failed',
        error: result.error ? pluginError(result.error) : localError(result.state),
        receipts,
        clientKey,
      }
    case 'accepted':
    case 'running':
      return clientKey
        ? { kind: 'pending', clientKey, state: result.state, progress: result.progress ?? null }
        : { kind: 'unknown', clientKey, notFound: false, error: localError('unexpected_state') }
    default:
      return { kind: 'unknown', clientKey, notFound: false, error: localError('unexpected_state') }
  }
}

export const PluginEventWire = z.looseObject({
  event_id: z.string(),
  installation_id: z.string(),
  type: z.string(),
  occurred_at: z.string(),
  cursor: z.string().regex(/^[A-Za-z0-9._:~-]{1,256}$/),
  payload: z.record(z.string(), z.unknown()).default({}),
  operation_key: z.string().nullish(),
})
