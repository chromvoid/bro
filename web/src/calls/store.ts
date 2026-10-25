/**
 * The page's one call. It lives outside every screen, so moving around the app never drops it,
 * and only the tab holding it opens the signalling socket; other tabs and devices of the account
 * see the call «elsewhere» (docs/calls-spec.md).
 */
import { computed, shallowRef } from 'vue'
import { toast } from 'vue-sonner'
import { useDeveloperMode } from '@/core/developer'
import { errorText } from '@/core/errors'
import { queryClient } from '@/core/query'
import { useShell } from '@/core/shell'
import { i18n } from '@/i18n'
import { speechPlayback } from '@/speech/playback'
import { useSession } from '@/stores/session'
import {
  acceptCall,
  endCall,
  errorKey,
  fetchCurrent,
  fetchIce,
  startCall,
  type Call,
  type Current,
  type EndBody,
} from './api'
import type { MediaDeps } from './network'
import { CallSession, ENDED_SHOW_MS, type CallDeps, type Notice } from './session'
import { SignalSocket } from './signal'

const t = (key: string) => i18n.global.t(key)

/** The browser's microphone and peer connection; a browser without WebRTC cannot call. */
export const browserMedia: MediaDeps = {
  getUserMedia(constraints) {
    if (!navigator.mediaDevices?.getUserMedia || typeof RTCPeerConnection === 'undefined')
      return Promise.reject(new DOMException('No WebRTC here', 'NotSupportedError'))
    return navigator.mediaDevices.getUserMedia(constraints)
  },
  createPeerConnection: (configuration) => new RTCPeerConnection(configuration),
}

/**
 * Keeps the screen on during a call. The shells lock the phone after about 30 s otherwise, and a
 * locked phone loses the microphone; old shells and browsers fall back to the Wake Lock API.
 */
class ScreenWake {
  private on = false
  private lock: WakeLockSentinel | null = null

  set(on: boolean): void {
    if (this.on === on) return
    this.on = on
    const shell = useShell()
    if (shell.capabilities.value?.screen_wake) {
      void shell.request(on ? 'screen.wake' : 'screen.release').catch(() => undefined)
      return
    }
    if (on) return void this.acquire()
    const lock = this.lock
    this.lock = null
    void lock?.release().catch(() => undefined)
  }

  /** The browser drops its lock when the page is hidden: asked again when it is visible. */
  resume(): void {
    if (this.on && !this.lock && !useShell().capabilities.value?.screen_wake) void this.acquire()
  }

  private async acquire(): Promise<void> {
    try {
      const lock = await navigator.wakeLock?.request('screen')
      if (!lock) return
      if (!this.on) return void lock.release().catch(() => undefined)
      this.lock = lock
      lock.addEventListener('release', () => {
        if (this.lock === lock) this.lock = null
      })
    } catch {
      // Refused (hidden page, power saving): the call goes on regardless.
    }
  }
}

const wake = new ScreenWake()
const session = shallowRef<CallSession | null>(null)
/** The server's open call for this person, as the overlay's query last read it. */
const current = shallowRef<Current | null>(null)
/** The last call this tab saw end: a stale `current` must not show it as running elsewhere. */
const finishedId = shallowRef<string | null>(null)
let removal: ReturnType<typeof setTimeout> | undefined

function say(notice: Notice): void {
  toast.error('key' in notice ? t(notice.key) : errorText(notice.error, t))
}

function refresh(): void {
  void queryClient.invalidateQueries({ queryKey: ['calls'] })
}

function remove(owner: CallSession): void {
  if (session.value !== owner) return
  clearTimeout(removal)
  session.value = null
}

function deps(): CallDeps {
  return {
    ...browserMedia,
    socket: (url, handlers) => new SignalSocket(url, handlers),
    api: {
      start: startCall,
      accept: acceptCall,
      end: endCall,
      current: () => fetchCurrent(),
      ice: fetchIce,
    },
    createAudio: () => document.createElement('audio'),
    keepAwake: (on) => wake.set(on),
    client: useShell().capabilities.value?.platform ?? 'browser',
    now: () => Date.now(),
    setTimeout: (handler, ms) => setTimeout(handler, ms),
    clearTimeout: (timer) => clearTimeout(timer),
    notice: say,
    refresh,
    finished(owner, how) {
      if (how === 'closed') return remove(owner)
      finishedId.value = owner.callId
      refresh()
      // Developers keep the ended screen with its summary until they close it.
      clearTimeout(removal)
      if (!useDeveloperMode().value) removal = setTimeout(() => remove(owner), ENDED_SHOW_MS)
    },
  }
}

/** Ends a call this tab has no session for: declining, or hanging up on another device. */
async function endFromHere(call: Call, body: EndBody): Promise<void> {
  finishedId.value = call.id
  try {
    await endCall(call.id, body)
  } catch (error) {
    finishedId.value = null
    const key = errorKey(error)
    say(key ? { key } : { error })
  } finally {
    refresh()
  }
}

export const calls = {
  session,
  current,
  finishedId,
  /** A call is running or ringing for this person, here or on another device. */
  busy: computed(() => !!session.value || !!current.value?.call),

  /** Must run synchronously in the tap: it asks for the microphone and unlocks playback. */
  dial(options: { channelId: string; peerName: string }): void {
    const me = useSession().me?.id
    if (session.value || !me) return
    speechPlayback.stop()
    session.value = CallSession.dial(deps(), { ...options, expectedUser: me })
  },

  /** Must run synchronously in the tap, like dial(). */
  answer(call: Call): void {
    const me = useSession().me?.id
    if (session.value || !me) return
    speechPlayback.stop()
    session.value = CallSession.answer(deps(), { call, expectedUser: me })
  },

  /** Says it is a refusal: a call another device of this person answered meanwhile stays. */
  decline: (call: Call) => endFromHere(call, { reason: 'declined' }),
  hangUpElsewhere: (call: Call) => endFromHere(call, {}),

  /** The ended screen's «Закрыть». */
  dismiss(): void {
    if (session.value?.view.value.phase === 'ended') remove(session.value)
  },

  /** The page or the account goes away: no `end` is sent; the server's presence rule ends it. */
  teardown(cause: 'signed_out' | 'pagehide'): void {
    if (cause === 'signed_out') {
      current.value = null
      finishedId.value = null
    }
    const active = session.value
    if (!active) return
    active.teardown()
    remove(active)
  },

  /** The page is visible again. */
  resume(): void {
    wake.resume()
  },
}
