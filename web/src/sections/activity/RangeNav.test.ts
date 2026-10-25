import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import RangeNav from './RangeNav.vue'

const nav = (anchor: string, period = 7) =>
  mount(RangeNav, {
    props: { anchor, period, today: '2026-10-08' },
    global: { plugins: [i18n], stubs: { PopoverContent: true } },
  })

describe('RangeNav', () => {
  it('names the period and steps it by its length, never past today', async () => {
    const wrapper = nav('2026-10-07')
    expect(wrapper.get('[data-testid="activity-range"]').text()).toContain('1–7 окт.')
    await wrapper.get('[aria-label="Предыдущий период"]').trigger('click')
    await wrapper.get('[aria-label="Следующий период"]').trigger('click')
    expect(wrapper.emitted('go')).toEqual([['2026-09-30'], ['2026-10-08']])
    expect(nav('2026-10-08').get('[aria-label="Следующий период"]').attributes('disabled')).toBe('')
  })
})
