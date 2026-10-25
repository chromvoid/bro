import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import DateField from './DateField.vue'

vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { timezone: 'UTC' } }) }))

const mounted: VueWrapper[] = []
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))
function field(props: Record<string, unknown> = {}, attrs: Record<string, unknown> = {}) {
  const wrapper = mount(DateField, { props, attrs, global: { plugins: [i18n] } })
  mounted.push(wrapper)
  return wrapper
}
const today = new Date().toISOString().slice(0, 10)

describe('date field', () => {
  it('says the date in words over a native date input that keeps its attributes', () => {
    const wrapper = field({ modelValue: today }, { id: 'when', max: today })
    expect(wrapper.get('[data-testid="date-field-label"]').text()).toBe('Сегодня')
    const input = wrapper.get('input')
    expect(input.attributes('type')).toBe('date')
    expect(input.attributes('id')).toBe('when')
    expect(input.attributes('max')).toBe(today)
  })

  it('shows what an empty field means and clears an optional date', async () => {
    expect(field().get('[data-testid="date-field-label"]').text()).toBe('Не выбрана')
    expect(field({ placeholder: 'Любая' }).get('[data-testid="date-field-label"]').text()).toBe(
      'Любая',
    )
    const wrapper = field({ modelValue: '2026-03-05', clearable: true })
    expect(wrapper.get('[data-testid="date-field-label"]').text()).toContain('5 марта')
    await wrapper.get('button[aria-label="Очистить дату"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([['']])
  })

  it('passes a picked date up', async () => {
    const wrapper = field({ modelValue: '' })
    await wrapper.get('input').setValue('2026-10-01')
    expect(wrapper.emitted('update:modelValue')).toEqual([['2026-10-01']])
  })
})
