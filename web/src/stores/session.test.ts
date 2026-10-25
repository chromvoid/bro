import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AccountHooks, AccountServices } from '@/core/account'
import { ApiError } from '@/core/http'
import { useSession } from './session'

const mocks = vi.hoisted(() => ({
  openAccount: vi.fn(),
  live: { start: vi.fn(), stop: vi.fn() },
  purgeAccount: vi.fn(),
  clear: vi.fn(),
  ambientAccount: vi.fn(async () => undefined),
  api: {
    get: vi.fn(),
    post: vi.fn(),
    postPublic: vi.fn(),
    forget: vi.fn(),
    hooks: {} as { unauthorized?: () => void },
  },
}))
vi.mock('@/core/live', () => ({ live: mocks.live }))
vi.mock('@/core/account', () => ({ openAccount: mocks.openAccount }))
vi.mock('@/core/storage', () => ({ purgeAccount: mocks.purgeAccount }))
vi.mock('@/core/client', () => ({ api: mocks.api }))
vi.mock('@/core/query', () => ({ queryClient: { clear: mocks.clear } }))
vi.mock('@/ambient/native', () => ({
  nativeAmbientAudio: () => ({ account: mocks.ambientAccount }),
}))
const identity = (id: string) => ({
  id,
  name: id,
  login: id,
  locale: 'ru',
  timezone: 'UTC',
  system_admin: false,
  revision: 1,
  scopes: [],
})
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
const services = () =>
  ({ close: vi.fn(), stream: { refresh: vi.fn() } }) as unknown as AccountServices

describe('session generation ownership', () => {
  afterEach(() => vi.restoreAllMocks())
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.get.mockReset()
    mocks.api.post.mockResolvedValue({})
    mocks.api.postPublic.mockResolvedValue({})
    mocks.openAccount.mockReset().mockImplementation(async () => services())
    mocks.purgeAccount.mockResolvedValue(undefined)
    setActivePinia(createPinia())
  })

  it('does not install an older identity or let a late 401 end the new account', async () => {
    for (const denied of [false, true]) {
      const old = deferred<ReturnType<typeof identity>>()
      mocks.api.get.mockImplementationOnce(() => old.promise).mockResolvedValueOnce(identity('new'))
      const session = useSession(createPinia())
      const first = session.load()
      const oldSignal = mocks.api.get.mock.calls.at(-1)?.[1] as AbortSignal
      await session.load()
      expect(oldSignal.aborted).toBe(true)
      if (denied) old.reject(new ApiError(401, null, null, null, null))
      else old.resolve(identity('old'))
      await first
      expect(session.me?.id).toBe('new')
      expect(session.state).toBe('ready')
      expect(mocks.live.start).toHaveBeenCalledTimes(denied ? 2 : 1)
      expect(session.expired).toBe(false)
    }
    expect(mocks.openAccount.mock.calls.map((args) => args[0])).toEqual(['new', 'new'])
    expect(mocks.ambientAccount).not.toHaveBeenCalled()
  })

  it('closes services whose IndexedDB open finished after logout', async () => {
    const opening = deferred<AccountServices>(),
      orphan = services()
    mocks.api.get.mockResolvedValueOnce(identity('old'))
    mocks.openAccount.mockImplementationOnce(() => opening.promise)
    const session = useSession()
    const loading = session.load()
    await flushPromises()
    await session.logout()
    opening.resolve(orphan)
    await loading
    expect(orphan.close).toHaveBeenCalledOnce()
    expect(session.account).toBeNull()
    expect(session.me).toBeNull()
    expect(session.state).toBe('signed-out')
    expect(mocks.live.start).not.toHaveBeenCalled()
    expect(mocks.live.stop).toHaveBeenCalled()
  })

  it('invalidates native capture before sending logout and on an authenticated account change', async () => {
    mocks.api.get.mockResolvedValueOnce(identity('old')).mockResolvedValueOnce(identity('new'))
    const session = useSession()
    await session.load()
    await session.load()
    expect(mocks.ambientAccount).toHaveBeenCalledExactlyOnceWith(null)
    mocks.ambientAccount.mockClear()
    mocks.live.stop.mockClear()
    mocks.api.post.mockImplementationOnce(async () => {
      expect(mocks.ambientAccount).toHaveBeenCalledExactlyOnceWith(null, { preserveQueue: false })
      expect(session.me).toBeNull()
      expect(mocks.live.stop).toHaveBeenCalledOnce()
      return {}
    })
    await session.logout()
  })

  it('retains unsent native audio on automatic401 and purges only on explicit logout', async () => {
    mocks.api.get.mockResolvedValueOnce(identity('owner'))
    const session = useSession()
    await session.load()
    mocks.api.hooks.unauthorized!()
    expect(session.nativeSessionLapsed).toBe(true)
    expect(session.expired).toBe(true)
    expect(mocks.ambientAccount).toHaveBeenLastCalledWith(null, { preserveQueue: true })
    await session.logout()
    expect(session.nativeSessionLapsed).toBe(false)
    expect(mocks.ambientAccount).toHaveBeenLastCalledWith(null, { preserveQueue: false })
  })

  it('preserves a restored native queue on the initial401 without showing the expired-session banner', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('{}', { status: 401 }))
    mocks.api.get.mockRejectedValueOnce(new ApiError(401, null, null, null, null))
    const session = useSession()
    await session.load()
    expect(session.state).toBe('signed-out')
    expect(session.nativeSessionLapsed).toBe(true)
    expect(session.expired).toBe(false)
    expect(mocks.ambientAccount).toHaveBeenLastCalledWith(null, { preserveQueue: true })
  })

  it('invalidates capture when a new login device belongs to the same user', async () => {
    mocks.api.get
      .mockResolvedValueOnce({ ...identity('owner'), device_id: 'previous' })
      .mockResolvedValueOnce({ ...identity('owner'), device_id: 'new' })
    const session = useSession()
    await session.load()
    const previous = session.account!
    await session.load()
    expect(mocks.ambientAccount).toHaveBeenCalledExactlyOnceWith(null)
    expect(previous.close).toHaveBeenCalledOnce()
    expect(session.me?.device_id).toBe('new')
  })

  it('ignores stream hooks from the previous account and a logout response after sign-in', async () => {
    const logout = deferred<unknown>()
    mocks.api.get.mockResolvedValueOnce(identity('old')).mockResolvedValueOnce(identity('new'))
    mocks.api.post.mockImplementationOnce(() => logout.promise)
    const session = useSession()
    await session.load()
    const previous = session.account!
    const hooks = mocks.openAccount.mock.calls[0]![3] as AccountHooks
    const signingOut = session.logout()
    expect(previous.close).toHaveBeenCalledOnce()
    expect(session.account).toBeNull()
    await session.signIn('new', 'synthetic-password')
    hooks.denied(401)
    hooks.accessChanged()
    logout.resolve({})
    await signingOut
    expect(session.me?.id).toBe('new')
    expect(session.state).toBe('ready')
    expect(mocks.api.get).toHaveBeenCalledTimes(2)
    expect(mocks.purgeAccount).toHaveBeenCalledWith('old')
  })

  it('does not reload identity after an obsolete sign-in completes', async () => {
    const login = deferred<unknown>()
    mocks.api.postPublic.mockImplementationOnce(() => login.promise)
    const session = useSession()
    const signingIn = session.signIn('old', 'synthetic-password')
    await session.logout()
    login.resolve({})
    await signingIn
    expect(mocks.api.get).not.toHaveBeenCalled()
    expect(session.state).toBe('signed-out')
  })

  it('resumes the same account stream after its permissions are successfully reread', async () => {
    mocks.api.get
      .mockResolvedValue({ ...identity('same'), revision: 2 })
      .mockResolvedValueOnce(identity('same'))
    const session = useSession()
    await session.load()
    const original = session.account!
    const hooks = mocks.openAccount.mock.calls[0]![3] as AccountHooks
    hooks.denied(403)
    await flushPromises()
    expect(session.account).toBe(original)
    expect(original.stream.refresh).toHaveBeenCalledOnce()
    hooks.denied(403)
    await flushPromises()
    expect(original.stream.refresh).toHaveBeenCalledOnce()
    expect(session.state).toBe('ready')
  })

  it('names the writes a sign-out would discard and nothing without an account', async () => {
    const session = useSession()
    expect(await session.unsettledWrites()).toEqual([])
    const pending = vi.fn().mockResolvedValue([
      { key: 'a', state: 'uncertain' },
      { key: 'b', state: 'resolved' },
      { key: 'c', state: 'prepared' },
    ])
    mocks.openAccount.mockImplementationOnce(async () => ({
      ...services(),
      journal: { pending },
    }))
    mocks.api.get.mockResolvedValueOnce(identity('me'))
    await session.load()
    expect((await session.unsettledWrites()).map((entry) => entry.key)).toEqual(['a', 'c'])
  })

  it('preserves the separate ring grant on cookie lapse and clears it on explicit logout', async () => {
    mocks.api.get.mockResolvedValue(identity('owner'))
    const session = useSession()
    await session.load()
    mocks.api.hooks.unauthorized?.()
    expect(session.expired).toBe(true)
    await session.load()
    await session.logout()
  })
})
