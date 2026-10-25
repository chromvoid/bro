import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import { StoredRingDeviceState } from '@/sections/personal/sensors'
import StoredRingState from './StoredRingState.vue'

const state = () =>
  StoredRingDeviceState.parse({
    observed_at: '2026-10-06T12:00:00Z',
    received_at: '2026-10-06T12:00:01Z',
    revision: 3,
    stale: false,
    battery_percent: 0,
    charging: false,
    battery_observed_at: '2026-10-04T10:00:00Z',
    battery_stale: true,
    charging_observed_at: '2026-10-04T10:00:00Z',
    charging_stale: true,
    hardware: 'synthetic-hw',
    firmware: 'synthetic-fw',
  })

describe('saved ring device state in the common web', () => {
  it('shows real zero and separately ages battery and charging after a fresh partial import', () => {
    const wrapper = mount(StoredRingState, {
      props: { state: state(), timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    expect(wrapper.text()).toContain('0 %')
    expect(wrapper.text()).toContain('Не подключена')
    expect(wrapper.text()).toContain('Показание батареи устарело')
    expect(wrapper.text()).toContain('Состояние зарядки устарело')
    expect(wrapper.text()).not.toContain('Состояние устарело')
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.find('[style]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('keeps a legacy battery with unknown observation time rather than assigning the latest import', () => {
    const legacy = state()
    legacy.battery_observed_at = null
    legacy.battery_stale = null
    const wrapper = mount(StoredRingState, {
      props: { state: legacy, timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    expect(wrapper.text()).toContain('Время чтения неизвестно')
    expect(wrapper.text()).not.toContain('Показание батареи устарело')
    wrapper.unmount()
  })
  it('keeps declared flags separate from proof and readback separate from successful changes', () => {
    const saved = state()
    saved.declared_capabilities = {
      core_3c: {
        captured_at: '2026-10-06T11:00:00Z',
        opcode: 60,
        payload_hex: '00'.repeat(14),
        flags: { gesture: true, camera: false },
        source_values: {},
        identity: { hardware: 'synthetic-hw', firmware: 'synthetic-fw' },
        decoder_version: 'synthetic-v1',
        evidence_ref: 'synthetic-proof',
        validation_status: 'declared_only',
      },
    }
    saved.capabilities = { gesture: { status: 'unknown', reason: 'protocol_unverified' } }
    saved.last_confirmed_settings = {
      heart_rate: {
        value: { enabled: true, interval_minutes: 5 },
        confirmed_at: '2026-10-06T11:00:00Z',
        readback: true,
      },
    }
    const wrapper = mount(StoredRingState, {
      props: { state: saved, timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    expect(wrapper.text()).toContain('Заявлено')
    expect(wrapper.text()).toContain('Не заявлено')
    expect(wrapper.text()).toContain('Доступность не подтверждена')
    expect(wrapper.text()).not.toContain('Подтверждённо отсутствует')
    expect(wrapper.text()).toContain('это не подтверждение изменения или автономной работы')
    expect(wrapper.text()).toContain('interval_minutes')
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
  it('explains declared source fields without turning their labels into qualified operations', () => {
    const saved = state()
    const declaration = {
      captured_at: '2026-10-06T11:00:00Z',
      payload_hex: '00'.repeat(14),
      source_values: {},
      identity: { hardware: 'synthetic-hw', firmware: 'synthetic-fw' },
      decoder_version: 'synthetic-v1',
      evidence_ref: 'synthetic-proof',
      validation_status: 'declared_only' as const,
    }
    saved.declared_capabilities = {
      core_01: {
        ...declaration,
        opcode: 1,
        flags: { plate: true, feature: true, rtk_mc: true },
      },
      core_3c: {
        ...declaration,
        opcode: 60,
        flags: { notifications: true, lover: true, prayer: true, moslin: false, ecg: false },
      },
    }
    saved.capabilities = {
      notifications: { status: 'unknown', readable: false, writable: false },
      heart_rate_schedule: { status: 'supported', readable: true, writable: false },
      heart_rate_alerts: { status: 'supported', readable: true, writable: false },
      stress_schedule: { status: 'unknown', readable: false, writable: false },
      manual_hrv: { status: 'unknown', readable: false, writable: false },
      unbind: { status: 'unknown', readable: false, writable: false },
    }
    const wrapper = mount(StoredRingState, {
      props: { state: saved, timezone: 'UTC' },
      global: { plugins: [i18n] },
    })
    const labels = wrapper.findAll('dt').map((item) => item.text())
    expect(labels).toContain('Уведомления')
    expect(labels).toContain('ЭКГ')
    expect(labels).toContain('Молитвенные функции (флаг SDK prayer)')
    for (const name of ['plate', 'feature', 'rtk_mc', 'moslin', 'lover']) {
      expect(labels).toContain(`Флаг SDK «${name}» (назначение не установлено)`)
    }
    expect(labels).toContain('Настройки автоматических замеров пульса')
    expect(labels).toContain('Пороги предупреждений о пульсе')
    expect(labels).toContain('Настройки автоматических замеров стресса')
    expect(labels).toContain('Ручной замер HRV')
    expect(labels).toContain('Отвязка на устройстве')
    const notificationRows = wrapper
      .findAll('dt')
      .filter((item) => item.text() === 'Уведомления')
      .map((item) => item.element.parentElement?.textContent)
    expect(notificationRows).toHaveLength(2)
    expect(notificationRows[0]).toContain('Заявлено')
    expect(notificationRows[1]).toContain('Доступность не подтверждена')
    expect(notificationRows[1]).toContain('не допущено')
    expect(notificationRows[1]).not.toContain('Доступен путь протокола')
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
})
