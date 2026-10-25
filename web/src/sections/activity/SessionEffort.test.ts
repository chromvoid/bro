import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { ActivitySession } from './api'
import SessionEffort from './SessionEffort.vue'

const transport = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/core/client', () => ({ api: transport }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ can: () => true }) }))

const clients: QueryClient[] = []
const session = (started_at: string | null) =>
  ActivitySession.parse({
    id: 'gym',
    revision: 1,
    kind: 'strength',
    date: '2026-10-14',
    timezone: 'Europe/Moscow',
    started_at,
    duration_minutes: 30,
    distance_km: null,
    exercises: [],
  })
async function screen(started_at: string | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  const wrapper = mount(SessionEffort, {
    props: { session: session(started_at) },
    global: { plugins: [i18n, [VueQueryPlugin, { queryClient: client }]] },
  })
  await flushPromises()
  return wrapper
}
beforeEach(() => transport.get.mockReset())
afterEach(() => clients.splice(0).forEach((client) => client.clear()))

describe('the strap heart of a logged session', () => {
  it('reads the session window and shows the heart rate, zones and load', async () => {
    transport.get.mockResolvedValue({
      effort: {
        source_name: 'WHOOP',
        heart_rate: { mean: 118, max: 138 },
        zone_seconds: [600, 300, 120, 0, 0],
        trimp: '24.6',
        effort: { rest: '52', max: '182.8', basis: 'tanaka' },
      },
    })
    const wrapper = await screen('2026-10-14T13:40:00+03:00')
    expect(transport.get).toHaveBeenCalledWith(
      '/v1/personal/effort?from=2026-10-14T10%3A40%3A00.000Z&to=2026-10-14T11%3A10%3A00.000Z',
      expect.anything(),
    )
    expect(wrapper.text()).toContain('Пульс по браслету')
    expect(wrapper.get('[data-testid="device-workout-heart-rate"]').text()).toBe(
      'Пульс: средний 118, максимум 138 уд/мин',
    )
    expect(wrapper.get('[data-testid="device-workout-load"]').text()).toBe('Нагрузка (TRIMP): 25')
  })

  it('asks nothing for a session without a start and shows nothing without strap data', async () => {
    expect((await screen(null)).html()).not.toContain('session-strap-effort')
    expect(transport.get).not.toHaveBeenCalled()
    transport.get.mockResolvedValue({ effort: null })
    expect(
      (await screen('2026-10-14T13:40:00+03:00'))
        .find('[data-testid="session-strap-effort"]')
        .exists(),
    ).toBe(false)
  })
})
