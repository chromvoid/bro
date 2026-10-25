import type { Api, WriteOptions } from '@/core/http'
import { isIsoDay, shiftDay, zonedInstant } from '@/core/dates'
import { z } from '@/core/zod'

const Instant = z.string().datetime({ offset: true })
export const TranscriptStatus = z.enum(['pending', 'processing', 'ready', 'empty', 'failed'])
const Summary = z.object({
  status: TranscriptStatus,
  preview: z.string().max(200).nullable(),
  error_code: z.string().nullable(),
  updated_at: Instant.nullable(),
  retry_may_repeat: z.boolean(),
})
export const Recording = z.object({
  id: z.uuid(),
  session_id: z.uuid(),
  sequence: z.number().int().nonnegative(),
  sha256: z.string(),
  stored: z.literal(true),
  format: z.enum(['wav', 'ogg-opus', 'aac-adts']),
  bytes: z.number().int().nonnegative(),
  duration_ms: z.number().int().positive(),
  expires_at: Instant.nullable(),
  started_at: Instant,
  received_at: Instant,
  state: z.literal('stored'),
  transcription: Summary,
})
export type Recording = z.infer<typeof Recording>
export const Recordings = z.object({
  recordings: z.array(Recording),
  next_cursor: z.string().nullable(),
})
export const Transcript = Summary.omit({ preview: true })
  .extend({
    id: z.uuid(),
    text: z.string().max(16_000).nullable(),
    provider: z.string().nullable(),
    model: z.string().nullable(),
    transcribed_at: Instant.nullable(),
  })
  .refine(
    (value) => value.status !== 'ready' || !!value.text?.trim(),
    'Ready transcript must contain text',
  )
export type Transcript = z.infer<typeof Transcript>
export const Usage = z.object({
  used_bytes: z.number().int().nonnegative(),
  limits: z.object({
    retention_days: z.number().int().nonnegative().nullable(),
    max_user_bytes: z.number().int().positive(),
  }),
})
export type Usage = z.infer<typeof Usage>
export interface DateRange {
  from?: string
  to?: string
}

export function dateRange(day: string, zone: string): DateRange {
  if (!isIsoDay(day)) return {}
  return {
    from: zonedInstant(`${day}T00:00`, zone) ?? undefined,
    to: zonedInstant(`${shiftDay(day, 1)}T00:00`, zone) ?? undefined,
  }
}
export function recordingsClient(api: Pick<Api, 'get' | 'send'>) {
  return {
    async list(range: DateRange, cursor: string | null, signal: AbortSignal) {
      const params = new URLSearchParams({ limit: '30' })
      if (cursor) params.set('cursor', cursor)
      if (range.from) params.set('from', range.from)
      if (range.to) params.set('to', range.to)
      return Recordings.parse(await api.get(`/v1/ambient-audio/recordings?${params}`, signal))
    },
    async usage(signal: AbortSignal) {
      return Usage.parse(await api.get('/v1/ambient-audio', signal))
    },
    async transcription(id: string, signal: AbortSignal) {
      const value = Transcript.parse(
        await api.get(`/v1/ambient-audio/batches/${encodeURIComponent(id)}/transcription`, signal),
      )
      if (value.id !== id) throw new Error('Invalid recording identity')
      return value
    },
    async remove(id: string, options: WriteOptions) {
      const { data } = await api.send(
        'DELETE',
        `/v1/ambient-audio/batches/${encodeURIComponent(id)}`,
        undefined,
        options,
      )
      const receipt = z.object({ id: z.uuid(), deleted: z.literal(true) }).parse(data)
      if (receipt.id !== id) throw new Error('Invalid deletion receipt')
    },
    async retry(id: string, options: WriteOptions) {
      const { data } = await api.send(
        'POST',
        `/v1/ambient-audio/batches/${encodeURIComponent(id)}/transcription/retry`,
        undefined,
        options,
      )
      const value = Transcript.parse(data)
      if (value.id !== id) throw new Error('Invalid recording identity')
      return value
    },
  }
}
export type RecordingsClient = ReturnType<typeof recordingsClient>

export function recordingSource(
  id: string,
  format: Recording['format'],
  canPlay: (mime: string) => string,
  fallback = false,
): string {
  const mime = {
    wav: 'audio/wav',
    'ogg-opus': 'audio/ogg; codecs="opus"',
    'aac-adts': 'audio/aac',
  }[format]
  const base = `/v1/ambient-audio/batches/${encodeURIComponent(id)}`
  return fallback || !canPlay(mime) ? `${base}/playback?format=wav` : base
}
export function transcriptErrorKey(code: string | null): string {
  if (['not_configured', 'auth_required', 'secret_unavailable'].includes(code ?? ''))
    return 'recordings.errors.connection'
  if (['no_access', 'execution_disabled', 'session_expired'].includes(code ?? ''))
    return 'recordings.errors.access'
  if (['application_limit', 'provider_limit'].includes(code ?? '')) return 'recordings.errors.limit'
  if (code === 'unknown_outcome') return 'recordings.errors.unknownOutcome'
  if (
    ['invalid_audio', 'decode_failed', 'decoder_unavailable', 'audio_missing'].includes(code ?? '')
  )
    return 'recordings.errors.audio'
  return 'recordings.errors.transcription'
}
