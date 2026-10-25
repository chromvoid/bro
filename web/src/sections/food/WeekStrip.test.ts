import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import WeekStrip from './WeekStrip.vue'

const mocks = vi.hoisted(() => ({ days: [] as unknown[] }))
vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({ data: ref({ days: mocks.days }) }),
}))

const strip = () =>
  mount(WeekStrip, {
    props: { day: '2026-10-07', today: '2026-10-08' },
    global: { plugins: [i18n] },
  })

describe('WeekStrip', () => {
  it('names an over day and explains the colours it shows', () => {
    mocks.days = [
      { date: '2026-10-05', energy_kcal: '2500', completed: true, target: '2000' },
      { date: '2026-10-06', energy_kcal: '1800', completed: false, target: '2000' },
    ]
    const wrapper = strip()
    const labels = wrapper.findAll('button').map((button) => button.attributes('aria-label'))
    expect(labels[0]).toContain('сверх лимита')
    expect(labels[1]).not.toContain('сверх лимита')
    expect(wrapper.get('[data-testid="week-legend"]').text()).toBe(
      'Не больше лимитаСверх лимитаДень заполнен',
    )
  })

  it('shows no legend for a week with nothing to explain', () => {
    mocks.days = [{ date: '2026-10-06', energy_kcal: '1800', completed: false, target: '2000' }]
    expect(strip().find('[data-testid="week-legend"]').exists()).toBe(false)
  })
})
