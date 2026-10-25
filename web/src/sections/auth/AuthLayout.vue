<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import BrandMark from '@/components/BrandMark.vue'

const { t } = useI18n()
</script>

<template>
  <main
    class="flex min-h-dvh flex-col items-center px-4 pt-[calc(2.5rem+var(--inset-top))] pb-10 md:justify-center"
  >
    <div class="w-full max-w-sm space-y-8">
      <div class="flex items-center gap-2.5">
        <BrandMark class="size-9" />
        <span class="text-lg font-semibold">{{ t('app.title') }}</span>
      </div>
      <slot />
    </div>
  </main>
</template>
