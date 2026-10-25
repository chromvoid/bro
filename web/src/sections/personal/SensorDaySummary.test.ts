import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import SensorDaySummary from './SensorDaySummary.vue'

const state = vi.hoisted(() => ({ daily: [] as Array<Record<string, unknown>> }))
vi.mock('./resolution', async (original) => {
  const { ref } = await import('vue')
  return {
    ...(await original<typeof import('./resolution')>()),
    useResolved: () => ({
      data: ref({ pages: [{ fingerprint: 'synthetic', daily: state.daily }] }),
      isPending: ref(false),
      isLoadingError: ref(false),
      error: ref(null),
      refetch: vi.fn(),
    }),
  }
})

function show() {
  return mount(SensorDaySummary, {
    props: { day: '2026-10-06', timezone: 'Europe/Moscow', today: '2026-10-06' },
    global: {
      plugins: [i18n],
      stubs: {
        RefreshFailed: true,
        DailyResolution: true,
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
}

describe('ring readings of a day', () => {
  it('lists only the metrics the ring measured that day', () => {
    state.daily = [
      { kind: 'heart_rate', value: '74', unit: 'bpm', status: 'available' },
      { kind: 'spo2', value: null, unit: '%', status: 'no_data' },
    ]
    const wrapper = show()
    expect(wrapper.findAll('details')).toHaveLength(1)
    expect(wrapper.text()).toContain('Пульс')
    expect(wrapper.text()).not.toContain('SpO')
  })

  it('has no card on a day the ring reported nothing', () => {
    state.daily = [{ kind: 'spo2', value: null, unit: '%', status: 'no_data' }]
    expect(show().find('[data-testid="sensor-day-summary"]').exists()).toBe(false)
  })
})
