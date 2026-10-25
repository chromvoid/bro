import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import WorkersView from './WorkersView.vue'

const mocks = vi.hoisted(() => ({ send: vi.fn(), setups: [] as Array<Record<string, unknown>> }))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('vue-sonner', () => ({ toast: Object.assign(vi.fn(), { error: vi.fn() }) }))
vi.mock('./useAdmin', () => ({
  notInstalled: () => false,
  useWorkers: () => ({
    data: ref({
      machines: [
        {
          id: 'machine',
          label: 'Домашний сервер',
          enabled: true,
          heartbeat: new Date().toISOString(),
          revision: 1,
          inventory: { workspaces: ['project'] },
        },
      ],
      profiles: [],
      executors: [],
      executor_grants: [],
      runs: [],
      setups: mocks.setups,
    }),
    isPending: ref(false),
    isLoadingError: ref(false),
  }),
}))
vi.mock('./useWorkerOps', () => ({
  useWorkerOps: () => ({ busy: ref(false), unresolved: ref([]), send: mocks.send }),
}))

let view: VueWrapper | undefined
beforeEach(() => {
  vi.resetAllMocks()
  mocks.send.mockResolvedValue(true)
  mocks.setups = []
})
afterEach(() => view?.unmount())

function open() {
  view = mount(WorkersView, {
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      stubs: {
        PageHeader: true,
        RefreshFailed: true,
        UnresolvedWrites: true,
        WorkerGrants: true,
        Panel: true,
      },
    },
  })
  return view
}

describe('Paseo executor enrollment', () => {
  it.each(['codex', 'claude'])(
    'verifies the selected %s provider on the selected machine',
    async (harness) => {
      const wrapper = open()
      const setup = wrapper.get('[data-testid="worker-setup"]')
      await setup.get('[aria-label="Машина"]').setValue('machine')
      await setup.get('[aria-label="Рабочая область"]').setValue('project')
      await setup.get('[aria-label="Агент"]').setValue(harness)
      await setup.get('[aria-label="Имя подключения"]').setValue('native-main')
      await setup.trigger('submit')
      await flushPromises()
      expect(mocks.send).toHaveBeenCalledWith(
        'setup.start',
        { machine_id: 'machine', workspace: 'project', harness, binding: 'native-main' },
        {},
        'Проверка подключения начата',
      )
      expect(wrapper.text()).toContain('войдите в его аккаунт на выбранной машине')
      expect(wrapper.find('input[type="password"]').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('Установить и подключить Codex')
    },
  )

  it.each([
    ['daemon_unavailable', 'Не удалось подключиться к Paseo'],
    ['provider_unavailable', 'Paseo не нашёл Claude'],
    ['identity_unavailable', 'Не удалось подтвердить аккаунт Claude'],
    ['future_reason', 'Подключение не удалось'],
  ])('explains enrollment failure without exposing its reason code (%s)', (reason, message) => {
    mocks.setups = [
      {
        id: 'setup',
        machine_id: 'machine',
        harness: 'claude',
        binding: 'native-claude',
        step: 'verify',
        state: 'failed',
        revision: 1,
        settings: { reason },
      },
    ]
    const wrapper = open()
    expect(wrapper.get('[data-testid="setup-problem"]').text()).toContain(message)
    expect(wrapper.text()).not.toContain(reason)
  })
})
