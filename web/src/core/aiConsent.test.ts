import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { consentKey, createAiConsent, type ConsentScope } from './aiConsent'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    values,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  }
}
const refusing = {
  getItem: () => {
    throw new DOMException('denied', 'SecurityError')
  },
  setItem: () => {
    throw new DOMException('denied', 'SecurityError')
  },
  removeItem: () => {
    throw new DOMException('denied', 'SecurityError')
  },
}

function setup(storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = memoryStorage()) {
  const scope = ref<ConsentScope | null>({ origin: 'https://bro.example', userId: 'owner' })
  return {
    scope,
    storage,
    consent: createAiConsent(
      () => scope.value,
      () => storage,
    ),
  }
}

describe('permission for external AI processing', () => {
  it('holds the action until «Разрешить», runs it once, and then no longer asks', () => {
    const { consent, storage } = setup()
    const action = vi.fn()
    consent.ask(action)
    expect(action).not.toHaveBeenCalled()
    expect(consent.asking.value).toBe(true)
    consent.allow()
    expect(action).toHaveBeenCalledOnce()
    expect(consent.asking.value).toBe(false)
    expect(consent.granted.value).toBe(true)
    expect(storage.getItem(consentKey({ origin: 'https://bro.example', userId: 'owner' }))).toBe(
      'granted',
    )
    consent.allow()
    consent.ask(action)
    expect(action).toHaveBeenCalledTimes(2)
    expect(consent.asking.value).toBe(false)
  })

  it('«Не сейчас» leaves the draft and asks again on the next attempt', async () => {
    const { consent } = setup()
    const draft = ref('Что я ел вчера?')
    const send = async () => {
      if (await consent.request()) draft.value = ''
    }
    const first = send()
    consent.decline()
    await first
    expect(draft.value).toBe('Что я ел вчера?')
    expect(consent.granted.value).toBe(false)
    const second = send()
    expect(consent.asking.value).toBe(true)
    consent.allow()
    await second
    expect(draft.value).toBe('')
  })

  it('asks again for another account or another server', () => {
    const { consent, scope } = setup()
    consent.ask(() => undefined)
    consent.allow()
    scope.value = { origin: 'https://bro.example', userId: 'child' }
    expect(consent.granted.value).toBe(false)
    scope.value = { origin: 'https://other.example', userId: 'owner' }
    expect(consent.granted.value).toBe(false)
    scope.value = { origin: 'https://bro.example', userId: 'owner' }
    expect(consent.granted.value).toBe(true)
  })

  it('does not give one account’s answer to the account that replaced it while asking', () => {
    const { consent, scope } = setup()
    const action = vi.fn()
    const declined = vi.fn()
    consent.ask(action, declined)
    scope.value = { origin: 'https://bro.example', userId: 'child' }
    consent.allow()
    expect(action).not.toHaveBeenCalled()
    expect(declined).toHaveBeenCalledOnce()
    expect(consent.granted.value).toBe(false)
  })

  it('refuses the waiting action when the session ended while asking', async () => {
    const { consent, scope } = setup()
    const answer = consent.request()
    scope.value = null
    consent.allow()
    expect(await answer).toBe(false)
    expect(consent.asking.value).toBe(false)
  })

  it('asks again after the permission is revoked', () => {
    const { consent } = setup()
    consent.ask(() => undefined)
    consent.allow()
    consent.revoke()
    expect(consent.granted.value).toBe(false)
    const action = vi.fn()
    consent.ask(action)
    expect(action).not.toHaveBeenCalled()
    expect(consent.asking.value).toBe(true)
  })

  it('keeps working without storage and asks again in the next session', () => {
    const page = setup(refusing)
    const action = vi.fn()
    page.consent.ask(action)
    page.consent.allow()
    page.consent.ask(action)
    expect(action).toHaveBeenCalledTimes(2)
    expect(setup(refusing).consent.granted.value).toBe(false)
  })
})
