import { describe, expect, it } from 'vitest'
import { ApiError, NetworkError } from '@/core/http'
import { Call, CallReply, Current, ServerFrame, duration, errorKey } from './api'

const call = {
  id: 'c1',
  channel_id: 'ch1',
  kind: 'audio',
  state: 'active',
  outcome: null,
  role: 'caller',
  peer: { id: 'u2', name: 'Анна' },
  created_at: '2026-09-23T12:00:00+00:00',
  answered_at: '2026-09-23T12:00:05+00:00',
  ended_at: null,
}

describe('call wire formats', () => {
  it('parses the call view, the current call and replies', () => {
    expect(Call.parse(call)).toMatchObject({ state: 'active', peer: { name: 'Анна' } })
    expect(Current.parse({ call: null }).call).toBeNull()
    const ended = { ...call, state: 'ended', outcome: 'completed', ended_at: call.answered_at }
    expect(CallReply.parse({ call: ended }).call.outcome).toBe('completed')
    expect(Call.safeParse({ ...call, state: 'busy' }).success).toBe(false)
    expect(Call.safeParse({ ...call, outcome: 'busy' }).success).toBe(false)
  })

  it('parses every frame the server sends and nothing else', () => {
    for (const frame of [
      { type: 'state', state: 'ended', outcome: 'lost' },
      { type: 'peer', present: false },
      { type: 'answer', sdp: 'v=0\r\n' },
      { type: 'pong' },
      { type: 'error', code: 'rate_limited' },
    ])
      expect(ServerFrame.parse(frame)).toEqual(frame)
    expect(ServerFrame.safeParse({ type: 'candidate', candidate: 'x' }).success).toBe(false)
    expect(ServerFrame.safeParse({ type: 'peer' }).success).toBe(false)
  })
})

describe('errorKey', () => {
  const refusal = (status: number, code: string) => new ApiError(status, code, null, null, null)

  it('names the refusals the call screens explain themselves', () => {
    expect(errorKey(refusal(409, 'busy'))).toBe('calls.error.busy')
    expect(errorKey(refusal(409, 'in_call'))).toBe('calls.error.inCall')
    expect(errorKey(refusal(403, 'not_friends'))).toBe('calls.error.notFriends')
    expect(errorKey(refusal(403, 'not_available'))).toBe('calls.error.notAvailable')
    expect(errorKey(refusal(429, 'rate_limited'))).toBe('calls.error.tooMany')
    expect(errorKey(refusal(409, 'answered_elsewhere'))).toBe('calls.error.answeredElsewhere')
    expect(errorKey(refusal(409, 'ended'))).toBe('calls.error.ended')
  })

  it('leaves «incoming» and everything else to the caller and errorText', () => {
    expect(errorKey(refusal(409, 'incoming'))).toBeNull()
    expect(errorKey(refusal(422, 'invalid_input'))).toBeNull()
    expect(errorKey(new NetworkError(false))).toBeNull()
    expect(errorKey(new Error('x'))).toBeNull()
  })
})

describe('duration', () => {
  it('counts minutes, then hours', () => {
    expect(duration(5)).toBe('0:05')
    expect(duration(133)).toBe('2:13')
    expect(duration(3723)).toBe('1:02:03')
    expect(duration(-1)).toBe('0:00')
  })
})
