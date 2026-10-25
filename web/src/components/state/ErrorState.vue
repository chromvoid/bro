<script setup lang="ts">
import { CircleAlert, RefreshCw } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { describeError } from '@/core/errors'

const props = defineProps<{ error?: unknown; title?: string; retrying?: boolean }>()
const emit = defineEmits<{ retry: [] }>()
const { t } = useI18n()
</script>

<template>
  <div
    class="flex flex-col items-center gap-3 px-6 py-10 text-center"
    role="alert"
    data-state="error"
  >
    <div class="bg-destructive/10 text-destructive grid size-12 place-items-center rounded-2xl">
      <CircleAlert class="size-6" />
    </div>
    <div class="max-w-sm space-y-1">
      <h2 class="text-lg font-semibold">{{ props.title ?? t('state.errorTitle') }}</h2>
      <p class="text-muted-foreground">{{ t(describeError(props.error)) }}</p>
    </div>
    <Button type="button" variant="outline" :disabled="retrying" @click="emit('retry')">
      <RefreshCw :class="{ 'animate-spin': retrying }" />
      {{ t('common.retry') }}
    </Button>
  </div>
</template>
