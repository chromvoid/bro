<script setup lang="ts">
import { ImagePlus } from '@lucide/vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { pictures } from '@/core/clipboard'

/** Picks pictures from the gallery or the camera: on a phone a paste of a picture often fails. */
defineProps<{ label: string; disabled?: boolean }>()
const emit = defineEmits<{ pick: [files: File[]] }>()
const { t } = useI18n()
const input = ref<HTMLInputElement | null>(null)

function picked(event: Event) {
  const target = event.target as HTMLInputElement
  const files = pictures(target.files ?? [])
  target.value = ''
  if (files.length) emit('pick', files)
}
</script>

<template>
  <Button
    type="button"
    variant="ghost"
    size="icon-sm"
    :disabled="disabled"
    :aria-label="`${t('tasks.images.add')}: ${label}`"
    @click="input?.click()"
    ><ImagePlus class="size-4" aria-hidden="true"
  /></Button>
  <input
    ref="input"
    type="file"
    accept="image/*"
    multiple
    class="hidden"
    tabindex="-1"
    aria-hidden="true"
    @change="picked"
  />
</template>
