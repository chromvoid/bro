<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useSession } from '@/stores/session'
const props = defineProps<{ targetType: 'message' | 'user' | 'resource'; targetId: string }>()
const { t } = useI18n()
const session = useSession()
const open = ref(false)
const reason = ref('')
const busy = ref(false)
const error = ref('')
let key = crypto.randomUUID()
async function report() {
  if (!reason.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api.post(
      '/v1/community/reports',
      { target_type: props.targetType, target_id: props.targetId, reason: reason.value.trim() },
      { key, expectedUser: session.me?.id },
    )
    open.value = false
    reason.value = ''
    key = crypto.randomUUID()
    toast.success(t('moderation.sent'))
  } catch (value) {
    error.value = errorText(value, t)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <Button variant="ghost" size="sm" class="text-muted-foreground" @click="open = true">{{
    t('moderation.report')
  }}</Button>
  <Panel v-model:open="open" :title="t('moderation.report')">
    <form class="space-y-3" @submit.prevent="report">
      <p class="text-muted-foreground text-sm">{{ t('moderation.description') }}</p>
      <Label :for="`report-${targetId}`">{{ t('moderation.reason') }}</Label>
      <Textarea :id="`report-${targetId}`" v-model="reason" maxlength="1000" :disabled="busy" />
      <p v-if="error" class="text-destructive text-sm" role="alert">{{ error }}</p>
      <Button type="submit" :disabled="busy || !reason.trim()">{{ t('moderation.submit') }}</Button>
    </form>
  </Panel>
</template>
