import { describe, expect, it, vi } from 'vitest'
import { visibleAmbientFailure, type AmbientState } from '@/ambient/native'
import type { Call } from '@/calls/api'
import { i18n } from '@/i18n'
import {
  ambientStatus,
  callStatus,
  connectionStatus,
  speechStatus,
  type AmbientInput,
} from './sources'
import { PRIORITY, isSticky, ranked } from './statusModel'

const t = (key: string, values?: Record<string, unknown>) =>
  values ? i18n.global.t(key, values) : i18n.global.t(key)

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

const idle: AmbientState = {
  recording: false,
  queued_batches: 0,
  queued_bytes: 0,
  last_uploaded_at: null,
  error: null,
}
const ambient = (overrides: Partial<AmbientInput> = {}, state: Partial<AmbientState> = {}) =>
  ambientStatus(
    {
      supported: true,
      state: { ...idle, ...state },
      failure: null,
      busy: false,
      signedIn: true,
      ...overrides,
    },
    t,
    { open: vi.fn(), stop: vi.fn() },
  )

describe('status sources', () => {
  it('keeps a call on another device on screen with its end one tap away', () => {
    const end = vi.fn()
    expect(callStatus({ mode: 'none' }, t, end)).toBeNull()
    expect(callStatus({ mode: 'incoming', call: call('ringing', 'callee') }, t, end)).toBeNull()
    const active = call('active', 'callee')
    const item = callStatus({ mode: 'elsewhere', call: active }, t, end)!
    expect(item.line).toBe('Разговор идёт на другом устройстве')
    expect(item.sticky).toBe(true)
    expect(item.quick?.label).toBe('Завершить')
    item.quick?.run?.()
    expect(end).toHaveBeenCalledWith(active)
    const ringing = callStatus({ mode: 'elsewhere', call: call('ringing', 'caller') }, t, end)!
    expect(ringing.line).toBe('Вы звоните с другого устройства')
    expect(ringing.quick?.label).toBe('Отменить')
  })

  it('shows a running recording with stop on the line and keeps it on screen', () => {
    const item = ambient({}, { recording: true, queued_batches: 2 })!
    expect(item.line).toBe('Идёт фоновая запись')
    expect(item.sticky).toBe(true)
    expect(item.quick?.label).toBe('Остановить')
    expect(item.detail).toContain('Ожидают отправки: 2.')
    expect(ambient({ busy: true }, { recording: true })!.quick?.disabled).toBe(true)
    expect(ambient({}, { recording: true, error: 'network_unavailable' })!.line).toBe(
      'Запись идёт, отправка не удаётся',
    )
  })

  it('explains a stopped recorder without raw native text', () => {
    const item = ambient({}, { error: 'synthetic-private-error' })!
    expect(item.line).toBe('Фоновая запись остановлена')
    expect(item.detail).toContain('Не удалось обновить состояние')
    expect(item.detail).not.toContain('synthetic-private-error')
    expect(item.sticky).toBe(false)
    expect(ambient({}, { error: 'codec_unavailable' })!.detail).toContain(
      'Сжатая запись недоступна',
    )
  })

  it('does not claim an ended session while signed in with nothing lost', () => {
    expect(ambient({}, { error: 'not_authenticated' })).toBeNull()
    expect(ambient({ signedIn: false }, { error: 'not_authenticated' })!.line).toBe(
      'Фоновая запись остановлена',
    )
    expect(ambient({}, { error: 'not_authenticated', queued_batches: 1 })!.detail).toContain(
      'закончилась сессия',
    )
    expect(visibleAmbientFailure(idle, 'permission_denied', true)).toBe('permission_denied')
  })

  it('reports audio waiting to upload and nothing for an idle recorder', () => {
    expect(ambient()).toBeNull()
    expect(ambient({ supported: false }, { recording: true })).toBeNull()
    const queued = ambient({}, { queued_batches: 3 })!
    expect(queued.line).toBe('Ждут отправки: 3')
    expect(queued.rank).toBe(PRIORITY.queued)
  })

  it('reports a lost connection in short and in full', () => {
    expect(connectionStatus(true, t)).toBeNull()
    const item = connectionStatus(false, t)!
    expect(item.line).toBe('Нет соединения')
    expect(item.detail).toContain('Показаны последние загруженные данные')
  })

  it('offers stop and the way back to the conversation while speech plays', () => {
    const stop = vi.fn()
    expect(speechStatus({ key: null, status: 'idle', problem: null }, t, stop)).toBeNull()
    const loading = speechStatus({ key: 'turn:answer', status: 'loading', problem: null }, t, stop)!
    expect(loading.line).toBe('Подготавливаем озвучку')
    expect(loading.detail).toBe('Голос ИИ')
    expect(loading.actions.map((action) => action.testid)).toEqual([
      'speech-player-stop',
      'speech-player-return',
    ])
    loading.quick?.run?.()
    expect(stop).toHaveBeenCalledOnce()
    const preview = speechStatus({ key: 'preview', status: 'playing', problem: null }, t, stop)!
    expect(preview.line).toBe('Проба голоса')
    expect(preview.actions).toHaveLength(1)
    const failed = speechStatus(
      { key: 'turn:answer', status: 'idle', problem: 'provider_error' },
      t,
      stop,
    )!
    expect(failed.line).toBe('Озвучка не удалась')
    expect(failed.detail).toBe('Провайдер не смог озвучить ответ. Попробуйте позже.')
    expect(failed.quick?.label).toBe('Закрыть')
  })

  it('puts the most important item first and keeps the line only for a call or a recording', () => {
    const offline = connectionStatus(false, t)
    const recording = ambient({}, { recording: true })
    const items = ranked([offline, null, recording])
    expect(items.map((item) => item.id)).toEqual(['recording', 'offline'])
    expect(isSticky(items)).toBe(true)
    expect(isSticky(ranked([offline]))).toBe(false)
  })
})
