import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import TimersView from './TimersView.vue'

const mocks = vi.hoisted(() => ({
  push: null as null | { value: string | null },
  timers: [] as unknown[],
  can: true,
}))
vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))
vi.mock('@/stores/session', () => ({ useSession: () => ({ can: () => mocks.can }) }))
vi.mock('@/core/operations', () => ({
  operationKind: () => 'timer.start',
  useLedger: () => ({
    operate: vi.fn(),
    unresolved: ref([]),
    check: vi.fn(),
    resend: vi.fn(),
    abandon: vi.fn(),
  }),
}))
vi.mock('@/core/undo', () => ({ useUndo: () => ({ confirm: vi.fn() }) }))
vi.mock('@/sections/notifications/devicePush', async () => {
  const { ref } = await import('vue')
  return {
    useDevicePush: () => {
      const state = ref(mocks.push?.value ?? null)
      return { state }
    },
  }
})
vi.mock('@tanstack/vue-query', () => ({
  useQuery: () => ({
    data: ref({ timers: mocks.timers }),
    isPending: ref(false),
    isLoadingError: ref(false),
    error: ref(null),
    refetch: vi.fn(),
  }),
}))

const view = (push: string | null) => {
  mocks.push = { value: push }
  return mount(TimersView, {
    global: {
      stubs: {
        PageHeader: { props: ['title'], template: '<h1>{{ title }}</h1>' },
        RemindersPanel: true,
        RefreshFailed: true,
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
}

describe('TimersView', () => {
  it('starts with the new timer when none runs, its empty line inside the form', () => {
    const wrapper = view('on')
    expect(wrapper.get('h1').text()).toBe('tasks.timersTitle')
    expect(wrapper.find('[data-testid="timers"]').exists()).toBe(false)
    expect(wrapper.get('#timers-new').element.parentElement!.textContent).toContain(
      'tasks.timersEmpty',
    )
  })

  it('says what a fired timer does on this device', () => {
    expect(view('on').get('[data-testid="timers-push"]').text()).toBe('tasks.timersPush.on')
    const off = view('off').get('[data-testid="timers-push"]')
    expect(off.text()).toContain('tasks.timersPush.off')
    expect(off.find('a').text()).toBe('tasks.timersPush.enable')
    const unsupported = view('unsupported').get('[data-testid="timers-push"]')
    expect(unsupported.text()).toBe('tasks.timersPush.unsupported')
    expect(unsupported.find('a').exists()).toBe(false)
    expect(view(null).find('[data-testid="timers-push"]').exists()).toBe(false)
  })

  it('names a timer without a label «Таймер»', () => {
    mocks.timers = [{ id: 'a', revision: 1, due_at: '2026-10-08T12:10:00Z', duration_seconds: 600 }]
    const wrapper = view('on')
    expect(wrapper.get('[data-testid="timers"]').text()).toContain('tasks.timer')
    expect(wrapper.find('[data-testid="timers-empty"]').exists()).toBe(false)
    mocks.timers = []
  })
})
