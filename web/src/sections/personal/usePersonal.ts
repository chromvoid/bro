import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { api } from '@/core/client'
import { shiftDay } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { operationKind, useLedger } from '@/core/operations'
import type { Submission } from '@/core/journal'
import { History, Overview, PERSONAL, weightPoints, type HistoryKind } from './api'

export function useOverview(day: Ref<string>, enabled?: Ref<boolean>) {
  return useQuery({
    enabled,
    queryKey: computed(() => ['personal', 'overview', day.value]),
    queryFn: async ({ signal }) =>
      Overview.parse(
        await api.get(`/v1/personal/overview?date=${encodeURIComponent(day.value)}`, signal),
      ),
  })
}

export function useHistory(
  range: Ref<{ from: string; to: string; kind: HistoryKind | null }>,
  enabled?: Ref<boolean>,
) {
  return useQuery({
    enabled,
    queryKey: computed(() => ['personal', 'history', range.value]),
    queryFn: async ({ signal }) => {
      const { from, to, kind } = range.value
      const query = new URLSearchParams({
        from,
        to,
        ...(kind ? { kind } : {}),
        ...(kind === 'body' ? { projection: 'body' } : {}),
      })
      return History.parse(await api.get(`/v1/personal/history?${query}`, signal))
    },
  })
}

/** The last 30 days of weighings and how much the weight changed over them. */
export function useWeightChange(day: Ref<string>) {
  const range = computed(() => ({
    from: shiftDay(day.value, -30),
    to: day.value,
    kind: 'weight' as HistoryKind,
  }))
  const history = useHistory(range)
  const points = computed(() => (history.data.value ? weightPoints(history.data.value) : []))
  const change = computed(() =>
    points.value.length > 1 ? points.value.at(-1)!.y - points.value[0]!.y : null,
  )
  return { points, change }
}

export function usePersonal() {
  const { t } = useI18n()
  const ledger = useLedger(PERSONAL)

  async function operate(
    kind: string,
    payload: Record<string, unknown>,
    expected: Record<string, number> = {},
  ): Promise<unknown> {
    return ledger.operate({ kind: `personal.${kind}`, payload, expected_revisions: expected })
  }

  /** A failed write keeps the form as typed; a conflict asks to look at the newer data first. */
  function failed(error: unknown) {
    toast.error(
      error instanceof ApiError && error.status === 409
        ? t('personal.conflict')
        : errorText(error, t),
    )
  }

  // «personal.weight.add» → personal.operations.weight_add
  const describe = (entry: Submission) =>
    t('personal.unresolved', {
      kind: t(`personal.operations.${operationKind(entry).split('.').slice(1).join('_')}`),
    })

  return { ...ledger, operate, failed, describe }
}
