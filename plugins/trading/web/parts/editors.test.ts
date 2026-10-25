import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type Component } from 'vue'
import CoreEditor from './CoreEditor.vue'
import MarkEditor from './MarkEditor.vue'

const cleanup: Array<() => void> = []
afterEach(() => cleanup.splice(0).forEach((close) => close()))

function mount(component: Component, props: Record<string, unknown>) {
  const root = document.createElement('div')
  document.body.append(root)
  const errors = vi.fn()
  const app = createApp(component, props)
  app.config.errorHandler = errors
  app.mount(root)
  cleanup.push(() => {
    app.unmount()
    root.remove()
  })
  const get = <T extends HTMLElement>(selector: string) => root.querySelector<T>(selector)!
  const input = async (selector: string, value: string) => {
    const element = get<HTMLInputElement>(selector)
    element.value = value
    element.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
  }
  const submit = async () => {
    get('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await nextTick()
  }
  return { root, errors, get, input, submit }
}

describe('core date validation', () => {
  const props = { coin: 'BTC', held: '1', method: 'lifo', core: null, busy: false, error: null }
  it.each(['', '9999-12-31', '2026-02-31'])(
    'rejects an empty, future or impossible day: %s',
    async (date) => {
      const save = vi.fn()
      const view = mount(CoreEditor, { ...props, onSave: save })
      await view.input('input[type=date]', date)
      expect(view.get<HTMLButtonElement>('button[type=submit]').disabled).toBe(true)
      await view.submit()
      expect(save).not.toHaveBeenCalled()
      expect(view.errors).not.toHaveBeenCalled()
    },
  )

  it('saves a valid day at local midnight and recovers after clearing it', async () => {
    const save = vi.fn()
    const view = mount(CoreEditor, { ...props, onSave: save })
    await view.input('input[type=date]', '')
    await view.input('input[type=date]', '2025-01-02')
    expect(view.get<HTMLButtonElement>('button[type=submit]').disabled).toBe(false)
    await view.submit()
    expect(save).toHaveBeenCalledWith('1', new Date('2025-01-02T00:00:00').toISOString())
    expect(view.errors).not.toHaveBeenCalled()
  })
})

describe('flow cost validation', () => {
  it.each(['own_wallet', 'auto'])(
    'ignores a hidden invalid cost for %s, preserving its draft',
    async (treatment) => {
      const save = vi.fn()
      const view = mount(MarkEditor, {
        item: { direction: 'in', treatment: 'external', coin: 'BTC', unit_cost_usd: null },
        busy: false,
        error: null,
        onSave: save,
      })
      await view.input('input[inputmode=decimal]', 'invalid')
      expect(view.get<HTMLButtonElement>('button[type=submit]').disabled).toBe(true)
      const choose = async (value: string) => {
        const radio = view.get<HTMLInputElement>(`input[value=${value}]`)
        radio.checked = true
        radio.dispatchEvent(new Event('change', { bubbles: true }))
        await nextTick()
      }
      await choose(treatment)
      expect(view.root.querySelector('input[inputmode=decimal]')).toBeNull()
      expect(view.get<HTMLButtonElement>('button[type=submit]').disabled).toBe(false)
      await view.submit()
      expect(save).toHaveBeenCalledWith(treatment, null)
      await choose('external')
      expect(view.get<HTMLInputElement>('input[inputmode=decimal]').value).toBe('invalid')
      expect(view.get<HTMLButtonElement>('button[type=submit]').disabled).toBe(true)
    },
  )
})
