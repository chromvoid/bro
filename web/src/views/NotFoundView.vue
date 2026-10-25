<script setup lang="ts">
import { FileQuestion } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import { Button } from '@/components/ui/button'

const { t } = useI18n()
</script>

<template>
  <PageHeader :title="t('app.notFound')" />
  <EmptyState :icon="FileQuestion" :title="t('app.notFound')" :description="t('app.notFoundText')">
    <Button as-child variant="outline">
      <RouterLink :to="{ name: 'today' }">{{ t('nav.today') }}</RouterLink>
    </Button>
  </EmptyState>
</template>
