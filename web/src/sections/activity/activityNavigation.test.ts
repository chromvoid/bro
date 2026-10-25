import { describe, expect, it } from 'vitest'
import { activityReturnQuery } from './activityNavigation'

describe('Activity detail return context', () => {
  const today = '2026-10-10'

  it('preserves independent overview day, analytics end, filter and selected exercise', () => {
    expect(
      activityReturnQuery(
        {
          tab: 'progress',
          day: '2026-10-04',
          end: '2026-10-08',
          period: '30',
          kind: 'strength',
          exercise: 'bench',
          metric: 'strength',
        },
        today,
        '2026-10-07',
      ),
    ).toEqual({
      tab: 'progress',
      day: '2026-10-04',
      end: '2026-10-08',
      period: '30',
      kind: 'strength',
      exercise: 'bench',
      metric: 'strength',
    })
  })

  it('ignores unknown keys, arrays, malformed or future dates and invalid filters', () => {
    expect(
      activityReturnQuery(
        {
          tab: 'unknown',
          day: ['2026-10-04'],
          end: '2026-10-11',
          period: '300',
          kind: 'unknown',
          metric: 'unknown',
          exercise: ['bench'],
          redirect: 'https://example.com',
        },
        today,
        '2026-10-05',
      ),
    ).toEqual({ tab: 'journal', day: '2026-10-05' })
    expect(activityReturnQuery({ day: '2026-02-30' }, today)).toEqual({ tab: 'journal' })
  })

  it('returns a directly opened session to its own date without inventing an analytics period', () => {
    expect(activityReturnQuery({}, today, '2026-10-03')).toEqual({
      tab: 'journal',
      day: '2026-10-03',
    })
  })
})
