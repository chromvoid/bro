import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive, shallowRef, type ComputedRef } from 'vue'
import type { Call, Current } from './api'
import type { CallSession, CallView } from './session'
import { calls } from './store'
import { useCalls, type CallModel } from './useCalls'

const mocks = vi.hoisted(() => ({
  fetchCurrent: vi.fn(),
  hold: vi.fn(),
  release: vi.fn(),
  socket: vi.fn(),
}))
vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  fetchCurrent: mocks.fetchCurrent,
}))
vi.mock('./signal', async (original) => ({
  ...(await original<typeof import('./signal')>()),
  SignalSocket: mocks.socket,
}))
vi.mock('@/core/client', () => ({ api: { hooks: {} }, updates: { hold: mocks.hold } }))
vi.mock('@/speech/playback', () => ({ speechPlayback: { stop: vi.fn() } }))
const person = reactive({
  me: { id: 'me', system_admin: false } as { id: string; system_admin: boolean } | null,
  scopes: ['calls.read', 'calls.write'],
  can(scope: string) {
    return this.scopes.includes(scope)
  },
})
vi.mock('@/stores/session', () => ({ useSession: () => person }))

const call = (state: Call['state'], role: Call['role']): Call => ({
  id: 'call-1',
  channel_id: 'ch1',
  kind: 'audio',
  state,
  outcome: null,
  role,
  peer: { id: 'peer', name: 'Анна' },
  created_at: '2026-09-23T12:00:00+00:00',
  answered_at: state === 'active' ? '2026-09-23T12:00:05+00:00' : null,
  ended_at: null,
})

let client: QueryClient
let model: ComputedRef<CallModel>
const mounted: Array<{ unmount(): void }> = []

async function settle() {
  for (let index = 0; index < 5; index += 1) await new Promise((done) => setTimeout(done, 0))
}

async function host() {
  const wrapper = mount(
    defineComponent({
      setup() {
        model = useCalls()
        return () => h('div')
      },
    }),
    { global: { plugins: [[VueQueryPlugin, { queryClient: client }]] } },
  )
  mounted.push(wrapper)
  await settle()
  return wrapper
}

function fakeSession(phase: CallView['phase'], since: number | null = null) {
  return {
    view: shallowRef({ phase } as CallView),
    since,
    callId: 'call-1',
    teardown: vi.fn(),
    observe: vi.fn(),
  }
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mocks.fetchCurrent.mockReset().mockResolvedValue({ call: null } satisfies Current)
  mocks.hold.mockReset().mockReturnValue(mocks.release)
  mocks.release.mockReset()
  mocks.socket.mockReset()
  person.me = { id: 'me', system_admin: false }
  person.scopes = ['calls.read', 'calls.write']
  calls.session.value = null
  calls.current.value = null
  calls.finishedId.value = null
})
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))

describe('useCalls', () => {
  it('reads the current call only when both rights are present', async () => {
    person.scopes = ['calls.read']
    await host()
    expect(mocks.fetchCurrent).not.toHaveBeenCalled()
    expect(model.value.mode).toBe('none')
    person.scopes = ['calls.read', 'calls.write']
    await settle()
    expect(mocks.fetchCurrent).toHaveBeenCalled()
  })

  it('rings here, and holds updates while it rings', async () => {
    mocks.fetchCurrent.mockResolvedValue({ call: call('ringing', 'callee') })
    await host()
    expect(model.value).toMatchObject({ mode: 'incoming', call: { id: 'call-1' } })
    expect(calls.busy.value).toBe(true)
    expect(mocks.hold).toHaveBeenCalledOnce()
    mocks.fetchCurrent.mockResolvedValue({ call: null })
    await client.invalidateQueries({ queryKey: ['calls'] })
    await settle()
    expect(model.value.mode).toBe('none')
    expect(mocks.release).toHaveBeenCalledOnce()
    expect(calls.busy.value).toBe(false)
  })

  it.each([
    ['active', 'callee'],
    ['ringing', 'caller'],
  ] as const)(
    'a call %s as %s without a session here shows elsewhere and opens no socket',
    async (state, role) => {
      mocks.fetchCurrent.mockResolvedValue({ call: call(state, role) })
      await host()
      expect(model.value).toMatchObject({ mode: 'elsewhere', call: { state, role } })
      expect(mocks.socket).not.toHaveBeenCalled()
      expect(mocks.hold).not.toHaveBeenCalled()
    },
  )

  it('shows the session and holds updates until its ended screen', async () => {
    await host()
    const session = fakeSession('connected')
    calls.session.value = session as unknown as CallSession
    await settle()
    expect(model.value.mode).toBe('session')
    expect(mocks.hold).toHaveBeenCalledOnce()
    session.view.value = { phase: 'ended' } as CallView
    await settle()
    expect(mocks.release).toHaveBeenCalledOnce()
  })

  it('feeds the session only reads sent after it learned its call id', async () => {
    await host()
    const later = fakeSession('connecting', Date.now() + 60_000)
    calls.session.value = later as unknown as CallSession
    await client.invalidateQueries({ queryKey: ['calls'] })
    await settle()
    expect(later.observe).not.toHaveBeenCalled()
    const earlier = fakeSession('connecting', Date.now() - 1)
    calls.session.value = earlier as unknown as CallSession
    await client.invalidateQueries({ queryKey: ['calls'] })
    await settle()
    expect(earlier.observe).toHaveBeenCalledWith({ call: null })
  })

  it('tears the session down without an end when the account changes or the page goes', async () => {
    await host()
    const first = fakeSession('connected')
    calls.session.value = first as unknown as CallSession
    person.me = { id: 'someone-else', system_admin: false }
    await settle()
    expect(first.teardown).toHaveBeenCalledOnce()
    expect(calls.session.value).toBeNull()

    const second = fakeSession('connected')
    calls.session.value = second as unknown as CallSession
    window.dispatchEvent(new Event('pagehide'))
    expect(second.teardown).toHaveBeenCalledOnce()
    expect(calls.session.value).toBeNull()
  })

  it('hides a call this tab just ended while the server read is stale', async () => {
    mocks.fetchCurrent.mockResolvedValue({ call: call('active', 'caller') })
    await host()
    calls.finishedId.value = 'call-1'
    expect(model.value.mode).toBe('none')
  })
})
