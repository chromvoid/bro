import { describe, expect, it } from 'vitest'
import { Task, type TaskState } from './api'
import { taskNeighbours } from './taskWorkspace'

const task = (id: string, state: TaskState = 'todo', list_id = 'project') =>
  Task.parse({
    id,
    title: id,
    state,
    status: state === 'done' ? 'done' : 'open',
    list_id,
    revision: 1,
  })

describe('loaded task neighbours', () => {
  it('follows displayed order within the current project and column', () => {
    const first = task('first')
    const selected = task('selected')
    const last = task('last')
    expect(
      taskNeighbours(selected, [
        first,
        task('waiting', 'waiting'),
        selected,
        task('elsewhere', 'todo', 'other'),
        last,
      ]),
    ).toEqual({ previous: first, next: last })
  })

  it('does not wrap or guess a neighbour when the selected task is not loaded', () => {
    const first = task('first')
    const last = task('last')
    expect(taskNeighbours(first, [first, last])).toEqual({ previous: null, next: last })
    expect(taskNeighbours(last, [first, last])).toEqual({ previous: first, next: null })
    expect(taskNeighbours(task('unknown'), [first, last])).toEqual({ previous: null, next: null })
    expect(taskNeighbours(null, [first, last])).toEqual({ previous: null, next: null })
  })

  it('uses newly loaded pages and stops using the former column after a move', () => {
    const selected = task('selected')
    const next = task('next')
    expect(taskNeighbours(selected, [selected]).next).toBeNull()
    expect(taskNeighbours(selected, [selected, next]).next).toBe(next)
    expect(taskNeighbours({ ...selected, state: 'waiting' }, [selected, next])).toEqual({
      previous: null,
      next: null,
    })
  })
})
