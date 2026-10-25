import { z } from '@/core/zod'

export const DdxOutcome = z.object({
  error: z.string().nullish(),
  warning: z.string().nullish(),
  new_analyses: z.number().nonnegative().default(0),
  projection_incomplete: z.boolean().optional(),
  updated_analyses: z.number().nonnegative().default(0),
  observations_changed: z.number().nonnegative().default(0),
  batches_committed: z.number().nonnegative().default(0),
  partial: z.boolean().default(false),
})
export const DdxRun = z.object({
  id: z.string(),
  source_id: z.string(),
  state: z.enum(['queued', 'running', 'succeeded', 'failed', 'partial', 'cancelled']),
  trigger: z.string().optional(),
  created_at: z.string().nullish(),
  started_at: z.string().nullish(),
  finished_at: z.string().nullish(),
  available_at: z.string().nullish(),
  attempts: z.number().optional(),
  outcome: DdxOutcome.nullish(),
})
export type DdxRun = z.infer<typeof DdxRun>

/** Explicitly strip anything beyond the server's public connection projection. */
export const DdxConnection = z.object({
  provider: z.literal('ddx'),
  generation: z.number(),
  revision: z.number(),
  auth_state: z.enum(['active', 'auth_required', 'disconnected']),
  phone_masked: z.string().nullish(),
  automatic: z.boolean(),
  can_reconnect: z.boolean().default(true),
  next_sync_at: z.string().nullish(),
  last_attempt_at: z.string().nullish(),
  last_success_at: z.string().nullish(),
  last_measurement_at: z.string().nullish(),
  retry_at: z.string().nullish(),
  error: z.string().nullish(),
  warning: z.string().nullish(),
  last_run_id: z.string().nullish(),
  last_result: DdxOutcome.nullish(),
  active_run: DdxRun.nullish(),
})
export type DdxConnection = z.infer<typeof DdxConnection>

export const DdxLogin = z.object({
  id: z.string(),
  request_key: z.string().optional(),
  state: z.enum([
    'sending',
    'waiting_code',
    'verifying',
    'staged',
    'ready',
    'failed',
    'cancelled',
    'expired',
    'interrupted',
  ]),
  phone_masked: z.string().nullish(),
  expires_at: z.string().nullish(),
  resend_at: z.string().nullish(),
  error: z.string().nullish(),
  source_id: z.string().nullish(),
  can_resend: z.boolean().optional(),
})
export type DdxLogin = z.infer<typeof DdxLogin>
export const DdxLoginReply = z.object({ login: DdxLogin })
export const DdxStatus = z.object({
  available: z.boolean(),
  can_disconnect: z.boolean().optional(),
  active_login: DdxLogin.nullable(),
})
export type DdxLoginMode = 'connect' | 'reconnect' | 'replace'
export const ACTIVE_DDX_LOGIN = new Set<DdxLogin['state']>([
  'sending',
  'waiting_code',
  'verifying',
  'staged',
])
export const ACTIVE_DDX_RUN = new Set<DdxRun['state']>(['queued', 'running'])

/** Phone input accepts familiar Russian formatting and explicit international prefixes. */
export function ddxPhone(input: string): string | null {
  let phone = input.trim().replace(/[\s()-]/g, '')
  if (/^[78]\d{10}$/.test(phone)) phone = `+7${phone.slice(1)}`
  return /^\+[1-9]\d{9,14}$/.test(phone) ? phone : null
}

export function ddxCode(input: string): string | null {
  const code = input.trim()
  return /^\d{6}$/.test(code) ? code : null
}

export function waitSeconds(until: string | null | undefined, now: number): number {
  const instant = until ? Date.parse(until) : NaN
  return Number.isFinite(instant) ? Math.max(0, Math.ceil((instant - now) / 1000)) : 0
}
