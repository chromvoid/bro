<script setup lang="ts">
import { useIntervalFn } from '@vueuse/core'
import { Mic, MicOff, Phone, PhoneOff, Volume2 } from '@lucide/vue'
import { DialogContent, DialogDescription, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { duration } from '@/calls/api'
import { calls } from '@/calls/store'
import type { CallModel } from '@/calls/useCalls'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { useDeveloperMode } from '@/core/developer'

/**
 * The call screen: ringing, talking and the ended note fill the screen. A call this account
 * holds on another device is the status line, in the page's flow.
 */
const props = defineProps<{ model: CallModel }>()
const { t } = useI18n()
const developer = useDeveloperMode()

const open = computed(() => props.model.mode === 'session' || props.model.mode === 'incoming')
const view = computed(() => (props.model.mode === 'session' ? props.model.view : null))
const name = computed(() =>
  props.model.mode === 'session'
    ? props.model.view.peerName
    : props.model.mode === 'none'
      ? ''
      : props.model.call.peer.name,
)
const initial = computed(() => name.value.trim().charAt(0).toUpperCase() || '?')
const talking = computed(() => !!view.value && !['outgoing', 'ended'].includes(view.value.phase))

// The length of the call, counted on this device from the moment the media connected.
const now = ref(Date.now())
const ticker = useIntervalFn(() => (now.value = Date.now()), 1000, { immediate: false })
watch(
  () => (view.value?.phase === 'ended' ? null : (view.value?.connectedAt ?? null)),
  (connectedAt) => {
    now.value = Date.now()
    if (connectedAt === null) ticker.pause()
    else ticker.resume()
  },
  { immediate: true },
)

const PHASES = {
  outgoing: 'calls.calling',
  connecting: 'calls.connecting',
  connected: 'calls.connected',
  unstable: 'calls.unstable',
} as const

/** The phase in words. Screen readers hear it; the screen shows the call's length instead. */
const phase = computed(() => {
  if (props.model.mode === 'incoming') return t('calls.incoming')
  const current = view.value
  if (!current) return ''
  return current.phase === 'ended'
    ? t(`calls.ended.${current.outcome ?? 'completed'}`)
    : t(PHASES[current.phase])
})
const status = computed(() =>
  view.value?.phase === 'connected'
    ? duration((now.value - (view.value.connectedAt ?? now.value)) / 1000)
    : phase.value,
)

/** Developer details: live during the call, the sent summary once it ended. */
const details = computed(() => {
  const current = view.value
  if (!developer.value || !current) return []
  const summary = current.phase === 'ended' ? current.summary : null
  const selected = summary ? (summary.selected ?? null) : current.selected
  const kinds = summary ? (summary.local_kinds ?? []) : current.kinds
  const rtt = summary ? summary.rtt_ms : current.rttMs
  const loss = summary ? summary.loss_pct : current.lossPct
  return [
    { label: 'calls.dev.signal', value: t(current.signal ? 'calls.dev.on' : 'calls.dev.off') },
    { label: 'calls.dev.ice', value: summary ? summary.ice : current.ice },
    { label: 'calls.dev.kinds', value: kinds.join(', ') || '—' },
    {
      label: 'calls.dev.pair',
      value: selected
        ? `${selected.local_type} → ${selected.remote_type} · ${selected.protocol} · ${selected.local_kind}`
        : '—',
    },
    { label: 'calls.dev.rtt', value: rtt ?? '—' },
    { label: 'calls.dev.loss', value: loss ?? '—' },
  ]
})

// Answer and «Включить звук» must reach the session inside the tap.
function answer() {
  if (props.model.mode === 'incoming') calls.answer(props.model.call)
}
function decline() {
  if (props.model.mode === 'incoming') void calls.decline(props.model.call)
}
function hangUp() {
  if (props.model.mode === 'session') props.model.session.hangUp()
}
function toggleMute() {
  if (props.model.mode === 'session') props.model.session.toggleMute()
}
function enableSound() {
  if (props.model.mode === 'session') props.model.session.enableSound()
}

/** The screen itself takes focus, no button: a stray Space or Enter must not answer the call. */
function focusScreen(event: Event) {
  event.preventDefault()
  ;(event.target as HTMLElement | null)?.focus({ preventScroll: true })
}
</script>

<template>
  <DialogRoot :open="open">
    <DialogPortal>
      <DialogContent
        class="bg-background fixed inset-0 z-50 flex flex-col pt-(--inset-top) pb-(--inset-bottom) outline-none"
        data-testid="call-screen"
        @open-auto-focus="focusScreen"
        @escape-key-down.prevent
        @pointer-down-outside.prevent
      >
        <p class="sr-only" role="status" aria-live="polite" data-testid="call-phase">
          {{ phase }}
        </p>
        <div
          class="mx-auto flex w-full max-w-md min-h-0 flex-1 flex-col items-center justify-center gap-3 overflow-y-auto px-6 py-8 text-center"
        >
          <Avatar class="size-24">
            <AvatarFallback class="text-3xl font-semibold">{{ initial }}</AvatarFallback>
          </Avatar>
          <DialogTitle class="text-2xl font-semibold break-words">{{ name }}</DialogTitle>
          <DialogDescription
            class="text-muted-foreground text-lg tabular-nums"
            data-testid="call-status"
          >
            {{ status }}
          </DialogDescription>
          <p v-if="view?.phase === 'outgoing'" class="text-muted-foreground text-sm">
            {{ t('calls.reachHint', { name }) }}
          </p>
          <p v-if="talking && view?.muted" class="text-warning text-sm font-medium">
            {{ t('calls.muted') }}
          </p>
          <dl
            v-if="details.length"
            class="bg-muted mt-4 grid w-full grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1 rounded-2xl p-4 text-left text-xs tabular-nums"
            data-testid="call-developer"
          >
            <template v-for="row in details" :key="row.label">
              <dt class="text-muted-foreground">{{ t(row.label) }}</dt>
              <dd class="break-words">{{ row.value }}</dd>
            </template>
          </dl>
        </div>

        <div class="mx-auto grid w-full max-w-md gap-3 px-6 pb-8">
          <Button
            v-if="view?.autoplayBlocked && view.phase !== 'ended'"
            variant="secondary"
            size="lg"
            @click="enableSound"
          >
            <Volume2 />{{ t('calls.enableSound') }}
          </Button>
          <template v-if="model.mode === 'incoming'">
            <Button size="lg" data-testid="call-answer" @click="answer">
              <Phone />{{ t('calls.answer') }}
            </Button>
            <Button variant="destructive" size="lg" data-testid="call-decline" @click="decline">
              <PhoneOff />{{ t('calls.decline') }}
            </Button>
          </template>
          <Button
            v-else-if="view?.phase === 'outgoing'"
            variant="destructive"
            size="lg"
            data-testid="call-hang-up"
            @click="hangUp"
          >
            <PhoneOff />{{ t('calls.cancel') }}
          </Button>
          <template v-else-if="talking">
            <Button
              variant="outline"
              size="lg"
              :aria-pressed="view?.muted"
              data-testid="call-mute"
              @click="toggleMute"
            >
              <MicOff v-if="view?.muted" /><Mic v-else />
              {{ view?.muted ? t('calls.unmute') : t('calls.mute') }}
            </Button>
            <Button variant="destructive" size="lg" data-testid="call-hang-up" @click="hangUp">
              <PhoneOff />{{ t('calls.hangUp') }}
            </Button>
          </template>
          <Button
            v-else-if="view?.phase === 'ended' && developer"
            variant="outline"
            size="lg"
            @click="calls.dismiss()"
          >
            {{ t('common.close') }}
          </Button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
