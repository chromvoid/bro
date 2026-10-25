<script setup lang="ts">
import messages from '@/locales/ring-stored-ru.json'
import { useI18n } from 'vue-i18n'
import { dayIn, momentLabel } from '@/core/dates'
import type { StoredRingDeviceState } from '@/sections/personal/sensors'

const { t } = useI18n({ useScope: 'local', messages: { ru: messages } })
const props = defineProps<{ state: StoredRingDeviceState; timezone: string }>()
const time = (value: string | null | undefined) =>
  value ? momentLabel(value, props.timezone, dayIn(props.timezone)) : t('ring.stored.timeUnknown')
const names: Record<string, string> = {
  heart_rate: t('ring.stored.names.heart_rate'),
  spo2: t('ring.stored.names.spo2'),
  temperature: t('ring.stored.names.temperature'),
  hrv: t('ring.stored.names.hrv'),
  stress: t('ring.stored.names.stress'),
  notifications: t('ring.stored.names.notifications'),
  ecg: t('ring.stored.names.ecg'),
  prayer: t('ring.stored.names.prayer'),
  moslin: t('ring.stored.names.moslin'),
  lover: t('ring.stored.names.lover'),
  plate: t('ring.stored.names.plate'),
  feature: t('ring.stored.names.feature'),
  rtk_mc: t('ring.stored.names.rtk_mc'),
  e_card: t('ring.stored.names.e_card'),
  one_key: t('ring.stored.names.one_key'),
  menstruation: t('ring.stored.names.menstruation'),
  wallpaper: t('ring.stored.names.wallpaper'),
  weather: t('ring.stored.names.weather'),
  wechat: t('ring.stored.names.wechat'),
  contacts: t('ring.stored.names.contacts'),
  gesture: t('ring.stored.names.gesture'),
  music: t('ring.stored.names.music'),
  video: t('ring.stored.names.video'),
  ebook: t('ring.stored.names.ebook'),
  game: t('ring.stored.names.game'),
  camera: t('ring.stored.names.camera'),
  phone_call: t('ring.stored.names.phone_call'),
  touch: t('ring.stored.names.touch'),
  interval_spo2: t('ring.stored.names.interval_spo2'),
  interval_temperature: t('ring.stored.names.interval_temperature'),
  blood_pressure: t('ring.stored.names.blood_pressure'),
  manual_heart: t('ring.stored.names.manual_heart'),
  new_sleep: t('ring.stored.names.new_sleep'),
  ble_pair: t('ring.stored.names.ble_pair'),
  app_revision: t('ring.stored.names.app_revision'),
  location: t('ring.stored.names.location'),
  heart_rate_schedule: t('ring.stored.names.heart_rate_schedule'),
  heart_rate_alerts: t('ring.stored.names.heart_rate_alerts'),
  spo2_schedule: t('ring.stored.names.spo2_schedule'),
  temperature_schedule: t('ring.stored.names.temperature_schedule'),
  hrv_schedule: t('ring.stored.names.hrv_schedule'),
  stress_schedule: t('ring.stored.names.stress_schedule'),
  workout: t('ring.stored.names.workout'),
  live_heart_rate: t('ring.stored.names.live_heart_rate'),
  manual_heart_rate: t('ring.stored.names.manual_heart_rate'),
  manual_spo2: t('ring.stored.names.manual_spo2'),
  manual_temperature: t('ring.stored.names.manual_temperature'),
  manual_hrv: t('ring.stored.names.manual_hrv'),
  manual_stress: t('ring.stored.names.manual_stress'),
  alarms: t('ring.stored.names.alarms'),
  drink_reminders: t('ring.stored.names.drink_reminders'),
  dnd: t('ring.stored.names.dnd'),
  sedentary: t('ring.stored.names.sedentary'),
  power_saving: t('ring.stored.names.power_saving'),
  gestures: t('ring.stored.names.gestures'),
  counter: t('ring.stored.names.counter'),
  profile: t('ring.stored.names.profile'),
  clock: t('ring.stored.names.clock'),
  find_ring: t('ring.stored.names.find_ring'),
  bind: t('ring.stored.names.bind'),
  unbind: t('ring.stored.names.unbind'),
  factory_reset: t('ring.stored.names.factory_reset'),
  power_off: t('ring.stored.names.power_off'),
  vibration_intensity: t('ring.stored.names.vibration_intensity'),
  restart: t('ring.stored.names.restart'),
}
</script>

<template>
  <details class="mt-2 space-y-3" data-testid="stored-ring-state">
    <summary class="text-primary min-h-11 cursor-pointer py-2">
      {{ t('ring.stored.title') }}
    </summary>
    <p class="text-muted-foreground text-xs">
      {{ t('ring.stored.received') }} {{ time(state.received_at) }} ·
      {{ t('ring.stored.observed') }} {{ time(state.observed_at) }}
      <span v-if="state.stale"> · {{ t('ring.stored.stale') }}</span>
    </p>
    <dl class="grid gap-3 sm:grid-cols-2">
      <div v-if="state.battery_percent !== null && state.battery_percent !== undefined">
        <dt class="font-medium">{{ t('ring.stored.battery') }}</dt>
        <dd>{{ state.battery_percent }} %</dd>
        <dd class="text-muted-foreground text-xs">{{ time(state.battery_observed_at) }}</dd>
        <dd v-if="state.battery_stale" class="text-warning text-xs">
          {{ t('ring.stored.batteryStale') }}
        </dd>
      </div>
      <div v-if="state.charging !== null && state.charging !== undefined">
        <dt class="font-medium">{{ t('ring.stored.charging') }}</dt>
        <dd>{{ state.charging ? t('ring.stored.connected') : t('ring.stored.disconnected') }}</dd>
        <dd class="text-muted-foreground text-xs">{{ time(state.charging_observed_at) }}</dd>
        <dd v-if="state.charging_stale" class="text-warning text-xs">
          {{ t('ring.stored.chargingStale') }}
        </dd>
      </div>
      <div v-if="state.firmware">
        <dt class="font-medium">{{ t('ring.stored.firmware') }}</dt>
        <dd>{{ state.firmware }}</dd>
      </div>
      <div v-if="state.hardware">
        <dt class="font-medium">{{ t('ring.stored.hardware') }}</dt>
        <dd>{{ state.hardware }}</dd>
      </div>
    </dl>
    <div v-if="Object.keys(state.last_confirmed_settings).length" class="space-y-2">
      <h3 class="font-medium">{{ t('ring.stored.confirmedSettings') }}</h3>
      <details v-for="(setting, name) in state.last_confirmed_settings" :key="name">
        <summary class="min-h-11 cursor-pointer py-2">
          {{ names[name] ?? name }} · {{ time(setting.confirmed_at) }}
        </summary>
        <p class="text-muted-foreground text-xs">
          {{ t('ring.stored.confirmedSettingsHint') }}
        </p>
        <pre class="overflow-auto whitespace-pre-wrap break-all text-xs">{{
          JSON.stringify(setting.value, null, 2)
        }}</pre>
        <p v-if="setting.evidence_ref" class="text-muted-foreground text-xs">
          {{ setting.evidence_ref }}
        </p>
      </details>
    </div>
    <div v-if="Object.keys(state.declared_capabilities).length" class="space-y-2">
      <h3 class="font-medium">{{ t('ring.stored.declaredCapabilities') }}</h3>
      <p class="text-muted-foreground text-xs">
        {{ t('ring.stored.declaredCapabilitiesHint') }}
      </p>
      <details v-for="(declaration, channel) in state.declared_capabilities" :key="channel">
        <summary class="min-h-11 cursor-pointer py-2">
          {{ channel }} · {{ time(declaration.captured_at) }}
        </summary>
        <dl class="grid gap-2 sm:grid-cols-2">
          <div v-for="(declared, name) in declaration.flags" :key="name">
            <dt>{{ names[name] ?? name }}</dt>
            <dd>{{ declared ? t('ring.stored.declared') : t('ring.stored.undeclared') }}</dd>
          </div>
        </dl>
        <p class="text-muted-foreground text-xs">
          {{ declaration.identity.hardware }} · {{ declaration.identity.firmware }}
        </p>
        <p class="text-muted-foreground text-xs">
          {{ t('ring.stored.sourceFieldsHint') }}
        </p>
        <pre class="overflow-auto whitespace-pre-wrap break-all text-xs">{{
          JSON.stringify(declaration.source_values, null, 2)
        }}</pre>
        <p class="text-muted-foreground text-xs">
          {{ declaration.decoder_version }} · {{ declaration.evidence_ref }}
        </p>
        <code class="break-all text-xs">{{ declaration.payload_hex }}</code>
      </details>
    </div>
    <details v-if="Object.keys(state.capabilities).length">
      <summary class="min-h-11 cursor-pointer py-2">{{ t('ring.stored.availability') }}</summary>
      <p class="text-muted-foreground text-xs">
        {{ t('ring.stored.availabilityHint') }}
      </p>
      <dl class="space-y-2">
        <div v-for="(capability, name) in state.capabilities" :key="name">
          <dt>{{ names[name] ?? name }}</dt>
          <dd>
            {{
              capability.status === 'supported'
                ? t('ring.stored.supported')
                : capability.status === 'unsupported'
                  ? t('ring.stored.unsupported')
                  : t('ring.stored.unconfirmed')
            }}
          </dd>
          <dd class="text-muted-foreground text-xs">
            {{ t('ring.stored.read') }}
            {{
              capability.readable === true
                ? t('ring.stored.allowed')
                : capability.readable === false
                  ? t('ring.stored.disallowed')
                  : t('ring.stored.allowanceUnknown')
            }}
            · {{ t('ring.stored.write') }}
            {{
              capability.writable === true
                ? t('ring.stored.allowed')
                : capability.writable === false
                  ? t('ring.stored.disallowed')
                  : t('ring.stored.allowanceUnknown')
            }}
          </dd>
          <dd class="text-muted-foreground text-xs">
            {{ capability.reason }} {{ capability.evidence_ref }}
          </dd>
        </div>
      </dl>
    </details>
  </details>
</template>
