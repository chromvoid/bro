import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, NetworkError, type Api } from '@/core/http'
import { NativeNotifications } from './native'

const identity = { id: 'owner', device_id: 'device', session_expires_at: '2030-01-01T00:00:00Z' }
const snapshot = {
  snapshot_id: 'snapshot',
  server_now: '2026-10-05T00:00:00Z',
  expires_at: '2030-01-01T00:00:00Z',
  items: [
    {
      key: 'timer:one',
      revision: 1,
      due_at: '2026-10-05T01:00:00Z',
      title: 'Таймер',
      body: '',
      link: '/timers',
    },
  ],
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function fixture(
  initiallyEnabled = false,
  wait: (milliseconds: number) => Promise<void> = async () => undefined,
) {
  let enabled = initiallyEnabled
  const request = vi.fn(async (type: string): Promise<unknown> => {
    if (type === 'notifications.state')
      return { permission: 'granted', enabled, push_available: false }
    if (type === 'notifications.permission') {
      enabled = true
      return { granted: true }
    }
    if (type === 'notifications.schedule') return { installed: ['timer:one'] }
    if (type === 'notifications.unregister') enabled = false
    return {}
  })
  const get = vi.fn(async (path: string) =>
    path === '/v1/client/config' ? { instance_id: 'home', name: 'Home' } : snapshot,
  )
  const post = vi.fn<
    (path: string, body: unknown, options?: unknown) => Promise<{ status: number; data: object }>
  >(async () => ({ status: 200, data: {} }))
  const service = new NativeNotifications(
    { available: async () => true, request },
    {
      get,
      post,
    } as unknown as Pick<Api, 'get' | 'post'>,
    wait,
  )
  return { service, request, get, post }
}

describe('native notifications', () => {
  beforeEach(() => localStorage.clear())
  it('does not register or schedule until the user enables this device', async () => {
    const { service, request, get, post } = fixture()
    await service.account(identity)
    expect(service.state.value).toBe('off')
    expect(get).not.toHaveBeenCalledWith('/v1/notifications/schedule')
    expect(post).not.toHaveBeenCalled()
    await service.enable()
    expect(service.state.value).toBe('on')
    expect(request).not.toHaveBeenCalledWith(
      'notifications.register',
      expect.anything(),
      expect.anything(),
    )
    expect(post).toHaveBeenCalledWith(
      '/v1/notifications/schedule/ack',
      { snapshot_id: 'snapshot', installed: ['timer:one'] },
      expect.objectContaining({ expectedUser: 'owner' }),
    )
  })

  it('ignores permission results from the account that has signed out', async () => {
    const { service, request, post } = fixture()
    await service.account(identity)
    const permission = deferred<{ granted: boolean }>()
    request.mockImplementationOnce(() => permission.promise)
    const enable = service.enable()
    await Promise.resolve()
    await service.account(null)
    permission.resolve({ granted: true })
    await expect(enable).rejects.toMatchObject({ name: 'AbortError' })
    expect(post).not.toHaveBeenCalled()
    expect(request).toHaveBeenCalledWith('notifications.session', { clear: true }, 60_000)
  })

  it('does not acknowledge an old schedule after the account changes', async () => {
    const { service, get, post } = fixture()
    await service.account(identity)
    await service.enable()
    post.mockClear()
    const schedule = deferred<typeof snapshot>()
    get.mockImplementationOnce(() => schedule.promise)
    const syncing = service.sync()
    await service.account(null)
    schedule.resolve(snapshot)
    await expect(syncing).rejects.toMatchObject({ name: 'AbortError' })
    expect(post).not.toHaveBeenCalled()
  })

  it('schedules the new account after discarding a late old-account response', async () => {
    const { service, get, post, request } = fixture(true)
    await service.account(identity)
    post.mockClear()
    request.mockClear()
    const schedule = deferred<typeof snapshot>()
    get.mockImplementationOnce(() => schedule.promise)
    const syncing = service.sync()
    const next = service.account({ ...identity, id: 'other', device_id: 'other-device' })
    for (let count = 0; count < 20; count++) await Promise.resolve()
    schedule.resolve(snapshot)
    await Promise.all([syncing, next])
    expect(post).toHaveBeenCalledOnce()
    expect(post).toHaveBeenCalledWith(
      '/v1/notifications/schedule/ack',
      { snapshot_id: 'snapshot', installed: ['timer:one'] },
      expect.objectContaining({ expectedUser: 'other' }),
    )
    expect(request.mock.calls.filter((call) => call[0] === 'notifications.schedule')).toHaveLength(
      1,
    )
  })

  it('never acknowledges unknown installed keys and cancels locally before server removal', async () => {
    const { service, request, post } = fixture()
    await service.account(identity)
    await service.enable()
    post.mockClear()
    request.mockImplementationOnce(async () => ({ installed: ['not-in-snapshot'] }))
    await expect(service.sync()).rejects.toThrow('invalid_schedule_receipt')
    expect(post).not.toHaveBeenCalled()
    post.mockRejectedValueOnce(new Error('offline'))
    await expect(service.disable()).rejects.toThrow('offline')
    expect(service.state.value).toBe('off')
    expect(request).toHaveBeenCalledWith('notifications.unregister')
  })

  it('only opens safe links for the currently bound installation, user and device', async () => {
    const { service } = fixture()
    await service.account(identity)
    const payload = { instance_id: 'home', user_id: 'owner', device_id: 'device', link: '/timers' }
    expect(await service.event({ event: 'notifications.opened', payload })).toBe('/timers')
    expect(
      await service.event({
        event: 'notifications.opened',
        payload: { ...payload, instance_id: 'other' },
      }),
    ).toBeNull()
    expect(
      await service.event({
        event: 'notifications.opened',
        payload: { ...payload, link: '//example.com' },
      }),
    ).toBeNull()
  })

  it.each(['before-commit', 'after-commit'])(
    'retries the exact installed receipt after a dropped %s ACK reply',
    async (failure) => {
      const { service, post, request } = fixture()
      await service.account(identity)
      await service.enable()
      post.mockClear()
      request.mockClear()
      const accepted = new Set<string>()
      let attempts = 0
      let commits = 0
      post.mockImplementation(async (_path, body) => {
        const key = JSON.stringify(body)
        if (++attempts === 1 && failure === 'before-commit') throw new NetworkError(false)
        if (!accepted.has(key)) {
          accepted.add(key)
          commits++
        }
        if (attempts === 1) throw new NetworkError(false)
        return { status: 200, data: {} }
      })
      await service.sync()
      expect(post).toHaveBeenCalledTimes(2)
      expect(commits).toBe(1)
      expect(post.mock.calls.map((call) => call[1])).toEqual([
        { snapshot_id: 'snapshot', installed: ['timer:one'] },
        { snapshot_id: 'snapshot', installed: ['timer:one'] },
      ])
      expect(
        request.mock.calls.filter((call) => call[0] === 'notifications.schedule'),
      ).toHaveLength(1)
      expect(localStorage.getItem('assistant.native.schedule-ack')).toBeNull()
    },
  )

  it('recovers durable pending ACKs before a fresh snapshot after WebKit restarts', async () => {
    const first = fixture()
    await first.service.account(identity)
    await first.service.enable()
    first.post.mockRejectedValue(new NetworkError(false))
    await expect(first.service.sync()).rejects.toBeInstanceOf(NetworkError)
    const saved = localStorage.getItem('assistant.native.schedule-ack')!
    expect(first.service.schedulePending.value).toBe(true)
    await first.service.account(null)
    localStorage.setItem('assistant.native.schedule-ack', saved)
    const resumed = fixture(true)
    await resumed.service.account(identity)
    const snapshotRead = resumed.get.mock.calls.findIndex(
      (call) => call[0] === '/v1/notifications/schedule',
    )
    expect(resumed.post.mock.invocationCallOrder[0]).toBeLessThan(
      resumed.get.mock.invocationCallOrder[snapshotRead]!,
    )
    expect(resumed.service.schedulePending.value).toBe(false)
    expect(localStorage.getItem('assistant.native.schedule-ack')).toBeNull()
  })

  it('does not restore an installation proof after the native session expiry changes', async () => {
    localStorage.setItem(
      'assistant.native.schedule-ack',
      JSON.stringify({
        instance_id: 'home',
        user_id: identity.id,
        device_id: identity.device_id,
        expires_at: '2031-01-01T00:00:00Z',
        snapshot_id: 'old-snapshot',
        installed: ['timer:one'],
      }),
    )
    const { service, post } = fixture(true)
    await service.account(identity)
    expect(post).toHaveBeenCalledOnce()
    expect(post.mock.calls[0]![1]).toEqual({
      snapshot_id: 'snapshot',
      installed: ['timer:one'],
    })
  })

  it('cancels old-account retries and refreshes a conflicting receipt once', async () => {
    const delay = deferred<void>()
    const { service, post } = fixture(false, () => delay.promise)
    await service.account(identity)
    await service.enable()
    post.mockClear()
    post.mockRejectedValueOnce(new NetworkError(false))
    const syncing = service.sync()
    for (let count = 0; count < 10; count++) await Promise.resolve()
    await service.account(null)
    delay.resolve()
    await expect(syncing).rejects.toMatchObject({ name: 'AbortError' })
    expect(post).toHaveBeenCalledOnce()
    expect(localStorage.getItem('assistant.native.schedule-ack')).toBeNull()
    const fresh = fixture()
    await fresh.service.account(identity)
    await fresh.service.enable()
    fresh.post.mockClear()
    fresh.post.mockRejectedValueOnce(new ApiError(409, null, null, null, null))
    await fresh.service.sync()
    expect(fresh.post).toHaveBeenCalledTimes(2)
  })

  it('consumes a cold-start notification tap only after the session binding is installed', async () => {
    const { service, request } = fixture()
    request.mockImplementationOnce(async () => ({
      pending_opened: {
        instance_id: 'home',
        user_id: 'owner',
        device_id: 'device',
        link: '/timers',
      },
    }))
    await service.account(identity)
    expect(service.opened.value?.link).toBe('/timers')
    await service.account(null)
    expect(service.opened.value).toBeNull()
  })
})
