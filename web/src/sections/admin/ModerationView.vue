<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import { Button } from '@/components/ui/button'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { z } from '@/core/zod'
import { useSession } from '@/stores/session'
const { t } = useI18n()
const session = useSession()
const Reports = z.object({
  reports: z.array(
    z.looseObject({
      id: z.string(),
      target_type: z.enum(['message', 'user', 'resource']),
      target_id: z.string(),
      reason: z.string(),
      revision: z.number(),
      evidence: z
        .looseObject({ text: z.string().optional(), name: z.string().optional() })
        .optional(),
    }),
  ),
})
const reports = useQuery({
  queryKey: ['community', 'moderation'],
  queryFn: async ({ signal }) =>
    Reports.parse(await api.get('/v1/admin/moderation/reports?status=open', signal)),
})
const busy = ref(false)
async function resolve(id: string, revision: number, action: 'dismiss' | 'hide' | 'disable') {
  if (busy.value) return
  busy.value = true
  try {
    await api.post(
      `/v1/admin/moderation/reports/${encodeURIComponent(id)}/resolve`,
      { action, revision },
      { key: crypto.randomUUID(), expectedUser: session.me?.id },
    )
    await reports.refetch()
  } catch (reason) {
    toast.error(errorText(reason, t))
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageHeader
    :title="t('moderation.title')"
    :description="t('moderation.queueText')"
    :back="{ name: 'admin' }"
  />
  <div class="mx-auto max-w-3xl space-y-4 px-4 pb-8 md:px-8">
    <LoadingState v-if="reports.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="reports.isLoadingError.value"
      :error="reports.error.value"
      @retry="reports.refetch()"
    />
    <p v-else-if="!reports.data.value?.reports.length" class="text-muted-foreground">
      {{ t('moderation.empty') }}
    </p>
    <article
      v-for="report in reports.data.value?.reports"
      :key="report.id"
      class="bg-card space-y-3 rounded-3xl border p-5"
    >
      <p class="font-medium">{{ report.reason }}</p>
      <p v-if="report.evidence" class="text-muted-foreground break-words whitespace-pre-wrap">
        {{ report.evidence.text ?? report.evidence.name }}
      </p>
      <div class="flex flex-wrap gap-2">
        <Button
          variant="outline"
          :disabled="busy"
          @click="resolve(report.id, report.revision, 'dismiss')"
          >{{ t('moderation.dismiss') }}</Button
        >
        <Button
          variant="destructive"
          :disabled="busy"
          @click="
            resolve(report.id, report.revision, report.target_type === 'user' ? 'disable' : 'hide')
          "
          >{{ t(report.target_type === 'user' ? 'moderation.disable' : 'moderation.hide') }}</Button
        >
      </div>
    </article>
  </div>
</template>
