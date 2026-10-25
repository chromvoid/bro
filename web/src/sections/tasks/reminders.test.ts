import { describe, expect, it } from 'vitest'
import { reminderPresets } from './reminders'

describe('reminder presets', () => {
  it('offers an hour from now, this evening and tomorrow morning in the person’s zone', () => {
    // 10:00 in Belgrade (UTC+2 in October).
    const presets = reminderPresets(new Date('2026-10-03T08:00:00Z'), 'Europe/Belgrade')
    expect(presets).toEqual([
      { key: 'hour', at: '2026-10-03T09:00:00.000Z' },
      { key: 'evening', at: '2026-10-03T19:00:00+02:00' },
      { key: 'morning', at: '2026-10-04T09:00:00+02:00' },
    ])
  })

  it('drops the evening once it is less than half an hour ahead or past', () => {
    const late = reminderPresets(new Date('2026-10-03T16:45:00Z'), 'Europe/Belgrade')
    expect(late.map((preset) => preset.key)).toEqual(['hour', 'morning'])
  })
})
