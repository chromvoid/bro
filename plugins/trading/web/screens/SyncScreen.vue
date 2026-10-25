<script setup lang="ts">
import { computed } from 'vue'
import { Button } from '@/components/ui/button'
import type { PluginHost } from '@/plugins/api'
import { dateTime, percent } from '../format'
import { connectionLabel, domainLabel, errorText, phaseLabel, streamLabel, t } from '../labels'
import { pluginError, valueOf, type Trading } from '../model'
import ReadStatus from '../parts/ReadStatus.vue'
import Section from '../parts/Section.vue'

const props = defineProps<{ host: PluginHost; trading: Trading }>()
const model = props.trading.sync
const status = computed(() => valueOf(model.status.value))
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const refresh = () => void model.refresh()
</script>

<template>
  <Section>
    <ReadStatus :model="model.status.value" :host="host" @retry="refresh" />
    <div v-if="status" class="bg-card space-y-1 rounded-3xl border p-5" data-testid="sync-status">
      <p class="font-semibold">
        {{ connectionLabel(status.connection?.state ?? 'not_configured') }}
        <template v-if="status.connection?.api_key_hint">
          · {{ status.connection.api_key_hint }}</template
        >
      </p>
      <p class="text-muted-foreground text-sm">{{ streamLabel(status.stream.state) }}</p>
    </div>
    <ul v-if="status?.domains.length" class="bg-card divide-y rounded-3xl border">
      <li v-for="domain in status.domains" :key="domain.domain" class="space-y-0.5 px-4 py-3">
        <p class="font-medium">
          {{ domainLabel(domain.domain) }} · {{ phaseLabel(domain.phase) }}
          <template v-if="domain.progress_pct">
            · {{ percent(domain.progress_pct, false) }}</template
          >
        </p>
        <p class="text-muted-foreground text-sm">
          {{ t('watermark', dateTime(domain.watermark_at, zone)) }}
        </p>
        <p v-if="domain.last_error_code" class="text-destructive text-sm">
          {{ errorText(pluginError(domain.last_error_code)) }}
        </p>
        <p v-if="domain.coverage_gap_before" class="text-muted-foreground text-sm">
          {{ t('coverage_gap', dateTime(domain.coverage_gap_before, zone)) }}
        </p>
      </li>
    </ul>
  </Section>

  <section class="bg-card space-y-3 rounded-3xl border p-5" data-testid="refill">
    <p class="text-sm">{{ t('refill_hint') }}</p>
    <div
      v-for="write in model.refills.value"
      :key="write.clientKey"
      class="space-y-2"
      role="status"
    >
      <p class="text-sm font-medium">
        {{
          write.admitted
            ? t('refill_pending')
            : write.notFound
              ? t('refill_not_found')
              : t('refill_unknown')
        }}
      </p>
      <div class="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          :disabled="model.acting.value"
          @click="model.check(write.clientKey)"
        >
          {{ t('check_result') }}
        </Button>
        <Button
          v-if="!write.admitted && write.notFound"
          size="sm"
          variant="ghost"
          :disabled="model.acting.value"
          @click="model.resend(write.clientKey)"
        >
          {{ t('resend') }}
        </Button>
      </div>
    </div>
    <div v-if="model.notice.value" class="space-y-2" role="status">
      <p
        :class="[
          'text-sm',
          model.notice.value.kind === 'done' ? 'text-success' : 'text-destructive',
        ]"
      >
        {{
          model.notice.value.kind === 'done'
            ? t('refill_done')
            : model.notice.value.kind === 'failed'
              ? `${t('refill_failed')} ${errorText(model.notice.value.error)}`
              : errorText(model.notice.value.error)
        }}
      </p>
      <Button size="sm" variant="ghost" @click="model.dismiss()">{{ t('dismiss') }}</Button>
    </div>
    <Button
      v-if="model.canRefill.value"
      :disabled="model.acting.value || model.refills.value.length > 0"
      @click="model.refill()"
    >
      {{ t('refill') }}
    </Button>
    <p v-else class="text-muted-foreground text-sm">{{ t('no_write') }}</p>
  </section>
</template>
