import { useQuery } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { formatAmount } from '@/core/numbers'
import { useLedger } from '@/core/operations'
import { useUndo } from '@/core/undo'
import { Day, LOOKUP, OPERATIONS, Receipt, undoRevisions } from './api'

export function useFoodDay(day: Ref<string>, enabled: MaybeRefOrGetter<boolean> = true) {
  return useQuery({
    queryKey: computed(() => ['nutrition', 'day', day.value]),
    queryFn: async ({ signal }) =>
      Day.parse(await api.get(`/v1/nutrition/day?date=${encodeURIComponent(day.value)}`, signal)),
    enabled: computed(() => toValue(enabled)),
  })
}

export function useFood() {
  const { t } = useI18n()
  const ledger = useLedger({ endpoint: OPERATIONS, lookupPrefix: LOOKUP, domain: 'nutrition' })
  const undoable = useUndo()

  async function operate(
    kind: string,
    payload: Record<string, unknown>,
    expected: Record<string, number> = {},
  ): Promise<Receipt> {
    return Receipt.parse(await ledger.operate({ kind, payload, expected_revisions: expected }))
  }

  /** Confirms a change and offers to undo it while the entries are as the operation left them. */
  function done(receipt: Receipt, message: string) {
    const revisions = undoRevisions(receipt)
    undoable.confirm(
      message,
      revisions
        ? () => operate('operation.undo', { operation_id: receipt.operation_id }, revisions)
        : undefined,
    )
  }

  /** Receipt summary for a logged food: «Записано: Йогурт — 90 ккал». */
  function loggedMessage(receipt: Receipt, fallback: string): string {
    const first = receipt.applied_entries?.[0]
    const name = receipt.name ?? first?.name ?? fallback
    const kcal =
      receipt.energy_kcal ?? (receipt.applied_entries?.length === 1 ? first?.energy_kcal : null)
    return kcal
      ? t('food.recorded', { name, kcal: formatAmount(kcal, 0) })
      : t('food.recordedNoKcal', { name })
  }

  /** A failed write: a conflict means the data changed, so the form stays open to retry. */
  function failed(error: unknown) {
    toast.error(
      error instanceof ApiError && error.status === 409 ? t('food.conflict') : errorText(error, t),
    )
  }

  return { ...ledger, operate, done, loggedMessage, failed }
}
