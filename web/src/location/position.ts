import { useShell } from '@/core/shell'
import { z } from '@/core/zod'
import type { Fix } from './api'

/**
 * Where this device's position comes from: the native shell's bridge (one system prompt, no second
 * WebView prompt) or the browser's Geolocation API. Only request() may show a permission prompt.
 */
export type Permission = 'granted' | 'prompt' | 'denied' | 'unavailable'

export interface PositionSource {
  kind: 'shell' | 'browser' | 'none'
  /** The permission now, without asking. */
  status(): Promise<Permission>
  /** Ask the person (the system prompt) and return the answer. */
  request(): Promise<Permission>
  /** A position when permission is already granted; null otherwise or when none comes. */
  current(): Promise<Fix | null>
}

// Ordinary accuracy (network and Wi-Fi), a position up to ten minutes old is good enough.
const OPTIONS: PositionOptions = { enableHighAccuracy: false, maximumAge: 600_000, timeout: 15_000 }

const Status = z.object({ permission: z.enum(['granted', 'prompt', 'denied']) })
const ShellFix = z.object({
  latitude: z.number(),
  longitude: z.number(),
  accuracy_m: z.number().positive(),
  captured_at: z.string(),
})

function fixOf(position: GeolocationPosition): Fix {
  return {
    latitude: position.coords.latitude,
    longitude: position.coords.longitude,
    accuracy_m: Math.max(1, Math.ceil(position.coords.accuracy)),
    captured_at: new Date(position.timestamp).toISOString(),
  }
}

function locate(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) =>
    navigator.geolocation.getCurrentPosition(resolve, reject, OPTIONS),
  )
}

function browser(): PositionSource {
  const supported = typeof navigator !== 'undefined' && 'geolocation' in navigator
  async function status(): Promise<Permission> {
    if (!supported) return 'unavailable'
    try {
      const state = (await navigator.permissions.query({ name: 'geolocation' })).state
      return state === 'granted' || state === 'denied' ? state : 'prompt'
    } catch {
      return 'prompt'
    }
  }
  return {
    kind: 'browser',
    status,
    async request() {
      if (!supported) return 'unavailable'
      try {
        await locate()
        return 'granted'
      } catch (error) {
        return (error as GeolocationPositionError).code === 1 ? 'denied' : status()
      }
    },
    async current() {
      if ((await status()) !== 'granted') return null
      try {
        return fixOf(await locate())
      } catch {
        return null
      }
    },
  }
}

function shell(request: ReturnType<typeof useShell>['request']): PositionSource {
  async function status(): Promise<Permission> {
    try {
      return Status.parse(await request('location.status')).permission
    } catch {
      return 'unavailable'
    }
  }
  return {
    kind: 'shell',
    status,
    async request() {
      try {
        // The person answers a system dialog; give them time.
        return Status.parse(await request('location.request', {}, 60_000)).permission
      } catch {
        return status()
      }
    },
    async current() {
      try {
        const fix = ShellFix.parse(await request('location.current', {}, 15_000))
        return { ...fix, accuracy_m: Math.ceil(fix.accuracy_m) }
      } catch {
        return null
      }
    },
  }
}

const NONE: PositionSource = {
  kind: 'none',
  status: async () => 'unavailable',
  request: async () => 'unavailable',
  current: async () => null,
}

/** The source for this page: a shell without the location capability is an older app to update. */
export async function positionSource(): Promise<PositionSource> {
  const native = useShell()
  const capabilities = await native.ready()
  if (!capabilities) return browser()
  return capabilities.location ? shell(native.request) : NONE
}
