import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { Submission } from '@/core/journal'
import PendingSend from './PendingSend.vue'
import { pendingSend } from './pending'
import { toExchange } from './model'
import { conversationDays } from './timeline'

vi.mock('vue-i18n', async (original) => ({
  ...(await original<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string) => key }),
}))
const developer = vi.hoisted(() => ({ on: true }))
vi.mock('@/core/developer', async () => {
  const { computed } = await import('vue')
  return { useDeveloperMode: () => computed(() => developer.on) }
})

const base: Submission = {
  key: 'old-send',
  endpoint: '/v1/conversation/messages',
  lookup: '/v1/conversation/submissions/by-key/old-send',
  method: 'POST',
  found: 'accepted',
  createdAt: Date.parse('2026-09-25T20:55:00Z'),
  body: JSON.stringify({ text: 'Записать обед', reply_to_message_id: 'reply', image_id: 'image' }),
  state: 'accepted',
  response: { message_id: 'old-message', state: 'queued' },
  failure: null,
  context: {},
}
const global = { stubs: { Button: { template: '<button><slot /></button>' } } }

describe('browser delivery presentation', () => {
  it('shows admission as delivery, never as a current server queue, with a timestamp and original link', async () => {
    const wrapper = mount(PendingSend, {
      props: { send: pendingSend(base), zone: 'Europe/Moscow' },
      global,
    })
    expect(wrapper.text()).toContain('assistant.delivered')
    expect(wrapper.text()).not.toContain('assistant.queued')
    expect(wrapper.find('.animate-spin').exists()).toBe(false)
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-09-25T20:55:00.000Z')
    expect(wrapper.get('time').text()).toBe('23:55')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'assistant.openMessage')!
      .trigger('click')
    expect(wrapper.emitted('open')).toEqual([['old-message']])
    expect(wrapper.text()).not.toContain('outcome.resend')
  })

  it('only animates a send actually in flight on this page; persisted sending needs lookup', () => {
    const entry = { ...base, state: 'sending' as const, response: null }
    const restored = mount(PendingSend, {
      props: { send: pendingSend(entry), zone: 'UTC' },
      global,
    })
    expect(restored.find('.animate-spin').exists()).toBe(false)
    expect(restored.text()).toContain('outcome.check')
    const active = mount(PendingSend, {
      props: { send: pendingSend(entry, true), zone: 'UTC' },
      global,
    })
    expect(active.find('.animate-spin').exists()).toBe(true)
    expect(active.text()).toContain('assistant.sendingNow')
    expect(active.text()).not.toContain('assistant.queued')
  })

  it('never offers to resend an old accepted entry with a malformed receipt', async () => {
    const wrapper = mount(PendingSend, {
      props: { send: pendingSend({ ...base, response: { state: 'queued' } }), zone: 'UTC' },
      global,
    })
    expect(wrapper.text()).not.toContain('assistant.delivered')
    expect(wrapper.text()).not.toContain('outcome.resend')
    expect(wrapper.text()).toContain('outcome.check')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'assistant.pendingDismiss')!
      .trigger('click')
    expect(wrapper.emitted('dismiss')).toHaveLength(1)
  })

  it('shows delivery diagnostics separately from the unknown write outcome and keeps legacy entries readable', () => {
    const entry: Submission = {
      ...base,
      state: 'uncertain',
      response: null,
      diagnostic: {
        kind: 'timeout',
        phase: 'response',
        elapsedMs: 30_005,
        status: 202,
        traceId: '0123456789abcdef0123456789abcdef',
      },
    }
    const wrapper = mount(PendingSend, {
      props: { send: pendingSend(entry), zone: 'UTC' },
      global,
    })
    expect(wrapper.text()).toContain('outcome.unknownTitle')
    const details = wrapper.get('[data-testid="delivery-diagnostic"]')
    expect(details.text()).toContain('assistant.deliveryDiagnostic.timeout')
    expect(details.text()).toContain('assistant.deliveryDiagnostic.response')
    expect(details.text()).toContain(entry.diagnostic!.traceId)
    expect(details.text()).toContain(entry.key)
    const legacy = mount(PendingSend, {
      props: { send: pendingSend({ ...entry, diagnostic: undefined }), zone: 'UTC' },
      global,
    })
    expect(legacy.get('[data-testid="delivery-diagnostic"]').text()).toContain(entry.key)
    expect(legacy.text()).not.toContain('assistant.deliveryDiagnostic.timeout')
    // Without developer mode the family sees the outcome, not the status, trace or key.
    developer.on = false
    try {
      const plain = mount(PendingSend, { props: { send: pendingSend(entry), zone: 'UTC' }, global })
      expect(plain.text()).toContain('outcome.unknownTitle')
      expect(plain.find('[data-testid="delivery-diagnostic"]').exists()).toBe(false)
      expect(plain.text()).not.toContain(entry.key)
    } finally {
      developer.on = true
    }
  })

  it('places an old pending submission before newer exchanges on its original local calendar day', () => {
    const latest = toExchange({
      id: 'new',
      created_at: '2026-09-25T21:05:00Z',
      text: 'Новое сообщение',
      state: 'completed',
    })
    const groups = conversationDays(
      [latest],
      [],
      [pendingSend(base)],
      'Europe/Moscow',
      new Date('2026-09-26T12:00:00Z'),
    )
    expect(
      groups.map((group) => [group.day, group.label, group.items.map((item) => item.key)]),
    ).toEqual([
      ['2026-09-25', 'Вчера', ['pending-old-send']],
      ['2026-09-26', 'Сегодня', ['new']],
    ])
  })

  it('keeps a record with an unreadable time out of the dated feed', () => {
    const undated = pendingSend({ ...base, createdAt: Number.NaN })
    expect(undated.createdAt).toBeNull()
    expect(conversationDays([], [], [undated], 'UTC')).toEqual([])
    const dated = pendingSend({ ...base, createdAt: Date.parse('2026-09-24T09:00:00Z') })
    expect(dated.createdAt).toBe('2026-09-24T09:00:00.000Z')
    expect(dated.body).toEqual({
      text: 'Записать обед',
      reply_to_message_id: 'reply',
      image_id: 'image',
    })
  })
})
