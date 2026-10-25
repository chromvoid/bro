/**
 * Calls (docs/calls-spec.md): the wire formats of /v1/calls and its signalling socket, and the
 * four requests. The server decides who may call whom and keeps the log; the client only maps
 * its refusals to words.
 */
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { z } from '@/core/zod'

export const CallState = z.enum(['ringing', 'active', 'ended'])
export const CallOutcome = z.enum([
  'completed',
  'declined',
  'cancelled',
  'missed',
  'failed',
  'lost',
])
export type CallOutcome = z.infer<typeof CallOutcome>

export const Call = z.looseObject({
  id: z.string(),
  channel_id: z.string(),
  kind: z.enum(['audio', 'video']),
  state: CallState,
  outcome: CallOutcome.nullable(),
  role: z.enum(['caller', 'callee']),
  peer: z.looseObject({ id: z.string(), name: z.string() }),
  created_at: z.string(),
  answered_at: z.string().nullable(),
  ended_at: z.string().nullable(),
})
export type Call = z.infer<typeof Call>
export const Current = z.looseObject({ call: Call.nullable() })
export type Current = z.infer<typeof Current>
export const CallReply = z.looseObject({ call: Call })

export const ServerFrame = z.discriminatedUnion('type', [
  z.object({ type: z.literal('state'), state: CallState, outcome: CallOutcome.nullable() }),
  z.object({ type: z.literal('peer'), present: z.boolean() }),
  z.object({ type: z.literal('offer'), sdp: z.string() }),
  z.object({ type: z.literal('answer'), sdp: z.string() }),
  z.object({ type: z.literal('pong') }),
  z.object({ type: z.literal('error'), code: z.string() }),
])
export type ServerFrame = z.infer<typeof ServerFrame>
export type ClientFrame = { type: 'offer' | 'answer'; sdp: string } | { type: 'ping' }
/** `declined` only refuses a ringing call: one answered on another device stays (409). */
export type EndReason =
  'hangup' | 'declined' | 'media_lost' | 'ice_timeout' | 'ice_failed' | 'no_offer'

/** What kind of network an address belongs to; the address itself never leaves the device. */
export type Kind =
  'lan4' | 'lan6' | 'tailnet4' | 'tailnet6' | 'public4' | 'public6' | 'mdns' | 'other' | 'unknown'
export type CandidateType = 'host' | 'srflx' | 'prflx' | 'relay'
export type Protocol = 'udp' | 'tcp'
export type Client = 'android' | 'ios' | 'browser'
export const KINDS: readonly Kind[] = [
  'lan4',
  'lan6',
  'tailnet4',
  'tailnet6',
  'public4',
  'public6',
  'mdns',
  'other',
  'unknown',
]
export const TYPES: readonly CandidateType[] = ['host', 'srflx', 'prflx', 'relay']
export const PROTOCOLS: readonly Protocol[] = ['udp', 'tcp']

export interface Selected {
  local_type: CandidateType
  remote_type: CandidateType
  local_kind: Kind
  protocol: Protocol
}

/** The diagnostics summary sent with `end`: codes and numbers about this side only. */
export interface Summary {
  client: Client
  ice: 'new' | 'checking' | 'connected'
  local_kinds?: Kind[]
  local_types?: CandidateType[]
  selected?: Selected | null
  rtt_ms?: number
  loss_pct?: number
  autoplay_blocked?: boolean
}

export interface EndBody {
  reason?: EndReason
  summary?: Summary
}

const path = (id: string, action: string) => `/v1/calls/${encodeURIComponent(id)}/${action}`

export async function fetchCurrent(signal?: AbortSignal): Promise<Current> {
  return Current.parse(await api.get('/v1/calls/current', signal))
}

const IceConfig = z.object({
  ice_servers: z.array(
    z.object({
      urls: z.union([z.string(), z.array(z.string())]),
      username: z.string().optional(),
      credential: z.string().optional(),
    }),
  ),
  ice_transport_policy: z.enum(['all', 'relay']).optional(),
})

/** Temporary credentials are available only after this account is a participant. */
export async function fetchIce(id: string): Promise<RTCConfiguration> {
  const value = IceConfig.parse(await api.get(path(id, 'ice')))
  return { iceServers: value.ice_servers, iceTransportPolicy: value.ice_transport_policy ?? 'all' }
}

/** The server makes the call id; the key makes a repeated tap the same call. */
export async function startCall(channelId: string, key: string, expectedUser: string) {
  const reply = await api.post(
    '/v1/calls',
    { channel_id: channelId, kind: 'audio' },
    {
      key,
      expectedUser,
    },
  )
  return CallReply.parse(reply.data).call
}

export async function acceptCall(id: string, expectedUser: string) {
  return CallReply.parse((await api.post(path(id, 'accept'), {}, { expectedUser })).data).call
}

/** Declines, cancels or hangs up; also answers 200 for a call that already ended. */
export async function endCall(id: string, body: EndBody) {
  return CallReply.parse((await api.post(path(id, 'end'), body)).data).call
}

const ERRORS: Record<string, string> = {
  busy: 'calls.error.busy',
  in_call: 'calls.error.inCall',
  not_friends: 'calls.error.notFriends',
  not_available: 'calls.error.notAvailable',
  rate_limited: 'calls.error.tooMany',
  answered_elsewhere: 'calls.error.answeredElsewhere',
  ended: 'calls.error.ended',
}

/**
 * The i18n key for a refusal the call screens explain in their own words, or null when the
 * generic errorText() applies. «incoming» has none: that call simply appears on screen.
 */
export function errorKey(error: unknown): string | null {
  return error instanceof ApiError && error.code ? (ERRORS[error.code] ?? null) : null
}

/** «2:13», or «1:02:03» from one hour: the length of a call. */
export function duration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const rest = String(total % 60).padStart(2, '0')
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${rest}` : `${minutes}:${rest}`
}
