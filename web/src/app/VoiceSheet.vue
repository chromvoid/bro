<script setup lang="ts">
import { CircleAlert, Mic, Send, Square } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { useDictation } from '@/dictation/useDictation'
import { handOff } from '@/sections/assistant/useComposerDraft'
import { useSession } from '@/stores/session'

/**
 * Dictation from any screen. The recognized text lands here for review and then in the
 * assistant's draft; it is never sent on its own (the same rule as dictation in the composer).
 */
const { t } = useI18n()
const router = useRouter()
const session = useSession()
const open = ref(false)
const text = ref('')
const dictation = useDictation(text)
const saving = ref(false)

const hasText = computed(() => !!text.value.trim())
const recording = computed(() => ['permission', 'recording'].includes(dictation.state.value))
const recordingTime = computed(() => {
  const seconds = dictation.elapsed.value
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
})
const status = computed(() => {
  if (dictation.limitReached.value && dictation.state.value === 'finalizing')
    return t('dictation.limit')
  const state = recording.value ? 'recording' : dictation.state.value
  return ['recording', 'finalizing'].includes(state) ? t(`dictation.${state}`) : ''
})

/** Opens the sheet and asks for the microphone at once: it must run inside the tap. */
function dictate() {
  open.value = true
  dictation.start()
}

/** Puts the text into the assistant's saved draft; false when it could not be kept. */
async function save(): Promise<boolean> {
  const account = session.account
  const words = text.value.trim()
  if (!words) return true
  if (!account) return false
  saving.value = true
  try {
    await handOff(account.store, account.journal, {
      mode: 'append',
      text: words,
      transcriptionId: dictation.transcriptionId.value,
    })
    if (account !== session.account) return false
    text.value = ''
    dictation.reset()
    return true
  } catch {
    toast.error(t('voice.handoffFailed'))
    return false
  } finally {
    saving.value = false
  }
}

async function toChat() {
  if (!(await save())) return
  open.value = false
  await router.push({ name: 'assistant' })
}

function discard() {
  text.value = ''
  dictation.reset()
  open.value = false
}

// Closing stops a recording (as «Отменить запись»); reviewed text stays in the assistant's draft.
watch(open, async (isOpen) => {
  if (isOpen) return
  dictation.cancel()
  if (hasText.value && (await save())) toast(t('voice.saved'))
})

defineExpose({ dictate })
</script>

<template>
  <Panel v-model:open="open" :title="t('voice.title')" :autofocus="false">
    <div
      v-if="dictation.active.value"
      class="bg-accent text-accent-foreground space-y-3 rounded-2xl p-4"
      data-testid="voice-status"
    >
      <p class="flex items-center gap-3" role="status">
        <span
          :class="[
            'size-2.5 shrink-0 rounded-full',
            recording ? 'bg-destructive animate-pulse' : 'bg-primary',
          ]"
          aria-hidden="true"
        />
        <span class="min-w-0 flex-1">
          {{ status }}
          <span v-if="recording" class="text-muted-foreground tabular-nums">
            · {{ t('dictation.elapsed', { time: recordingTime }) }}
          </span>
        </span>
      </p>
      <!-- Partial text is shown, not announced: a screen reader hears the result once. -->
      <p
        class="min-h-12 text-lg leading-relaxed break-words whitespace-pre-wrap"
        aria-live="off"
        data-testid="voice-live"
      >
        {{ text || t('voice.hint') }}
      </p>
      <div class="grid grid-cols-2 gap-2">
        <Button variant="outline" @click="dictation.cancel()">{{ t('dictation.cancel') }}</Button>
        <Button :disabled="!recording" data-testid="voice-stop" @click="dictation.stop()">
          <Square class="fill-current" />{{ t('dictation.stop') }}
        </Button>
      </div>
    </div>

    <template v-else>
      <div
        v-if="dictation.problem.value"
        class="bg-warning-surface flex items-start gap-2 rounded-xl px-3 py-2 text-sm"
        role="alert"
        data-testid="voice-problem"
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
            :disabled="saving"
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
            @click="open = false"
            >{{ t('dictation.configure') }}</RouterLink
          >
        </div>
      </div>

      <div v-if="hasText" class="space-y-2">
        <Label for="voice-text">{{ t('voice.transcript') }}</Label>
        <Textarea id="voice-text" v-model="text" data-testid="voice-text" />
        <p class="text-muted-foreground text-sm">{{ t('dictation.review') }}</p>
      </div>

      <div class="grid grid-cols-1 gap-2">
        <Button
          v-if="hasText"
          size="lg"
          :disabled="saving"
          data-testid="voice-to-chat"
          @click="toChat"
        >
          <Send />{{ t('voice.toChat') }}
        </Button>
        <Button variant="outline" :disabled="saving" @click="dictation.start()">
          <Mic />{{ hasText ? t('voice.again') : t('dictation.start') }}
        </Button>
        <Button v-if="hasText" variant="ghost" class="text-destructive" @click="discard">
          {{ t('voice.discard') }}
        </Button>
        <Button v-else variant="ghost" @click="toChat">{{ t('voice.typeInstead') }}</Button>
      </div>
    </template>
  </Panel>
</template>
