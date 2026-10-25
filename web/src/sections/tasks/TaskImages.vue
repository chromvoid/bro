<script setup lang="ts">
import { ImageOff, LoaderCircle, X } from '@lucide/vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ImageViewer from '@/components/ImageViewer.vue'
import type { TaskImage } from './api'

/** The pictures of one field: small previews that open at full size, removable while editing. */
const props = withDefaults(
  defineProps<{
    images: TaskImage[]
    /** The address of a picture, its small preview or the whole of it. */
    source: (image: TaskImage, preview: boolean) => string
    label: string
    removable?: boolean
    /** Uploads still under way, shown as placeholders after the pictures. */
    pending?: number
  }>(),
  { removable: false, pending: 0 },
)
const emit = defineEmits<{ remove: [id: string] }>()
const { t } = useI18n()
const HEIGHT = 96
const failed = reactive(new Set<string>())
const viewing = ref<TaskImage | null>(null)
const viewerOpen = computed({
  get: () => viewing.value !== null,
  set: (open) => {
    if (!open) viewing.value = null
  },
})

// The box is known before the preview loads, so the card does not jump; a very wide one is cut.
function box(image: TaskImage) {
  const width = Math.round((image.width * HEIGHT) / Math.max(1, image.height))
  return { width: Math.min(Math.max(width, HEIGHT / 2), HEIGHT * 2), height: HEIGHT }
}
</script>

<template>
  <ul
    v-if="images.length || pending"
    class="flex flex-wrap gap-2"
    :aria-label="label"
    data-testid="task-images"
  >
    <li v-for="(image, index) in images" :key="image.id" class="relative">
      <div
        v-if="failed.has(image.id)"
        class="bg-muted text-muted-foreground flex h-24 w-24 flex-col items-center justify-center gap-1 rounded-lg p-2 text-center text-xs"
        role="img"
        :aria-label="t('tasks.images.unavailable')"
      >
        <ImageOff class="size-5" aria-hidden="true" />{{ t('tasks.images.unavailable') }}
      </div>
      <button
        v-else
        type="button"
        class="focus-visible:ring-ring block rounded-lg focus-visible:ring-2 focus-visible:outline-none"
        :aria-label="t('tasks.images.open', { index: index + 1 })"
        @click="viewing = image"
      >
        <img
          :src="source(image, true)"
          v-bind="box(image)"
          alt=""
          loading="lazy"
          decoding="async"
          class="bg-muted h-24 w-auto max-w-48 min-w-12 rounded-lg object-cover"
          @error="failed.add(image.id)"
        />
      </button>
      <button
        v-if="removable"
        type="button"
        class="bg-background/90 text-foreground focus-visible:ring-ring absolute -top-2 -right-2 grid size-7 place-items-center rounded-full border shadow-sm focus-visible:ring-2 focus-visible:outline-none pointer-coarse:size-9"
        :aria-label="t('tasks.images.remove', { index: index + 1 })"
        @click="emit('remove', image.id)"
      >
        <X class="size-4" aria-hidden="true" />
      </button>
    </li>
    <li
      v-for="index in pending"
      :key="`pending-${index}`"
      class="bg-muted text-muted-foreground grid h-24 w-24 place-items-center rounded-lg"
      role="status"
      :aria-label="t('tasks.images.uploading')"
    >
      <LoaderCircle class="size-5 animate-spin" aria-hidden="true" />
    </li>
  </ul>
  <ImageViewer
    v-model:open="viewerOpen"
    :src="viewing ? props.source(viewing, false) : null"
    :title="t('tasks.images.viewer')"
  />
</template>
