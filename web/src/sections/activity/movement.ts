import type { History, Metric } from '@/sections/personal/api'

type Result = Metric | History['daily'][number] | undefined

/** Whether a day's movement total covers the whole day, part of it, or is not known to. */
export function coverageOf(row: Result): 'complete' | 'partial' | 'coverageUnknown' {
  const complete = row && 'complete' in row ? row.complete : undefined
  const resolved = row?.resolution?.coverage?.complete
  if (complete === false || resolved === false || row?.resolution?.status === 'partial')
    return 'partial'
  return complete === true || resolved === true ? 'complete' : 'coverageUnknown'
}
