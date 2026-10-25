import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ShellError } from '@/core/shell'
import { NativeAmbientAudio, ambientErrorKey } from './native'

const identity = { id: 'owner', device_id: 'device', session_expires_at: '2030-01-01T00:00:00Z' }
const idle = {
  recording: false,
  queued_batches: 0,
  queued_bytes: 0,
  last_uploaded_at: null,
  error: null,
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
function fixture(supported = true) {
  const request = vi.fn(async (type: string): Promise<unknown> =>
    type === 'ambient.state' ? idle : {},
  )
  const available = vi.fn(async () => supported)
  const service = new NativeAmbientAudio({ available, request })
  return { service, request, available }
}

describe('native ambient audio', () => {
  it('offers nothing to browsers or older shells and never starts a microphone on account binding', async () => {
    const browser = fixture(false)
    await browser.service.account(identity)
    await browser.service.refreshState()
    expect(browser.service.supported.value).toBe(false)
    expect(browser.request).not.toHaveBeenCalled()

    const { service, request } = fixture()
    await service.account(identity)
    expect(request.mock.calls.map(([type]) => type)).toEqual(['ambient.account', 'ambient.state'])
    expect(request).toHaveBeenCalledWith('ambient.account', {
      user_id: 'owner',
      device_id: 'device',
      expires_at: identity.session_expires_at,
    })
    await service.open()
    expect(request.mock.calls.map(([type]) => type)).toEqual([
      'ambient.account',
      'ambient.state',
      'ambient.open',
      'ambient.state',
    ])
  })

  it('clears synchronously on logout and ignores a state response from the ended account', async () => {
    const { service, request } = fixture()
    await service.account(identity)
    const pending = deferred<unknown>()
    request.mockImplementationOnce(() => pending.promise)
    const refreshing = service.refreshState()
    await flushPromises()
    const clearing = service.account(null)
    expect(service.state.value).toBeNull()
    expect(request).toHaveBeenLastCalledWith('ambient.account', {
      user_id: null,
      preserve_queue: false,
    })
    pending.resolve({ ...idle, recording: true, queued_batches: 5 })
    await Promise.all([refreshing, clearing])
    expect(service.state.value).toBeNull()
  })

  it('does not send a delayed account binding after sign-out during capability detection', async () => {
    const supported = deferred<boolean>()
    const request = vi.fn(async () => ({}))
    const service = new NativeAmbientAudio({ available: () => supported.promise, request })
    const binding = service.account(identity)
    const clearing = service.account(null)
    supported.resolve(true)
    await Promise.all([binding, clearing])
    expect(request.mock.calls).toEqual([
      ['ambient.account', { user_id: null, preserve_queue: false }],
    ])
    expect(service.state.value).toBeNull()
  })

  it('purges the old local queue before binding a different user or login device', async () => {
    for (const next of [
      { ...identity, id: 'other' },
      { ...identity, device_id: 'another-device' },
    ]) {
      const { service, request } = fixture()
      await service.account(identity)
      request.mockClear()
      await service.account(next)
      expect(request.mock.calls.map(([type]) => type)).toEqual([
        'ambient.account',
        'ambient.account',
        'ambient.state',
      ])
      expect(request.mock.calls[0]).toEqual([
        'ambient.account',
        { user_id: null, preserve_queue: false },
      ])
    }
  })

  it('reads accountless events as hints and never installs their unverified payload', async () => {
    const { service, request } = fixture()
    await service.account(identity)
    request.mockClear()
    await service.event({ event: 'ambient.state', payload: { ...idle, recording: true } })
    expect(request).toHaveBeenCalledExactlyOnceWith('ambient.state')
    expect(service.state.value?.recording).toBe(false)
    await service.account(null)
    request.mockClear()
    await service.event({ event: 'ambient.state', payload: { ...idle, recording: true } })
    expect(request).not.toHaveBeenCalled()
  })

  it('revokes authority while preserving finalized audio after automatic authentication loss', async () => {
    const { service, request } = fixture()
    await service.account(identity)
    const expired = service.account(null, { preserveQueue: true })
    expect(request).toHaveBeenLastCalledWith('ambient.account', {
      user_id: null,
      preserve_queue: true,
    })
    expect(service.state.value).toBeNull()
    await expired
    await expect(service.open()).rejects.toMatchObject({ name: 'AbortError' })
    expect(request.mock.calls.map(([type]) => type)).not.toContain('ambient.clear')
    await service.account(null)
    expect(request).toHaveBeenLastCalledWith('ambient.account', {
      user_id: null,
      preserve_queue: false,
    })
  })

  it('keeps a stop receipt when an older recording state finishes afterwards', async () => {
    const { service, request } = fixture()
    await service.account(identity)
    const previous = deferred<unknown>()
    request.mockImplementationOnce(() => previous.promise)
    const oldRefresh = service.refreshState()
    await flushPromises()
    await service.stop()
    previous.resolve({ ...idle, recording: true })
    await oldRefresh
    expect(service.state.value?.recording).toBe(false)
    expect(request).toHaveBeenCalledWith('ambient.stop', {}, undefined)
  })

  it('ignores an older failed state refresh after a successful stop', async () => {
    const { service, request } = fixture()
    await service.account(identity)
    const previous = deferred<unknown>()
    request.mockImplementationOnce(() => previous.promise)
    const oldRefresh = service.refreshState()
    await flushPromises()
    await service.stop()
    previous.reject(new ShellError('timeout'))
    await oldRefresh
    expect(service.state.value?.recording).toBe(false)
    expect(service.failure.value).toBeNull()
  })

  it('does not read or install control results after the identity changes', async () => {
    const { service, request } = fixture()
    await service.account(identity)
    const opening = deferred<unknown>()
    request.mockImplementationOnce(() => opening.promise)
    const opened = service.open()
    await flushPromises()
    await service.account(null)
    request.mockClear()
    opening.resolve({})
    await opened
    expect(request).not.toHaveBeenCalled()
    expect(service.state.value).toBeNull()
    expect(service.busy.value).toBe(false)
  })

  it('reports bridge failures through safe error keys, keeping the previous recording status', async () => {
    const { service, request } = fixture()
    await service.account(identity)
    service.state.value = { ...idle, recording: true }
    request.mockRejectedValueOnce(new ShellError('synthetic-private-error'))
    await service.stop()
    expect(service.state.value.recording).toBe(true)
    expect(ambientErrorKey(service.failure.value)).toBe('ambientAudio.errors.unknown')
    expect(ambientErrorKey('permission_denied')).toBe('ambientAudio.errors.permission')
    expect(ambientErrorKey('not_authenticated')).toBe('ambientAudio.errors.session')
    expect(ambientErrorKey('queue_full')).toBe('ambientAudio.errors.full')
    expect(ambientErrorKey('interrupted')).toBe('ambientAudio.errors.interrupted')
  })
})
