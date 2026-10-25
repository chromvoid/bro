import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { router as appRouter, sections, trackOpening } from './router'

function navigation() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', children: sections }],
  })
  // Exercise navigation without loading screens or opening an account.
  for (const route of router.getRoutes()) {
    if (route.components) route.components.default = { render: () => null }
  }
  return router
}

describe('assistant routes on a shared host', () => {
  it('opens the public join page outside the authenticated app shell', async () => {
    const link = appRouter.resolve(
      '/join#server=https%3A%2F%2Ffamily.example&kind=invite&token=abc',
    )
    expect(link.name).toBe('join')
    expect(link.meta.public).toBe(true)
    expect(link.matched).toHaveLength(1)
    const target = { ...link, name: link.name ?? undefined }
    expect(await appRouter.options.scrollBehavior!(target, target, null)).toEqual({ top: 0 })
  })

  it('resolves the owner recordings archive as its own screen', () => {
    const route = navigation().resolve({ name: 'recordings' })
    expect(route.href).toBe('/recordings')
    expect(route.meta.scope).toBe('session.control')
  })
  it('resolves activity details with the activity scope and navigation section', () => {
    const link = navigation().resolve({
      name: 'activity-session',
      params: { session: 'synthetic' },
    })
    expect(link.href).toBe('/activity/synthetic')
    expect(link.meta.scope).toBe('activity.read')
    expect(link.meta.section).toBe('activity')
  })

  it('resolves the body profile deep link with personal read permissions', () => {
    const router = navigation()
    const link = router.resolve({ name: 'personal-profile', query: { field: 'birth' } })
    expect(link.href).toBe('/personal/profile?field=birth')
    expect(link.meta.scope).toBe('personal.read')
    expect(link.meta.section).toBe('personal')
  })

  it('keeps named Today and chat navigation away from the host dashboard', () => {
    const router = navigation()
    expect(router.resolve({ name: 'today' }).href).toBe('/today')
    expect(router.resolve({ name: 'assistant' }).href).toBe('/assistant')
  })

  it('opens a message a notification links to in the chat', async () => {
    const router = navigation()
    await router.push('/assistant/m/00000000-0000-4000-8000-000000000001')
    expect(router.currentRoute.value.name).toBe('assistant')
    expect(router.currentRoute.value.query.message).toBe('00000000-0000-4000-8000-000000000001')
  })

  it('keeps the dedicated-origin root working and preserves its query and fragment', async () => {
    const router = navigation()
    await router.push('/?from=bookmark#main')
    expect(router.currentRoute.value.name).toBe('today')
    expect(router.currentRoute.value.fullPath).toBe('/today?from=bookmark#main')
  })
})

describe('task workspace scroll position', () => {
  const resolve = (url: string) => {
    const route = navigation().resolve(url)
    return { ...route, name: route.name ?? undefined }
  }
  const scroll = appRouter.options.scrollBehavior!
  const workspace = '/tasks?list=project&layout=board&sort=manual&important=1'

  it('keeps the board in place when opening, changing and closing a card or discussion', async () => {
    const locations = [
      workspace,
      `${workspace}&task=one`,
      `${workspace}&task=two`,
      `${workspace}&task=two&task_tab=discussion`,
      workspace,
    ]
    for (let index = 1; index < locations.length; index++) {
      expect(await scroll(resolve(locations[index]!), resolve(locations[index - 1]!), null)).toBe(
        false,
      )
    }
  })

  it('still starts a different project, filter, layout or screen at the top', async () => {
    for (const target of [
      '/tasks?list=other&layout=board&sort=manual&important=1&task=one',
      '/tasks?list=project&layout=board&sort=manual&task=one',
      '/tasks?list=project&layout=list&sort=manual&important=1&task=one',
      '/today',
    ]) {
      expect(await scroll(resolve(target), resolve(`${workspace}&task=one`), null)).toEqual({
        top: 0,
      })
    }
  })

  it('preserves browser history restoration and explicit fragment navigation', async () => {
    const saved = { left: 20, top: 560 }
    expect(
      await scroll(resolve(`${workspace}&task=two`), resolve(`${workspace}&task=one`), saved),
    ).toBe(saved)
    expect(
      await scroll(resolve(`${workspace}&task=two#steps`), resolve(`${workspace}&task=one`), null),
    ).toEqual({ el: '#steps' })
  })
})

describe('the screen being opened', () => {
  const screen = { render: () => null }
  function later() {
    let arrive!: () => void
    const code = new Promise((resolve) => (arrive = () => resolve(screen)))
    return { load: () => code, arrive }
  }
  function app(tasks: () => Promise<unknown>, food: () => Promise<unknown> = async () => screen) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/today', component: screen },
        { path: '/tasks', component: tasks },
        { path: '/food', component: food },
      ],
    })
    return { router, opening: trackOpening(router) }
  }

  it('is named while its code loads and cleared once it is shown', async () => {
    const tasks = later()
    const { router, opening } = app(tasks.load)
    await router.push('/today')
    expect(opening.value).toBeNull()
    const shown = router.push('/tasks')
    await vi.waitFor(() => expect(opening.value?.fullPath).toBe('/tasks'))
    expect(router.currentRoute.value.fullPath).toBe('/today')
    tasks.arrive()
    await shown
    expect(opening.value).toBeNull()
    expect(router.currentRoute.value.fullPath).toBe('/tasks')
  })

  it('is cleared when its code fails to load', async () => {
    const report = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { router, opening } = app(() => Promise.reject(new Error('offline')))
    await router.push('/today')
    await expect(router.push('/tasks')).rejects.toThrow('offline')
    expect(opening.value).toBeNull()
    expect(report).toHaveBeenCalledOnce()
    report.mockRestore()
  })

  it('follows a later tap over an earlier one still loading', async () => {
    const tasks = later()
    const food = later()
    const { router, opening } = app(tasks.load, food.load)
    await router.push('/today')
    const first = router.push('/tasks')
    await vi.waitFor(() => expect(opening.value?.fullPath).toBe('/tasks'))
    const second = router.push('/food')
    await vi.waitFor(() => expect(opening.value?.fullPath).toBe('/food'))
    tasks.arrive()
    await first
    expect(opening.value?.fullPath).toBe('/food')
    food.arrive()
    await second
    expect(opening.value).toBeNull()
    expect(router.currentRoute.value.fullPath).toBe('/food')
  })
})
