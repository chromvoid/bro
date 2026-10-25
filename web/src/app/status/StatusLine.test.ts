import { Mic, CloudOff } from '@lucide/vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { i18n } from '@/i18n'
import StatusLine from './StatusLine.vue'
import StatusSheet from './StatusSheet.vue'
import { speechStatus } from './sources'
import { isSticky, type StatusItem } from './statusModel'
import type { ShellStatus } from './useShellStatus'

const mounted: VueWrapper[] = []
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))

function status(list: StatusItem[]): ShellStatus {
  const items = computed(() => list)
  const open = ref(false)
  return {
    items,
    top: computed(() => items.value[0] ?? null),
    sticky: computed(() => isSticky(items.value)),
    open,
    setOpen: (value: boolean) => (open.value = value),
  }
}
function line(value: ShellStatus) {
  const wrapper = mount(StatusLine, {
    props: { status: value },
    global: { plugins: [i18n], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  mounted.push(wrapper)
  return wrapper
}

const stop = vi.fn()
const recording: StatusItem = {
  id: 'recording',
  rank: 1,
  icon: Mic,
  tone: 'danger',
  line: 'Идёт фоновая запись',
  quick: { label: 'Остановить', run: stop, variant: 'destructive', testid: 'ambient-stop' },
  actions: [],
  sticky: true,
  testid: 'ambient-status',
}
const offline: StatusItem = {
  id: 'offline',
  rank: 4,
  icon: CloudOff,
  tone: 'warning',
  line: 'Нет соединения',
  actions: [],
  sticky: false,
}

describe('status line', () => {
  it('takes no space without a status', () => {
    expect(line(status([])).find('[data-testid="status-line"]').exists()).toBe(false)
  })

  it('shows the top item, how many more wait, and opens the sheet', async () => {
    const value = status([recording, offline])
    const wrapper = line(value)
    expect(wrapper.get('[data-testid="status-line"]').attributes('data-items')).toBe(
      'recording offline',
    )
    const open = wrapper.get('[data-testid="ambient-status"]')
    expect(open.text()).toContain('Идёт фоновая запись')
    expect(open.text()).toContain('+1')
    expect(open.attributes('aria-label')).toBe(
      'Идёт фоновая запись. Ещё сообщений: 1. Открыть подробности',
    )
    expect(wrapper.get('[role="status"]').text()).toBe('Идёт фоновая запись')
    await open.trigger('click')
    expect(value.open.value).toBe(true)
  })

  it('keeps the urgent action on the line itself', async () => {
    const wrapper = line(status([recording]))
    await wrapper.get('[data-testid="ambient-stop"]').trigger('click')
    expect(stop).toHaveBeenCalledOnce()
    expect(wrapper.text()).not.toContain('+')
  })

  it('opens speech details and stops only when its button is tapped', async () => {
    const stopSpeech = vi.fn()
    const item = speechStatus(
      { key: 'preview', status: 'playing', problem: null },
      (key, values) => (values ? i18n.global.t(key, values) : i18n.global.t(key)),
      stopSpeech,
    )!
    const value = status([item])
    const wrapper = line(value)
    expect(wrapper.get('[data-testid="speech-player"]').text()).toContain('Проба голоса')
    await wrapper.get('[data-testid="speech-player"]').trigger('click')
    expect(value.open.value).toBe(true)
    expect(stopSpeech).not.toHaveBeenCalled()
    const sheet = mount(StatusSheet, {
      props: { status: value },
      global: {
        plugins: [i18n],
        stubs: { Panel: { template: '<div><slot /></div>' } },
      },
    })
    mounted.push(sheet)
    expect(sheet.get('[data-testid="speech-player"]').text()).toContain('Голос ИИ')
    await sheet.get('[data-testid="speech-player-stop"]').trigger('click')
    expect(stopSpeech).toHaveBeenCalledOnce()
  })

  it('announces a changing line once, in its own words', () => {
    const wrapper = line(
      status([
        {
          ...offline,
          id: 'ambient',
          line: 'Ждут отправки: 2',
          announce: 'Ожидается отправка записи',
        },
      ]),
    )
    expect(wrapper.get('[role="status"]').text()).toBe('Ожидается отправка записи')
  })
})
