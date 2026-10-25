import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import DerivedBackfillControl from './DerivedBackfillControl.vue'

const transport = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  failed: vi.fn(),
  writable: true,
}))
vi.mock('@/core/client', () => ({ api: { get: transport.get } }))
vi.mock('@/stores/session', () => ({
  useSession: () => ({
    me: { id: 'owner' },
    can: (scope: string) => scope === 'personal.read' || transport.writable,
  }),
}))
vi.mock('./usePersonal', () => ({
  usePersonal: () => ({
    operate: transport.operate,
    failed: transport.failed,
    unresolved: ref([]),
    describe: vi.fn(),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
const id = 'c'.repeat(64)
const receipt = {
  derivation_run_id: id,
  state: 'queued',
  from: '2026-10-08',
  to: '2026-10-09',
  as_of: '2026-10-09T08:00:00Z',
  scheduled_days: 2,
  results_saved: 0,
  pending: true,
}
const run = (state: 'queued' | 'succeeded' = 'queued') => ({
  run_id: id,
  state,
  from: receipt.from,
  to: receipt.to,
  next_date: state === 'queued' ? '2026-10-09' : '2026-10-10',
  as_of: receipt.as_of,
  time_zone: 'UTC',
  source_scope: null,
  trigger: 'owner_backfill',
  processed_days: state === 'queued' ? 1 : 2,
  scheduled_days: 2,
  results_saved: state === 'queued' ? 3 : 6,
  results_unchanged: 1,
  error: null,
  created_at: receipt.as_of,
  finished_at: state === 'queued' ? null : '2026-10-09T08:01:00Z',
})
function show(extra: { pending?: boolean; from?: string; to?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(DerivedBackfillControl, {
    props: { from: receipt.from, to: receipt.to, timezone: 'UTC', pending: false, ...extra },
    global: {
      plugins: [
        [VueQueryPlugin, { queryClient: client }],
        createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
      ],
      stubs: { UnresolvedWrites: true },
    },
  })
  return {
    wrapper,
    close: () => {
      wrapper.unmount()
      client.clear()
    },
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  transport.writable = true
})
describe('explicit stored-score backfill', () => {
  it('does not calculate on read and distinguishes queued acknowledgement from completed results', async () => {
    transport.operate.mockResolvedValue(receipt)
    transport.get.mockResolvedValueOnce(run()).mockResolvedValueOnce(run('succeeded'))
    const { wrapper, close } = show()
    await flushPromises()
    expect(transport.operate).not.toHaveBeenCalled()
    expect(transport.get).not.toHaveBeenCalled()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Пересчитать и сохранить историю')!
      .trigger('click')
    await flushPromises()
    expect(transport.operate).toHaveBeenCalledExactlyOnceWith('derived.backfill', {
      from: receipt.from,
      to: receipt.to,
      time_zone: 'UTC',
    })
    expect(transport.get).toHaveBeenCalledExactlyOnceWith(
      `/v1/personal/derived-backfills/${id}`,
      expect.any(AbortSignal),
    )
    expect(wrapper.get('[data-testid="derived-backfill-status"]').text()).toContain('В очереди')
    expect(wrapper.text()).toContain(
      'Обработано дней: 1 / 2 · Сохранено результатов: 3 · Без изменений: 1',
    )
    expect(wrapper.text()).not.toContain('Пересчёт завершён')
    expect(wrapper.emitted('completed')).toBeUndefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Проверить выполнение пересчёта')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Пересчёт завершён')
    expect(wrapper.text()).toContain('Обработано дней: 2 / 2 · Сохранено результатов: 6')
    expect(wrapper.emitted('completed')).toHaveLength(1)
    expect(transport.operate).toHaveBeenCalledTimes(1)
    close()
  })
  it('offers no mutation to a read-only owner', async () => {
    transport.writable = false
    const { wrapper, close } = show()
    await flushPromises()
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(transport.operate).not.toHaveBeenCalled()
    expect(transport.get).not.toHaveBeenCalled()
    close()
  })
  it.each([{ pending: true }, { from: '2025-01-01', to: '2026-10-09' }])(
    'does not queue overlapping or oversized backfills %j',
    async (props) => {
      const { wrapper, close } = show(props)
      await flushPromises()
      const button = wrapper
        .findAll('button')
        .find((item) => item.text() === 'Пересчитать и сохранить историю')!
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
      expect(transport.operate).not.toHaveBeenCalled()
      close()
    },
  )
})
