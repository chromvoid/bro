import { describe, expect, it } from 'vitest'
import { Task } from './api'
import { dropTarget, queueTarget } from './queue'

const task = (id: string, parent_id: string | null = null) =>
  Task.parse({ id, parent_id, list_id: 'list', title: id, status: 'open', revision: 1 })
const a = task('a')
const b = task('b')
const c = task('c')
const child = task('child', 'parent')

describe('board sibling queue', () => {
  it('calculates top and upward moves within the same parent level', () => {
    expect(queueTarget([a, child, b, c], b, 'up', false)).toEqual({ beforeId: 'a' })
    expect(queueTarget([a, child, b, c], c, 'top', false)).toEqual({ beforeId: 'a' })
    expect(queueTarget([a, child, b], child, 'up', false)).toBeNull()
  })

  it('moves down past one sibling and loads more when that neighbor is still unknown', () => {
    expect(queueTarget([a, child, b, c], a, 'down', false)).toEqual({ beforeId: 'c' })
    expect(queueTarget([a, b], a, 'down', true)).toEqual({ more: true })
    expect(queueTarget([a, b], b, 'down', true)).toEqual({ more: true })
    expect(queueTarget([a, b], a, 'down', false)).toEqual({ beforeId: null })
    expect(queueTarget([a, b], b, 'down', false)).toBeNull()
    expect(queueTarget([a, b], b, 'end', true)).toEqual({ beforeId: null })
  })

  it('never treats a partial drop tail or a different parent as the insertion neighbor', () => {
    expect(dropTarget([a, b, child], b, 1, true)).toBeUndefined()
    expect(dropTarget([b, child, a], b, 0, true)).toBe('a')
    expect(dropTarget([a, b], b, 1, false)).toBeNull()
  })
})
