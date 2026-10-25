import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { errorText } from '@/core/errors'
import { operationKind, useLedger } from '@/core/operations'
import type { Submission } from '@/core/journal'
import { WORKERS } from './api'

/** Worker operations go through the journal: a lost response is checked, never silently resent. */
export function useWorkerOps() {
  const { t } = useI18n()
  const ledger = useLedger(WORKERS)
  const busy = ref(false)

  async function send(
    kind: string,
    payload: Record<string, unknown>,
    expected: Record<string, number> = {},
    message: string = t('admin.saved'),
  ): Promise<boolean> {
    if (busy.value) return false
    busy.value = true
    try {
      await ledger.operate({ kind, payload, expected_revisions: expected })
      toast.success(message)
      return true
    } catch (error) {
      toast.error(errorText(error, t))
      return false
    } finally {
      busy.value = false
    }
  }

  const describe = (entry: Submission) => t('admin.unresolved', { kind: operationKind(entry) })

  return { ...ledger, busy, send, describe }
}
