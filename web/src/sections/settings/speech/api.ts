import { api } from '@/core/client'
import type { Api } from '@/core/http'
import { z } from '@/core/zod'

const Capability = z.enum(['asr', 'tts'])
export const SpeechModel = z.object({
  id: z.string(),
  name: z.string(),
  capabilities: z.array(Capability),
  asr_mode: z.enum(['streaming', 'file']).nullable(),
  voices: z.array(z.string()),
  default_voice: z.string().nullable(),
  tts_segment_chars: z.number().positive().nullable(),
  selectable: z.boolean(),
  unavailable_reason: z.string().nullable(),
})
export type SpeechModel = z.infer<typeof SpeechModel>
export const SpeechModelCatalog = z.object({ models: z.array(SpeechModel) })
export const SpeechProvider = z.object({
  id: z.string(),
  label: z.string(),
  capabilities: z.array(Capability),
  connection_kind: z.enum(['api_key', 'subscription', 'local']),
  voices: z.array(z.string()).default([]),
  default_voice: z.string().nullish(),
  dynamic_models: z.boolean().default(false),
  models: z.array(SpeechModel).default([]),
})
export const SpeechConnection = z.object({
  id: z.string(),
  ownership: z.enum(['personal', 'shared']),
  provider: z.string(),
  label: z.string(),
  enabled: z.boolean(),
  revision: z.number().int().nonnegative(),
  has_key: z.boolean(),
  capabilities: z.array(Capability),
})
export type SpeechConnection = z.infer<typeof SpeechConnection>
export const SpeechPreferences = z.object({
  revision: z.number().int().nonnegative(),
  asr: z.string(),
  fallback_asr: z.string().nullable(),
  tts: z.string().nullable(),
  voice: z.string(),
  tts_model: z.string().nullable().default(null),
  asr_model: z.string().nullable().default(null),
  fallback_asr_model: z.string().nullable().default(null),
})
export type SpeechPreferences = z.infer<typeof SpeechPreferences>
export const SpeechReadiness = z.object({
  state: z.enum(['configured', 'unavailable']),
  code: z.string().optional(),
  action: z.string().optional(),
  retry_after: z.number().nullish(),
  provider: z.string().optional(),
  label: z.string().optional(),
})
export const SpeechSnapshot = z.object({
  providers: z.array(SpeechProvider),
  connections: z.array(SpeechConnection),
  preferences: SpeechPreferences.optional(),
  asr: SpeechReadiness.optional(),
  tts: SpeechReadiness.optional(),
})
export type SpeechSnapshot = z.infer<typeof SpeechSnapshot>

export interface ConnectionInput {
  provider: string
  label: string
  enabled: boolean
  revision: number
  api_key?: string
}

/** Secrets go directly to the guarded HTTP client, never through the durable write journal. */
export function speechSettingsClient(
  user: string,
  currentUser: () => string | undefined,
  signal: AbortSignal,
  admin = false,
  client: Api = api,
) {
  const endpoint = `/v1/${admin ? 'admin' : 'me'}/speech`
  const beforeSend = () => {
    signal.throwIfAborted()
    if (currentUser() !== user) throw new DOMException('Account changed', 'AbortError')
  }
  const options = { expectedUser: user, signal, beforeSend }
  return {
    async models(id: string, requestSignal?: AbortSignal, refresh = false) {
      beforeSend()
      const pending = requestSignal ? AbortSignal.any([signal, requestSignal]) : signal
      const value = await client.get(
        `${endpoint}/connections/${encodeURIComponent(id)}/models?capability=all${refresh ? '&refresh=1' : ''}`,
        pending,
      )
      pending.throwIfAborted()
      beforeSend()
      return SpeechModelCatalog.parse(value).models
    },
    async saveConnection(id: string, input: ConnectionInput) {
      await client.send(
        'PUT',
        `${endpoint}/connections/${encodeURIComponent(id)}`,
        JSON.stringify(input),
        options,
      )
    },
    async deleteConnection(id: string, revision: number) {
      await client.send(
        'DELETE',
        `${endpoint}/connections/${encodeURIComponent(id)}`,
        JSON.stringify({ revision }),
        options,
      )
    },
    async savePreferences(preferences: SpeechPreferences) {
      await client.send('PUT', '/v1/me/speech/preferences', JSON.stringify(preferences), options)
    },
  }
}

export function usableConnections(
  snapshot: SpeechSnapshot | undefined,
  capability: 'asr' | 'tts',
  catalogs: Record<string, SpeechModel[]> = {},
) {
  return (
    snapshot?.connections.filter((item) => {
      if (!item.enabled || !item.has_key) return false
      const provider = snapshot.providers.find((value) => value.id === item.provider)
      const models = catalogs[item.id] ?? (provider?.dynamic_models ? undefined : provider?.models)
      return models === undefined || models.some((model) => model.capabilities.includes(capability))
    }) ?? []
  )
}

export function usableModel(model: SpeechModel, capability: 'asr' | 'tts') {
  return (
    model.capabilities.includes(capability) &&
    model.selectable &&
    (capability === 'asr' ? model.asr_mode !== null : model.voices.length > 0)
  )
}

export interface SpeechCatalogState {
  revision: number
  phase: 'loading' | 'ready' | 'error'
  models: SpeechModel[]
  failure: string
}

/** Do not silently select a different provider after access was revoked or a key disabled. */
export function missingSelection(selected: string | null, available: SpeechConnection[]) {
  return !!selected && selected !== 'auto' && !available.some((item) => item.id === selected)
}
