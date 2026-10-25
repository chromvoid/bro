import { QueryClient } from '@tanstack/vue-query'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { prefetchSections } from './prefetch'

function sections() {
  const loaded: string[] = []
  const lazy = (name: string) => () => {
    loaded.push(name)
    return Promise.resolve({ render: () => null })
  }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/food', name: 'food', component: lazy('food') },
      { path: '/tasks', name: 'tasks', component: lazy('tasks') },
    ],
  })
  return { router, loaded }
}

const preload = async (load: () => Promise<unknown>) => {
  await load()
}

describe('prefetchSections', () => {
  it('loads the sections once the screen in view has its data', async () => {
    const { router, loaded } = sections()
    const client = new QueryClient()
    let answer!: (value: string) => void
    const today = new Promise<string>((resolve) => (answer = resolve))
    void client.fetchQuery({ queryKey: ['today'], queryFn: () => today })
    const done = prefetchSections(router, ['food', 'tasks'], preload, client)
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(loaded).toEqual([])
    answer('ready')
    await done
    expect(loaded).toEqual(['food', 'tasks'])
  })

  it('loads each section once, however often it is asked', async () => {
    const { router, loaded } = sections()
    const client = new QueryClient()
    await prefetchSections(router, ['food'], preload, client)
    await prefetchSections(router, ['food', 'tasks'], preload, client)
    await router.push('/food')
    expect(loaded).toEqual(['food', 'tasks'])
  })
})
