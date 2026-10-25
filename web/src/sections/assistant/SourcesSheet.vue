<script setup lang="ts">
import { ExternalLink } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import type { Source } from './model'

defineProps<{ sources: Source[]; limitations: string[] }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()

function host(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}
</script>

<template>
  <Sheet v-model:open="open">
    <SheetContent class="w-full overflow-y-auto sm:max-w-lg">
      <SheetHeader>
        <SheetTitle>{{ t('assistant.sourcesTitle') }}</SheetTitle>
        <SheetDescription v-if="limitations.length">{{
          t('assistant.limitations')
        }}</SheetDescription>
      </SheetHeader>
      <ul v-if="limitations.length" class="text-muted-foreground list-disc space-y-1 px-8 text-sm">
        <li v-for="(limitation, index) in limitations" :key="index">{{ limitation }}</li>
      </ul>
      <ol class="space-y-3 px-4 pb-6">
        <li v-for="source in sources" :key="source.url" class="space-y-1.5 rounded-xl border p-3">
          <a
            :href="source.url"
            target="_blank"
            rel="noopener noreferrer"
            class="text-primary flex items-start gap-2 font-medium underline-offset-2 hover:underline"
          >
            <span class="min-w-0 flex-1">{{ source.title || host(source.url) }}</span>
            <ExternalLink class="mt-1 size-4 shrink-0" />
          </a>
          <p class="text-muted-foreground text-xs">
            {{ host(source.url)
            }}<template v-if="source.published_at"> · {{ source.published_at }}</template>
          </p>
          <p v-if="source.text" class="text-sm">«{{ source.text }}»</p>
          <Badge v-if="source.relation === 'contradicts'" variant="warning">{{
            t('assistant.contradicts')
          }}</Badge>
          <Badge v-else-if="source.relation === 'supports'" variant="success">{{
            t('assistant.supports')
          }}</Badge>
        </li>
      </ol>
    </SheetContent>
  </Sheet>
</template>
