<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import ReportPanel from './ReportPanel.vue'
defineProps<{ targetType: 'message' | 'user' | 'resource'; targetId: string }>()
const { t } = useI18n()
const open = ref(false)
</script>
<template>
  <Button variant="ghost" size="sm" class="text-muted-foreground" @click="open = true">{{
    t('moderation.report')
  }}</Button>
  <ReportPanel v-model:open="open" :target-type="targetType" :target-id="targetId" />
</template>
