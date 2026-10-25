import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive, ref } from 'vue'
import { i18n } from '@/i18n'
import type { Channel, Message } from './api'
import ChatView from './ChatView.vue'

const mocks = vi.hoisted(() => ({
  dial: vi.fn(),
  busy: { value: false },
  channels: [] as Channel[],
  messages: [] as Message[],
  invalidateQueries: vi.fn(),
}))
vi.mock('@tanstack/vue-query', () => ({
  useQueryClient: () => ({ invalidateQueries: mocks.invalidateQueries }),
}))
vi.mock('@/calls/store', () => ({ calls: { dial: mocks.dial, busy: mocks.busy } }))
vi.mock('@/core/client', () => ({ api: { post: vi.fn(async () => ({ status: 200, data: {} })) } }))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => ({ params: { channel: 'ch1' } }),
}))
vi.mock('./useCircle', () => ({
  isGone: () => false,
  useChannels: () => ({ data: ref({ channels: mocks.channels }), refetch: vi.fn() }),
  useSpaces: () => ({ data: ref({ spaces: [], invitations: [] }) }),
  useMessages: () => ({
    data: ref({ messages: mocks.messages, channel: { id: 'ch1' } }),
    isPending: ref(false),
    isError: ref(false),
    isLoadingError: ref(false),
    isRefetchError: ref(false),
    error: ref(null),
    dataUpdatedAt: ref(0),
    refetch: vi.fn(),
  }),
}))
const person = reactive({
  me: { id: 'me', timezone: 'UTC' },
  account: null,
  scopes: ['calls.read', 'calls.write'],
  can(scope: string) {
    return this.scopes.includes(scope)
  },
})
vi.mock('@/stores/session', () => ({ useSession: () => person }))

const direct: Channel = {
  id: 'ch1',
  kind: 'direct',
  name: 'Личная переписка',
  unread: 0,
  peer_id: 'anna',
  peer_name: 'Анна',
}

function chat() {
  return mount(ChatView, {
    global: {
      plugins: [i18n],
      stubs: {
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        Panel: { template: '<div />' },
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
}

beforeEach(() => {
  mocks.dial.mockReset()
  mocks.busy.value = false
  mocks.channels = [direct]
  mocks.messages = []
  person.scopes = ['calls.read', 'calls.write']
})

describe('chat calls', () => {
  it('calls the other person of a direct chat from the header', async () => {
    const wrapper = chat()
    const button = wrapper.get('[data-testid="call"]')
    expect(button.attributes('aria-label')).toBe('Позвонить: Анна')
    await button.trigger('click')
    expect(mocks.dial).toHaveBeenCalledWith({ channelId: 'ch1', peerName: 'Анна' })
  })

  it('offers no call without both rights, outside direct chats or while busy', () => {
    person.scopes = ['calls.read']
    expect(chat().find('[data-testid="call"]').exists()).toBe(false)
    person.scopes = ['calls.read', 'calls.write']
    mocks.channels = [{ ...direct, kind: 'space', peer_id: null, space_id: 's1' }]
    expect(chat().find('[data-testid="call"]').exists()).toBe(false)
    mocks.channels = [direct]
    mocks.busy.value = true
    expect(chat().get('[data-testid="call"]').attributes('disabled')).toBeDefined()
  })

  it('shows calls as lines, without author, bubble or reply', () => {
    mocks.messages = [
      {
        id: 'm1',
        actor_user_id: 'anna',
        author: 'user',
        created_at: new Date().toISOString(),
        text: 'Пропущенный звонок',
        call: { call_id: 'c1', kind: 'audio', outcome: 'missed', duration_s: null },
      },
      {
        id: 'm2',
        actor_user_id: 'me',
        author: 'user',
        created_at: new Date().toISOString(),
        text: 'Звонок · 2:13',
        call: { call_id: 'c2', kind: 'audio', outcome: 'completed', duration_s: 133 },
      },
      {
        id: 'm3',
        actor_user_id: 'anna',
        author: 'user',
        created_at: new Date().toISOString(),
        text: 'Привет',
      },
    ]
    const wrapper = chat()
    const lines = wrapper.findAll('[data-testid="call-line"]')
    expect(lines.map((line) => line.text().split(' · ').slice(0, -1).join(' · '))).toEqual([
      'Пропущенный звонок',
      'Звонок · 2:13',
    ])
    expect(lines.every((line) => !line.find('button').exists())).toBe(true)
    expect(wrapper.findAll('[data-testid="message"]')).toHaveLength(1)
  })
})
