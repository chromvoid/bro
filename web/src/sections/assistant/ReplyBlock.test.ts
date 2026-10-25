import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref, type Ref } from 'vue'
import { i18n } from '@/i18n'
import { toast } from 'vue-sonner'
import { speechPlayback, type PlaybackState } from '@/speech/playback'
import { conversationBusy, revealedReply } from './busy'
import { toExchange } from './model'
import type { Reply } from './model'
import ReplyBlock from './ReplyBlock.vue'

vi.mock('@/speech/playback', async () => {
  const { ref } = await import('vue')
  return {
    speechPlayback: {
      state: ref({ key: null, status: 'idle', problem: null }),
      stop: vi.fn(),
      turn: vi.fn(),
    },
  }
})
const developer = vi.hoisted(() => ({ enabled: false }))
vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/core/developer', async () => {
  const { computed } = await import('vue')
  return { useDeveloperMode: () => computed(() => developer.enabled) }
})
const state = speechPlayback.state as Ref<PlaybackState>
const reply = toExchange({
  id: 'message',
  created_at: '2026-10-03T09:00:00Z',
  text: 'Вопрос',
  state: 'completed',
  turns: [{ id: 'answer', state: 'completed', result: { reply: 'Синтетический ответ' } }],
}).replies[0]!

const screens: VueWrapper[] = []
function screen(answer: Reply = reply, extra: { latest?: boolean } = {}) {
  const wrapper = mount(ReplyBlock, {
    attachTo: document.body,
    props: { reply: answer, ...extra },
    global: {
      plugins: [i18n],
      stubs: { SourcesSheet: true, TurnDetails: true, RouterLink: true },
    },
  })
  screens.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  developer.enabled = false
  state.value = { key: null, status: 'idle', problem: null }
})
afterEach(() => {
  screens.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

async function menu(wrapper: VueWrapper) {
  await wrapper.get('[data-testid="reply-menu"]').trigger('keydown', { key: 'ArrowDown' })
  await flushPromises()
  return new DOMWrapper(document.body.querySelector('[role="menu"]')!)
}

describe('answer menu', () => {
  it('opens with the keyboard, copies the original Markdown, and reports one success', async () => {
    const markdown = '**Ответ**\n\n- [Источник](https://example.org/a)\n- `текст`'
    const write = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    const wrapper = screen({ ...reply, text: markdown, messageId: 'reply' })
    expect(wrapper.get('[data-testid="reply"]').classes()).not.toContain('border')
    expect(wrapper.get('[data-testid="reply-actions"]').findAll('button')).toHaveLength(3)
    expect(wrapper.text()).not.toContain('Голос ИИ')
    const actions = await menu(wrapper)
    expect(actions.find('[data-testid="answer-reply"]').exists()).toBe(true)
    expect(actions.find('[data-testid="answer-details"]').exists()).toBe(false)
    await actions.get('[data-testid="copy-answer"]').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(write).toHaveBeenCalledExactlyOnceWith(markdown)
    expect(toast.success).toHaveBeenCalledExactlyOnceWith('Ответ скопирован')
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('reports a clipboard refusal once without claiming success', async () => {
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('Clipboard denied'))
    const actions = await menu(screen())
    await actions.get('[data-testid="copy-answer"]').trigger('click')
    await flushPromises()
    expect(toast.error).toHaveBeenCalledExactlyOnceWith(
      'Не удалось скопировать ответ. Выделите текст вручную.',
    )
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('ignores a second copy selection while the first clipboard write is pending', async () => {
    let finish!: () => void
    const write = vi
      .spyOn(navigator.clipboard, 'writeText')
      .mockImplementation(() => new Promise<void>((resolve) => (finish = resolve)))
    const actions = await menu(screen())
    const copy = actions.get('[data-testid="copy-answer"]')
    await copy.trigger('click')
    await copy.trigger('click')
    expect(write).toHaveBeenCalledOnce()
    finish()
    await flushPromises()
    expect(toast.success).toHaveBeenCalledOnce()
  })

  it('offers only valid web sources and opens the source sheet from the menu', async () => {
    const wrapper = screen({
      ...reply,
      sources: [
        { url: 'https://example.org/a', title: 'Источник' },
        { url: 'javascript:alert(1)' },
        { url: 'https://user:secret@example.org/private' },
        { url: '/v1/private' },
      ],
    })
    const actions = await menu(wrapper)
    expect(actions.get('[data-testid="answer-sources"]').text()).toBe('Источники · 1')
    await actions.get('[data-testid="answer-sources"]').trigger('click')
    await flushPromises()
    const sheet = wrapper.getComponent({ name: 'SourcesSheet' })
    expect(sheet.props('open')).toBe(true)
    expect(sheet.props('sources')).toEqual([{ url: 'https://example.org/a', title: 'Источник' }])
  })

  it('does not offer sources when every address is invalid', async () => {
    const actions = await menu(screen({ ...reply, sources: [{ url: 'data:text/html,test' }] }))
    expect(actions.find('[data-testid="answer-sources"]').exists()).toBe(false)
  })

  it('keeps replying bound to the answer and diagnostics available only in developer mode', async () => {
    developer.enabled = true
    const answer = { ...reply, messageId: 'specific-reply' }
    const wrapper = screen(answer)
    let actions = await menu(wrapper)
    await actions.get('[data-testid="answer-reply"]').trigger('click')
    expect(wrapper.emitted('answer')).toEqual([[answer]])
    actions = await menu(wrapper)
    await actions.get('[data-testid="answer-details"]').trigger('click')
    await flushPromises()
    expect(wrapper.getComponent({ name: 'TurnDetails' }).props('open')).toBe(true)
  })
})

describe('answer playback controls', () => {
  it('keeps its answer playing when conversation history is unmounted', () => {
    state.value = { key: 'turn:answer', status: 'playing', problem: null }
    const wrapper = screen()
    expect(wrapper.get('[data-testid="speech-toggle"]').text()).toBe('Остановить')
    wrapper.unmount()
    expect(speechPlayback.stop).not.toHaveBeenCalled()
  })

  it('recognizes its ongoing answer after returning and stops only on an explicit click', async () => {
    state.value = { key: 'turn:answer', status: 'loading', problem: null }
    const wrapper = screen()
    await wrapper.get('[data-testid="speech-toggle"]').trigger('click')
    expect(speechPlayback.stop).toHaveBeenCalledOnce()
    expect(speechPlayback.turn).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('actions while the conversation is busy', () => {
  it('offers failed model text in details without adding service receipt facts', () => {
    const text = 'Завтрак сохранён'
    const failed = toExchange({
      id: 'message',
      created_at: '2026-10-03T09:00:00Z',
      state: 'failed',
      turns: [
        {
          id: 'failed',
          state: 'failed',
          result: {
            reply: text,
            services: [
              { service: 'nutrition', receipt_text: text, receipts: [{ operation_id: 'meal' }] },
            ],
          },
        },
      ],
    }).replies[0]!
    const wrapper = mount(ReplyBlock, {
      props: { reply: failed, collapseFailedText: true },
      global: {
        plugins: [i18n],
        stubs: { SourcesSheet: true, TurnDetails: true, RouterLink: true },
      },
    })
    const details = wrapper.get('[data-testid="failed-answer-details"]')
    expect(details.get('summary').text()).toBe('Подробности')
    expect(details.attributes('open')).toBeUndefined()
    expect(details.text()).toContain(text)
    expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === 'Подробности')).toBe(false)
  })

  it('waits to confirm or decline until the conversation is free', async () => {
    const proposal = toExchange({
      id: 'message',
      created_at: '2026-10-03T09:00:00Z',
      text: 'Запиши обед',
      state: 'completed',
      turns: [
        {
          id: 'proposal',
          reply_message_id: 'reply',
          state: 'completed',
          result: {
            reply: 'Модель спрашивает: записать обед?',
            question: 'Служебный вопрос: подтвердить запись?',
            confirmation_pending: true,
            proposal_status: 'open',
          },
        },
      ],
    }).replies[0]!
    const busy = ref(true)
    const wrapper = mount(ReplyBlock, {
      props: { reply: proposal },
      global: {
        plugins: [i18n],
        provide: { [conversationBusy as symbol]: busy },
        stubs: { SourcesSheet: true, TurnDetails: true, RouterLink: true },
      },
    })
    const actions = () =>
      wrapper
        .get('[data-testid="question"]')
        .findAll('button')
        .map((button) => (button.element as HTMLButtonElement).disabled)
    expect(wrapper.text()).toContain('Модель спрашивает: записать обед?')
    expect(wrapper.text()).not.toContain('Служебный вопрос: подтвердить запись?')
    expect(actions()).toEqual([true, true])
    busy.value = false
    await nextTick()
    expect(actions()).toEqual([false, false])
    await wrapper.get('[data-testid="question"] button').trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[proposal, true]])
    const modelProposal = { ...proposal, question: null }
    await wrapper.setProps({ reply: modelProposal })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === i18n.global.t('assistant.confirm'))!
      .trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([
      [proposal, true],
      [modelProposal, true],
    ])
  })

  it.each([
    { name: 'empty model answer', change: { text: '' } },
    { name: 'whitespace model answer', change: { text: ' \n ' } },
    { name: 'failed model answer', change: { responseFailed: true, question: null } },
    { name: 'unknown outcome', change: { unknownOutcome: true, question: null } },
    { name: 'incomplete answer', change: { completed: false } },
    { name: 'collapsed answer', change: {}, collapsed: true },
  ])('blocks accepting a proposal with $name while keeping decline available', async (testCase) => {
    const proposal: Reply = {
      ...reply,
      text: 'Установить цель 2000 ккал?',
      question: 'Raw service proposal question',
      confirmationPending: true,
      proposalStatus: 'open',
      ...testCase.change,
    }
    const wrapper = screen(proposal)
    if (testCase.collapsed) await wrapper.setProps({ collapseFailedText: true })
    const accept = wrapper
      .findAll('button')
      .find((button) => button.text() === i18n.global.t('assistant.confirm'))!
    const decline = wrapper
      .findAll('button')
      .find((button) => button.text() === i18n.global.t('assistant.decline'))!
    expect((accept.element as HTMLButtonElement).disabled).toBe(true)
    expect((decline.element as HTMLButtonElement).disabled).toBe(false)
    expect(wrapper.text()).not.toContain('Raw service proposal question')
    await accept.trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await decline.trigger('click')
    expect(wrapper.emitted('confirm')).toEqual([[proposal, false]])
    if (proposal.unknownOutcome)
      expect(wrapper.text()).toContain(i18n.global.t('assistant.unknownTurn'))
  })
})

describe('model answer presentation', () => {
  it.each([
    'Записано: синтетический йогурт — 90 ккал',
    'Записал ужин: йогурт и яблоко — **180 ккал**.',
  ])(
    'shows only the model answer without service confirmations or object actions: %s',
    (answer) => {
      const text = 'Записано: синтетический йогурт — 90 ккал'
      const recorded = toExchange({
        id: 'message',
        created_at: '2026-10-03T09:00:00Z',
        state: 'completed',
        turns: [
          {
            id: 'answer',
            state: 'completed',
            result: {
              reply: answer,
              services: [
                {
                  service: 'nutrition',
                  status: 'completed',
                  receipt_text: text,
                  receipts: [{ operation_id: 'operation', kind: 'entry.add_many' }],
                  effects: [
                    {
                      operation_ref: 'operation',
                      kind: 'entry.add_many',
                      entities: [
                        {
                          kind: 'entry',
                          id: 'entry',
                          label: 'Raw catalog food label',
                          date: '2026-10-03',
                          revision: 1,
                        },
                      ],
                    },
                  ],
                },
              ],
            },
          },
        ],
      }).replies[0]!
      const wrapper = mount(ReplyBlock, {
        props: { reply: recorded },
        global: {
          plugins: [i18n],
          stubs: {
            SourcesSheet: true,
            TurnDetails: true,
            RouterLink: { props: ['to'], template: '<a data-testid="recorded-open"><slot /></a>' },
          },
        },
      })
      expect(recorded.recorded[0]?.receipts).toEqual([
        { operation_id: 'operation', kind: 'entry.add_many' },
      ])
      expect(recorded.committed).toBe(true)
      expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="recorded-actions"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="recorded-open"]').exists()).toBe(false)
      expect(wrapper.find('recorded-undo-stub').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('Raw catalog food label')
      if (answer !== text) {
        expect(wrapper.text()).not.toContain(text)
        expect(wrapper.get('strong').text()).toBe('180 ккал')
      } else expect(wrapper.text().split(text)).toHaveLength(2)
      wrapper.unmount()
    },
  )

  it('leaves service refusal and research explanations to the model answer', () => {
    const wrapper = screen({
      ...reply,
      text: 'Количество исправил. Для задачи нужен другой список.',
      refusals: ['Service-generated refusal'],
      researchStatus: 'insufficient_evidence',
    })
    expect(wrapper.text()).toContain('Количество исправил. Для задачи нужен другой список.')
    expect(wrapper.text()).not.toContain('Service-generated refusal')
    expect(wrapper.find('.bg-warning-surface').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Сведений мало')
  })

  it('uses a tool question only for the reply control while showing the model question', async () => {
    const answer = {
      ...reply,
      text: 'Сколько граммов было в порции?',
      question: 'Service-generated question',
    }
    const wrapper = screen(answer)
    expect(wrapper.text()).toContain('Сколько граммов было в порции?')
    expect(wrapper.text()).not.toContain('Service-generated question')
    const action = wrapper.get('[data-testid="question"] button')
    expect(action.text()).toBe(i18n.global.t('assistant.reply'))
    await action.trigger('click')
    expect(wrapper.emitted('answer')).toEqual([[answer]])
  })

  it('leaves account-login instructions to the model answer', () => {
    const wrapper = screen({
      ...reply,
      text: 'Продолжите подключение на странице аккаунтов.',
      accountLogins: ['login-job'],
    })
    expect(wrapper.text()).toContain('Продолжите подключение на странице аккаунтов.')
    expect(wrapper.text()).not.toContain(i18n.global.t('assistant.openAccounts'))
    expect(wrapper.find('router-link-stub').exists()).toBe(false)
  })
})

describe('answer actions in a long conversation', () => {
  const hidden = ['pointer-fine:opacity-0', 'pointer-coarse:absolute', 'pointer-coarse:opacity-0']

  it('shows the newest answer’s actions and keeps earlier ones for a hover or a long press', () => {
    const latest = screen(reply, { latest: true })
    expect(latest.get('[data-testid="reply-actions"]').classes()).not.toEqual(
      expect.arrayContaining(hidden),
    )
    const earlier = screen()
    const row = earlier.get('[data-testid="reply-actions"]')
    expect(row.classes()).toEqual(expect.arrayContaining(hidden))
    // Hidden from sight only: a screen reader and the keyboard still reach them.
    expect(row.get('[data-testid="speech-toggle"]').text()).toBe(
      i18n.global.t('speechPlayback.play'),
    )
    expect(row.get('[data-testid="copy-inline"]').text()).toBe(i18n.global.t('assistant.copy'))
  })

  it('shows an earlier answer’s actions on a tap on a touch screen, one answer at a time', async () => {
    const revealed = ref<string | null>(null)
    const open = (answer: Reply) => {
      const wrapper = mount(ReplyBlock, {
        attachTo: document.body,
        props: { reply: answer },
        global: {
          plugins: [i18n],
          stubs: { SourcesSheet: true, TurnDetails: true, RouterLink: true },
          provide: { [revealedReply as symbol]: revealed },
        },
      })
      screens.push(wrapper)
      return wrapper
    }
    const first = open(reply)
    const second = open({ ...reply, turnId: 'other' })
    const row = (wrapper: VueWrapper) => wrapper.get('[data-testid="reply-actions"]').classes()
    async function tap(wrapper: VueWrapper, pointerType = 'touch', target = 'reply-text') {
      const element = wrapper.get(`[data-testid="${target}"]`)
      await element.trigger('pointerdown', { pointerType })
      await element.trigger('pointerup', { pointerType })
      await element.trigger('click')
    }
    // A mouse has the hover for that.
    await tap(first, 'mouse')
    expect(row(first)).toEqual(expect.arrayContaining(hidden))
    await tap(first)
    expect(row(first)).not.toEqual(expect.arrayContaining(hidden))
    await tap(second)
    expect(row(second)).not.toEqual(expect.arrayContaining(hidden))
    expect(row(first)).toEqual(expect.arrayContaining(hidden))
    // A control acts on its own and leaves the actions shown; another tap on the answer hides them.
    await tap(second, 'touch', 'speech-toggle')
    expect(speechPlayback.turn).toHaveBeenCalledExactlyOnceWith('other')
    expect(row(second)).not.toEqual(expect.arrayContaining(hidden))
    await tap(second)
    expect(row(second)).toEqual(expect.arrayContaining(hidden))
    expect(revealed.value).toBeNull()
  })

  it('keeps an earlier answer’s actions in sight while it is being spoken', () => {
    state.value = { key: 'turn:answer', status: 'playing', problem: null }
    const row = screen().get('[data-testid="reply-actions"]')
    expect(row.classes()).not.toEqual(expect.arrayContaining(hidden))
  })

  it('opens the menu after a long press on a touch screen, and not when the finger scrolls', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const text = screen().get('[data-testid="reply-text"]')
    await text.trigger('pointerdown', { pointerType: 'touch', clientX: 10, clientY: 10 })
    await text.trigger('pointermove', { pointerType: 'touch', clientX: 10, clientY: 40 })
    vi.advanceTimersByTime(600)
    await text.trigger('pointerdown', { pointerType: 'mouse', clientX: 10, clientY: 10 })
    vi.advanceTimersByTime(600)
    vi.useRealTimers()
    await flushPromises()
    expect(document.body.querySelector('[role="menu"]')).toBeNull()

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await text.trigger('pointerdown', { pointerType: 'touch', clientX: 10, clientY: 10 })
    vi.advanceTimersByTime(450)
    vi.useRealTimers()
    await flushPromises()
    const menu = document.body.querySelector('[role="menu"]')
    expect(menu?.querySelector('[data-testid="copy-answer"]')).not.toBeNull()
    expect(menu?.querySelector('[data-testid="answer-speech"]')?.textContent).toContain(
      i18n.global.t('speechPlayback.play'),
    )
    // The tap that ends the press does not act on what is under the finger.
    const first = new MouseEvent('click', { bubbles: true, cancelable: true })
    text.element.dispatchEvent(first)
    expect(first.defaultPrevented).toBe(true)
    const next = new MouseEvent('click', { bubbles: true, cancelable: true })
    text.element.dispatchEvent(next)
    expect(next.defaultPrevented).toBe(false)
  })

  it('takes a touch screen’s context menu and leaves a mouse the browser’s own', async () => {
    const text = screen().get('[data-testid="reply-text"]')
    await text.trigger('pointerdown', { pointerType: 'mouse' })
    const mouse = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    text.element.dispatchEvent(mouse)
    expect(mouse.defaultPrevented).toBe(false)
    await text.trigger('pointerdown', { pointerType: 'touch' })
    await text.trigger('pointerup', { pointerType: 'touch' })
    const touch = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    text.element.dispatchEvent(touch)
    expect(touch.defaultPrevented).toBe(true)
    await flushPromises()
    expect(document.body.querySelector('[role="menu"]')).not.toBeNull()
  })
})
