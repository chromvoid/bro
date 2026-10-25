import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import TodayStepsCard from './TodayStepsCard.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), write: true }))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({ can: (scope: string) => scope !== 'personal.write' || mocks.write }),
}))
const TODAY = '2026-10-06'
const NEXT_DAY = '2026-10-07'
const clients: QueryClient[] = []
const wrappers: Array<{ unmount: () => void }> = []

function report(date = TODAY, steps: Record<string, unknown> = {}) {
  const empty = { status: 'no_data', value: null, unit: 'min' }
  return {
    date,
    timezone: 'Europe/Moscow',
    profile: { user_id: 'synthetic-user', timezone: 'Europe/Moscow', revision: 1 },
    metrics: {
      weight: { ...empty, kind: 'weight', unit: 'kg' },
      sleep: { ...empty, kind: 'sleep' },
      active_minutes: { ...empty, kind: 'active_minutes' },
      steps: { ...empty, kind: 'steps', unit: 'count', ...steps },
    },
    preferences: {},
    sources: [],
  }
}

async function screen() {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(queries)
  const wrapper = mount(TodayStepsCard, {
    props: { today: TODAY, timezone: 'Europe/Moscow' },
    global: {
      plugins: [i18n, [VueQueryPlugin, { queryClient: queries }]],
      stubs: {
        MetricSourcesPanel: {
          props: ['open', 'row', 'day'],
          template:
            '<div v-if="open" data-testid="metric-sources-panel" :data-row="row.key" :data-day="day" />',
        },
        RouterLink: { props: ['to'], template: '<a :data-to="to.name"><slot /></a>' },
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, queries }
}

describe('compact current steps card', () => {
  beforeEach(() => {
    mocks.get.mockReset()
    mocks.write = true
  })
  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    for (const client of clients.splice(0)) client.clear()
  })

  it('reads today independently and clearly labels the date, amount and unit', async () => {
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: '3200' }))
    const { wrapper } = await screen()
    expect(mocks.get).toHaveBeenCalledWith(
      '/v1/personal/overview?date=2026-10-06',
      expect.any(AbortSignal),
    )
    expect(wrapper.get('h2').text()).toBe('Шаги сегодня')
    // The heading says «today»; the date is already in the screen's own header.
    expect(wrapper.text()).not.toContain('6 октября 2026')
    expect(wrapper.get('[data-testid="today-steps-value"]').text().replaceAll('\u00a0', ' ')).toBe(
      '3 200 шагов',
    )
  })

  it('shows measured zero without inventing freshness or a goal', async () => {
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: 0 }))
    const { wrapper } = await screen()
    expect(wrapper.get('[data-testid="today-steps-value"]').text()).toBe('0 шагов')
    expect(wrapper.find('[data-testid="today-steps-status"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="today-steps-goal"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Измерено:')
  })

  it('shows no data instead of a fabricated zero', async () => {
    mocks.get.mockResolvedValue(report())
    const { wrapper } = await screen()
    expect(wrapper.find('[data-testid="today-steps-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="today-steps-status"]').text()).toBe('Нет данных')
  })

  it('states a conflicting decision without its amount and without a choice', async () => {
    mocks.get.mockResolvedValue(
      report(TODAY, {
        status: 'conflict',
        value: '450',
        resolution: {
          status: 'conflict',
          fingerprint: 'synthetic-fingerprint',
          resolver_version: 'personal-resolution-v1',
        },
      }),
    )
    const { wrapper } = await screen()
    expect(wrapper.find('[data-testid="today-steps-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="today-steps-status"]').attributes('data-status')).toBe(
      'conflict',
    )
    // The same words as the day's tile in «Активность».
    expect(wrapper.get('[data-testid="today-steps-status"]').text()).toBe(
      'Показания не согласованы',
    )
    expect(wrapper.find('[data-testid="resolution-explanation"]').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it.each([null, '0', '-1', '8000'])('only shows a supplied positive goal (%s)', async (target) => {
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: '350', target }))
    const { wrapper } = await screen()
    expect(wrapper.find('[data-testid="today-steps-goal"]').exists()).toBe(target === '8000')
  })

  it('uses supplied measurement time in the account zone and leaves protocol detail to Activity', async () => {
    mocks.get.mockResolvedValue(
      report(TODAY, {
        status: 'available',
        value: '350',
        resolution: {
          status: 'partial',
          fingerprint: 'synthetic-fingerprint',
          resolver_version: 'personal-resolution-v1',
          selected: [{ id: 'synthetic-fact', contribution: '350' }],
          protocol: { verified: false, flags: ['protocol_unverified'] },
          freshness: { measured_at: '2026-10-06T16:10:00Z' },
        },
      }),
    )
    const { wrapper } = await screen()
    expect(wrapper.text()).toContain('Измерено: сегодня, 19:10')
    // How the total was resolved is read in Activity's day details.
    expect(wrapper.find('[data-testid="resolution-explanation"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Как получен результат')
    expect(wrapper.text()).not.toContain('Формат протокола')
    const link = wrapper.get('[data-testid="today-steps-activity"]')
    expect(link.attributes('data-to')).toBe('activity')
  })

  it('changes the query at midnight and hides the previous amount until new data arrives', async () => {
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: '3200' }))
    const { wrapper } = await screen()
    let finish: (value: ReturnType<typeof report>) => void = () => {}
    mocks.get.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    await wrapper.setProps({ today: NEXT_DAY })
    await flushPromises()
    expect(mocks.get).toHaveBeenLastCalledWith(
      '/v1/personal/overview?date=2026-10-07',
      expect.any(AbortSignal),
    )
    expect(wrapper.find('[data-testid="today-steps-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-state="loading"]').exists()).toBe(true)
    finish(report(NEXT_DAY, { status: 'available', value: 0 }))
    await flushPromises()
    expect(wrapper.get('[data-testid="today-steps-value"]').text()).toBe('0 шагов')
  })

  it('rejects a response for an earlier day even if that response contains steps', async () => {
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: '3200' }))
    const { wrapper } = await screen()
    await wrapper.setProps({ today: NEXT_DAY })
    await flushPromises()
    expect(wrapper.find('[data-testid="today-steps-value"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="today-steps-status"]').text()).toBe('Нет данных')
  })

  it('offers a compact retry after an initial read error', async () => {
    mocks.get.mockRejectedValue(new Error('synthetic read failure'))
    const { wrapper } = await screen()
    expect(wrapper.find('[data-state="error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="today-steps-value"]').exists()).toBe(false)
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: '350' }))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="today-steps-value"]').text()).toBe('350 шагов')
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
  })

  it('retains the loaded current amount with a warning after refresh failure', async () => {
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: '350' }))
    const { wrapper, queries } = await screen()
    mocks.get.mockRejectedValue(new Error('synthetic refresh failure'))
    await queries.invalidateQueries({ queryKey: ['personal', 'overview', TODAY] })
    await flushPromises()
    expect(wrapper.get('[data-testid="today-steps-value"]').text()).toBe('350 шагов')
    expect(wrapper.find('[data-state="refresh-failed"]').exists()).toBe(true)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
  })

  it('does not reuse yesterday cached amount after the next day read fails', async () => {
    mocks.get.mockResolvedValue(report(TODAY, { status: 'available', value: '3200' }))
    const { wrapper } = await screen()
    mocks.get.mockRejectedValue(new Error('synthetic next day failure'))
    await wrapper.setProps({ today: NEXT_DAY })
    await flushPromises()
    expect(wrapper.find('[data-testid="today-steps-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-state="error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('3\u00a0200')
  })

  it("names the devices behind today's steps and opens where they come from", async () => {
    mocks.get.mockImplementation(async (path: string) =>
      path.startsWith('/v1/personal/resolved?')
        ? {
            from: TODAY,
            to: TODAY,
            timezone: 'Europe/Moscow',
            computed_at: `${TODAY}T10:00:00Z`,
            daily: [],
            series: [],
            next_cursor: null,
            fingerprint: 'f',
          }
        : path === '/v1/personal/sources'
          ? {
              sources: [
                {
                  id: 'ring',
                  name: 'R20_5AF2',
                  kinds: [],
                  status: 'connected',
                  revision: 1,
                  metric_keys: ['steps'],
                  device: { kind: 'ring' },
                },
              ],
            }
          : report(TODAY, {
              status: 'available',
              value: '3200',
              resolution: {
                status: 'available',
                selected: [],
                alternatives: [],
                fingerprint: 'f',
                resolver_version: '1',
                contributions: [{ source_id: 'ring', count: 12, value: '3200', spans: [] }],
              },
            }),
    )
    const { wrapper } = await screen()
    // The line appears with the day's decision and then reads the device names.
    await flushPromises()
    const line = wrapper.get('[data-testid="source-line"]')
    expect(line.text()).toBe('Источник: кольцо')
    await line.trigger('click')
    await flushPromises()
    const panel = wrapper.get('[data-testid="metric-sources-panel"]')
    expect(panel.attributes('data-row')).toBe('steps')
    expect(panel.attributes('data-day')).toBe(TODAY)
  })
})
