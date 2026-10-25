<script setup lang="ts">
import { Mic } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { useSession } from '@/stores/session'
import { ambientErrorKey, nativeAmbientAudio, visibleAmbientFailure } from './native'

const { t } = useI18n()
const native = nativeAmbientAudio()
const session = useSession()
const failure = computed(() =>
  visibleAmbientFailure(native.state.value, native.failure.value, session.state === 'ready'),
)
const uploaded = computed(() => {
  const value = native.state.value?.last_uploaded_at
  if (!value || !Number.isFinite(Date.parse(value))) return null
  return new Intl.DateTimeFormat('ru', { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(value),
  )
})
function open() {
  void native.open().catch(() => undefined)
}
</script>

<template>
  <section
    v-if="native.supported.value"
    class="bg-card space-y-3 rounded-3xl border p-5"
    aria-labelledby="ambient-title"
    data-testid="ambient-settings"
  >
    <h2 id="ambient-title" class="flex items-center gap-2 font-semibold">
      <Mic class="size-5" aria-hidden="true" />{{ t('ambientAudio.title') }}
    </h2>
    <p class="text-muted-foreground text-sm">{{ t('ambientAudio.description') }}</p>
    <p class="text-muted-foreground text-sm">{{ t('ambientAudio.retention') }}</p>
    <p v-if="native.state.value" class="text-sm font-medium" role="status">
      {{ t(native.state.value.recording ? 'ambientAudio.recording' : 'ambientAudio.stopped') }}
    </p>
    <p v-if="native.state.value?.queued_batches" class="text-muted-foreground text-sm">
      {{ t('ambientAudio.queue', { count: native.state.value.queued_batches }) }}
      {{ t('ambientAudio.offline') }}
    </p>
    <p v-if="uploaded" class="text-muted-foreground text-sm">
      {{ t('ambientAudio.uploaded', { when: uploaded }) }}
    </p>
    <!-- The status line raises the alarm; here the problem is a quiet part of the state. -->
    <p v-if="failure" class="text-destructive text-sm" data-testid="ambient-settings-failure">
      {{ t(ambientErrorKey(failure)) }}
    </p>
    <Button variant="outline" :disabled="native.busy.value" @click="open">
      {{ t('ambientAudio.controls') }}
    </Button>
  </section>
</template>
