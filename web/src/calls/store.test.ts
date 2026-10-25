import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/core/http'
import { queryClient } from '@/core/query'
import type { Call } from './api'
import { calls } from './store'

const mocks = vi.hoisted(() => ({ endCall: vi.fn(), toast: vi.fn() }))
vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  endCall: mocks.endCall,
}))
vi.mock('@/core/client', () => ({ api: { hooks: {} }, updates: { hold: vi.fn() } }))
vi.mock('@/speech/playback', () => ({ speechPlayback: { stop: vi.fn() } }))
vi.mock('vue-sonner', () => ({ toast: { error: mocks.toast } }))

const ringing: Call = {
  id: 'call-1',
  channel_id: 'ch1',
  kind: 'audio',
  state: 'ringing',
  outcome: null,
  role: 'callee',
  peer: { id: 'peer', name: 'Анна' },
  created_at: '2026-09-23T12:00:00+00:00',
  answered_at: null,
  ended_at: null,
}

beforeEach(() => {
  mocks.endCall.mockReset().mockResolvedValue({ ...ringing, state: 'ended', outcome: 'declined' })
  mocks.toast.mockReset()
  calls.finishedId.value = null
  vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue()
})

describe('ending a call without a session here', () => {
  it('declining says so; hanging up on another device does not', async () => {
    await calls.decline(ringing)
    expect(mocks.endCall).toHaveBeenLastCalledWith('call-1', { reason: 'declined' })
    expect(calls.finishedId.value).toBe('call-1')
    await calls.hangUpElsewhere({ ...ringing, state: 'active' })
    expect(mocks.endCall).toHaveBeenLastCalledWith('call-1', {})
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['calls'] })
  })

  it('a decline that comes after an answer on another device explains it and reads again', async () => {
    mocks.endCall.mockRejectedValueOnce(
      new ApiError(409, 'answered_elsewhere', null, null, null, 'Звонок принят'),
    )
    await calls.decline(ringing)
    expect(mocks.toast).toHaveBeenCalledWith('Звонок принят на другом устройстве')
    expect(calls.finishedId.value).toBeNull()
    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['calls'] })
  })
})
