import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { exchangeActions } from './busy'
import ExchangeItem from './ExchangeItem.vue'
import ReplyBlock from './ReplyBlock.vue'
import { toExchange, type Message } from './model'

vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({
    t: (key: string) => key,
    te: (key: string) => key.startsWith('assistant.failure.model_'),
  }),
}))
// Technical details are shown in developer mode; these tests check what it shows.
vi.mock('@/core/developer', async () => {
  const { computed } = await import('vue')
  return { useDeveloperMode: () => computed(() => true) }
})

const base: Message = {
  id: 'message',
  created_at: '2026-10-01T09:00:00Z',
  text: 'Запиши завтрак',
  state: 'failed',
  error: 'model_unavailable',
}
const global = {
  stubs: {
    Button: { template: '<button><slot /></button>' },
    SourcesSheet: true,
    TurnDetails: true,
    RouterLink: { props: ['to'], template: '<a><slot /></a>' },
  },
}

/** The conversation's shared action state, as AssistantView provides it. */
function actions(busy: boolean) {
  const state = { busy: ref(busy), controlling: ref(new Set<string>()) }
  return { state, global: { ...global, provide: { [exchangeActions as symbol]: state } } }
}

describe('conversation result presentation', () => {
  it('keeps the time inside the message and shows actions only for the newest answer', () => {
    const exchange = toExchange({
      ...base,
      state: 'completed',
      error: null,
      turns: [{ id: 'turn', state: 'completed', result: { reply: 'Ответ' } }],
    })
    const wrapper = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC', latestReply: 'turn' },
      global,
    })
    const bubble = wrapper.get('.bg-accent')
    expect(bubble.get('time').attributes('datetime')).toBe(exchange.createdAt)
    expect(wrapper.getComponent(ReplyBlock).props('latest')).toBe(true)
    wrapper.unmount()
    const earlier = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC', latestReply: 'another' },
      global,
    })
    expect(earlier.getComponent(ReplyBlock).props('latest')).toBe(false)
    earlier.unmount()
  })

  it('leaves the stop of a running request to the composer', () => {
    const exchange = toExchange({
      ...base,
      state: 'processing',
      error: null,
      run_generation: crypto.randomUUID(),
      can_clarify: true,
    })
    const wrapper = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC' },
      global: { ...global, stubs: { ...global.stubs, ActivityPanel: true } },
    })
    expect(wrapper.find('activity-panel-stub').exists()).toBe(true)
    expect(wrapper.findAll('button')).toHaveLength(0)
    wrapper.unmount()
  })

  it('shows a queued message unsent, with sending it now, editing and removing beside it', async () => {
    const exchange = toExchange({
      ...base,
      state: 'queued',
      error: null,
      run_generation: crypto.randomUUID(),
    })
    const stubs = { ...global.stubs, ActivityPanel: true }
    const wrapper = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC', movable: true },
      global: { ...global, stubs },
    })
    const bubble = wrapper.get('[data-queued]')
    expect(bubble.classes()).toContain('border-dashed')
    expect(bubble.classes()).not.toContain('bg-accent')
    expect(bubble.find('time').exists()).toBe(false)
    expect(bubble.text()).toContain('assistant.controls.queued')
    // The bubble says it waits: no progress line under it.
    expect(wrapper.find('activity-panel-stub').exists()).toBe(false)
    const [now, edit, remove] = wrapper.get('[data-testid="queued-actions"]').findAll('button')
    expect(now!.text()).toBe('assistant.controls.sendNow')
    expect(edit!.attributes('aria-label')).toBe('assistant.controls.editQueued')
    expect(remove!.attributes('aria-label')).toBe('assistant.controls.withdraw')
    await now!.trigger('click')
    await edit!.trigger('click')
    await remove!.trigger('click')
    expect(wrapper.emitted('sendNow')).toEqual([[exchange]])
    expect(wrapper.emitted('editQueued')).toEqual([[exchange]])
    expect(wrapper.emitted('control')).toEqual([[exchange, 'withdraw']])
    // A photo, a reply or no running request to take it: it waits its turn.
    await wrapper.setProps({ movable: false })
    expect(wrapper.find('[data-testid="send-now"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="queued-actions"]').findAll('button')).toHaveLength(2)
    wrapper.unmount()
    const started = mount(ExchangeItem, {
      props: { exchange: { ...exchange, queued: false }, zone: 'UTC', movable: true },
      global: { ...global, stubs },
    })
    expect(started.find('[data-queued]').exists()).toBe(false)
    expect(started.find('[data-testid="queued-actions"]').exists()).toBe(false)
    expect(started.get('.bg-accent').find('time').exists()).toBe(true)
    started.unmount()
  })

  it('restores a proven-safe photo request and replaces its recovery action with durable history', async () => {
    const exchange = toExchange({
      ...base,
      text: '',
      images: [{ id: 'meal-photo', width: 600, height: 400, url: '/v1/images/meal-photo' }],
      turns: [
        {
          id: 'failure',
          state: 'failed',
          result: {
            execution: { effects: 'none', completion: 'failed', safe_to_resubmit: true },
          },
        },
      ],
    })
    const shared = actions(true)
    const wrapper = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC' },
      global: shared.global,
    })
    const button = wrapper.findAll('button').find((item) => item.text() === 'assistant.editAgain')!
    expect(button.attributes('disabled')).toBeDefined()
    shared.state.busy.value = false
    await nextTick()
    await button.trigger('click')
    expect(wrapper.emitted('restore')).toEqual([[exchange]])
    expect(wrapper.emitted('check')).toBeUndefined()
    expect(wrapper.emitted('confirm')).toBeUndefined()
    await wrapper.setProps({ recovered: true })
    expect(wrapper.find('[data-testid="failed"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="recovered-failure"]').text()).toContain(
      'assistant.recoveredFailure',
    )
    expect(wrapper.findAll('button').some((item) => item.text() === 'assistant.editAgain')).toBe(
      false,
    )
    wrapper.unmount()
    const reloaded = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC', recovered: true },
      global,
    })
    expect(reloaded.find('[data-testid="failed"]').exists()).toBe(false)
    expect(reloaded.find('[data-testid="recovered-failure"]').exists()).toBe(true)
  })

  it('shows the small preview in its final box, and the stored photo when there is none', () => {
    const exchange = toExchange({
      ...base,
      state: 'completed',
      error: null,
      text: '',
      images: [
        {
          id: 'new',
          width: 1600,
          height: 1200,
          url: '/v1/conversation/images/new',
          preview_url: '/v1/conversation/images/new/preview?v=1',
        },
        { id: 'old', width: 600, height: 400, url: '/v1/conversation/images/old' },
      ],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
    const [preview, original] = wrapper.findAll('img')
    expect(preview!.attributes()).toMatchObject({
      src: '/v1/conversation/images/new/preview?v=1',
      width: '320',
      height: '240',
      loading: 'lazy',
      decoding: 'async',
    })
    // The placeholder is a class: the CSP allows neither inline styles nor data: images.
    expect(preview!.classes()).toContain('bg-muted')
    expect(preview!.attributes('style')).toBeUndefined()
    expect(original!.attributes()).toMatchObject({
      src: '/v1/conversation/images/old',
      width: '360',
      height: '240',
    })
    wrapper.unmount()
  })

  it('keeps partial-effect safeguards without appending service facts to failed model text', () => {
    const receipt = 'Йогурт 150 г — 90 ккал'
    const exchange = toExchange({
      ...base,
      turns: [
        {
          id: 'turn',
          state: 'failed',
          result: {
            reply: `${receipt}. Но задачу создать не удалось.`,
            execution: { effects: 'confirmed', completion: 'partial', safe_to_resubmit: false },
            services: [
              { service: 'nutrition', receipt_text: receipt, receipts: [{ operation_id: 'meal' }] },
              { service: 'tasks', status: 'rejected', message: 'Список недоступен' },
            ],
          },
        },
      ],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
    const details = wrapper.get('[data-testid="failed-answer-details"]')
    expect(details.element.tagName).toBe('DETAILS')
    expect(details.attributes('open')).toBeUndefined()
    expect(details.get('summary').text()).toBe('assistant.details')
    expect(details.text()).toContain('Но задачу создать не удалось.')
    expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
    expect(details.find('[data-testid="recorded-result"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="failed"]').text()).toContain('assistant.partialResult')
    expect(wrapper.get('[data-testid="failed"]').text()).toContain('assistant.confirmedChanges')
    expect(wrapper.get('[data-testid="failed"]').text()).not.toContain('assistant.noChanges')
    expect(wrapper.text()).not.toContain('assistant.editAgain')
  })

  it('reports confirmed effects and an unknown outcome without offering whole-request recovery', () => {
    const exchange = toExchange({
      ...base,
      turns: [
        {
          id: 'turn',
          state: 'failed',
          result: {
            execution: { effects: 'unknown', completion: 'partial', safe_to_resubmit: true },
            services: [
              {
                service: 'nutrition',
                receipt_text: 'Завтрак сохранён',
                receipts: [{ operation_id: 'meal' }],
              },
              { service: 'tasks', status: 'unknown' },
            ],
          },
        },
      ],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
    expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Завтрак сохранён')
    const status = wrapper.get('[data-testid="failed"]').text()
    expect(status).toContain('assistant.confirmedChanges')
    expect(status).toContain('assistant.resultUnknown')
    expect(status).not.toContain('assistant.noChanges')
    expect(wrapper.text()).toContain('assistant.pendingCheck')
    expect(wrapper.text()).not.toContain('assistant.editAgain')
  })

  it('does not infer an unchanged outcome or recovery permission for a legacy failure', () => {
    const wrapper = mount(ExchangeItem, {
      props: { exchange: toExchange(base), zone: 'UTC' },
      global,
    })
    expect(wrapper.find('[data-testid="failed"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('assistant.noChanges')
    expect(wrapper.text()).not.toContain('assistant.editAgain')
  })

  it('anchors and highlights only the selected assistant reply', () => {
    const exchange = toExchange({
      ...base,
      state: 'completed',
      error: null,
      turns: [
        {
          id: 'first-turn',
          reply_message_id: 'first-reply',
          state: 'completed',
          result: { reply: 'Первый ответ' },
        },
        {
          id: 'second-turn',
          reply_message_id: 'second-reply',
          state: 'completed',
          result: { reply: 'Найденный ответ' },
        },
      ],
    })
    const wrapper = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC', highlightedReplyId: 'second-reply' },
      global,
    })
    expect(wrapper.get('#message-second-reply').classes()).toContain('ring-2')
    expect(wrapper.get('#message-first-reply').classes()).not.toContain('ring-2')
    expect(wrapper.get('#message-message').classes()).not.toContain('bg-accent/50')
  })

  it('shows a neutral stopped status and retires its restore action after durable handoff', () => {
    const exchange = toExchange({
      ...base,
      error: 'user_withdrawn',
      control_action: 'withdraw',
      control_state: 'settled',
      control_execution: { effects: 'none', completion: 'failed', safe_to_resubmit: true },
    })
    const wrapper = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC', recovered: true },
      global,
    })
    expect(wrapper.find('[data-testid="controlled"]').text()).toContain(
      'assistant.controls.withdrawn',
    )
    expect(wrapper.find('[data-testid="failed"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('assistant.editAgain')
  })

  it('retains the read-only outcome check after stop settles with an unknown result', async () => {
    const exchange = toExchange({
      ...base,
      error: 'user_stopped',
      control_action: 'stop',
      control_state: 'settled',
      control_execution: { effects: 'unknown', completion: 'failed', safe_to_resubmit: false },
      turns: [
        {
          id: 'stopped-turn',
          state: 'failed',
          result: {
            execution: { effects: 'unknown', completion: 'failed', safe_to_resubmit: false },
          },
        },
      ],
    })
    const shared = actions(true)
    const wrapper = mount(ExchangeItem, {
      props: { exchange, zone: 'UTC' },
      global: shared.global,
    })
    const check = wrapper.get('[data-testid="controlled"]').get('button')
    expect(check.text()).toBe('assistant.pendingCheck')
    expect(check.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('assistant.editAgain')
    shared.state.busy.value = false
    await nextTick()
    await check.trigger('click')
    expect(wrapper.emitted('check')).toEqual([[exchange.replies[0]]])
    expect(wrapper.emitted('restore')).toBeUndefined()
  })

  it('shows typed access errors and confirmed effects even without legacy receipt text', () => {
    const exchange = toExchange({
      ...base,
      error: null,
      turns: [
        {
          id: 'turn',
          state: 'failed',
          result: {
            error: 'model_auth_required',
            execution: { effects: 'confirmed', completion: 'partial', safe_to_resubmit: false },
          },
        },
      ],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC', admin: true }, global })
    expect(wrapper.text()).toContain('assistant.answerInterrupted')
    expect(wrapper.text()).toContain('assistant.failure.model_auth_required')
    expect(wrapper.text()).toContain('assistant.confirmedChanges')
    expect(wrapper.text()).toContain('assistant.fixAccess')
    expect(wrapper.text()).not.toContain('assistant.noChanges')
    expect(wrapper.text()).not.toContain('assistant.editAgain')
  })

  it('does not announce no changes when an unresolved outcome contradicts execution metadata', () => {
    const exchange = toExchange({
      ...base,
      error: 'outcome_pending',
      turns: [
        {
          id: 'turn',
          state: 'failed',
          result: { execution: { effects: 'none', completion: 'failed', safe_to_resubmit: true } },
        },
      ],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
    expect(wrapper.text()).toContain('assistant.outcomeUnknown')
    expect(wrapper.text()).not.toContain('assistant.noChanges')
    expect(wrapper.text()).not.toContain('assistant.editAgain')
  })

  it('keeps intermediate tool exceptions in details without turning a completed result into a failure', () => {
    const exchange = toExchange({
      ...base,
      state: 'completed',
      error: null,
      turns: [
        {
          id: 'turn',
          state: 'completed',
          result: {
            reply: 'Исправил количество.',
            services: [
              {
                service: 'nutrition',
                status: 'rejected',
                error: 'Missing',
                message: 'entry not found',
              },
              {
                service: 'nutrition',
                status: 'rejected',
                error: 'Conflict',
                message: 'resolve existing draft before replacing it',
              },
              {
                service: 'nutrition',
                status: 'completed',
                receipt_text: 'Порция изменена: 120 г',
                receipts: [{ operation_id: 'portion' }],
              },
              { service: 'tasks', status: 'rejected', message: 'Список недоступен' },
            ],
          },
        },
      ],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
    expect(wrapper.text()).toContain('Исправил количество.')
    expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Порция изменена: 120 г')
    expect(wrapper.text()).not.toContain('Список недоступен')
    expect(wrapper.text()).not.toContain('entry not found')
    expect(wrapper.text()).not.toContain('resolve existing draft')
    expect(wrapper.find('[data-testid="failed"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('assistant.details')
    expect(wrapper.find('[data-testid="reply-menu"]').exists()).toBe(true)
  })

  it('shows a committed result after response failure, without a whole-request retry', () => {
    const exchange = toExchange({
      ...base,
      turns: [
        {
          id: 'turn',
          reply_message_id: 'reply',
          state: 'failed',
          result: {
            error: 'model_unavailable',
            services: [
              {
                service: 'nutrition',
                status: 'completed',
                receipt_text: 'Йогурт 150 г — 90 ккал',
                receipts: [{ operation_id: 'operation', kind: 'entry.add_many' }],
              },
            ],
          },
        },
      ],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
    expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Йогурт 150 г — 90 ккал')
    expect(wrapper.get('[data-testid="failed"]').text()).toContain('assistant.answerInterrupted')
    expect(
      wrapper.findAll('button').some((button) => button.text() === 'assistant.editAgain'),
    ).toBe(false)
    expect(wrapper.find('[data-testid="reply-menu"]').exists()).toBe(true)
  })

  it.each(['Осталось 40 г белка.', '~~Записано: гречка с курицей, 260 г.~~ Осталось 40 г белка.'])(
    'shows the saved model answer without adding service facts, also on reload: %s',
    (reply) => {
      const message: Message = {
        ...base,
        text: 'Запиши обед и скажи, сколько белка осталось',
        state: 'completed',
        error: null,
        turns: [
          {
            id: 'turn',
            state: 'completed',
            result: {
              reply,
              services: [
                {
                  service: 'nutrition',
                  status: 'completed',
                  receipt_text: 'Записано: гречка с курицей, 260 г.',
                  receipts: [{ operation_id: 'lunch', kind: 'entry.add_many' }],
                },
              ],
            },
          },
        ],
      }
      // Both the first render and a fresh mount use persisted history, without an
      // ActivityPanel or receipt state carried over from the live stream.
      for (let mountIndex = 0; mountIndex < 2; mountIndex++) {
        const wrapper = mount(ExchangeItem, {
          props: { exchange: toExchange(message), zone: 'UTC' },
          global,
        })
        expect(wrapper.text()).toContain('Осталось 40 г белка.')
        expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
        if (!reply.includes('Записано:'))
          expect(wrapper.text()).not.toContain('Записано: гречка с курицей, 260 г.')
        expect(wrapper.find('[data-testid="activity"]').exists()).toBe(false)
        expect(wrapper.find('.bg-success-surface').exists()).toBe(false)
        wrapper.unmount()
      }
    },
  )

  it.each([
    ['completed', 'Йогурт 150 г — 90 ккал'],
    ['failed', 'Йогурт 150 г — 90 ккал'],
    ['completed', 'Йогурт 150 г — **90 ккал**. Записал.'],
    ['failed', 'Йогурт 150 г — **90 ккал**. Записал.'],
  ])('does not repeat receipt text already in a %s answer: %s', (state, reply) => {
    const text = 'Йогурт 150 г — 90 ккал'
    const exchange = toExchange({
      ...base,
      turns: [
        {
          id: 'turn',
          state,
          result: {
            error: state === 'failed' ? 'model_unavailable' : null,
            reply,
            services: [
              {
                service: 'nutrition',
                status: 'completed',
                receipt_text: text,
                receipts: [{ operation_id: 'operation' }],
              },
            ],
          },
        },
      ],
    })
    const wrapper = mount(ReplyBlock, { props: { reply: exchange.replies[0]! }, global })
    expect(wrapper.text().split(text)).toHaveLength(2)
    expect(wrapper.find('[data-testid="recorded-result"]').exists()).toBe(false)
  })

  it('offers a receipt lookup for an unknown operation, never a new submission', async () => {
    const exchange = toExchange({
      ...base,
      turns: [{ id: 'turn', state: 'resolving', result: { error: 'outcome_pending' } }],
    })
    const wrapper = mount(ExchangeItem, { props: { exchange, zone: 'UTC' }, global })
    const button = wrapper
      .findAll('button')
      .find((item) => item.text() === 'assistant.pendingCheck')!
    await button.trigger('click')
    expect(wrapper.emitted('check')).toHaveLength(1)
    expect(wrapper.findAll('button').some((item) => item.text() === 'assistant.editAgain')).toBe(
      false,
    )
  })

  it('retires a consumed confirmation in the original reply', () => {
    const exchange = toExchange({
      ...base,
      state: 'completed',
      turns: [
        {
          id: 'turn',
          state: 'completed',
          result: {
            reply: 'Записать?',
            question: 'Подтвердить?',
            confirmation_pending: true,
            proposal_status: 'resolved',
          },
        },
      ],
    })
    const wrapper = mount(ReplyBlock, { props: { reply: exchange.replies[0]! }, global })
    expect(wrapper.get('[data-testid="proposal-closed"]').text()).toBe(
      'assistant.proposal.resolved',
    )
    expect(wrapper.find('[data-testid="question"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some((button) => button.text() === 'assistant.confirm')).toBe(
      false,
    )
  })

  it('re-renders only the exchanges that show an action when the conversation becomes busy', async () => {
    const done = toExchange({
      ...base,
      id: 'done',
      state: 'completed',
      error: null,
      turns: [{ id: 'answer', state: 'completed', result: { reply: 'Готово' } }],
    })
    const failed = toExchange({
      ...base,
      id: 'failed',
      turns: [
        {
          id: 'failure',
          state: 'failed',
          result: { execution: { effects: 'none', completion: 'failed', safe_to_resubmit: true } },
        },
      ],
    })
    const updates: string[] = []
    const shared = actions(false)
    mount(
      defineComponent({
        setup: () => () =>
          [done, failed].map((exchange) =>
            h(ExchangeItem, {
              key: exchange.id,
              exchange,
              zone: 'UTC',
              onVnodeUpdated: () => updates.push(exchange.id),
            }),
          ),
      }),
      { global: shared.global },
    )
    shared.state.busy.value = true
    await nextTick()
    expect(updates).toEqual(['failed'])
  })
})
