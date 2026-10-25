<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ArrowDown, ArrowUp, ChevronsLeft, ChevronRight, Plus, Power, Trash2 } from '@lucide/vue'
import { useDocumentVisibility, useIntervalFn } from '@vueuse/core'
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { api } from '@/core/client'
import { dayIn, momentLabel } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { useSession } from '@/stores/session'
import {
  Display,
  LAYOUTS,
  Settings,
  TICKER_PERIODS,
  cleanSettings,
  moved,
  toggledTicker,
  uptimeParts,
  withLayout,
  type Layout,
  type Page,
} from './api'

/**
 * One screen: the frame it shows right now (the same image the device draws), buttons that
 * act like the device's own, its state, and the pages it cycles through.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const queries = useQueryClient()
const id = computed(() => String(route.params.device))
const zone = computed(() => session.me?.timezone ?? 'UTC')
const display = useQuery({
  queryKey: computed(() => ['display', id.value]),
  // State and «on line» change while the screen is open.
  refetchInterval: 15_000,
  queryFn: async ({ signal }) =>
    Display.parse(await api.get(`/v1/display/devices/${encodeURIComponent(id.value)}`, signal)),
})
const available = computed(() => (display.data.value?.cards ?? []).filter((card) => card.available))
const cardTitle = (card: string) =>
  display.data.value?.cards.find((item) => item.id === card)?.title ?? card

// The preview is the device's current frame; it is fetched again while the tab is visible.
const image = ref<string | null>(null)
let etag: string | null = null
let loading: Promise<void> | null = null
function loadPreview(): Promise<void> {
  loading ??= fetchPreview().finally(() => (loading = null))
  return loading
}
async function fetchPreview() {
  const response = await fetch(`/v1/display/devices/${encodeURIComponent(id.value)}/preview.png`, {
    credentials: 'same-origin',
    headers: etag ? { 'If-None-Match': etag } : {},
  })
  if (response.status !== 200) return
  etag = response.headers.get('ETag')
  if (image.value) URL.revokeObjectURL(image.value)
  image.value = URL.createObjectURL(await response.blob())
}
const visibility = useDocumentVisibility()
const { pause, resume } = useIntervalFn(() => void loadPreview().catch(() => {}), 5000, {
  immediateCallback: true,
})
watch(visibility, (state) => (state === 'visible' ? resume() : pause()))
watch(id, () => {
  etag = null
  void loadPreview()
})
onBeforeUnmount(() => {
  if (image.value) URL.revokeObjectURL(image.value)
})
const pressing = ref(false)
async function press(event: 'short' | 'long') {
  if (pressing.value) return
  pressing.value = true
  try {
    await api.post(`/v1/display/devices/${encodeURIComponent(id.value)}/preview/input`, { event })
    await loadPreview()
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    pressing.value = false
  }
}

function lastSeen(): string {
  const seen = display.data.value?.device.last_seen_at
  return seen ? momentLabel(seen, zone.value, dayIn(zone.value)) : t('display.neverSeen')
}
function uptime(seconds: number): string {
  const { days, hours, minutes } = uptimeParts(seconds)
  return days
    ? t('display.uptimeDays', { days, hours })
    : t('display.uptimeHours', { hours, minutes })
}

// Editing works on a copy; saving sends the whole set with the revision it started from.
const draft = shallowRef<Settings | null>(null)
const name = ref('')
const saving = ref(false)
// The copy is taken again only when the saved settings change (a save, another device), not on
// every refresh of the screen's state, which would throw away what is being chosen.
watch(
  () =>
    display.data.value
      ? `${display.data.value.device.id}:${display.data.value.device.revision}`
      : null,
  (key) => {
    const value = display.data.value
    if (!key || !value) return
    draft.value = Settings.parse(value.settings)
    name.value = value.device.name
  },
  { immediate: true },
)
const fallback = computed(() => available.value[0]?.id ?? 'clock')
function update(change: (settings: Settings) => Settings) {
  if (draft.value) draft.value = change(draft.value)
}
function setLayout(index: number, layout: string) {
  update((settings) => ({
    ...settings,
    pages: settings.pages.map((page, i) =>
      i === index ? withLayout(page, layout as Layout, fallback.value) : page,
    ),
  }))
}
function setCard(index: number, slot: number, card: string) {
  update((settings) => ({
    ...settings,
    pages: settings.pages.map((page, i) =>
      i === index ? { ...page, cards: page.cards.map((c, s) => (s === slot ? card : c)) } : page,
    ),
  }))
}
function setTitle(index: number, title: string) {
  update((settings) => ({
    ...settings,
    pages: settings.pages.map((page, i) => (i === index ? { ...page, title } : page)),
  }))
}
/** Cards with a brief form: the available ones and those already in this strip. */
function briefs(page: Page) {
  return (display.data.value?.cards ?? []).filter(
    (card) => card.brief && (card.available || (page.ticker ?? []).includes(card.id)),
  )
}
function setTicker(index: number, card: string, on: boolean) {
  update((settings) => ({
    ...settings,
    pages: settings.pages.map((page, i) => (i === index ? toggledTicker(page, card, on) : page)),
  }))
}
function setTickerPeriod(index: number, seconds: number) {
  update((settings) => ({
    ...settings,
    pages: settings.pages.map((page, i) => (i === index ? { ...page, ticker_s: seconds } : page)),
  }))
}
function addPage() {
  update((settings) => ({
    ...settings,
    pages: [...settings.pages, { layout: 'single', cards: [fallback.value] }],
  }))
}
function removePage(index: number) {
  update((settings) => ({ ...settings, pages: settings.pages.filter((_, i) => i !== index) }))
}
function movePage(index: number, to: number) {
  update((settings) => ({ ...settings, pages: moved(settings.pages, index, to) }))
}
const HOME = [0, 60, 300, 900, 3600]
const home = computed({
  get: () => String(draft.value?.home_after_s ?? 0),
  set: (value: string) => update((settings) => ({ ...settings, home_after_s: Number(value) })),
})
const changed = computed(
  () =>
    !!display.data.value &&
    !!draft.value &&
    (JSON.stringify(cleanSettings(draft.value)) !==
      JSON.stringify(cleanSettings(display.data.value.settings)) ||
      name.value.trim() !== display.data.value.device.name),
)
async function save() {
  const current = display.data.value
  if (!current || !draft.value || saving.value) return
  saving.value = true
  try {
    await api.send(
      'PUT',
      `/v1/display/devices/${encodeURIComponent(id.value)}`,
      JSON.stringify({
        revision: current.device.revision,
        settings: cleanSettings(draft.value),
        ...(name.value.trim() ? { name: name.value.trim() } : {}),
      }),
    )
    toast.success(t('display.saved'))
    await queries.invalidateQueries({ queryKey: ['display', id.value] })
    await queries.invalidateQueries({ queryKey: ['devices'] })
    await loadPreview()
  } catch (error) {
    toast.error(
      error instanceof ApiError && error.status === 409
        ? t('display.changed')
        : errorText(error, t),
    )
    if (error instanceof ApiError && error.status === 409) await display.refetch()
  } finally {
    saving.value = false
  }
}
function reset() {
  if (display.data.value) draft.value = Settings.parse(display.data.value.default)
}

const revoking = ref(false)
const revokeBusy = ref(false)
async function revoke() {
  if (revokeBusy.value) return
  revokeBusy.value = true
  try {
    await api.post(`/v1/devices/${encodeURIComponent(id.value)}/revoke`, {})
    toast.success(t('devices.revoked', { name: display.data.value?.device.name ?? '' }))
    await queries.invalidateQueries({ queryKey: ['devices'] })
    await router.replace({ name: 'settings-devices' })
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    revokeBusy.value = false
    revoking.value = false
  }
}
</script>

<template>
  <PageHeader
    :title="display.data.value?.device.name ?? t('display.title')"
    :back="{ name: 'settings-devices' }"
  />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <RefreshFailed :query="display" />
    <LoadingState v-if="display.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="display.isLoadingError.value"
      :error="display.error.value"
      @retry="display.refetch()"
    />
    <template v-else-if="display.data.value && draft">
      <section class="space-y-3" aria-labelledby="preview-title">
        <div class="flex flex-wrap items-center gap-2">
          <h2 id="preview-title" class="font-semibold">{{ t('display.preview') }}</h2>
          <Badge :variant="display.data.value.online ? 'default' : 'secondary'">
            {{ t(display.data.value.online ? 'display.online' : 'display.offline') }}
          </Badge>
        </div>
        <div class="overflow-hidden rounded-2xl border bg-(--display-screen-background) p-2">
          <img
            v-if="image"
            :src="image"
            :alt="t('display.previewAlt')"
            width="400"
            height="300"
            class="aspect-[4/3] w-full [image-rendering:pixelated]"
            data-testid="display-preview"
          />
          <div v-else class="aspect-[4/3] w-full" />
        </div>
        <div class="flex flex-wrap gap-2">
          <Button variant="outline" :disabled="pressing" @click="press('short')">
            <ChevronRight />{{ t('display.next') }}
          </Button>
          <Button variant="outline" :disabled="pressing" @click="press('long')">
            <ChevronsLeft />{{ t('display.home') }}
          </Button>
        </div>
        <p class="text-muted-foreground text-sm">{{ t('display.previewHint') }}</p>
      </section>

      <section class="space-y-2" aria-labelledby="state-title">
        <h2 id="state-title" class="font-semibold">{{ t('display.state') }}</h2>
        <dl
          class="bg-card grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-3xl border p-5 text-sm"
        >
          <dt class="text-muted-foreground">{{ t('display.lastSeen') }}</dt>
          <dd>{{ lastSeen() }}</dd>
          <template v-if="display.data.value.device.status?.rssi != null">
            <dt class="text-muted-foreground">{{ t('display.wifi') }}</dt>
            <dd>{{ display.data.value.device.status.rssi }} dBm</dd>
          </template>
          <template v-if="display.data.value.device.status?.firmware">
            <dt class="text-muted-foreground">{{ t('display.firmware') }}</dt>
            <dd>{{ display.data.value.device.status.firmware }}</dd>
          </template>
          <template v-if="display.data.value.device.status?.uptime != null">
            <dt class="text-muted-foreground">{{ t('display.uptime') }}</dt>
            <dd>{{ uptime(display.data.value.device.status.uptime) }}</dd>
          </template>
        </dl>
      </section>

      <section class="space-y-3" aria-labelledby="pages-title">
        <h2 id="pages-title" class="font-semibold">{{ t('display.pages') }}</h2>
        <div class="space-y-1.5">
          <Label for="display-name">{{ t('devices.name') }}</Label>
          <Input id="display-name" v-model="name" maxlength="60" />
        </div>
        <ol class="space-y-3">
          <li
            v-for="(page, index) in draft.pages"
            :key="index"
            class="bg-card space-y-3 rounded-3xl border p-4"
            :data-testid="`display-page-${index}`"
          >
            <div class="flex items-center gap-2">
              <span class="font-medium">{{ t('display.page', { number: index + 1 }) }}</span>
              <span class="ms-auto flex gap-1">
                <Button
                  size="icon"
                  variant="ghost"
                  :disabled="index === 0"
                  :aria-label="t('display.up')"
                  @click="movePage(index, index - 1)"
                  ><ArrowUp
                /></Button>
                <Button
                  size="icon"
                  variant="ghost"
                  :disabled="index === draft.pages.length - 1"
                  :aria-label="t('display.down')"
                  @click="movePage(index, index + 1)"
                  ><ArrowDown
                /></Button>
                <Button
                  size="icon"
                  variant="ghost"
                  class="text-destructive"
                  :disabled="draft.pages.length === 1"
                  :aria-label="t('display.remove')"
                  @click="removePage(index)"
                  ><Trash2
                /></Button>
              </span>
            </div>
            <div class="grid gap-3 sm:grid-cols-2">
              <div class="space-y-1.5">
                <Label :for="`layout-${index}`">{{ t('display.layout') }}</Label>
                <NativeSelect
                  :id="`layout-${index}`"
                  :model-value="page.layout"
                  @update:model-value="(value) => setLayout(index, String(value))"
                >
                  <option v-for="(_, layout) in LAYOUTS" :key="layout" :value="layout">
                    {{ t(`display.layouts.${layout}`) }}
                  </option>
                </NativeSelect>
              </div>
              <div class="space-y-1.5">
                <Label :for="`title-${index}`">{{ t('display.pageTitle') }}</Label>
                <Input
                  :id="`title-${index}`"
                  :model-value="page.title ?? ''"
                  maxlength="40"
                  :placeholder="t('display.pageTitleHint')"
                  @update:model-value="(value) => setTitle(index, String(value))"
                />
              </div>
              <div v-for="(card, slot) in page.cards" :key="slot" class="space-y-1.5">
                <Label :for="`card-${index}-${slot}`">{{
                  t('display.slot', { number: slot + 1 })
                }}</Label>
                <NativeSelect
                  :id="`card-${index}-${slot}`"
                  :model-value="card"
                  @update:model-value="(value) => setCard(index, slot, String(value))"
                >
                  <option v-if="!available.some((item) => item.id === card)" :value="card" disabled>
                    {{ cardTitle(card) }} · {{ t('display.unavailable') }}
                  </option>
                  <option v-for="item in available" :key="item.id" :value="item.id">
                    {{ item.title }}
                  </option>
                </NativeSelect>
              </div>
            </div>
            <fieldset class="space-y-2" :data-testid="`display-ticker-${index}`">
              <legend class="text-sm font-medium">{{ t('display.ticker') }}</legend>
              <p class="text-muted-foreground text-sm">{{ t('display.tickerHint') }}</p>
              <div class="flex flex-wrap gap-x-5 gap-y-2">
                <Label
                  v-for="item in briefs(page)"
                  :key="item.id"
                  class="flex items-center gap-2 font-normal"
                >
                  <Checkbox
                    :model-value="(page.ticker ?? []).includes(item.id)"
                    :disabled="!item.available"
                    @update:model-value="(on) => setTicker(index, item.id, on === true)"
                  />{{ item.title }}
                </Label>
              </div>
              <div v-if="(page.ticker ?? []).length > 1" class="max-w-60 space-y-1.5">
                <Label :for="`ticker-${index}`">{{ t('display.tickerPeriod') }}</Label>
                <NativeSelect
                  :id="`ticker-${index}`"
                  :model-value="String(page.ticker_s ?? 12)"
                  @update:model-value="(value) => setTickerPeriod(index, Number(value))"
                >
                  <option v-for="seconds in TICKER_PERIODS" :key="seconds" :value="String(seconds)">
                    {{ t('display.everySeconds', { count: seconds }) }}
                  </option>
                </NativeSelect>
              </div>
            </fieldset>
          </li>
        </ol>
        <Button variant="outline" :disabled="draft.pages.length >= 8" @click="addPage">
          <Plus />{{ t('display.addPage') }}
        </Button>
        <div class="space-y-1.5">
          <Label for="display-home">{{ t('display.homeAfter') }}</Label>
          <NativeSelect id="display-home" v-model="home">
            <option v-for="seconds in HOME" :key="seconds" :value="String(seconds)">
              {{ t(`display.homeOptions.${seconds}`) }}
            </option>
          </NativeSelect>
        </div>
        <div class="flex flex-wrap gap-2">
          <Button :disabled="!changed || saving" data-testid="display-save" @click="save">
            {{ t('common.save') }}
          </Button>
          <Button variant="ghost" @click="reset">{{ t('display.reset') }}</Button>
        </div>
      </section>

      <Button variant="outline" class="text-destructive w-full" @click="revoking = true">
        <Power />{{ t('display.revoke') }}
      </Button>
    </template>
  </div>

  <Dialog v-model:open="revoking">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('display.revokeTitle') }}</DialogTitle>
        <DialogDescription>{{ t('display.revokeText') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="revoking = false">{{ t('common.cancel') }}</Button>
        <Button variant="destructive" :disabled="revokeBusy" @click="revoke">
          {{ t('display.revoke') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
