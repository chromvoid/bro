<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import NoAccess from '@/components/state/NoAccess.vue'
import { Button } from '@/components/ui/button'

const { t } = useI18n()
</script>

<template>
  <PageHeader :title="t('state.noAccessTitle')" />
  <NoAccess />
  <div class="flex justify-center">
    <Button as-child variant="outline">
      <RouterLink :to="{ name: 'today' }">{{ t('nav.today') }}</RouterLink>
    </Button>
  </div>
</template>
