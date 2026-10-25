<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { History, MapPin, Route, Trash2 } from '@lucide/vue'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import locationPlacesMessages from '@/locales/location-places-ru.json'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { dayIn, momentLabel } from '@/core/dates'
import { errorText } from '@/core/errors'
import { LOCATION_KEY, forgetHistory, readLocation, sendFix } from '@/location/api'
import {
  backgroundRecording,
  type BackgroundRecording,
  type BackgroundState,
} from '@/location/background'
import { positionSource, type Permission, type PositionSource } from '@/location/position'
import { useSession } from '@/stores/session'

/**
 * Where this person is and what the server keeps of it (docs/location-weather-spec.md §3.6):
 * this device's permission, the current place and the movement history with its deletion.
 */
const { t } = useI18n({ useScope: 'local', messages: { ru: locationPlacesMessages } })
const session = useSession()
const queries = useQueryClient()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const location = useQuery({ queryKey: LOCATION_KEY, queryFn: ({ signal }) => readLocation(signal) })

const source = ref<PositionSource | null>(null)
const permission = ref<Permission | null>(null)
const busy = ref(false)
const confirming = ref(false)

const recording = ref<BackgroundRecording | null>(null)
const background = ref<BackgroundState | null>(null)

onMounted(async () => {
  source.value = await positionSource()
  permission.value = await source.value.status()
  recording.value = await backgroundRecording()
  const user = session.me?.id
  if (recording.value && user)
    background.value = await recording.value.status(user).catch(() => null)
})

/** The phone records with the app closed: «Всегда» first, then this phone's own grant. */
async function toggleBackground(on: boolean) {
  const user = session.me?.id
  if (!recording.value || !user || busy.value) return
  busy.value = true
  try {
    background.value = on ? await recording.value.enable(user) : await recording.value.disable()
    if (on && !background.value.enabled) toast.error(t('locationSettings.backgroundDenied'))
    await location.refetch()
  } catch (error) {
    toast.error(errorText(error, t))
    background.value = await recording.value.status(user).catch(() => background.value)
  } finally {
    busy.value = false
  }
}

async function allow() {
  if (!source.value || busy.value) return
  busy.value = true
  try {
    permission.value = await source.value.request()
    const fix = permission.value === 'granted' ? await source.value.current() : null
    if (fix) {
      await sendFix(fix)
      await location.refetch()
    }
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

async function forget() {
  busy.value = true
  try {
    await forgetHistory()
    confirming.value = false
    await queries.invalidateQueries({ queryKey: LOCATION_KEY })
    toast.success(t('locationSettings.forgotten'))
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

const current = computed(() => location.data.value?.current ?? null)
const place = computed(() => {
  const value = current.value
  if (!value) return ''
  return value.place ?? `${value.latitude.toFixed(5)}, ${value.longitude.toFixed(5)}`
})
const details = computed(() => {
  const value = current.value
  if (!value) return ''
  return [
    momentLabel(value.captured_at, zone.value, dayIn(zone.value)),
    t('locationSettings.accuracy', { meters: value.accuracy_m }),
    value.device,
  ]
    .filter(Boolean)
    .join(' · ')
})
const history = computed(() => location.data.value?.history ?? null)
</script>

<template>
  <PageHeader :title="t('locationSettings.title')" :back="{ name: 'settings' }" />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <section class="bg-card space-y-3 rounded-3xl border p-5" aria-labelledby="location-device">
      <h2 id="location-device" class="flex items-center gap-2 font-semibold">
        <MapPin class="size-5" />{{ t('locationSettings.device') }}
      </h2>
      <p class="text-muted-foreground" data-testid="location-permission">
        {{ permission ? t(`locationSettings.permission.${permission}`) : '' }}
      </p>
      <Button
        v-if="permission === 'prompt'"
        size="sm"
        :disabled="busy"
        data-testid="location-allow"
        @click="allow"
      >
        <MapPin />{{ t('locationSettings.allow') }}
      </Button>
    </section>

    <section
      v-if="recording && permission === 'granted'"
      class="bg-card space-y-3 rounded-3xl border p-5"
      aria-labelledby="location-background"
    >
      <h2 id="location-background" class="flex items-center gap-2 font-semibold">
        <Route class="size-5" />{{ t('locationSettings.background') }}
      </h2>
      <div class="flex items-center justify-between gap-3">
        <Label for="location-background-switch" class="font-normal">{{
          t(
            background?.enabled
              ? 'locationSettings.backgroundOn'
              : 'locationSettings.backgroundOff',
          )
        }}</Label>
        <Switch
          id="location-background-switch"
          :model-value="background?.enabled ?? false"
          :disabled="busy"
          data-testid="location-background"
          @update:model-value="(on: boolean) => toggleBackground(on)"
        />
      </div>
    </section>

    <RefreshFailed :query="location" />
    <LoadingState v-if="location.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="location.isLoadingError.value"
      :error="location.error.value"
      @retry="location.refetch()"
    />
    <section
      v-else
      class="bg-card space-y-3 rounded-3xl border p-5"
      aria-labelledby="location-history"
    >
      <h2 id="location-history" class="flex items-center gap-2 font-semibold">
        <History class="size-5" />{{ t('locationSettings.history') }}
      </h2>
      <div v-if="current" data-testid="location-current">
        <p class="font-medium">{{ place }}</p>
        <p class="text-muted-foreground text-sm">{{ details }}</p>
      </div>
      <p v-else class="text-muted-foreground">{{ t('locationSettings.none') }}</p>
      <p v-if="history && history.points" class="text-muted-foreground text-sm">
        {{
          t(
            'locationSettings.points',
            {
              count: history.points,
              since: history.since ? momentLabel(history.since, zone, dayIn(zone)) : '',
            },
            history.points,
          )
        }}
      </p>
      <Button
        v-if="history && history.points"
        variant="outline"
        size="sm"
        data-testid="location-forget"
        @click="confirming = true"
      >
        <Trash2 />{{ t('locationSettings.forget') }}
      </Button>
      <p class="text-muted-foreground text-xs">
        <a
          class="underline"
          href="https://www.openstreetmap.org/copyright"
          target="_blank"
          rel="noopener noreferrer"
          >{{ t('places.attribution') }}</a
        >
      </p>
    </section>
  </div>

  <Dialog v-model:open="confirming">
    <DialogContent data-testid="location-forget-dialog">
      <DialogHeader>
        <DialogTitle>{{ t('locationSettings.forgetTitle') }}</DialogTitle>
        <DialogDescription>{{ t('locationSettings.forgetText') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="confirming = false">{{
          t('locationSettings.keep')
        }}</Button>
        <Button
          variant="destructive"
          :disabled="busy"
          data-testid="location-forget-confirm"
          @click="forget"
        >
          {{ t('locationSettings.forgetConfirm') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
