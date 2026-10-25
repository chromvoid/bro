import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import type { LocationQuery } from 'vue-router'
import { EMPTY_TASK_FILTERS } from './taskFilters'
import { useTaskFilters } from './useTaskFilters'

const mocks = vi.hoisted(() => ({ useRoute: vi.fn(), useRouter: vi.fn() }))
vi.mock('vue-router', () => mocks)

describe('useTaskFilters', () => {
  const replace = vi.fn().mockResolvedValue(undefined)
  let route: { query: LocationQuery }

  beforeEach(() => {
    vi.clearAllMocks()
    route = reactive({ query: { list: 'project', sort: 'manual' } })
    mocks.useRoute.mockReturnValue(route)
    mocks.useRouter.mockReturnValue({ replace })
  })

  it('reads navigation changes without a second local search or filter state', () => {
    const filters = useTaskFilters('board')
    expect(filters.filters.value).toEqual(EMPTY_TASK_FILTERS)
    route.query = { list: 'project', q: 'find me', important: '1', assignee: 'me' }
    expect(filters.count.value).toBe(3)
    expect(filters.narrowed.value).toBe(true)
    expect(filters.queryKey.value).toEqual(['find me', true, false, 'me', 'recent'])
    route.query = { list: 'another-project' }
    expect(filters.filters.value).toEqual(EMPTY_TASK_FILTERS)
  })

  it('patches the current URL while preserving its selection and shared search', async () => {
    route.query = { list: 'project', task: 'task', layout: 'board', q: 'query', overdue: '1' }
    const filters = useTaskFilters('board')
    await filters.change({ important: true, assignee: 'unassigned' })
    expect(replace).toHaveBeenCalledWith({
      query: {
        list: 'project',
        task: 'task',
        layout: 'board',
        q: 'query',
        overdue: '1',
        important: '1',
        assignee: 'unassigned',
        completed_scope: undefined,
      },
    })
    expect(route.query.important).toBeUndefined()
  })

  it('uses the effective layout when whole-history search widens a list', async () => {
    const layout = ref<'board' | 'list'>('board')
    route.query = { list: 'project', q: 'query', view: 'open' }
    const filters = useTaskFilters(layout)
    layout.value = 'list'
    await filters.wholeHistory()
    expect(replace.mock.lastCall?.[0]).toMatchObject({
      query: { list: 'project', q: 'query', view: 'all', completed_scope: 'all' },
    })
    await filters.reset()
    expect(replace.mock.lastCall?.[0]).toMatchObject({
      query: { list: 'project', view: 'open', q: undefined, completed_scope: undefined },
    })
  })
})
