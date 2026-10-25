import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useSession, type Me } from '@/stores/session'
import { developerPreference, setDeveloperMode, useDeveloperMode } from './developer'

const me = (system_admin: boolean): Me => ({
  id: 'u1',
  login: 'owner',
  name: 'Владелец',
  locale: 'ru',
  timezone: 'UTC',
  system_admin,
  revision: 1,
  scopes: [],
})

describe('developer mode', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    developerPreference.value = false
  })

  it('shows technical details only to an administrator who turned them on', () => {
    const session = useSession()
    const enabled = useDeveloperMode()
    session.me = me(true)
    expect(enabled.value).toBe(false)
    setDeveloperMode(true)
    expect(enabled.value).toBe(true)
    expect(localStorage.getItem('assistant.web.developer')).toBe('1')
    session.me = me(false)
    expect(enabled.value).toBe(false)
  })

  it('forgets the choice on this device when turned off', () => {
    setDeveloperMode(true)
    setDeveloperMode(false)
    expect(localStorage.getItem('assistant.web.developer')).toBeNull()
    expect(developerPreference.value).toBe(false)
  })
})
