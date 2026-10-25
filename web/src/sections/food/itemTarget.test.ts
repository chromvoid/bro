import { describe, expect, it } from 'vitest'
import { Day, ItemTarget } from './api'
import { targetItem } from './itemTarget'

const day = Day.parse({
  date: '2026-10-04',
  day_revision: 1,
  completed: false,
  entries: [],
  totals: {},
  goal: null,
  remaining_kcal: null,
  remaining_complete: false,
  drafts: [],
  items: [
    {
      kind: 'dish',
      id: 'dish',
      name: 'Same name',
      quantity: '100',
      unit: 'g',
      revision: 2,
      components: [],
    },
    {
      id: 'neighbor',
      name: 'Same name',
      quantity: '100',
      unit: 'g',
      revision: 1,
      local_date: '2026-10-04',
    },
  ],
})

describe('current diary target', () => {
  it('uses the canonical kind and current day to select the exact object', () => {
    const target = ItemTarget.parse({
      kind: 'dish',
      id: 'dish',
      date: '2026-10-04',
      revision: 2,
      status: 'active',
    })
    expect(targetItem(target, day)?.id).toBe('dish')
    expect(targetItem({ ...target, date: '2026-10-03' }, day)).toBeNull()
    expect(targetItem({ ...target, kind: 'entry' }, day)).toBeNull()
  })
  it('does not replace missing or deleted identities with a matching label', () => {
    const target = ItemTarget.parse({
      kind: 'dish',
      id: 'missing',
      date: '2026-10-04',
      revision: 2,
      status: 'active',
    })
    expect(targetItem(target, day)).toBeNull()
    expect(targetItem({ ...target, id: 'dish', status: 'deleted' }, day)).toBeNull()
  })
})
