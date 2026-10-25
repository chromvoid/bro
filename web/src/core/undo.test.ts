import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './http'
import { useUndo } from './undo'

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }))
vi.mock('vue-sonner', () => ({ toast }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

/** The «Отменить» action of the last confirmation. */
async function pressUndo() {
  const options = toast.success.mock.calls.at(-1)![1] as { action?: { onClick(): void } }
  options.action!.onClick()
  await vi.waitFor(() =>
    expect(toast.mock.calls.length + toast.error.mock.calls.length).toBeGreaterThan(0),
  )
}

describe('undoable confirmations', () => {
  beforeEach(() => vi.clearAllMocks())

  it('confirms plainly when there is nothing to take back', () => {
    useUndo().confirm('Записано')
    expect(toast.success).toHaveBeenCalledWith('Записано', { action: undefined })
  })

  it('offers one more way on beside the undo', () => {
    const run = vi.fn()
    useUndo().confirm('Перенесено', vi.fn(), { label: 'Показать', run })
    const { cancel } = toast.success.mock.calls[0]![1] as { cancel: Record<string, unknown> }
    expect(cancel.label).toBe('Показать')
    ;(cancel.onClick as () => void)()
    expect(run).toHaveBeenCalledOnce()
  })

  it('takes the change back and says so', async () => {
    const undo = vi.fn().mockResolvedValue({})
    useUndo().confirm('Задача выполнена', undo)
    expect(toast.success.mock.calls[0]![1]).toMatchObject({ action: { label: 'common.undo' } })
    await pressUndo()
    expect(undo).toHaveBeenCalledOnce()
    expect(toast).toHaveBeenCalledWith('common.undone')
  })

  it('reports a change made since, and a moment that has passed, as too late', async () => {
    const undo = useUndo()
    undo.confirm('Задача выполнена', () =>
      Promise.reject(new ApiError(409, 'conflict', null, null, null)),
    )
    await pressUndo()
    expect(toast.error).toHaveBeenLastCalledWith('common.undoFailed')
    vi.clearAllMocks()
    undo.confirm('Таймер отменён', async () => false)
    await pressUndo()
    expect(toast.error).toHaveBeenLastCalledWith('common.undoFailed')
    expect(toast).not.toHaveBeenCalled()
  })

  it('stays quiet when the person cancels the opposite transition', async () => {
    useUndo().confirm('Задача возвращена', async () => null)
    const options = toast.success.mock.calls.at(-1)![1] as { action: { onClick(): void } }
    options.action.onClick()
    await Promise.resolve()
    expect(toast).not.toHaveBeenCalled()
    expect(toast.error).not.toHaveBeenCalled()
  })
})
