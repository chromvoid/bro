<script setup lang="ts">
import { MISSING } from '../format'
import { t } from '../labels'

/**
 * A figure the service may not determine: then a muted dash, not «не определено» at headline size;
 * screen readers still hear the words, and CalculationIssues says why.
 */
defineProps<{ text: string | null }>()
</script>

<template>
  <template v-if="text !== null">{{ text }}</template>
  <template v-else>
    <span class="text-muted-foreground" aria-hidden="true">{{ MISSING }}</span>
    <span class="sr-only">{{ t('calculation_unknown') }}</span>
  </template>
</template>
