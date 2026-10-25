<script setup lang="ts">
import { Lock } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

/**
 * G18: the place was taken away (rights changed, removed from the space, archived). Its content
 * is hidden and nothing more is written; this is not shown as a network error.
 */
const { t } = useI18n()
</script>

<template>
  <div class="flex flex-col items-center gap-3 px-6 py-12 text-center" data-testid="gone">
    <div class="bg-warning-surface text-warning grid size-12 place-items-center rounded-2xl">
      <Lock class="size-6" />
    </div>
    <div class="max-w-sm space-y-1">
      <h2 class="text-lg font-semibold">{{ t('circle.goneTitle') }}</h2>
      <p class="text-muted-foreground">{{ t('circle.goneText') }}</p>
    </div>
    <div class="flex flex-wrap justify-center gap-2">
      <Button as-child
        ><RouterLink :to="{ name: 'circle' }">{{ t('circle.toSpaces') }}</RouterLink></Button
      >
      <Button variant="ghost" as-child>
        <RouterLink :to="{ name: 'assistant' }">{{ t('circle.toAssistant') }}</RouterLink>
      </Button>
    </div>
  </div>
</template>
