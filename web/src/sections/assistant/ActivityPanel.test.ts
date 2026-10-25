import { mount } from '@vue/test-utils'
import { ref, shallowRef } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ActivityPanel from './ActivityPanel.vue'
import { Activity } from './activity'

const mocks = vi.hoisted(() => ({ useActivity: vi.fn() }))
vi.mock('./useActivity', () => ({ useActivity: mocks.useActivity }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, locale: ref('ru') }) }))
// Technical details are shown in developer mode; these tests check what it shows.
vi.mock('@/core/developer', async () => {
  const { computed } = await import('vue')
  return { useDeveloperMode: () => computed(() => true) }
})

describe('activity presentation', () => {
  afterEach(() => vi.restoreAllMocks())
  it('escapes public text, keeps token updates silent, and never renders raw tool labels', async () => {
    const snapshot = shallowRef(
      Activity.parse({
        message_id: 'message',
        turn_id: 'turn',
        run_id: 'run',
        seq: 1,
        status: 'active',
        phase: 'tool',
        source: 'assistant',
        preview_text: '<img src=x onerror=alert(1)>',
        started_at: '2026-10-02T12:00:00Z',
        updated_at: '2026-10-02T12:00:02Z',
        elapsed_ms: 2000,
        model_call: 1,
        attempt: 1,
        steps: [
          {
            id: 'tool',
            kind: 'tool',
            status: 'running',
            label: 'internal.secret',
            started_at: '',
            duration_ms: null,
          },
        ],
        usage: { calls: 1, output_tokens: 0, total_tokens: null, account_id: 'hidden-account' },
      }),
    )
    const connection = ref('connected')
    mocks.useActivity.mockReturnValue({
      snapshot,
      preview: ref(snapshot.value.preview_text),
      confirmed: shallowRef([]),
      connection,
    })
    const wrapper = mount(ActivityPanel, {
      props: { messageId: 'message', queued: false },
    })
    expect(wrapper.get('[data-testid="live-answer"]').attributes('aria-live')).toBe('off')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('[data-testid="live-answer"]').text()).toContain(
      '<img src=x onerror=alert(1)>',
    )
    expect(wrapper.get('[data-testid="processing"]').text()).toBe('assistant.activity.tool')
    expect(wrapper.text()).not.toContain('internal.secret')
    expect(wrapper.text()).not.toContain('hidden-account')
    expect(wrapper.get('[data-usage-metric="output_tokens"] dd').text()).toBe('0')
    expect(wrapper.get('[data-usage-metric="total_tokens"] dd').text()).toBe(
      'assistant.usage.unknown',
    )
    // The progress stream's own connection is not the person's concern: the request is in work.
    for (const state of ['connecting', 'reconnecting', 'unavailable', 'offline']) {
      connection.value = state
      await wrapper.vm.$nextTick()
      expect(wrapper.find('[data-testid="live-answer"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="processing"]').text()).toBe('assistant.processing')
      expect(wrapper.find('.motion-safe\\:animate-pulse').exists()).toBe(false)
      expect(wrapper.find('button').exists()).toBe(false)
    }
    await wrapper.setProps({ queued: true })
    expect(wrapper.get('[data-testid="processing"]').text()).toBe('assistant.queued')
    wrapper.unmount()
  })

  it('formats the streaming model preview without adding server-confirmed write text', async () => {
    const snapshot = shallowRef(
      Activity.parse({
        message_id: 'message',
        run_id: 'run',
        seq: 1,
        status: 'active',
        phase: 'generating',
        preview_text: 'Йогурт — **180 ккал**',
        source: 'assistant',
        updated_at: '',
        elapsed_ms: 0,
        model_call: 1,
        attempt: 1,
        steps: [],
        usage: null,
      }),
    )
    const confirmed = shallowRef([
      { id: 'operation', service: 'nutrition', text: 'Записано: йогурт <img src=x> — 180 ккал' },
    ])
    const connection = ref('connected')
    const preview = ref(snapshot.value.preview_text)
    mocks.useActivity.mockReturnValue({
      snapshot,
      preview,
      confirmed,
      connection,
    })
    const wrapper = mount(ActivityPanel, { props: { messageId: 'message', queued: false } })
    expect(wrapper.get('[data-testid="live-answer"] strong').text()).toBe('180 ккал')
    expect(wrapper.find('[data-testid="confirmed-result"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Записано: йогурт')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.bg-success-surface').exists()).toBe(false)
    preview.value = 'Йогурт и яблоко — **260 ккал**'
    confirmed.value = [
      ...confirmed.value,
      { id: 'apple', service: 'nutrition', text: 'Записано: яблоко — 80 ккал' },
    ]
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="live-answer"] strong').text()).toBe('260 ккал')
    expect(wrapper.text()).not.toContain('Записано:')
    connection.value = 'offline'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="live-answer"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="confirmed-result"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Записано:')
    wrapper.unmount()
  })

  it('keeps the text of an ended run while the saved reply loads, only with a connection', async () => {
    const connection = ref('ended')
    mocks.useActivity.mockReturnValue({
      snapshot: shallowRef(null),
      preview: ref('Йогурт — **180 ккал**'),
      confirmed: shallowRef([]),
      connection,
    })
    const wrapper = mount(ActivityPanel, { props: { messageId: 'message', queued: false } })
    expect(wrapper.get('[data-testid="live-answer"] strong').text()).toBe('180 ккал')
    expect(wrapper.get('[data-testid="processing"]').text()).toBe('assistant.activity.verifying')
    expect(wrapper.find('[data-testid="activity-elapsed"]').exists()).toBe(false)
    // The text starts where the saved reply's will; the stage line stands under it.
    expect(
      wrapper
        .get('[data-testid="live-answer"]')
        .element.compareDocumentPosition(wrapper.get('[data-testid="processing"]').element) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    connection.value = 'offline'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="live-answer"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
