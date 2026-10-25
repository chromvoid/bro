import { describe, expect, it } from 'vitest'
import {
  activeTaskFilterCount,
  applyTaskFilterParams,
  EMPTY_TASK_FILTERS,
  hasTaskNarrowing,
  readTaskFilters,
  resetTaskFiltersQuery,
  taskFilterKey,
  taskFiltersQuery,
  taskLayoutQuery,
  taskWholeHistoryQuery,
} from './taskFilters'

describe('task filter URL contract', () => {
  it('reads only canonical scalar values and keeps search literal', () => {
    expect(
      readTaskFilters({
        q: '  literal [query]  ',
        important: '1',
        overdue: '1',
        assignee: 'unassigned',
        completed_scope: 'all',
      }),
    ).toEqual({
      q: 'literal [query]',
      important: true,
      overdue: true,
      assignee: 'unassigned',
      completed_scope: 'all',
    })
    expect(
      readTaskFilters({
        q: ['first', 'second'],
        important: ['1', '0'],
        overdue: 'true',
        assignee: 'someone',
        completed_scope: 'forever',
      }),
    ).toEqual(EMPTY_TASK_FILTERS)
    expect(readTaskFilters({ important: 1, overdue: 1, completed_scope: ['all'] })).toEqual(
      EMPTY_TASK_FILTERS,
    )
  })

  it('combines conditions with AND and replaces the mutually exclusive assignee', () => {
    const query = {
      list: 'project',
      layout: 'board',
      task: 'selected-task',
      q: ' query ',
      important: '1',
      assignee: 'me',
      sort: 'manual',
    }
    const changed = taskFiltersQuery(query, { overdue: true, assignee: 'unassigned' })
    expect(changed).toEqual({
      ...query,
      q: 'query',
      overdue: '1',
      assignee: 'unassigned',
      completed_scope: undefined,
    })
    expect(query.assignee).toBe('me')
    expect(taskFiltersQuery(changed, { assignee: null }).assignee).toBeUndefined()
    expect(taskFiltersQuery(changed, { important: false }).important).toBeUndefined()
  })

  it('resets filters while keeping selection, layout and other route state', () => {
    const reset = resetTaskFiltersQuery({
      list: 'project',
      layout: 'list',
      task: 'selected-task',
      view: 'today',
      q: 'query',
      important: '1',
      overdue: '1',
      assignee: 'me',
      completed_scope: 'all',
    })
    expect(reset).toMatchObject({
      list: 'project',
      layout: 'list',
      task: 'selected-task',
      view: 'today',
    })
    expect(readTaskFilters(reset)).toEqual(EMPTY_TASK_FILTERS)
    expect(readTaskFilters({ list: 'another-project' })).toEqual(EMPTY_TASK_FILTERS)
  })

  it('widens whole-history list search and preserves it through layout changes', () => {
    const query = { list: 'project', q: 'query', important: '1', view: 'open' }
    const widened = taskWholeHistoryQuery(query, 'list')
    expect(widened).toMatchObject({ ...query, view: 'all', completed_scope: 'all' })
    expect(taskWholeHistoryQuery(query, 'board')).toMatchObject({
      ...query,
      completed_scope: 'all',
    })
    expect(taskLayoutQuery(widened, 'board')).toMatchObject({
      ...widened,
      layout: 'board',
    })
    expect(taskLayoutQuery({ ...widened, view: undefined }, 'list')).toMatchObject({
      ...widened,
      layout: 'list',
    })
    expect(taskLayoutQuery(query, 'list')).toEqual({ ...query, layout: 'list' })
  })

  it('uses identical conditions on first and continuation requests without stale values', () => {
    const filters = readTaskFilters({ q: 'query', overdue: '1', assignee: 'me' })
    const first = new URLSearchParams('list=project&q=old&q=duplicate&important=1')
    const continuation = new URLSearchParams('list=project&cursor=page-two&completed_scope=all')
    applyTaskFilterParams(first, filters)
    applyTaskFilterParams(continuation, filters)
    expect(first.getAll('q')).toEqual(['query'])
    expect(first.has('important')).toBe(false)
    expect(first.get('completed_scope')).toBe('recent')
    expect(continuation.get('cursor')).toBe('page-two')
    continuation.delete('cursor')
    expect(Object.fromEntries(continuation)).toEqual(Object.fromEntries(first))
  })

  it('binds every condition but distinguishes narrowing from widening completed history', () => {
    expect(hasTaskNarrowing(EMPTY_TASK_FILTERS)).toBe(false)
    const all = { ...EMPTY_TASK_FILTERS, completed_scope: 'all' as const }
    expect(activeTaskFilterCount(all)).toBe(1)
    expect(hasTaskNarrowing(all)).toBe(false)
    const filters = readTaskFilters({
      q: 'query',
      important: '1',
      overdue: '1',
      assignee: 'me',
      completed_scope: 'all',
    })
    expect(activeTaskFilterCount(filters)).toBe(5)
    expect(hasTaskNarrowing(filters)).toBe(true)
    expect(taskFilterKey(filters)).toEqual(['query', true, true, 'me', 'all'])
  })
})
