import { describe, expect, it } from 'vitest'
import { DictationDraft } from './draft'

describe('DictationDraft', () => {
  it('replaces the dictated suffix with each partial and the final correction', () => {
    const draft = new DictationDraft('Уже набрано')
    let value = draft.update('Уже набрано', 'один')
    expect(value).toBe('Уже набрано один')
    value = draft.update(value, 'один два')
    expect(value).toBe('Уже набрано один два')
    expect(draft.update(value, 'Один, два.')).toBe('Уже набрано Один, два.')
  })

  it('keeps manual edits over late partials, the final result and cancellation', () => {
    const draft = new DictationDraft('')
    draft.update('', 'черновик')
    const edited = 'исправлено руками'
    expect(draft.update(edited, 'поздний результат')).toBe(edited)
    expect(draft.manuallyEdited).toBe(true)
    expect(draft.cancel(edited)).toBe(edited)
  })

  it('restores the original draft on cancel when nothing was edited', () => {
    const draft = new DictationDraft('Начало\n')
    const value = draft.update('Начало\n', 'речь')
    expect(value).toBe('Начало\nречь')
    expect(draft.cancel(value)).toBe('Начало\n')
  })
})
