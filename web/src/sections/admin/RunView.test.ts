import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ru from '@/locales/ru.json'
import RunView from './RunView.vue'

const mocks = vi.hoisted(() => ({ run: {} as Record<string, unknown>, send: vi.fn() }))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRoute: () => ({ params: { run: 'managed-run' } }),
}))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { timezone: 'UTC' } }) }))
vi.mock('./useAdmin', () => ({
  notInstalled: () => false,
  useRun: () => ({
    data: ref(mocks.run),
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
  mocks.run = {
    id: 'managed-run',
    goal: 'Проверить проект',
    status: 'running',
    reason: null,
    deadline: '2100-01-01T00:00:00Z',
    workspace: 'project',
    stop_requested: false,
    can_send: true,
    revision: 7,
    attempts: [{ id: 'managed-attempt', state: 'running' }],
    requests: [],
    events: [],
  }
})
afterEach(() => view?.unmount())

function open() {
  view = mount(RunView, {
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ru', messages: { ru } })],
      stubs: { PageHeader: true, RefreshFailed: true, UnresolvedWrites: true },
    },
  })
  return view
}

describe('a managed agent session', () => {
  it.each(['running', 'ready', 'paused', 'cancelled'])(
    'sends a follow-up to the same %s session with its current revision',
    async (status) => {
      mocks.run.status = status
      if (status === 'cancelled') mocks.run.stop_requested = true
      if (status !== 'running') mocks.run.deadline = '2000-01-01T00:00:00Z'
      const wrapper = open()
      const form = wrapper.get('[data-testid="run-followup"] form')
      await form.get('textarea').setValue('  Добавь проверку доступа  ')
      await form.trigger('submit')
      await flushPromises()
      expect(mocks.send).toHaveBeenCalledWith(
        'run.send',
        { run_id: 'managed-run', text: 'Добавь проверку доступа' },
        { 'managed-run': 7 },
        'Уточнение принято к доставке',
      )
      expect((form.get('textarea').element as HTMLTextAreaElement).value).toBe('')
    },
  )

  it('retains a follow-up when delivery is not confirmed', async () => {
    mocks.send.mockResolvedValue(false)
    const wrapper = open()
    const form = wrapper.get('[data-testid="run-followup"] form')
    await form.get('textarea').setValue('Проверь ещё мобильную версию')
    await form.trigger('submit')
    await flushPromises()
    expect((form.get('textarea').element as HTMLTextAreaElement).value).toBe(
      'Проверь ещё мобильную версию',
    )
  })

  it.each([
    { status: 'waiting_input' },
    { status: 'blocked', reason: 'outcome_unknown' },
    { status: 'failed' },
    { stop_requested: true },
    { deadline: '2000-01-01T00:00:00Z' },
    { attempts: [] },
  ])('honors server admission when the session cannot continue (%j)', (changes) => {
    Object.assign(mocks.run, changes, { can_send: false })
    const wrapper = open()
    expect(wrapper.find('[data-testid="run-followup"]').exists()).toBe(false)
    expect(mocks.send).not.toHaveBeenCalled()
  })

  it('keeps the question answer typed and addressed to the outstanding request', async () => {
    mocks.run.status = 'waiting_input'
    mocks.run.can_send = false
    mocks.run.requests = [
      {
        id: 'question-request',
        payload: { questions: [{ id: 'scope', question: 'Что проверить?', options: [] }] },
      },
    ]
    const wrapper = open()
    expect(wrapper.find('[data-testid="run-followup"]').exists()).toBe(false)
    const form = wrapper.get('[data-testid="run-question"] form')
    await form.get('input').setValue('Права доступа')
    await form.trigger('submit')
    await flushPromises()
    expect(mocks.send).toHaveBeenCalledWith(
      'input.answer',
      {
        request_id: 'question-request',
        answer: { answers: { scope: { answers: ['Права доступа'] } } },
      },
      { 'managed-run': 7 },
      'Ответ передан',
    )
  })
})
