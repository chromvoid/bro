<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { BellRing, Moon, Send } from '@lucide/vue'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useDevicePush } from './devicePush'
import { NotificationSettings, PushConfig, disableWebPush, enableWebPush } from './push'

/**
 * Where and when the assistant may reach this person outside the open app: this device's push,
 * quiet hours (urgent passes them) and topics that stay in the app (docs/notifications-spec.md §6).
 */
const { t, te } = useI18n()
const queries = useQueryClient()
const { native, isNative, state, refresh: refreshDevice } = useDevicePush()
const settings = useQuery({
  queryKey: ['notifications', 'settings'],
  queryFn: async ({ signal }) =>
    NotificationSettings.parse(await api.get('/v1/notifications/settings', signal)),
})
const config = useQuery({
  queryKey: ['notifications', 'push-config'],
  queryFn: async ({ signal }) => PushConfig.parse(await api.get('/v1/push/config', signal)),
})

const busy = ref(false)

async function toggleDevice(on: boolean) {
  const key = config.data.value?.webpush?.public_key
  if (busy.value || (on && !isNative.value && !key)) return
  busy.value = true
  try {
    if (isNative.value) {
      if (on) await native.enable()
      else await native.disable()
    } else if (on) await enableWebPush(key!)
    else await disableWebPush()
    await refreshDevice()
    toast.success(t(on ? 'notificationSettings.deviceOn' : 'notificationSettings.deviceOff'))
  } catch (error) {
    await refreshDevice()
    toast.error(state.value === 'denied' ? t('notificationSettings.denied') : errorText(error, t))
  } finally {
    busy.value = false
  }
}

const form = reactive({ quiet: true, start: '23:00', end: '08:00', muted: [] as string[] })
watch(
  () => settings.data.value,
  (value) => {
    if (!value) return
    Object.assign(form, {
      quiet: value.quiet_start !== null,
      start: value.quiet_start ?? '23:00',
      end: value.quiet_end ?? '08:00',
      muted: [...value.muted_topics],
    })
  },
  { immediate: true },
)
// Message keys cannot hold dots: `timers.fired` is `timers_fired` there.
const topicLabel = (topic: string) => {
  const key = `notificationSettings.topics.${topic.replace(/\./g, '_')}`
  return te(key) ? t(key) : topic
}
const topics = computed(() => settings.data.value?.topics ?? [])

async function save() {
  const current = settings.data.value
  if (!current || busy.value) return
  busy.value = true
  try {
    const { data } = await api.send(
      'PUT',
      '/v1/notifications/settings',
      JSON.stringify({
        quiet_start: form.quiet ? form.start : null,
        quiet_end: form.quiet ? form.end : null,
        muted_topics: form.muted,
        revision: current.revision,
      }),
    )
    queries.setQueryData(['notifications', 'settings'], NotificationSettings.parse(data))
    void native.sync().catch(() => undefined)
    toast.success(t('notificationSettings.saved'))
  } catch (error) {
    toast.error(errorText(error, t))
    await settings.refetch()
  } finally {
    busy.value = false
  }
}

async function test() {
  try {
    await api.post('/v1/notifications/test', {})
    toast.success(t('notificationSettings.testSent'))
  } catch (error) {
    toast.error(errorText(error, t))
  }
}
</script>

<template>
  <PageHeader
    :title="t('notificationSettings.title')"
    :description="t('notificationSettings.text')"
    :back="{ name: 'settings' }"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <section class="bg-card space-y-3 rounded-3xl border p-5" aria-labelledby="push-device">
      <h2 id="push-device" class="flex items-center gap-2 font-semibold">
        <BellRing class="size-5" />{{ t('notificationSettings.device') }}
      </h2>
      <p v-if="isNative" class="text-muted-foreground" data-testid="push-shell">
        {{ t('notificationSettings.shell') }}
      </p>
      <p v-if="isNative && native.schedulePending.value" class="text-warning text-sm" role="status">
        {{ t('notificationSettings.schedulePending') }}
      </p>
      <p
        v-if="!isNative && config.data.value && !config.data.value.webpush"
        class="text-muted-foreground"
      >
        {{ t('notificationSettings.serverOff') }}
      </p>
      <p v-else-if="state === 'unsupported'" class="text-muted-foreground">
        {{ t('notificationSettings.unsupported') }}
      </p>
      <p v-else-if="state === 'denied'" class="text-muted-foreground">
        {{ t('notificationSettings.denied') }}
      </p>
      <div v-else-if="state" class="flex items-center justify-between gap-3">
        <Label for="push-this-device" class="font-normal">{{
          t(state === 'on' ? 'notificationSettings.deviceOn' : 'notificationSettings.deviceHint')
        }}</Label>
        <Switch
          id="push-this-device"
          :model-value="state === 'on'"
          :disabled="busy"
          data-testid="push-toggle"
          @update:model-value="(on: boolean) => toggleDevice(on)"
        />
      </div>
      <Button variant="outline" size="sm" data-testid="push-test" @click="test">
        <Send />{{ t('notificationSettings.test') }}
      </Button>
    </section>

    <RefreshFailed :query="settings" />
    <LoadingState v-if="settings.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="settings.isLoadingError.value"
      :error="settings.error.value"
      @retry="settings.refetch()"
    />
    <form v-else class="space-y-5" @submit.prevent="save">
      <section class="bg-card space-y-3 rounded-3xl border p-5" aria-labelledby="quiet-hours">
        <div class="flex items-center justify-between gap-3">
          <h2 id="quiet-hours" class="flex items-center gap-2 font-semibold">
            <Moon class="size-5" />{{ t('notificationSettings.quiet') }}
          </h2>
          <Switch v-model="form.quiet" :aria-label="t('notificationSettings.quiet')" />
        </div>
        <div v-if="form.quiet" class="grid grid-cols-2 gap-3">
          <div class="space-y-1.5">
            <Label for="quiet-start">{{ t('notificationSettings.from') }}</Label>
            <Input id="quiet-start" v-model="form.start" type="time" />
          </div>
          <div class="space-y-1.5">
            <Label for="quiet-end">{{ t('notificationSettings.to') }}</Label>
            <Input id="quiet-end" v-model="form.end" type="time" />
          </div>
        </div>
        <p class="text-muted-foreground text-sm">{{ t('notificationSettings.quietHint') }}</p>
      </section>

      <section
        v-if="topics.length"
        class="bg-card space-y-3 rounded-3xl border p-5"
        aria-labelledby="push-topics"
      >
        <h2 id="push-topics" class="font-semibold">{{ t('notificationSettings.topicsTitle') }}</h2>
        <ul class="space-y-2">
          <li
            v-for="item in topics"
            :key="item.topic"
            class="flex items-center justify-between gap-3"
          >
            <Label :for="`topic-${item.topic}`" class="font-normal">{{
              topicLabel(item.topic)
            }}</Label>
            <Switch
              :id="`topic-${item.topic}`"
              :model-value="!form.muted.includes(item.topic)"
              @update:model-value="
                (on: boolean) =>
                  (form.muted = on
                    ? form.muted.filter((topic) => topic !== item.topic)
                    : [...form.muted, item.topic])
              "
            />
          </li>
        </ul>
        <p class="text-muted-foreground text-sm">{{ t('notificationSettings.topicsHint') }}</p>
      </section>
      <Button type="submit" :disabled="busy">{{ t('notificationSettings.save') }}</Button>
    </form>
  </div>
</template>
