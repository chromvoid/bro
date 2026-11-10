/**
 * Permission to send what a person writes, says or photographs to the AI services connected on
 * their server (App Review 5.1.2(i)). Every place that hands content to a model asks first; the
 * answer is kept on this device for one server and one account, so another account or another
 * server asks again. Storage that refuses writes keeps the answer for this page only.
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { useSession } from '@/stores/session'

const PREFIX = 'assistant.web.ai-consent:'

type KeyStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export interface ConsentScope {
  origin: string
  userId: string
}

interface Waiting {
  key: string | null
  allowed: () => unknown
  declined: () => unknown
}

export interface AiConsent {
  /** This account on this server has allowed it. */
  granted: ComputedRef<boolean>
  /** The question is on screen. */
  asking: Ref<boolean>
  /**
   * Runs the action at once when allowed, inside the caller's tap; otherwise asks and runs it once
   * on «Разрешить», inside that tap. «Не сейчас» runs `declined` instead.
   */
  ask(action: () => unknown, declined?: () => unknown): void
  /** The same question for an action that can wait for the answer: true when it may go ahead. */
  request(): Promise<boolean>
  allow(): void
  decline(): void
  revoke(): void
}

export const consentKey = (scope: ConsentScope) => `${PREFIX}${scope.origin}|${scope.userId}`

export function createAiConsent(
  scope: () => ConsentScope | null,
  storage: () => KeyStorage,
): AiConsent {
  // Answers this page keeps when storage cannot.
  const remembered = new Set<string>()
  const revision = ref(0)
  const asking = ref(false)
  let waiting: Waiting | null = null

  const currentKey = () => {
    const value = scope()
    return value ? consentKey(value) : null
  }
  function stored(key: string): boolean {
    try {
      if (storage().getItem(key) === 'granted') return true
    } catch {
      // Read below from what this page remembers.
    }
    return remembered.has(key)
  }
  const granted = computed(() => {
    void revision.value
    const key = currentKey()
    return !!key && stored(key)
  })

  function ask(action: () => unknown, declined: () => unknown = () => undefined) {
    if (granted.value) {
      action()
      return
    }
    // A newer action replaces the one still waiting: only the last tap goes ahead.
    const previous = waiting
    waiting = { key: currentKey(), allowed: action, declined }
    asking.value = true
    previous?.declined()
  }

  function settle(): Waiting | null {
    const value = waiting
    waiting = null
    asking.value = false
    return value
  }

  function allow() {
    const value = settle()
    if (!value) return
    const key = currentKey()
    // Signed out, or the account or the server changed while the question was open: that answer
    // is not theirs.
    if (!key || value.key !== key) {
      value.declined()
      return
    }
    remembered.add(key)
    try {
      storage().setItem(key, 'granted')
    } catch {
      // Kept for this page in `remembered`.
    }
    revision.value++
    value.allowed()
  }

  function decline() {
    settle()?.declined()
  }

  function revoke() {
    const key = currentKey()
    if (!key) return
    remembered.delete(key)
    try {
      storage().removeItem(key)
    } catch {
      // Nothing stored to remove.
    }
    revision.value++
  }

  return {
    granted,
    asking,
    ask,
    request: () =>
      new Promise<boolean>((resolve) =>
        ask(
          () => resolve(true),
          () => resolve(false),
        ),
      ),
    allow,
    decline,
    revoke,
  }
}

export const aiConsent = createAiConsent(
  () => {
    const id = useSession().me?.id
    return id ? { origin: window.location.origin, userId: id } : null
  },
  () => window.localStorage,
)
