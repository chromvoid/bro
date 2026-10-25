/**
 * A form draft that outlives reloads, conflicts and a lost connection: it is stored per account
 * (removed at sign-out) and cleared only when the user saves or discards it.
 */
import { ref, watch, type Ref } from 'vue'
import { useSession } from '@/stores/session'

export function useDraft<T extends object>(target: Ref<string | null>, empty: () => T) {
  const session = useSession()
  const value = ref(empty()) as Ref<T>
  const restored = ref(false)
  let loading = false

  let readVersion = 0
  let pending: { key: string; timer: ReturnType<typeof setTimeout>; write: () => void } | null =
    null
  let writing: Promise<void> = Promise.resolve()
  let clearing: string | null = null
  function flush() {
    const snapshot = pending
    if (!snapshot) return
    pending = null
    clearTimeout(snapshot.timer)
    snapshot.write()
  }
  function save(key: string, draft: T) {
    if (pending?.key === key) clearTimeout(pending.timer)
    else flush()
    const store = session.account?.store
    const text = JSON.stringify(draft)
    const write = () => {
      // Bind the snapshot to its account; a delayed save never enters another account.
      writing = writing
        .catch(() => undefined)
        .then(async () => {
          if (store && store === session.account?.store)
            await store.saveDraft({ target: key, text, revision: 0, updatedAt: Date.now() })
        })
      void writing.catch(() => undefined)
    }
    pending = {
      key,
      write,
      timer: setTimeout(() => {
        pending = null
        write()
      }, 300),
    }
  }

  watch(
    [target, () => session.account?.store],
    async ([key, store]) => {
      const version = ++readVersion
      // Clicking another card must not let its debounce replace the previous card's draft.
      flush()
      loading = true
      value.value = empty()
      restored.value = false
      const saved = key
        ? await writing.catch(() => undefined).then(() => store?.draft(key))
        : undefined
      if (version !== readVersion) return
      if (saved) {
        try {
          value.value = { ...empty(), ...(JSON.parse(saved.text) as T) }
          restored.value = true
        } catch {
          // A draft from an older version of the form is dropped.
        }
      }
      loading = false
    },
    { immediate: true },
  )
  watch(
    value,
    (draft) => {
      if (!loading && target.value && target.value !== clearing) save(target.value, draft)
    },
    { deep: true },
  )

  /** Starts the draft from the current server state (when nothing was saved). */
  function start(from: T) {
    if (!restored.value) value.value = { ...from }
  }

  async function clear(key = target.value) {
    const store = session.account?.store
    clearing = key
    if (key === target.value) {
      ++readVersion
      loading = false
    }
    if (pending && pending.key === key) {
      clearTimeout(pending.timer)
      pending = null
    }
    try {
      // A save already in IndexedDB must finish before deletion, or it could resurrect the draft.
      await writing.catch(() => undefined)
      if (key && store === session.account?.store) await store?.deleteDraft(key)
      if (key === target.value && store === session.account?.store) restored.value = false
    } finally {
      if (clearing === key) clearing = null
    }
  }

  return { value, restored, start, clear }
}
