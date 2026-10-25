import { beforeEach, describe, expect, it } from 'vitest'
import { TaskList } from '@/sections/circle/api'
import {
  lastPlace,
  placesOf,
  progressOf,
  projectLayout,
  projectOrder,
  rememberOrder,
  rememberLayout,
  rememberPlace,
} from './places'

const list = (fields: Record<string, unknown>) =>
  TaskList.parse({ member_access: 'none', revision: 1, effective: 'manage', ...fields })
const names = (scope: string) => scope

describe('placesOf', () => {
  it('offers the scopes, active projects, then lists; finished projects apart', () => {
    const { places, finished } = placesOf(
      [
        list({ id: 'mine', name: 'Личные задачи', personal_user_id: 'me' }),
        list({ id: 'shop', name: 'Покупки', list_kind: 'list' }),
        list({ id: 'bath', name: 'Ремонт', list_kind: 'project', counts: { todo: 2, done: 1 } }),
        list({ id: 'old', name: 'Переезд', list_kind: 'project', project_state: 'archived' }),
      ],
      names,
    )
    expect(places.map((place) => place.key)).toEqual(['all', 'personal', 'bath', 'shop'])
    expect(places[0]!.query).toEqual({ scope: 'all' })
    expect(places[2]).toMatchObject({ query: { list: 'bath' }, progress: { done: 1, total: 3 } })
    expect(finished.map((place) => place.key)).toEqual(['old'])
  })

  it('adds «assigned to me» once something is shared', () => {
    const { places } = placesOf([list({ id: 'family', name: 'Семья', space_id: 's' })], names)
    expect(places.map((place) => place.key)).toEqual(['all', 'assigned', 'personal', 'family'])
  })

  it('counts nothing for a list without tasks', () => {
    expect(progressOf(list({ id: 'x', name: 'X' }))).toEqual({ done: 0, total: 0 })
  })
})

describe('remembered place and layout', () => {
  beforeEach(() => localStorage.clear())

  it('keeps the last list or scope, not the overview', () => {
    expect(lastPlace()).toBeNull()
    rememberPlace({ list: 'bath' })
    expect(lastPlace()).toEqual({ list: 'bath' })
    rememberPlace({ scope: 'personal' })
    expect(lastPlace()).toEqual({ scope: 'personal' })
    rememberPlace({})
    expect(lastPlace()).toEqual({})
  })

  it('remembers a project shown as its list until the board is chosen again', () => {
    expect(projectLayout('bath')).toBeUndefined()
    rememberLayout('bath', 'list')
    expect(projectLayout('bath')).toBe('list')
    rememberLayout('bath', 'board')
    expect(projectLayout('bath')).toBeUndefined()
  })

  it('ignores storage it cannot read', () => {
    localStorage.setItem('assistant.tasks.place', '{broken')
    expect(lastPlace()).toBeNull()
  })
})

describe('remembered project order', () => {
  beforeEach(() => localStorage.clear())

  it('defaults to manual and stores an independent choice per project', () => {
    expect(projectOrder('home')).toBe('manual')
    rememberOrder('home', 'due')
    rememberOrder('trip', 'priority')
    expect(projectOrder('home')).toBe('due')
    expect(projectOrder('trip')).toBe('priority')
    expect(projectOrder('new')).toBe('manual')
  })

  it('an explicit URL wins and invalid stored or URL values cannot become orders', () => {
    rememberOrder('home', 'created')
    expect(projectOrder('home', 'manual')).toBe('manual')
    expect(projectOrder('home', 'priority')).toBe('priority')
    expect(projectOrder('home', 'unknown')).toBe('created')
    localStorage.setItem('assistant.tasks.orders', '{"home":"old-sort"}')
    expect(projectOrder('home')).toBe('manual')
  })
})
