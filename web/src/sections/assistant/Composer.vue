<script setup lang="ts">
import { ArrowUp, Camera, Check, CircleAlert, LoaderCircle, Mic, Square, X } from '@lucide/vue'
import { useEventListener, useMediaQuery } from '@vueuse/core'
import { computed, nextTick, onBeforeUnmount, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { setComposerFocus, tabBarHidden } from '@/app/composing'
import { useRoomFor } from '@/core/layout'
import { useDictation } from '@/dictation/useDictation'
import { cn } from '@/lib/utils'

const props = defineProps<{
  /** A message this one answers (a clarification or a specific result). */
  replyTo?: string | null
  /** A prepared photo waiting to be sent with the message. */
  photo?: string | null
  /** A chosen photo is still being prepared; it cannot be sent yet. */
  photoPending?: boolean
  /** The photo is on its way to the server; a send waits for it. */
  photoUploading?: boolean
  sending?: boolean
  disabled?: boolean
  /** Only sending is blocked; text, attachments and the saved draft remain editable. */
  sendBlocked?: boolean
  /** A request is being answered: with nothing to send, the send button stops it. */
  stoppable?: boolean
  /** Its stop is on the way or accepted, and the request is winding down. */
  stopping?: boolean
}>()
const emit = defineEmits<{
  send: [payload: { text: string; transcriptionId: string | null }]
  stop: []
  attach: [file: File]
  clearReply: []
  removePhoto: []
}>()
const draft = defineModel<string>({ required: true })

const { t } = useI18n()
// One microphone at a time: on a phone the tab bar's own dictates into this composer, so the
// field shows its microphone only while the keyboard hides the tab bar, or on a wide screen.
const wide = useMediaQuery('(min-width: 768px)')
const ownMicrophone = computed(() => wide.value || tabBarHidden.value)
const dictation = useDictation(draft)
const recording = computed(() => ['permission', 'recording'].includes(dictation.state.value))
const field = useTemplateRef<HTMLTextAreaElement>('field')
const picker = useTemplateRef<HTMLInputElement>('picker')
// The buttons and a usable field share a row from 18rem of the root font; at large text sizes
// the field takes its own row above them.
const form = useTemplateRef<HTMLFormElement>('form')
const oneRow = useRoomFor(form, 18)
// Leaving the screen with the keyboard up gives the tab bar back.
onBeforeUnmount(() => setComposerFocus(false))
const hasContent = computed(() => !!draft.value.trim() || !!props.photo)
const canSend = computed(
  () =>
    hasContent.value &&
    !props.sending &&
    !props.disabled &&
    !props.sendBlocked &&
    !props.photoPending &&
    !dictation.active.value,
)
const recordingTime = computed(() => {
  const seconds = dictation.elapsed.value
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
const status = computed(() => {
  if (dictation.limitReached.value && dictation.state.value === 'finalizing')
    return t('dictation.limit')
  const state = recording.value ? 'recording' : dictation.state.value
  return ['recording', 'finalizing', 'review'].includes(state) ? t(`dictation.${state}`) : ''
})

function send() {
  if (!canSend.value) return
  emit('send', { text: draft.value.trim(), transcriptionId: dictation.transcriptionId.value })
}

function onKeydown(event: KeyboardEvent) {
  // Enter sends on a keyboard; Shift+Enter and phone keyboards insert a line break.
  if (
    event.key === 'Enter' &&
    !event.shiftKey &&
    !event.isComposing &&
    matchMedia('(pointer: fine)').matches
  ) {
    event.preventDefault()
    send()
  }
}

useEventListener(window, 'keydown', (event) => {
  if (event.key !== 'Escape' || !dictation.active.value) return
  event.preventDefault()
  dictation.cancel()
})

// Replacing the microphone must not leave keyboard focus on the document. Keep an editing
// field focused; otherwise focus a control so dictation does not bring up a phone keyboard.
let dictationTrigger: HTMLElement | null = null
watch(dictation.active, async (active) => {
  const focused = document.activeElement
  if (active) dictationTrigger = focused instanceof HTMLElement ? focused : null
  const restoreFocus = !!form.value
    ?.querySelector('[data-testid="dictation-controls"]')
    ?.contains(focused)
  await nextTick()
  if (
    active &&
    dictation.active.value &&
    focused !== field.value &&
    document.activeElement !== field.value
  )
    form.value
      ?.querySelector<HTMLButtonElement>('[data-testid="dictation-cancel"]')
      ?.focus({ preventScroll: true })
  else if (restoreFocus) {
    const microphone = form.value?.querySelector<HTMLButtonElement>(
      '[data-testid="dictation-start"]',
    )
    const target = microphone ?? (dictationTrigger?.isConnected ? dictationTrigger : null)
    target?.focus({ preventScroll: true })
  }
})

watch(recording, async (isRecording, wasRecording) => {
  if (isRecording || !wasRecording) return
  const finish = form.value?.querySelector('[data-testid="dictation-stop"]')
  if (!finish || finish !== document.activeElement) return
  await nextTick()
  if (dictation.active.value)
    form.value
      ?.querySelector<HTMLButtonElement>('[data-testid="dictation-cancel"]')
      ?.focus({ preventScroll: true })
})

function picked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) emit('attach', file)
  input.value = ''
}

// Grow with the text up to a limit; the field scrolls after that.
watch(draft, async () => {
  await nextTick()
  const element = field.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 200)}px`
})
watch(
  () => draft.value.trim(),
  (text) => {
    if (!text && !dictation.active.value) dictation.reset()
  },
)

/** The tab bar's microphone on this screen: start here, or finish a running recording. */
function dictate(): boolean {
  if (props.disabled) return false
  if (recording.value) dictation.stop()
  else if (!dictation.active.value) dictation.start()
  return true
}

defineExpose({
  dictate,
  focus: () => {
    // Right away, inside the tap: iOS opens the keyboard only then. Text just put in the draft
    // reaches the field with the next render, and the caret goes to its end.
    field.value?.focus()
    void nextTick(() => {
      const element = field.value
      if (element && element === document.activeElement)
        element.setSelectionRange(element.value.length, element.value.length)
    })
  },
  submitted: (transcriptionId: string | null) => {
    if (dictation.transcriptionId.value === transcriptionId) dictation.reset()
  },
})
</script>

<template>
  <div
    class="bg-background/95 px-3 pt-1 pb-2 backdrop-blur md:px-6 md:pb-4"
    @focusin="setComposerFocus(true)"
    @focusout="setComposerFocus(false)"
  >
    <div class="mx-auto max-w-3xl space-y-2">
      <div
        v-if="replyTo"
        class="bg-muted text-muted-foreground flex items-center gap-2 rounded-xl px-3 py-2 text-sm"
        data-testid="reply-to"
      >
        <span class="min-w-0 flex-1 truncate">{{
          t('assistant.replyingTo', { text: replyTo })
        }}</span>
        <Button
          variant="ghost"
          size="icon"
          :aria-label="t('assistant.cancelReply')"
          @click="emit('clearReply')"
        >
          <X />
        </Button>
      </div>

      <div
        v-if="photoPending"
        class="bg-muted text-muted-foreground flex size-20 items-center justify-center rounded-xl border"
        role="status"
        :aria-label="t('assistant.photoPreparing')"
        data-testid="photo-pending"
      >
        <LoaderCircle class="size-5 animate-spin" aria-hidden="true" />
      </div>
      <div v-else-if="photo" class="relative w-fit">
        <img
          :src="photo"
          :alt="t('assistant.photoPreview')"
          class="h-20 rounded-xl border object-cover"
        />
        <div
          v-if="photoUploading"
          class="bg-background/60 text-foreground absolute inset-0 flex items-center justify-center rounded-xl"
          role="status"
          :aria-label="t('assistant.photoUploading')"
          data-testid="photo-uploading"
        >
          <LoaderCircle class="size-5 animate-spin" aria-hidden="true" />
        </div>
        <Button
          variant="secondary"
          size="icon"
          class="absolute -top-2 -right-2 rounded-full"
          :aria-label="t('assistant.removePhoto')"
          @click="emit('removePhoto')"
        >
          <X />
        </Button>
      </div>

      <div
        v-if="dictation.problem.value"
        class="bg-warning-surface flex items-start gap-2 rounded-xl px-3 py-2 text-sm"
        role="alert"
        data-testid="dictation-problem"
      >
        <CircleAlert class="text-warning mt-0.5 size-4 shrink-0" />
        <div class="space-y-1">
          <p>{{ t(`dictation.${dictation.problem.value}`) }}</p>
          <p v-if="dictation.incomplete.value">{{ t('dictation.incomplete') }}</p>
          <p v-if="dictation.failure.value.retryAfter !== undefined">
            {{ t('dictation.retryAfter', { seconds: dictation.failure.value.retryAfter }) }}
          </p>
          <button
            v-if="dictation.canRetry.value"
            type="button"
            class="text-primary inline-block underline underline-offset-2"
            :disabled="disabled"
            @click="dictation.retry()"
          >
            {{ t('common.retry') }}
          </button>
          <RouterLink
            v-if="
              ['not_configured', 'no_access', 'auth_required', 'secret_unavailable'].includes(
                dictation.problem.value,
              )
            "
            to="/settings/speech"
            class="text-primary inline-block underline underline-offset-2"
            >{{ t('dictation.configure') }}</RouterLink
          >
        </div>
      </div>

      <!-- Starting points from the screen while nothing is being written or dictated. -->
      <slot v-if="!dictation.active.value" name="suggestions" />

      <!-- One surface: a single row where it fits, the field above its buttons at large text. -->
      <form
        ref="form"
        class="bg-card border-input has-[textarea:focus-visible]:border-ring has-[textarea:focus-visible]:ring-ring/50 flex flex-wrap items-end justify-end gap-0.5 rounded-[1.625rem] border p-[3px] has-[textarea:focus-visible]:ring-3"
        data-testid="composer"
        @submit.prevent="send"
      >
        <input ref="picker" type="file" accept="image/*" class="hidden" @change="picked" />
        <Button
          v-if="!dictation.active.value"
          type="button"
          variant="ghost"
          size="icon"
          :class="cn('text-muted-foreground rounded-full', !oneRow && 'mr-auto')"
          :aria-label="t('assistant.attachPhoto')"
          :disabled="disabled"
          @click="picker?.click()"
        >
          <Camera class="size-5" />
        </Button>
        <label class="sr-only" for="composer-field">{{ t('assistant.messageLabel') }}</label>
        <textarea
          id="composer-field"
          ref="field"
          v-model="draft"
          rows="1"
          :placeholder="t('assistant.placeholder')"
          :disabled="disabled"
          enterkeyhint="send"
          :class="
            cn(
              'placeholder:text-muted-foreground min-h-11 min-w-0 flex-1 resize-none bg-transparent px-2 py-2.5 text-base outline-none disabled:opacity-50',
              oneRow && !dictation.active.value ? 'basis-0' : 'order-first basis-full px-3',
            )
          "
          @keydown="onKeydown"
        />
        <!-- Recording replaces the normal actions inside the same composer. The timer stays
             outside the live region: assistive technology announces state changes only. -->
        <div
          v-if="dictation.active.value"
          class="flex w-full min-w-0 items-center gap-2"
          data-testid="dictation-controls"
        >
          <Button
            type="button"
            variant="ghost"
            size="icon"
            class="text-muted-foreground size-[44px] shrink-0 rounded-full"
            :aria-label="t('dictation.cancel')"
            :title="t('dictation.cancel')"
            data-testid="dictation-cancel"
            @click="dictation.cancel()"
          >
            <X class="size-5" aria-hidden="true" />
          </Button>
          <div class="flex min-w-0 flex-1 items-center gap-2 px-1 text-sm" aria-hidden="true">
            <template v-if="recording">
              <span class="bg-destructive size-2 shrink-0 rounded-full motion-safe:animate-pulse" />
              <span class="tabular-nums" data-testid="dictation-timer">{{ recordingTime }}</span>
            </template>
            <template v-else>
              <LoaderCircle
                class="text-muted-foreground size-4 shrink-0 motion-safe:animate-spin"
              />
              <span class="text-muted-foreground min-w-0 break-words">{{ status }}</span>
            </template>
          </div>
          <span class="sr-only" role="status" data-testid="dictation-status">{{ status }}</span>
          <Button
            v-if="recording"
            type="button"
            size="icon"
            class="size-[44px] shrink-0 rounded-full"
            :aria-label="t('dictation.stopDictation')"
            :title="t('dictation.stopDictation')"
            data-testid="dictation-stop"
            @click="dictation.stop()"
          >
            <Check class="size-5" aria-hidden="true" />
          </Button>
        </div>
        <Button
          v-if="ownMicrophone && !dictation.active.value"
          type="button"
          size="icon"
          variant="ghost"
          class="text-muted-foreground rounded-full"
          :aria-label="t('dictation.start')"
          :disabled="disabled"
          data-testid="dictation-start"
          @click="dictation.start()"
        >
          <Mic class="size-5" />
        </Button>
        <!-- While Bro answers, the empty field's send button stops it; written text queues. -->
        <Button
          v-if="!dictation.active.value && stoppable && !hasContent"
          type="button"
          size="icon"
          class="rounded-full"
          :aria-label="t(stopping ? 'assistant.controls.stopping' : 'assistant.controls.stop')"
          :title="t(stopping ? 'assistant.controls.stopping' : 'assistant.controls.stop')"
          :disabled="stopping || sending"
          data-testid="composer-stop"
          @click="emit('stop')"
        >
          <LoaderCircle v-if="stopping" class="size-5 animate-spin" />
          <Square v-else class="size-3.5 fill-current" />
        </Button>
        <Button
          v-else-if="!dictation.active.value"
          type="submit"
          size="icon"
          class="rounded-full"
          :aria-label="t('assistant.send')"
          :disabled="!canSend"
          data-testid="composer-send"
        >
          <ArrowUp class="size-5" />
        </Button>
      </form>
    </div>
  </div>
</template>
