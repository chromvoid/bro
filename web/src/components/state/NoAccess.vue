<script setup lang="ts">
import { Lock } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import EmptyState from './EmptyState.vue'

const { t } = useI18n()
</script>

<template>
  <EmptyState
    :icon="Lock"
    :title="t('state.noAccessTitle')"
    :description="t('state.noAccessText')"
  />
</template>
