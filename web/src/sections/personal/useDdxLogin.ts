import { useIntervalFn } from '@vueuse/core'
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { api } from '@/core/client'
import { ApiError, NetworkError } from '@/core/http'
import { useSession } from '@/stores/session'
import {
  ACTIVE_DDX_LOGIN,
  DdxLoginReply,
  DdxStatus,
  ddxCode,
  ddxPhone,
  waitSeconds,
  type DdxLogin,
  type DdxLoginMode,
} from './ddx'

type Start = { phone: string; mode: DdxLoginMode; source_id?: string; expected_revision?: number }

/** Provider secrets live only in this component's memory, never in the submission journal. */
export function useDdxLogin(onConnected: () => void) {
  const session = useSession()
  const phone = ref('')
  const code = ref('')
  const retryAt = ref<string | null>(null)
  const login = shallowRef<DdxLogin | null>(null)
  const available = ref<boolean | null>(null)
  const canDisconnect = ref(false)
  const busy = ref(false)
  const checking = ref(false)
  const error = ref<string | null>(null)
  const uncertain = ref(false)
  const retryStart = ref(false)
  const active = computed(() => !!login.value && ACTIVE_DDX_LOGIN.has(login.value.state))
  let pendingStart: { key: string; payload: Start } | null = null
  let readyId: string | null = null
  let generation = 0
  let actionVersion = 0
  let controller = new AbortController()
  let disposed = false

  function owner() {
    const account = session.account
    const id = session.me?.id
    const captured = generation
    const signal = controller.signal
    const valid = () =>
      !disposed &&
      captured === generation &&
      session.state === 'ready' &&
      !!id &&
      session.me?.id === id &&
      session.account === account &&
      account?.store.userId === id &&
      !signal.aborted
    const beforeSend = () => {
      if (!valid()) throw new DOMException('Account changed', 'AbortError')
    }
    beforeSend()
    return { valid, beforeSend, expectedUser: id!, signal }
  }

  function reset() {
    generation++
    actionVersion++
    controller.abort()
    controller = new AbortController()
    phone.value = ''
    code.value = ''
    retryAt.value = null
    login.value = null
    available.value = null
    canDisconnect.value = false
    busy.value = false
    checking.value = false
    error.value = null
    uncertain.value = false
    retryStart.value = false
    pendingStart = null
    readyId = null
  }

  function accept(next: DdxLogin) {
    login.value = next
    retryAt.value = next.resend_at ?? retryAt.value
    uncertain.value = false
    retryStart.value = false
    pendingStart = null
    if (!ACTIVE_DDX_LOGIN.has(next.state)) code.value = ''
    if (next.state === 'ready' && readyId !== next.id) {
      readyId = next.id
      phone.value = ''
      onConnected()
    }
  }

  async function refresh() {
    if (checking.value || disposed || !session.can('personal.write')) return
    let guard: ReturnType<typeof owner>
    try {
      guard = owner()
    } catch {
      return
    }
    checking.value = true
    const version = actionVersion
    try {
      const status = DdxStatus.parse(await api.get('/v1/personal/ddx/status', guard.signal))
      if (!guard.valid() || version !== actionVersion) return
      available.value = status.available
      canDisconnect.value = status.can_disconnect ?? status.available
      if (pendingStart) {
        if (status.active_login?.request_key === pendingStart.key) accept(status.active_login)
        else retryStart.value = true
      } else if (status.active_login) accept(status.active_login)
      else if (login.value) {
        const response = DdxLoginReply.parse(
          await api.get(
            `/v1/personal/ddx/logins/${encodeURIComponent(login.value.id)}`,
            guard.signal,
          ),
        )
        if (!guard.valid() || version !== actionVersion) return
        accept(response.login)
      }
      error.value = null
    } catch {
      if (guard.valid() && version === actionVersion) error.value = 'network_error'
    } finally {
      if (guard.valid()) checking.value = false
    }
  }

  async function post(path: string, body: unknown, key: string = crypto.randomUUID()) {
    if (busy.value || !session.can('personal.write')) return
    const guard = owner()
    actionVersion++
    busy.value = true
    error.value = null
    try {
      const response = DdxLoginReply.parse(
        (
          await api.post(path, body, {
            ...guard,
            key,
            beforeSend: () => {
              guard.beforeSend()
              if (!session.can('personal.write'))
                throw new DOMException('Access changed', 'AbortError')
            },
          })
        ).data,
      )
      if (guard.valid()) accept(response.login)
    } catch (failure) {
      if (!guard.valid()) return
      error.value =
        failure instanceof ApiError ? (failure.code ?? 'request_failed') : 'network_error'
      uncertain.value = failure instanceof NetworkError || !(failure instanceof ApiError)
      if (!uncertain.value) pendingStart = null
    } finally {
      if (guard.valid()) busy.value = false
    }
  }

  async function start(mode: DdxLoginMode, sourceId?: string, revision?: number) {
    if (busy.value || active.value || uncertain.value || available.value !== true) return
    if (waitSeconds(retryAt.value, Date.now()) > 0) {
      error.value = 'resend_cooldown'
      return
    }
    const normalized = ddxPhone(phone.value)
    if (!normalized) {
      error.value = 'invalid_phone'
      return
    }
    pendingStart = {
      key: crypto.randomUUID(),
      payload: {
        phone: normalized,
        mode,
        ...(sourceId ? { source_id: sourceId, expected_revision: revision } : {}),
      },
    }
    await post('/v1/personal/ddx/logins', pendingStart.payload, pendingStart.key)
  }

  async function repeatStart() {
    if (!pendingStart || !retryStart.value || busy.value) return
    retryStart.value = false
    await post('/v1/personal/ddx/logins', pendingStart.payload, pendingStart.key)
  }

  async function submitCode() {
    if (!login.value || login.value.state !== 'waiting_code' || busy.value || uncertain.value)
      return
    const value = ddxCode(code.value)
    if (!value) {
      error.value = 'invalid_verification_code'
      return
    }
    code.value = ''
    await post(`/v1/personal/ddx/logins/${encodeURIComponent(login.value.id)}/code`, {
      code: value,
    })
  }

  async function action(kind: 'resend' | 'cancel') {
    if (!login.value || busy.value || (uncertain.value && kind === 'resend')) return
    code.value = ''
    await post(`/v1/personal/ddx/logins/${encodeURIComponent(login.value.id)}/${kind}`, {})
  }

  function clear() {
    if (active.value || busy.value || uncertain.value) return
    actionVersion++
    phone.value = ''
    code.value = ''
    login.value = null
    error.value = null
  }

  watch(
    () => [session.account, session.me?.id, session.state, session.can('personal.write')] as const,
    () => {
      reset()
      if (session.state === 'ready' && session.account?.store.userId === session.me?.id)
        void refresh()
    },
    { immediate: true, flush: 'sync' },
  )
  useIntervalFn(() => {
    if (active.value && !busy.value) void refresh()
  }, 2000)
  onScopeDispose(() => {
    disposed = true
    reset()
  })
  return {
    phone,
    code,
    retryAt,
    login,
    available,
    canDisconnect,
    busy,
    checking,
    error,
    uncertain,
    retryStart,
    active,
    refresh,
    start,
    repeatStart,
    submitCode,
    action,
    clear,
  }
}
