import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, shallowReactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Api, type WriteOptions } from '@/core/http'
import { useDdxLogin } from './useDdxLogin'

const mocks = vi.hoisted(() => ({ useSession: vi.fn(), get: vi.fn(), post: vi.fn() }))
vi.mock('@/stores/session', () => ({ useSession: mocks.useSession }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get, post: mocks.post } }))

const waiting = {
  id: 'synthetic-login',
  state: 'waiting_code',
  phone_masked: '+7••••••0001',
  can_resend: false,
  resend_at: '2026-10-02T10:01:00Z',
}
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('DDX enrollment ownership and delivery', () => {
  let wrapper: VueWrapper
  let auth: ReturnType<typeof useDdxLogin>
  let session: ReturnType<typeof accountSession>
  let network: ReturnType<typeof vi.fn<typeof fetch>>
  let connected: ReturnType<typeof vi.fn<() => void>>
  function accountSession(id = 'user-a') {
    return shallowReactive({
      state: 'ready',
      me: { id },
      account: { store: { userId: id }, journal: { submit: vi.fn() } },
      can: (): boolean => true,
    })
  }
  async function setup() {
    session = accountSession()
    mocks.useSession.mockReturnValue(session)
    const client = new Api(network)
    mocks.get.mockImplementation(client.get.bind(client))
    mocks.post.mockImplementation(client.post.bind(client))
    connected = vi.fn()
    wrapper = mount(
      defineComponent({
        setup() {
          auth = useDdxLogin(connected)
          return () => h('div')
        },
      }),
    )
    await flushPromises()
  }
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    network = vi.fn<typeof fetch>(async (path) =>
      String(path) === '/v1/auth/csrf'
        ? json({ token: 'synthetic-csrf' })
        : json({ available: true, active_login: null }),
    )
  })
  afterEach(() => {
    wrapper?.unmount()
    vi.useRealTimers()
  })

  it('sends a single push request under repeated submit and never uses the journal', async () => {
    const pending = deferred<Response>()
    network.mockImplementation(async (path) =>
      String(path) === '/v1/auth/csrf'
        ? json({ token: 'synthetic-csrf' })
        : String(path) === '/v1/personal/ddx/logins'
          ? pending.promise
          : json({ available: true, active_login: null }),
    )
    await setup()
    auth.phone.value = '8 (900) 000-00-01'
    const first = auth.start('connect')
    await auth.start('connect')
    await flushPromises()
    expect(
      network.mock.calls.filter(([path]) => String(path) === '/v1/personal/ddx/logins'),
    ).toHaveLength(1)
    const request = network.mock.calls.find(
      ([path]) => String(path) === '/v1/personal/ddx/logins',
    )![1]!
    expect(JSON.parse(String(request.body))).toEqual({ phone: '+79000000001', mode: 'connect' })
    expect(request.headers).toMatchObject({
      'X-Expected-User': 'user-a',
      'Idempotency-Key': expect.any(String),
    })
    pending.resolve(json({ login: waiting }))
    await first
    expect(auth.login.value?.state).toBe('waiting_code')
    expect(session.account.journal.submit).not.toHaveBeenCalled()
  })

  it('recovers a lost start response by reading status without sending another push', async () => {
    let requestKey: string | null = null
    network.mockImplementation(async (path, options) => {
      if (String(path) === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
      if (String(path) === '/v1/personal/ddx/logins') {
        requestKey = new Headers(options?.headers).get('Idempotency-Key')
        throw new Error('connection lost')
      }
      return json({
        available: true,
        active_login: requestKey ? { ...waiting, request_key: requestKey } : null,
      })
    })
    await setup()
    auth.phone.value = '+79000000001'
    await auth.start('connect')
    expect(auth.uncertain.value).toBe(true)
    await auth.refresh()
    expect(auth.uncertain.value).toBe(false)
    expect(auth.login.value?.id).toBe(waiting.id)
    expect(
      network.mock.calls.filter(([path]) => String(path) === '/v1/personal/ddx/logins'),
    ).toHaveLength(1)
  })

  it('does not mistake a previous ready attempt for a lost new request', async () => {
    const previous = {
      ...waiting,
      id: 'previous-login',
      request_key: 'previous-key',
      state: 'ready',
    }
    let attempts = 0
    network.mockImplementation(async (path) => {
      if (String(path) === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
      if (String(path) === '/v1/personal/ddx/logins') {
        if (++attempts === 1) throw new Error('not delivered')
        return json({ login: waiting })
      }
      return json({ available: true, active_login: previous })
    })
    await setup()
    connected.mockClear()
    auth.clear()
    auth.phone.value = '+79000000001'
    await auth.start('replace', 'source-a', 4)
    await auth.refresh()
    expect(auth.login.value).toBeNull()
    expect(auth.uncertain.value).toBe(true)
    expect(auth.retryStart.value).toBe(true)
    expect(connected).not.toHaveBeenCalled()
    await auth.repeatStart()
    const options = mocks.post.mock.calls.map((call) => call[2] as WriteOptions)
    expect(options[1]?.key).toBe(options[0]?.key)
    expect(mocks.post.mock.calls[1]?.[1]).toEqual({
      phone: '+79000000001',
      mode: 'replace',
      source_id: 'source-a',
      expected_revision: 4,
    })
    expect(auth.login.value?.id).toBe(waiting.id)
  })

  it('ignores a status response issued before the new start request', async () => {
    await setup()
    const stale = deferred<Response>()
    network.mockImplementation(async (path) => {
      if (String(path) === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
      if (String(path) === '/v1/personal/ddx/logins') return json({ login: waiting })
      return stale.promise
    })
    const refresh = auth.refresh()
    auth.phone.value = '+79000000001'
    await auth.start('connect')
    stale.resolve(
      json({ available: true, active_login: { ...waiting, id: 'previous', state: 'ready' } }),
    )
    await refresh
    expect(auth.login.value?.id).toBe(waiting.id)
    expect(auth.login.value?.state).toBe('waiting_code')
    expect(connected).not.toHaveBeenCalled()
  })

  it('clears OTP immediately, preserves leading zeroes and does not replay code on polling', async () => {
    network.mockImplementation(async (path) => {
      if (String(path) === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
      if (String(path).endsWith('/code')) return json({ login: { ...waiting, state: 'verifying' } })
      return json({ available: true, active_login: waiting })
    })
    await setup()
    auth.code.value = '001234'
    const sent = auth.submitCode()
    expect(auth.code.value).toBe('')
    await sent
    await auth.refresh()
    const codes = network.mock.calls.filter(([path]) => String(path).endsWith('/code'))
    expect(codes).toHaveLength(1)
    expect(JSON.parse(String(codes[0]![1]!.body))).toEqual({ code: '001234' })
    expect(session.account.journal.submit).not.toHaveBeenCalled()
  })

  it('invalidates secrets and prevents a pending request from crossing an account switch', async () => {
    const csrf = deferred<Response>()
    network.mockImplementation(async (path) =>
      String(path) === '/v1/auth/csrf'
        ? csrf.promise
        : json({ available: true, active_login: null }),
    )
    await setup()
    auth.phone.value = '+79000000001'
    auth.code.value = '123456'
    const start = auth.start('connect')
    await flushPromises()
    session.account = accountSession('user-b').account
    session.me = { id: 'user-b' }
    csrf.resolve(json({ token: 'synthetic-old-csrf' }))
    await start
    expect(auth.phone.value).toBe('')
    expect(auth.code.value).toBe('')
    expect(auth.login.value).toBeNull()
    expect(connected).not.toHaveBeenCalled()
    expect(network.mock.calls.some(([path]) => String(path) === '/v1/personal/ddx/logins')).toBe(
      false,
    )
  })

  it('clears enrollment data on write access loss and refreshes availability when access returns', async () => {
    await setup()
    auth.phone.value = '+79000000001'
    auth.code.value = '001234'
    session.can = () => false
    await flushPromises()
    expect(auth.phone.value).toBe('')
    expect(auth.code.value).toBe('')
    expect(auth.available.value).toBeNull()
    expect(auth.canDisconnect.value).toBe(false)
    mocks.get.mockClear()
    session.can = () => true
    await flushPromises()
    expect(auth.available.value).toBe(true)
    expect(mocks.get).toHaveBeenCalledWith('/v1/personal/ddx/status', expect.any(AbortSignal))
  })

  it('retains the provider cooldown when closing a terminal attempt and beginning again', async () => {
    vi.setSystemTime(new Date('2026-10-02T10:00:00Z'))
    network.mockImplementation(async () =>
      json({
        available: true,
        active_login: { ...waiting, state: 'failed', error: 'rate_limited' },
      }),
    )
    await setup()
    auth.clear()
    auth.phone.value = '+79000000001'
    await auth.start('connect')
    expect(auth.error.value).toBe('resend_cooldown')
    expect(mocks.post).not.toHaveBeenCalled()
    expect(auth.retryAt.value).toBe(waiting.resend_at)
  })

  it('uses the same logical key for an explicit retry after an empty recovery read', async () => {
    let attempts = 0
    network.mockImplementation(async (path) => {
      if (String(path) === '/v1/auth/csrf') return json({ token: 'synthetic-csrf' })
      if (String(path) === '/v1/personal/ddx/logins') {
        if (++attempts === 1) throw new Error('not delivered')
        return json({ login: waiting })
      }
      return json({ available: true, active_login: null })
    })
    await setup()
    auth.phone.value = '+79000000001'
    await auth.start('connect')
    await auth.refresh()
    expect(auth.retryStart.value).toBe(true)
    await auth.repeatStart()
    const options = mocks.post.mock.calls.map((call) => call[2] as WriteOptions)
    expect(options[0]?.key).toBe(options[1]?.key)
    expect(auth.login.value?.id).toBe(waiting.id)
  })
})
