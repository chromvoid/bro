/**
 * A confirmation that offers to take the change back while it is still as it was left. The undo
 * is a domain operation with expected revisions, so a later change makes it fail (409) instead of
 * overwriting someone's newer edit.
 */
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { errorText } from './errors'
import { ApiError } from './http'

export interface ToastStep {
  label: string
  run: () => void
}

export function useUndo() {
  const { t } = useI18n()

  async function revert(undo: () => Promise<unknown>) {
    try {
      const result = await undo()
      if (result === null) return
      if (result === false) return void toast.error(t('common.undoFailed'))
      toast(t('common.undone'))
    } catch (error) {
      toast.error(
        error instanceof ApiError && error.status === 409
          ? t('common.undoFailed')
          : errorText(error, t),
      )
    }
  }

  /**
   * Confirms with `message`; without `undo` it is a plain confirmation. `undo` resolves `false`
   * when it is too late to take the change back (a timer that would have finished by now), or null
   * when the person cancels a confirmation required by the opposite transition. `also` is one more
   * way on from the confirmation, such as showing where the change landed.
   */
  function confirm(message: string, undo?: () => Promise<unknown>, also?: ToastStep) {
    toast.success(message, {
      action: undo ? { label: t('common.undo'), onClick: () => void revert(undo) } : undefined,
      ...(also ? { cancel: { label: also.label, onClick: also.run } } : {}),
    })
  }

  return { confirm }
}
