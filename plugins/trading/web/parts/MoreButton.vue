<script setup lang="ts">
import { Button } from '@/components/ui/button'
import type { PluginError } from '@/plugins/api'
import { errorText, t } from '../labels'

defineProps<{ busy: boolean; error: PluginError | null }>()
const emit = defineEmits<{ more: [] }>()
</script>

<template>
  <div class="space-y-2">
    <p v-if="error" class="text-destructive text-sm" role="alert">{{ errorText(error) }}</p>
    <Button variant="outline" class="w-full" :disabled="busy" @click="emit('more')">
      {{ t('more') }}
    </Button>
  </div>
</template>
