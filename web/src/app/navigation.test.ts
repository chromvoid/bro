import { describe, expect, it } from 'vitest'
import { PRIMARY, SECONDARY, VOICE_TAB, tabBar, visibleItems } from './navigation'

const names = (viewer: Parameters<typeof visibleItems>[1]) =>
  [...visibleItems(PRIMARY, viewer), ...visibleItems(SECONDARY, viewer)].map((item) => item.name)

describe('navigation', () => {
  it('shows sections by scope and administration only to administrators', () => {
    expect(names({ scopes: ['tasks.read'], system_admin: false })).toEqual([
      'today',
      'assistant',
      'tasks',
      'circle',
      'settings',
    ])
    expect(names({ scopes: ['nutrition.read', 'personal.read'], system_admin: true })).toEqual([
      'today',
      'assistant',
      'food',
      'circle',
      'personal',
      'settings',
      'admin',
    ])
  })

  it('shows activity separately only with its own read scope', () => {
    expect(names({ scopes: ['activity.read'], system_admin: false })).toContain('activity')
    expect(names({ scopes: ['personal.read'], system_admin: false })).not.toContain('activity')
  })

  it('shows nothing before the account is known', () => {
    expect(names(null)).toEqual([])
    expect(tabBar(null)).toEqual([])
  })

  it('offers timers as a section of their own, not inside tasks', () => {
    expect(names({ scopes: ['timers.read'], system_admin: false })).toContain('timers')
    expect(names({ scopes: ['tasks.read'], system_admin: false })).not.toContain('timers')
  })

  it('offers the recordings archive without requiring a native shell', () => {
    expect(names({ scopes: ['session.control'], system_admin: false })).toContain('recordings')
  })

  it('puts the microphone in the middle of the phone tab bar', () => {
    const tabs = (scopes: string[]) =>
      tabBar({ scopes, system_admin: false }).map((tab) => (tab === VOICE_TAB ? 'mic' : tab.name))
    expect(tabs(['nutrition.read', 'tasks.read'])).toEqual([
      'today',
      'food',
      'mic',
      'tasks',
      'assistant',
    ])
    expect(tabs(['tasks.read'])).toEqual(['today', 'tasks', 'mic', 'assistant'])
    expect(tabs([])).toEqual(['today', 'mic', 'assistant'])
  })
})
