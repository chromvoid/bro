<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import { useEventListener } from '@vueuse/core'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { useSession } from '@/stores/session'
import { recordingSource, type Recording } from './api'

const props = defineProps<{ recording: Recording; owner: string }>()
const emit = defineEmits<{ refresh: [] }>()
const { t } = useI18n()
const session = useSession()
const device = session.me?.device_id
const player = useTemplateRef<HTMLAudioElement>('player')
const fallback = ref(false)
const failed = ref(false)
const canFallback = ref(false)
const capability = document.createElement('audio')
const source = computed(() =>
  recordingSource(
    props.recording.id,
    props.recording.format,
    (mime) => capability.canPlayType(mime),
    fallback.value,
  ),
)
function stop() {
  if (!player.value) return
  player.value.pause()
  player.value.removeAttribute('src')
  player.value.load()
}
function error() {
  failed.value = true
  const code = player.value?.error?.code
  canFallback.value = !source.value.includes('/playback?') && (code === 3 || code === 4)
}
function compatible() {
  stop()
  fallback.value = true
  failed.value = false
  canFallback.value = false
}
watch(
  () => [session.me?.id, session.me?.device_id, session.state] as const,
  ([id, currentDevice, state]) => {
    if (id !== props.owner || currentDevice !== device || state !== 'ready') stop()
  },
  { flush: 'sync' },
)
watch(
  () => props.recording.id,
  () => {
    stop()
    fallback.value = false
    failed.value = false
    canFallback.value = false
  },
)
useEventListener(window, 'pagehide', stop)
onBeforeUnmount(stop)
</script>

<template>
  <div class="space-y-2" data-testid="recording-player">
    <audio
      ref="player"
      :src="source"
      class="w-full"
      controls
      preload="none"
      :aria-label="t('recordings.listen')"
      @error="error"
    />
    <div v-if="failed" class="space-y-2 text-sm" role="alert">
      <p>{{ t('recordings.playbackFailed') }}</p>
      <Button v-if="canFallback" variant="outline" size="sm" @click="compatible">{{
        t('recordings.compatible')
      }}</Button>
      <Button v-else variant="outline" size="sm" @click="emit('refresh')">{{
        t('recordings.refresh')
      }}</Button>
    </div>
  </div>
</template>
