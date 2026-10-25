<script setup lang="ts">
import { Copy, Ellipsis, Info, Library, MessageSquareReply, Volume2, Square } from '@lucide/vue'
import { computed, inject, ref, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { speechPlayback } from '@/speech/playback'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import MarkdownText from '@/components/MarkdownText'
import { useDeveloperMode } from '@/core/developer'
import { safeWebLink } from '@/core/links'
import { cn } from '@/lib/utils'
import { conversationBusy, revealedReply } from './busy'
import type { Reply } from './model'
import SourcesSheet from './SourcesSheet.vue'
import TurnDetails from './TurnDetails.vue'

const props = defineProps<{
  reply: Reply
  admin?: boolean
  collapseFailedText?: boolean
  compactOutcome?: boolean
  highlighted?: boolean
  /** The newest answer in the conversation: it always shows its actions. */
  latest?: boolean
}>()
const emit = defineEmits<{
  answer: [reply: Reply]
  confirm: [reply: Reply, accept: boolean]
  check: [reply: Reply]
}>()
const { t } = useI18n()
const busy = inject(conversationBusy, ref(false))
// Consent requires the completed model explanation to be visible beside the action.
const canAccept = computed(
  () =>
    props.reply.completed &&
    !!props.reply.text.trim() &&
    !props.reply.responseFailed &&
    !props.reply.unknownOutcome &&
    !props.collapseFailedText,
)
function confirm(accept: boolean) {
  if (busy.value || (accept && !canAccept.value)) return
  emit('confirm', props.reply, accept)
}
const playback = speechPlayback.state
const ownPlayback = computed(() => playback.value.key === `turn:${props.reply.turnId}`)
const speaking = computed(() => ownPlayback.value && playback.value.status !== 'idle')
function toggleSpeech() {
  if (speaking.value) speechPlayback.stop()
  else void speechPlayback.turn(props.reply.turnId)
}
const sourcesOpen = ref(false)
const detailsOpen = ref(false)
// A long history has a sheet per reply: each mounts on its first opening and then stays, so it can
// still animate closed.
const sourcesUsed = ref(false)
const detailsUsed = ref(false)
function openSources() {
  sourcesUsed.value = sourcesOpen.value = true
}
function openDetails() {
  detailsUsed.value = detailsOpen.value = true
}
const copying = ref(false)
async function copyAnswer() {
  if (copying.value || !props.reply.text) return
  copying.value = true
  try {
    await navigator.clipboard.writeText(props.reply.text)
    toast.success(t('assistant.copied'))
  } catch {
    toast.error(t('assistant.copyFailed'))
  } finally {
    copying.value = false
  }
}
// Model usage and the trace code are for an administrator who asked for them.
const developer = useDeveloperMode()
// One array per reply: a new one on every render would make the answer render and parse again.
const sources = computed(() => props.reply.sources.filter((source) => safeWebLink(source.url)))
const links = computed(() => sources.value.map((source) => source.url))

const speakable = computed(
  () =>
    props.reply.completed &&
    !!props.reply.text &&
    !props.reply.responseFailed &&
    !props.reply.unknownOutcome,
)
const hasMenu = computed(
  () =>
    !!props.reply.text || !!props.reply.messageId || sources.value.length > 0 || developer.value,
)
const menuOpen = ref(false)

// An earlier answer shows its actions on a hover with a mouse, and on a tap on a touch screen.
const revealed = inject(revealedReply, ref<string | null>(null))
const shown = computed(
  () => props.latest || speaking.value || revealed.value === props.reply.turnId,
)
let pointer = ''
function pointed(event: PointerEvent) {
  pointer = event.pointerType
}
/** A tap on the answer, not on a link or a control inside it, shows or hides its actions. */
function tapped(event: MouseEvent) {
  if (props.latest || pointer === 'mouse' || !pointer) return
  if ((event.target as Element).closest('a, button, summary, input, textarea, [role="button"]'))
    return
  revealed.value = revealed.value === props.reply.turnId ? null : props.reply.turnId
}

// A long press on the answer opens its menu on a touch screen, as on a task card; moving the
// finger scrolls.
let press: ReturnType<typeof setTimeout> | undefined
let pressed = false
let touching = false
let pressedOpen = false
let origin = { x: 0, y: 0 }
// A long press opens the menu under the finger, as a context menu does; otherwise it hangs from
// «⋯». The anchor is one object for the menu's lifetime: reka-ui forwards a `reference` prop only
// if it was set on the first render.
let pressAt: DOMRect | null = null
const trigger = useTemplateRef<{ $el: HTMLElement }>('trigger')
const menuAnchor = {
  getBoundingClientRect: () =>
    pressAt ?? trigger.value?.$el.getBoundingClientRect() ?? new DOMRect(),
  // The scrolling conversation around «⋯» moves the menu with it.
  get contextElement() {
    return trigger.value?.$el
  },
}
function openAt(x: number, y: number) {
  pressAt = new DOMRect(x, y, 0, 0)
  pressedOpen = true
  menuOpen.value = true
}
function down(event: PointerEvent) {
  touching = event.pointerType === 'touch'
  if (!touching || !hasMenu.value) return
  pressed = false
  origin = { x: event.clientX, y: event.clientY }
  press = setTimeout(() => {
    pressed = true
    openAt(origin.x, origin.y)
  }, 450)
}
function moved(event: PointerEvent) {
  if (press && Math.hypot(event.clientX - origin.x, event.clientY - origin.y) > 8) cancel()
}
function cancel() {
  clearTimeout(press)
  press = undefined
}
/** The tap that ends a long press does not follow a link under the finger. */
function landed(event: MouseEvent) {
  if (!pressed) return
  pressed = false
  event.preventDefault()
  event.stopPropagation()
}
/** Android reports a long press as a context menu; a mouse keeps the browser's own. */
function context(event: MouseEvent) {
  if (!touching || !hasMenu.value) return
  event.preventDefault()
  if (!menuOpen.value) openAt(event.clientX, event.clientY)
}
/**
 * Focus goes back to «⋯» without moving the conversation (WebKit scrolls to a button laid over
 * the next message); after a long press it goes nowhere, so the hidden row stays hidden.
 */
function menuClosed(event: Event) {
  event.preventDefault()
  if (!pressedOpen) trigger.value?.$el.focus({ preventScroll: true })
  pressedOpen = false
  pressAt = null
}
</script>

<template>
  <div
    :id="reply.messageId ? `message-${reply.messageId}` : undefined"
    :class="[
      'group/reply relative min-w-0 space-y-3 transition-colors',
      highlighted && 'bg-accent/50 ring-primary ring-2',
    ]"
    data-testid="reply"
    @pointerdown.capture="pointed"
    @click="tapped"
  >
    <details
      v-if="reply.text && collapseFailedText"
      class="text-muted-foreground text-sm"
      data-testid="failed-answer-details"
    >
      <summary class="min-h-11 cursor-pointer py-3 font-medium">
        {{ t('assistant.details') }}
      </summary>
      <MarkdownText :text="reply.text" :allowed-links="links" />
    </details>
    <div
      v-else-if="reply.text"
      :class="
        cn(hasMenu && 'pointer-coarse:select-none pointer-coarse:[-webkit-touch-callout:none]')
      "
      data-testid="reply-text"
      @pointerdown="down"
      @pointermove="moved"
      @pointerup="cancel"
      @pointercancel="cancel"
      @click.capture="landed"
      @contextmenu="context"
    >
      <MarkdownText :text="reply.text" :allowed-links="links" />
    </div>
    <div
      v-if="reply.question"
      class="bg-accent flex flex-col gap-2 rounded-xl p-3"
      data-testid="question"
    >
      <div v-if="reply.confirmationPending" class="flex flex-wrap gap-2">
        <Button :disabled="busy || !canAccept" @click="confirm(true)">{{
          t('assistant.confirm')
        }}</Button>
        <Button variant="outline" :disabled="busy" @click="confirm(false)">
          {{ t('assistant.decline') }}
        </Button>
      </div>
      <Button v-else variant="outline" class="self-start" @click="emit('answer', reply)">
        <MessageSquareReply />{{ t('assistant.reply') }}
      </Button>
    </div>
    <div
      v-else-if="reply.confirmationPending"
      class="bg-accent flex flex-wrap gap-2 rounded-xl p-3"
    >
      <Button :disabled="busy || !canAccept" @click="confirm(true)">{{
        t('assistant.confirm')
      }}</Button>
      <Button variant="outline" :disabled="busy" @click="confirm(false)">
        {{ t('assistant.decline') }}
      </Button>
    </div>

    <div
      v-if="reply.unknownOutcome && !compactOutcome"
      class="bg-warning-surface space-y-2 rounded-xl p-3 text-sm"
      role="status"
    >
      <p>{{ t('assistant.unknownTurn') }}</p>
      <Button :disabled="busy" @click="emit('check', reply)">{{
        t('assistant.pendingCheck')
      }}</Button>
    </div>

    <p
      v-if="reply.proposalStatus && reply.proposalStatus !== 'open'"
      class="text-muted-foreground text-sm"
      data-testid="proposal-closed"
    >
      {{ t(`assistant.proposal.${reply.proposalStatus}`) }}
    </p>

    <!-- The newest answer shows its actions. Earlier ones show them on a hover, a tap on a touch
         screen or keyboard focus, keep them for a long press, and in the accessibility tree. -->
    <div
      :class="
        cn(
          '-ms-2.5 flex flex-wrap items-center gap-0.5 transition-opacity',
          !shown &&
            'pointer-fine:opacity-0 pointer-fine:group-hover/reply:opacity-100 pointer-fine:focus-within:opacity-100',
          // Hidden on a touch screen they take no room; keyboard focus shows them over the rest.
          !shown &&
            'pointer-coarse:bg-background pointer-coarse:pointer-events-none pointer-coarse:absolute pointer-coarse:z-10 pointer-coarse:rounded-xl pointer-coarse:opacity-0 pointer-coarse:focus-within:pointer-events-auto pointer-coarse:focus-within:opacity-100 pointer-coarse:focus-within:shadow-md',
        )
      "
      :data-shown="shown || undefined"
      data-testid="reply-actions"
    >
      <Button
        v-if="speakable"
        variant="ghost"
        size="icon"
        class="text-muted-foreground"
        data-testid="speech-toggle"
        :aria-pressed="speaking"
        @click="toggleSpeech"
      >
        <Square v-if="speaking" class="size-4.5" /><Volume2 v-else class="size-4.5" />
        <span class="sr-only">{{
          t(speaking ? 'speechPlayback.stop' : 'speechPlayback.play')
        }}</span>
      </Button>
      <Button
        v-if="reply.text"
        variant="ghost"
        size="icon"
        class="text-muted-foreground"
        :disabled="copying"
        data-testid="copy-inline"
        @click="copyAnswer"
      >
        <Copy class="size-4.5" /><span class="sr-only">{{ t('assistant.copy') }}</span>
      </Button>
      <DropdownMenu v-if="hasMenu" v-model:open="menuOpen">
        <DropdownMenuTrigger as-child>
          <Button
            ref="trigger"
            size="icon"
            variant="ghost"
            class="text-muted-foreground"
            :aria-label="t('assistant.answerActions')"
            data-testid="reply-menu"
          >
            <Ellipsis class="size-4.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="start"
          class="w-auto min-w-[min(12rem,calc(100vw-2rem))] max-w-[calc(100vw-2rem)]"
          :reference="menuAnchor"
          @close-auto-focus="menuClosed"
        >
          <DropdownMenuItem
            v-if="reply.text"
            class="min-h-11"
            :disabled="copying"
            data-testid="copy-answer"
            @select="copyAnswer"
          >
            <Copy />{{ t('assistant.copy') }}
          </DropdownMenuItem>
          <DropdownMenuItem
            v-if="speakable"
            class="min-h-11"
            data-testid="answer-speech"
            @select="toggleSpeech"
          >
            <Square v-if="speaking" /><Volume2 v-else />{{
              t(speaking ? 'speechPlayback.stop' : 'speechPlayback.play')
            }}
          </DropdownMenuItem>
          <DropdownMenuItem
            v-if="reply.messageId"
            class="min-h-11"
            data-testid="answer-reply"
            @select="emit('answer', reply)"
          >
            <MessageSquareReply />{{ t('assistant.reply') }}
          </DropdownMenuItem>
          <DropdownMenuItem
            v-if="sources.length"
            class="min-h-11"
            data-testid="answer-sources"
            @select="openSources"
          >
            <Library />{{ t('assistant.sources') }} · {{ sources.length }}
          </DropdownMenuItem>
          <DropdownMenuItem
            v-if="developer"
            class="min-h-11"
            data-testid="answer-details"
            @select="openDetails"
          >
            <Info />{{ t('assistant.details') }}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>

    <p
      v-if="ownPlayback && playback.problem"
      role="alert"
      class="text-warning text-sm"
      data-testid="speech-error"
    >
      {{ t(`speechPlayback.errors.${playback.problem}`) }}
      <RouterLink
        v-if="
          ['not_configured', 'no_access', 'auth_required', 'secret_unavailable'].includes(
            playback.problem,
          )
        "
        to="/settings/speech"
        class="text-primary underline underline-offset-2"
        >{{ t('dictation.configure') }}</RouterLink
      >
    </p>

    <SourcesSheet
      v-if="sourcesUsed"
      v-model:open="sourcesOpen"
      :sources="sources"
      :limitations="reply.limitations"
    />
    <TurnDetails
      v-if="developer && detailsUsed"
      v-model:open="detailsOpen"
      :turn-id="reply.turnId"
    />
  </div>
</template>
