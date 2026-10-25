import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { i18n } from '@/i18n'
import StatsView from './StatsView.vue'

const day = (date: string, energy: string | null) => ({
  date,
  logged: energy !== null,
  energy_kcal: energy ?? '0',
  energy_complete: true,
  protein_g: '0',
  fat_g: '0',
  carbohydrate_g: '0',
  completed: date === '2026-10-05',
  target: energy === null ? null : '2000',
  macro_targets: null,
  kcal_by_kind: energy === null ? {} : { lunch: energy },
})
vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({
    data: ref({
      days: [
        day('2026-10-05', '1950'),
        day('2026-10-06', '2500'),
        day('2026-10-07', '1500'),
        day('2026-10-08', null),
      ],
    }),
    isPending: ref(false),
    isLoadingError: ref(false),
  }),
}))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { timezone: 'UTC' } }) }))

describe('StatsView', () => {
  it('explains the chart with swatches and states conclusions in words', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { name: 'food-stats', path: '/food/stats', component: StatsView },
        { name: 'food', path: '/food', component: StatsView },
      ],
    })
    await router.push('/food/stats?day=2026-10-05')
    const wrapper = mount(StatsView, {
      global: { plugins: [router, i18n], stubs: { PageHeader: true, RefreshFailed: true } },
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="stats-legend"]').text()).toBe(
      'Лимит на деньНе больше лимитаСверх лимита',
    )
    expect(
      wrapper.findAll('[data-testid="stats-conclusions"] li').map((item) => item.text()),
    ).toEqual([
      'Около лимита (±10 %): 1 из 3 дней',
      'Больше лимита на 10 % и более: 1',
      'Меньше лимита на 10 % и более: 1',
      '1 день из 4 отмечен заполненным',
    ])
    wrapper.unmount()
  })
})
