<script setup lang="ts">
import { ChevronLeft, ChevronRight, FileJson, Search, ShieldCheck } from '@lucide/vue'
import { useQuery } from '@tanstack/vue-query'
import { computed, ref, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { dayIn, momentLabel } from '@/core/dates'
import {
  ARCHIVE_PAGE_SIZE,
  SourceAnalysisReply,
  archiveLabelKey,
  archiveValueText,
  searchArchive,
  type ArchiveField,
  type ArchivePage,
  type SourceAnalysisSummary,
} from './bodyArchive'

const props = defineProps<{
  archive: SourceAnalysisSummary | null
  archiveListTruncated?: boolean
  timezone?: string
  today?: string
}>()
const { t } = useI18n()
const searchId = useId()
const open = ref(false)
const filter = ref('')
const page = ref(0)
const searching = ref(false)
const searchFailed = ref(false)
const result = shallowRef<ArchivePage>({ rows: [], total: 0 })
const expandedText = ref<string | null>(null)
const textOffset = ref(0)
const TEXT_PAGE_SIZE = 2000
const details = useQuery({
  queryKey: computed(() => ['personal', 'analysis', props.archive?.id, props.archive?.revision]),
  enabled: computed(() => open.value && props.archive !== null),
  gcTime: 0,
  staleTime: Infinity,
  // Generic deep merging assigns special keys; original provider keys must survive refetches.
  structuralSharing: false,
  queryFn: async ({ signal }) => {
    const expected = props.archive
    if (!expected) throw new Error('No source analysis selected')
    const { analysis } = SourceAnalysisReply.parse(
      await api.get(`/v1/personal/analyses/${encodeURIComponent(expected.id)}`, signal),
    )
    if (
      analysis.id !== expected.id ||
      analysis.source_id !== expected.source_id ||
      analysis.provider_record_id !== expected.provider_record_id ||
      analysis.revision < expected.revision
    )
      throw new Error('Source analysis changed')
    return analysis
  },
})

const loaded = computed(() => {
  const analysis = details.data.value
  const expected = props.archive
  if (!analysis || !expected) return null
  return analysis.id === expected.id &&
    analysis.source_id === expected.source_id &&
    analysis.revision >= expected.revision
    ? analysis
    : null
})
const pageCount = computed(() => Math.max(1, Math.ceil(result.value.total / ARCHIVE_PAGE_SIZE)))
const dateLabel = computed(() => {
  const value = loaded.value?.received_at ?? props.archive?.received_at
  const zone = props.timezone ?? 'UTC'
  return value ? momentLabel(value, zone, props.today ?? dayIn(zone)) : ''
})

function fieldLabel(key: string): string {
  const translation = archiveLabelKey(key)
  return translation ? t(translation) : key
}
function valueLabel(field: ArchiveField): string {
  if (field.type === 'null') return t('personal.archive.null')
  if (field.type === 'boolean') return t(`personal.archive.${field.value ? 'true' : 'false'}`)
  if (field.type === 'array') return t('personal.archive.emptyArray')
  if (field.type === 'object') return t('personal.archive.emptyObject')
  const text = archiveValueText(field)
  return text === '' ? t('personal.archive.emptyString') : text
}
function groupLabel(field: ArchiveField): string {
  if (field.parts[0] === 'detail') return t('personal.archive.detail')
  if (field.parts[0] === 'summary') return t('personal.archive.summary')
  return t('personal.archive.sourceData')
}
function toggle(event: Event) {
  open.value = (event.target as HTMLDetailsElement).open
}
function nextText(field: ArchiveField) {
  textOffset.value = (expandedText.value === field.path ? textOffset.value : 0) + TEXT_PAGE_SIZE
  expandedText.value = field.path
}

watch([filter, loaded], () => {
  page.value = 0
})
watch(
  [open, loaded, filter, page],
  ([visible, analysis, query, currentPage], _, onCleanup) => {
    const controller = new AbortController()
    expandedText.value = null
    textOffset.value = 0
    result.value = { rows: [], total: 0 }
    searchFailed.value = false
    searching.value = !!visible && analysis !== null
    if (!visible || !analysis) return
    const timer = setTimeout(
      () => {
        void searchArchive(analysis.payload, {
          query,
          page: currentPage,
          signal: controller.signal,
          label: fieldLabel,
        })
          .then((found) => {
            if (!controller.signal.aborted) result.value = found
          })
          .catch(() => {
            if (!controller.signal.aborted) searchFailed.value = true
          })
          .finally(() => {
            if (!controller.signal.aborted) searching.value = false
          })
      },
      query ? 180 : 0,
    )
    onCleanup(() => {
      controller.abort()
      clearTimeout(timer)
    })
  },
  { immediate: true },
)
</script>

<template>
  <details
    class="bg-muted/20 overflow-hidden rounded-2xl border"
    data-testid="body-source-details"
    @toggle="toggle"
  >
    <summary class="text-primary cursor-pointer px-4 py-4 text-sm font-semibold">
      <FileJson class="mr-2 inline size-4 align-text-bottom" aria-hidden="true" />
      {{ t('personal.archive.title') }}
      <span v-if="archive" class="text-muted-foreground ml-2 text-xs font-normal">
        {{ t('personal.archive.version', { revision: archive.revision }) }}
      </span>
    </summary>
    <div v-if="open" class="space-y-4 border-t p-4">
      <div class="text-muted-foreground flex gap-2 text-sm">
        <ShieldCheck class="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <p>{{ t('personal.archive.readOnly') }}</p>
      </div>
      <RefreshFailed :query="details" />
      <p v-if="!archive" class="text-muted-foreground rounded-xl border border-dashed p-4 text-sm">
        {{ t(archiveListTruncated ? 'personal.archive.narrowPeriod' : 'personal.archive.waiting') }}
      </p>
      <LoadingState v-else-if="details.isPending.value" :rows="2" />
      <ErrorState
        v-else-if="details.isLoadingError.value"
        :error="details.error.value"
        :retrying="details.isFetching.value"
        @retry="details.refetch()"
      />
      <template v-else-if="loaded">
        <div class="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
          <span>{{ loaded.source_name ?? t('personal.archive.sourceData') }}</span>
          <span>{{ t('personal.archive.saved', { date: dateLabel }) }}</span>
          <span>{{ t('personal.archive.version', { revision: loaded.revision }) }}</span>
        </div>
        <div class="space-y-1.5">
          <Label :for="searchId">{{ t('personal.archive.search') }}</Label>
          <div class="relative">
            <Search
              class="text-muted-foreground absolute top-3.5 left-3 size-4"
              aria-hidden="true"
            />
            <Input
              :id="searchId"
              v-model="filter"
              class="pl-9"
              type="search"
              :placeholder="t('personal.archive.searchHint')"
              autocomplete="off"
              maxlength="200"
            />
          </div>
        </div>
        <p v-if="searching" class="text-muted-foreground py-4 text-sm" role="status">
          {{ t('personal.archive.searching') }}
        </p>
        <p v-else-if="searchFailed" class="text-destructive py-4 text-sm" role="alert">
          {{ t('personal.archive.searchFailed') }}
        </p>
        <template v-else>
          <p class="text-muted-foreground text-xs" role="status">
            {{ t('personal.archive.count', { count: result.total }) }}
          </p>
          <p v-if="!result.rows.length" class="text-muted-foreground py-5 text-sm">
            {{ t('personal.archive.noMatches') }}
          </p>
          <dl v-else class="divide-y overflow-hidden rounded-xl border bg-background">
            <div
              v-for="field in result.rows"
              :key="field.path"
              class="min-w-0 space-y-2 p-3 sm:grid sm:grid-cols-2 sm:gap-4 sm:space-y-0"
              data-testid="archive-field"
            >
              <dt class="min-w-0 space-y-1">
                <span class="block text-sm font-medium wrap-anywhere">{{
                  fieldLabel(field.key)
                }}</span>
                <code
                  v-if="archiveLabelKey(field.key)"
                  class="text-muted-foreground block text-xs wrap-anywhere"
                  >{{ field.key }}</code
                >
                <span class="text-muted-foreground block text-xs">{{ groupLabel(field) }}</span>
                <code class="text-muted-foreground block text-xs wrap-anywhere">{{
                  field.path
                }}</code>
              </dt>
              <dd class="min-w-0 text-sm">
                <template v-if="valueLabel(field).length > TEXT_PAGE_SIZE">
                  <pre class="max-h-64 overflow-auto font-sans whitespace-pre-wrap wrap-anywhere">{{
                    valueLabel(field).slice(
                      expandedText === field.path ? textOffset : 0,
                      (expandedText === field.path ? textOffset : 0) + TEXT_PAGE_SIZE,
                    )
                  }}</pre>
                  <p class="text-muted-foreground my-2 text-xs">
                    {{
                      t('personal.archive.textPart', {
                        start: (expandedText === field.path ? textOffset : 0) + 1,
                        end: Math.min(
                          (expandedText === field.path ? textOffset : 0) + TEXT_PAGE_SIZE,
                          valueLabel(field).length,
                        ),
                        total: valueLabel(field).length,
                      })
                    }}
                  </p>
                  <div class="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      :disabled="expandedText !== field.path || textOffset === 0"
                      :aria-label="t('personal.archive.previousText')"
                      @click="textOffset = Math.max(0, textOffset - TEXT_PAGE_SIZE)"
                      ><ChevronLeft class="size-4" aria-hidden="true" />
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      :disabled="
                        expandedText === field.path &&
                        textOffset + TEXT_PAGE_SIZE >= valueLabel(field).length
                      "
                      :aria-label="t('personal.archive.nextText')"
                      @click="nextText(field)"
                      ><ChevronRight class="size-4" aria-hidden="true" />
                    </Button>
                  </div>
                </template>
                <span v-else class="whitespace-pre-wrap wrap-anywhere">{{
                  valueLabel(field)
                }}</span>
              </dd>
            </div>
          </dl>
          <nav
            v-if="pageCount > 1"
            class="flex flex-wrap items-center justify-between gap-2"
            :aria-label="t('personal.archive.pages')"
          >
            <Button variant="outline" size="sm" :disabled="page === 0" @click="page--">
              <ChevronLeft class="size-4" aria-hidden="true" />{{ t('personal.archive.previous') }}
            </Button>
            <span class="text-muted-foreground text-xs">{{
              t('personal.archive.page', { page: page + 1, total: pageCount })
            }}</span>
            <Button variant="outline" size="sm" :disabled="page + 1 >= pageCount" @click="page++">
              {{ t('personal.archive.next') }}<ChevronRight class="size-4" aria-hidden="true" />
            </Button>
          </nav>
        </template>
      </template>
    </div>
  </details>
</template>
