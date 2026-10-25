<script setup lang="ts">
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog'

/** One picture at full size, inside the app: a new tab would open without the session. */
defineProps<{ src: string | null; title: string }>()
const open = defineModel<boolean>('open', { required: true })
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent
      class="max-w-[calc(100%-1rem)] place-items-center p-2 pt-10 sm:max-w-[min(64rem,calc(100%-2rem))]"
      data-testid="image-viewer"
    >
      <DialogTitle class="sr-only">{{ title }}</DialogTitle>
      <img
        v-if="src"
        :src="src"
        :alt="title"
        decoding="async"
        class="bg-muted max-h-[calc(100dvh-var(--inset-top)-var(--inset-bottom)-6rem)] w-auto max-w-full rounded-lg object-contain"
      />
    </DialogContent>
  </Dialog>
</template>
