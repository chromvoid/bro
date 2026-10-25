<script setup lang="ts">
import { Button } from '@/components/ui/button'
import type { StatusAction } from './statusModel'

defineProps<{ action: StatusAction }>()
const emit = defineEmits<{ done: [] }>()
</script>

<template>
  <Button
    v-if="action.to"
    as-child
    size="sm"
    :variant="action.variant ?? 'default'"
    class="shrink-0"
    :data-testid="action.testid"
  >
    <RouterLink :to="action.to" @click="emit('done')">{{ action.label }}</RouterLink>
  </Button>
  <Button
    v-else
    size="sm"
    :variant="action.variant ?? 'default'"
    class="shrink-0"
    :disabled="action.disabled"
    :data-testid="action.testid"
    @click="
      () => {
        action.run?.()
        emit('done')
      }
    "
  >
    {{ action.label }}
  </Button>
</template>
