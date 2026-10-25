import { nextTick, shallowRef } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ShellCapabilities } from './shell'
import { applyTheme, syncShellTheme } from './theme'

function mediaPreference(dark = false) {
  let listener: ((event: MediaQueryListEvent) => void) | null = null
  const removeEventListener = vi.fn()
  const media = {
    matches: dark,
    addEventListener: (_: string, value: (event: MediaQueryListEvent) => void) =>
      (listener = value),
    removeEventListener,
  } as unknown as MediaQueryList
  return {
    media,
    removeEventListener,
    change: (matches: boolean) => listener?.({ matches } as MediaQueryListEvent),
  }
}

const ios: ShellCapabilities = {
  shell_api: 1,
  platform: 'ios',
  system_bars: true,
}
const cleanups: Array<() => void> = []

describe('native system bar theme', () => {
  afterEach(() => {
    for (const stop of cleanups.splice(0)) stop()
    applyTheme('system')
  })

  it('waits for capabilities and sends the saved page theme rather than the system theme', async () => {
    const capabilities = shallowRef<ShellCapabilities | null>(null)
    const request = vi.fn(async () => ({}))
    const { media } = mediaPreference(true)
    applyTheme('light')
    cleanups.push(syncShellTheme({ capabilities, request }, media))
    expect(request).not.toHaveBeenCalled()
    capabilities.value = ios
    await nextTick()
    expect(request).toHaveBeenLastCalledWith('theme.set', { dark: false, preference: 'light' })
    applyTheme('dark')
    await nextTick()
    expect(request).toHaveBeenLastCalledWith('theme.set', { dark: true, preference: 'dark' })
    applyTheme('system')
    await nextTick()
    expect(request).toHaveBeenLastCalledWith('theme.set', { dark: true, preference: 'system' })
  })

  it('follows system changes only while the page uses the system preference', async () => {
    const capabilities = shallowRef<ShellCapabilities | null>(ios)
    const request = vi.fn(async () => ({}))
    const preference = mediaPreference(false)
    cleanups.push(syncShellTheme({ capabilities, request }, preference.media))
    expect(request).toHaveBeenLastCalledWith('theme.set', { dark: false, preference: 'system' })
    preference.change(true)
    await nextTick()
    expect(request).toHaveBeenLastCalledWith('theme.set', { dark: true, preference: 'system' })
    applyTheme('light')
    await nextTick()
    request.mockClear()
    preference.change(false)
    await nextTick()
    expect(request).not.toHaveBeenCalled()
  })

  it('keeps older shells and browsers inert and tolerates a refused request', async () => {
    const capabilities = shallowRef<ShellCapabilities | null>(null)
    const request = vi.fn(async () => {
      throw new Error('unknown_type')
    })
    const { media } = mediaPreference()
    cleanups.push(syncShellTheme({ capabilities, request }, media))
    capabilities.value = { shell_api: 1, platform: 'ios' }
    applyTheme('dark')
    await nextTick()
    expect(request).not.toHaveBeenCalled()
    capabilities.value = ios
    await nextTick()
    expect(request).toHaveBeenCalledExactlyOnceWith('theme.set', { dark: true, preference: 'dark' })
  })

  it('releases the media listener and stops sending after cleanup', async () => {
    const capabilities = shallowRef<ShellCapabilities | null>(ios)
    const request = vi.fn(async () => ({}))
    const preference = mediaPreference()
    const stop = syncShellTheme({ capabilities, request }, preference.media)
    stop()
    expect(preference.removeEventListener).toHaveBeenCalledWith('change', expect.any(Function))
    request.mockClear()
    applyTheme('dark')
    preference.change(true)
    await nextTick()
    expect(request).not.toHaveBeenCalled()
  })
})
