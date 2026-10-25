import { onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { useSession } from '@/stores/session'
import {
  ActivityStream,
  publicPreview,
  type Activity,
  type ActivityConnection,
  type Confirmed,
} from './activity'

/** Component lifetime + account ownership surround the disposable read-only transport. */
export function useActivity(messageId: Ref<string>, enabled: Ref<boolean>, settled: () => void) {
  const session = useSession()
  const snapshot = shallowRef<Activity | null>(null)
  // The public answer text. A run's terminal snapshot carries none, and history replaces this view
  // with the saved reply only a round-trip later: until then the last text stays, so the
  // conversation does not shrink under a reader.
  const preview = ref('')
  const confirmed = shallowRef<Confirmed[]>([])
  const connection = ref<ActivityConnection>('connecting')
  let stream: ActivityStream | null = null
  let generation = 0
  let disposed = false
  let denied = false
  let permissionOwner = session.account
  let permissionVersion = session.me
  let confirmationMessage = messageId.value
  let unsubscribe: (() => void) | undefined

  function stop(clear = true) {
    generation++
    stream?.stop()
    stream = null
    if (clear) {
      snapshot.value = null
      preview.value = ''
    }
  }

  function connect() {
    // A visible window streams even without the focus: on a desktop the chat is often read beside
    // another window, and a hidden one costs a connection for nothing.
    if (
      disposed ||
      denied ||
      !enabled.value ||
      !session.account ||
      document.visibilityState === 'hidden'
    )
      return
    stop()
    const current = generation
    const owner = session.account
    const valid = () =>
      !disposed && generation === current && session.account === owner && enabled.value
    stream = new ActivityStream({
      messageId: messageId.value,
      onSnapshot(value) {
        if (!valid()) return
        snapshot.value = value
        // Live text, its intentional clear on a tool step or a retry, and none after a failure.
        if (value.status !== 'completed') preview.value = publicPreview(value)
        // This is the full currently authorized server projection, not model text.
        // Replacing it also removes receipts for a service whose access was revoked.
        if (value.confirmed !== undefined) confirmed.value = value.confirmed
      },
      onConnection(value) {
        if (!valid()) return
        connection.value = value
        // A preview without a live connection is not evidence of current execution.
        if (['connecting', 'reconnecting', 'offline', 'denied', 'unavailable'].includes(value)) {
          snapshot.value = null
          preview.value = ''
        }
        if (value === 'denied') {
          denied = true
          confirmed.value = []
        }
      },
      onTerminal(status) {
        if (valid() && status !== 'unavailable') settled()
      },
    })
    void stream.start()
  }

  watch(
    [messageId, enabled, () => session.account, () => session.me],
    () => {
      stop()
      unsubscribe?.()
      const changedMessage = confirmationMessage !== messageId.value
      if (
        changedMessage ||
        permissionOwner !== session.account ||
        permissionVersion !== session.me
      ) {
        confirmed.value = []
        confirmationMessage = messageId.value
      }
      if (
        changedMessage ||
        permissionOwner !== session.account ||
        permissionVersion !== session.me
      ) {
        denied = false
        permissionOwner = session.account
        permissionVersion = session.me
      }
      unsubscribe = session.account?.stream.subscribe((event) => {
        if (event.topic === 'access.changed') {
          denied = true
          connection.value = 'denied'
          confirmed.value = []
          stop()
          return
        }
        if (event.topic !== 'assistant.changed' || !event.data || typeof event.data !== 'object')
          return
        const data = event.data as Record<string, unknown>
        if (data.message_id !== messageId.value) return
        if (!['queued', 'routing', 'processing'].includes(String(data.state))) {
          // The run is over: its text stays until the saved reply, unless it failed and will not
          // become one.
          const text = data.state === 'failed' ? '' : preview.value
          stop()
          preview.value = text
          if (connection.value === 'connected') connection.value = 'ended'
          return
        }
        // The run moved on (routing → processing, a new run after a clarification): a stream that
        // is waiting out a retry or found no run yet connects now instead of after its backoff.
        if (connection.value !== 'connected') connect()
      })
      connect()
    },
    { immediate: true, flush: 'sync' },
  )
  function visibility() {
    if (document.visibilityState === 'hidden') {
      stop()
      connection.value = 'offline'
    } else if (connection.value !== 'connected') connect()
  }
  const pagehide = () => {
    stop()
    connection.value = 'offline'
  }
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('pagehide', pagehide)
  window.addEventListener('pageshow', visibility)
  window.addEventListener('online', visibility)
  onScopeDispose(() => {
    disposed = true
    stop()
    confirmed.value = []
    unsubscribe?.()
    document.removeEventListener('visibilitychange', visibility)
    window.removeEventListener('pagehide', pagehide)
    window.removeEventListener('pageshow', visibility)
    window.removeEventListener('online', visibility)
  })
  return { snapshot, preview, confirmed, connection }
}
