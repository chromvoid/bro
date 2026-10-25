/** Who is signed in, with which scopes, and the account services that belong to that session. */
import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { openAccount, type AccountServices } from '@/core/account'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { live } from '@/core/live'
import type { Submission } from '@/core/journal'
import { queryClient } from '@/core/query'
import { purgeAccount } from '@/core/storage'
import { z } from '@/core/zod'
import { nativeNotifications } from '@/sections/notifications/native'
import { nativeAmbientAudio } from '@/ambient/native'

const Me = z.object({
  id: z.string(),
  login: z.string().nullable(),
  name: z.string(),
  locale: z.string(),
  timezone: z.string(),
  system_admin: z.boolean(),
  revision: z.number(),
  scopes: z.array(z.string()),
  device_id: z.string().nullable().optional(),
  session_expires_at: z.string().nullable().optional(),
})
export type Me = z.infer<typeof Me>

export type SessionState = 'loading' | 'ready' | 'signed-out' | 'unavailable'

export const useSession = defineStore('session', () => {
  const me = shallowRef<Me | null>(null)
  const state = ref<SessionState>('loading')
  const account = shallowRef<AccountServices | null>(null)
  /** The session ended while in use (401), as opposed to a deliberate sign-out. */
  const expired = ref(false)
  /** Automatic authentication loss revokes native capture but retains its unsent audio. */
  const nativeSessionLapsed = ref(false)

  async function identify(signal: AbortSignal): Promise<Me> {
    return Me.parse(await api.get('/v1/me', signal))
  }

  /** Development only: the local launcher signs the owner in without a password. */
  async function developmentLogin(signal: AbortSignal): Promise<boolean> {
    if (!import.meta.env.DEV) return false
    const response = await fetch('/v1/auth/development', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
      signal,
    })
    try {
      return response.ok && !signal.aborted
    } finally {
      await response.body?.cancel().catch(() => undefined)
    }
  }

  let generation = 0
  let controller: AbortController | null = null
  const invalidateIdentity = () => {
    generation++
    controller?.abort()
    controller = null
    return generation
  }
  let loading: Promise<void> | null = null

  /** Resolves once the first identity check has settled. */
  function ready(): Promise<void> {
    return loading ?? load()
  }

  function load(): Promise<void> {
    const current = invalidateIdentity()
    controller = new AbortController()
    loading = identifySession(current, controller.signal)
    return loading
  }

  async function identifySession(current: number, signal: AbortSignal): Promise<void> {
    const valid = () => current === generation && !signal.aborted
    try {
      let identity: Me
      try {
        identity = await identify(signal)
      } catch (error) {
        if (!valid()) return
        if (
          !(error instanceof ApiError && error.status === 401) ||
          !(await developmentLogin(signal))
        ) {
          throw error
        }
        if (!valid()) return
        identity = await identify(signal)
      }
      if (!valid()) return
      if (
        !account.value ||
        me.value?.id !== identity.id ||
        me.value?.device_id !== identity.device_id
      )
        await switchAccount(identity, valid)
      else if (
        me.value?.revision !== identity.revision ||
        me.value.scopes.toSorted().join(' ') !== identity.scopes.toSorted().join(' ')
      )
        account.value.stream.refresh()
      if (!valid()) return
      me.value = identity
      state.value = 'ready'
      live.start()
      nativeSessionLapsed.value = false
    } catch (error) {
      if (!valid()) return
      if (error instanceof ApiError && error.status === 401) await endSession(true)
      else state.value = 'unavailable'
    }
  }

  async function switchAccount(next: Me, valid: () => boolean): Promise<void> {
    live.stop()
    if (me.value && (me.value.id !== next.id || me.value.device_id !== next.device_id)) {
      void nativeNotifications()
        .account(null)
        .catch(() => undefined)
      void nativeAmbientAudio()
        .account(null)
        .catch(() => undefined)
    }
    account.value?.close()
    account.value = null
    queryClient.clear()
    api.forget()
    let opened: AccountServices | null = null
    const owned = () => opened !== null && account.value === opened
    opened = await openAccount(next.id, api, queryClient, {
      accessChanged: () => {
        if (owned()) void load()
      },
      denied: (status) => {
        if (owned()) void (status === 401 ? endSession(true) : load())
      },
      changed: () => {
        if (owned())
          void nativeNotifications()
            .sync()
            .catch(() => undefined)
      },
    })
    // Opening IndexedDB can outlive logout or a different account's identity response.
    if (!valid()) {
      opened.close()
      return
    }
    account.value = opened
  }

  function endSession(lapsed = false): void {
    invalidateIdentity()
    live.stop()
    nativeSessionLapsed.value = lapsed
    void nativeAmbientAudio()
      .account(null, { preserveQueue: lapsed })
      .catch(() => undefined)
    void nativeNotifications()
      .account(null)
      .catch(() => undefined)
    loading = Promise.resolve()
    if (lapsed && state.value === 'ready') expired.value = true
    account.value?.close()
    account.value = null
    me.value = null
    queryClient.clear()
    api.forget()
    state.value = 'signed-out'
  }

  /** Signs out and removes this account's drafts, journal and cursor from the device. */
  async function logout(): Promise<void> {
    const userId = me.value?.id
    // Detach the account immediately; a slow logout must not admit another local write.
    endSession()
    controller = new AbortController()
    const signal = controller.signal
    try {
      await api.post('/v1/auth/logout', {}, { signal, expectedUser: userId })
    } catch {
      // Local data is removed even when the server cannot be reached.
    }
    if (userId) await purgeAccount(userId)
  }

  /** Writes on this device whose outcome is not settled yet; signing out would discard them. */
  async function unsettledWrites(): Promise<Submission[]> {
    const entries = (await account.value?.journal.pending()) ?? []
    return entries.filter((entry) => entry.state !== 'resolved')
  }

  /** Password sign-in; the server sets the session cookie. */
  async function signIn(login: string, password: string): Promise<void> {
    const current = invalidateIdentity()
    controller = new AbortController()
    const signal = controller.signal
    api.forget()
    await api.postPublic('/v1/auth/login', { login, password }, signal)
    if (current !== generation || signal.aborted) return
    expired.value = false
    await load()
  }

  /** Accepts an invitation (creating the account) or sets a new password from a reset link. */
  async function redeem(body: {
    token: string
    password: string
    login?: string
    name?: string
    timezone?: string
  }): Promise<void> {
    await api.postPublic('/v1/auth/redeem', body)
  }

  function can(scope: string): boolean {
    return me.value?.scopes.includes(scope) ?? false
  }

  // Only a session that was working can expire; the first identity check handles its own 401.
  api.hooks.unauthorized = () => {
    if (state.value === 'ready') void endSession(true)
  }

  return {
    me,
    state,
    account,
    expired,
    nativeSessionLapsed,
    can,
    load,
    ready,
    signIn,
    redeem,
    logout,
    unsettledWrites,
  }
})
