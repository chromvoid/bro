import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type { Call } from '@/calls/api'
import type { CallSession, CallView } from '@/calls/session'
import type { CallModel } from '@/calls/useCalls'
import { i18n } from '@/i18n'
import CallOverlay from './CallOverlay.vue'

const mocks = vi.hoisted(() => ({
  answer: vi.fn(),
  decline: vi.fn(async () => undefined),
  dismiss: vi.fn(),
  developer: { on: false },
}))
vi.mock('@/calls/store', () => ({
  calls: { answer: mocks.answer, decline: mocks.decline, dismiss: mocks.dismiss },
}))
vi.mock('@/core/developer', async () => {
  const { computed } = await import('vue')
  return { useDeveloperMode: () => computed(() => mocks.developer.on) }
})

const call = (state: Call['state'], role: Call['role']): Call => ({
  id: 'call-1',
  channel_id: 'ch1',
  kind: 'audio',
  state,
  outcome: null,
  role,
  peer: { id: 'peer', name: 'Анна' },
  created_at: '2026-09-23T12:00:00+00:00',
  answered_at: null,
  ended_at: null,
})

const session = {
  hangUp: vi.fn(),
  toggleMute: vi.fn(),
  enableSound: vi.fn(),
}

function view(changes: Partial<CallView>): CallView {
  return {
    role: 'caller',
    phase: 'outgoing',
    peerName: 'Анна',
    muted: false,
    autoplayBlocked: false,
    connectedAt: null,
    outcome: null,
    signal: true,
    ice: 'new',
    kinds: [],
    selected: null,
    rttMs: null,
    lossPct: null,
    summary: null,
    ...changes,
  }
}

let wrapper: VueWrapper | undefined

function screen() {
  return document.body.querySelector<HTMLElement>('[data-testid="call-screen"]')
}
/** What screen readers hear: the phase, never the ticking length. */
function phase() {
  const region = screen()?.querySelector('[data-testid="call-phase"]')
  expect(region?.getAttribute('role')).toBe('status')
  expect(region?.getAttribute('aria-live')).toBe('polite')
  return region?.textContent?.trim()
}
function button(text: string) {
  const found = [...document.body.querySelectorAll('button')].find((item) =>
    item.textContent?.includes(text),
  )
  if (!found) throw new Error(`no button «${text}»`)
  return found
}
async function show(model: CallModel) {
  await wrapper!.setProps({ model })
  await nextTick()
}
const inSession = (changes: Partial<CallView>): CallModel => ({
  mode: 'session',
  session: session as unknown as CallSession,
  view: view(changes),
})

function overlay() {
  wrapper?.unmount()
  wrapper = mount(CallOverlay, {
    props: { model: { mode: 'none' } },
    attachTo: document.body,
    global: { plugins: [i18n] },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.developer.on = false
  overlay()
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  document.body.innerHTML = ''
})

describe('call overlay', () => {
  it('takes no space without a call or with a call on another device', async () => {
    expect(screen()).toBeNull()
    await show({ mode: 'elsewhere', call: call('active', 'callee') })
    expect(screen()).toBeNull()
    expect(document.body.querySelector('[data-testid="call-elsewhere"]')).toBeNull()
  })

  it('rings full screen and answers inside the tap', async () => {
    const ringing = call('ringing', 'callee')
    await show({ mode: 'incoming', call: ringing })
    expect(screen()?.querySelector('h2')?.textContent).toBe('Анна')
    expect(screen()?.querySelector('[data-testid="call-status"]')?.textContent?.trim()).toBe(
      'Входящий звонок',
    )
    expect(phase()).toBe('Входящий звонок')
    button('Ответить').click()
    expect(mocks.answer).toHaveBeenCalledWith(ringing)
    button('Отклонить').click()
    expect(mocks.decline).toHaveBeenCalledWith(ringing)
  })

  it('takes focus off the field being typed in, but gives it to no button', async () => {
    const field = document.createElement('textarea')
    document.body.append(field)
    field.focus()
    await show({ mode: 'incoming', call: call('ringing', 'callee') })
    await flushPromises()
    await nextTick()
    expect(document.activeElement).toBe(screen())
    expect(document.activeElement).not.toBe(button('Ответить'))
  })

  it('says while calling that the other person must have the app open', async () => {
    await show(inSession({ phase: 'outgoing' }))
    expect(screen()?.querySelector('[data-testid="call-status"]')?.textContent?.trim()).toBe(
      'Звоним…',
    )
    expect(phase()).toBe('Звоним…')
    expect(screen()?.textContent).toContain('увидит звонок, только если приложение открыто')
    button('Отменить').click()
    expect(session.hangUp).toHaveBeenCalled()
  })

  it('counts the call from the connection and mutes on request', async () => {
    await show(inSession({ phase: 'connected', connectedAt: Date.now() - 65_000, muted: true }))
    expect(screen()?.querySelector('[data-testid="call-status"]')?.textContent?.trim()).toBe('1:05')
    expect(phase()).toBe('Связь установлена')
    expect(screen()?.textContent).toContain('Микрофон выключен')
    const mute = button('Включить микрофон')
    expect(mute.getAttribute('aria-pressed')).toBe('true')
    mute.click()
    expect(session.toggleMute).toHaveBeenCalled()
    button('Завершить').click()
    expect(session.hangUp).toHaveBeenCalled()
  })

  it('offers sound when the browser refused to play it', async () => {
    await show(inSession({ phase: 'connecting', autoplayBlocked: true }))
    expect(screen()?.textContent).toContain('Соединяем…')
    button('Включить звук').click()
    expect(session.enableSound).toHaveBeenCalled()
  })

  it('names the outcome; developers keep the summary until they close it', async () => {
    await show(inSession({ phase: 'unstable' }))
    expect(phase()).toBe('Связь прервалась, ждём…')
    await show(inSession({ phase: 'ended', outcome: 'cancelled' }))
    expect(screen()?.textContent).toContain('Звонок отменён')
    expect(phase()).toBe('Звонок отменён')
    expect(screen()?.querySelector('[data-testid="call-developer"]')).toBeNull()
    expect(screen()?.querySelectorAll('button')).toHaveLength(0)

    mocks.developer.on = true
    overlay()
    await show(
      inSession({
        phase: 'ended',
        outcome: null,
        summary: {
          client: 'browser',
          ice: 'connected',
          local_kinds: ['lan4', 'tailnet4'],
          selected: {
            local_type: 'host',
            remote_type: 'prflx',
            local_kind: 'lan4',
            protocol: 'udp',
          },
          rtt_ms: 12,
        },
      }),
    )
    const details = screen()?.querySelector('[data-testid="call-developer"]')?.textContent ?? ''
    expect(screen()?.textContent).toContain('Звонок завершён')
    expect(details).toContain('host → prflx · udp · lan4')
    expect(details).toContain('lan4, tailnet4')
    button('Закрыть').click()
    expect(mocks.dismiss).toHaveBeenCalled()
  })
})
