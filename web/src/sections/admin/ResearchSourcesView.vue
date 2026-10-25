<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ChevronRight, Globe, Plus } from '@lucide/vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { useLedger, type Operation } from '@/core/operations'
import {
  RESEARCH,
  ResearchRegistry,
  switches,
  type ResearchSource,
  type ResearchSettings,
} from './researchSources'

/**
 * The trusted sources research suggests to hosted Codex. The assistant keeps
 * the registry; this page shows it and takes the same operations (docs: research README).
 */
const { t } = useI18n()
const registry = useQuery({
  queryKey: ['research', 'sources'],
  queryFn: async ({ signal }) =>
    ResearchRegistry.parse(await api.get('/v1/research/sources', signal)),
})
const ledger = useLedger(RESEARCH)
const busy = ref(false)
const editing = ref(false)
const form = reactive({
  id: null as string | null,
  revision: 0,
  name: '',
  topics: [] as string[],
  domains: '',
  entry_url: '',
  priority: 50,
  best_for: '',
  status: 'active',
})
const topicLabel = (slug: string) =>
  registry.data.value?.topics.find((topic) => topic.slug === slug)?.label ?? slug
const active = computed(
  () => registry.data.value?.sources.filter((source) => source.status === 'active') ?? [],
)
const retired = computed(
  () => registry.data.value?.sources.filter((source) => source.status === 'retired') ?? [],
)
const pending = computed(
  () => registry.data.value?.sources.filter((source) => source.status === 'candidate') ?? [],
)
// Active sources under their first topic, topics in the registry's order.
const groups = computed(() => {
  const order = registry.data.value?.topics.map((topic) => topic.slug) ?? []
  const byTopic = new Map<string, ResearchSource[]>()
  for (const source of active.value) {
    const topic = source.topics[0] ?? 'unsorted'
    byTopic.set(topic, [...(byTopic.get(topic) ?? []), source])
  }
  return [...byTopic.entries()]
    .sort(([a], [b]) => order.indexOf(a) - order.indexOf(b))
    .map(([topic, sources]) => ({ topic, sources }))
})
function edit(source: ResearchSource | null) {
  Object.assign(form, {
    id: source?.id ?? null,
    revision: source?.revision ?? 0,
    name: source?.name ?? '',
    topics: [...(source?.topics ?? [])],
    domains: source?.domains.join(', ') ?? '',
    entry_url: source?.entry_url ?? '',
    priority: source?.priority ?? 50,
    best_for: source?.best_for ?? '',
    status: source?.status ?? 'active',
  })
  editing.value = true
}
const toggle = (list: string[], value: string, on: boolean | 'indeterminate') =>
  on === true ? [...new Set([...list, value])] : list.filter((item) => item !== value)

async function run(operation: Operation, done: string) {
  if (busy.value) return
  busy.value = true
  try {
    await ledger.operate(operation)
    toast.success(done)
    editing.value = false
  } catch (error) {
    toast.error(
      error instanceof ApiError && error.status === 409
        ? t('researchSources.changed')
        : errorText(error, t),
    )
  } finally {
    busy.value = false
  }
}

function save() {
  const fields = {
    name: form.name.trim(),
    topics: form.topics,
    domains: form.domains
      .split(/[\s,]+/)
      .map((domain) => domain.trim())
      .filter(Boolean),
    entry_url: form.entry_url.trim() || null,
    priority: Number(form.priority),
    best_for: form.best_for.trim(),
  }
  void run(
    form.id
      ? {
          kind: 'source.update',
          payload: { source_id: form.id, changes: fields },
          expected_revisions: { [form.id]: form.revision },
        }
      : { kind: 'source.add', payload: fields },
    t('researchSources.saved'),
  )
}
const decide = (source: ResearchSource, kind: string, done: string) =>
  run(
    {
      kind,
      payload: { source_id: source.id },
      expected_revisions: { [source.id]: source.revision },
    },
    done,
  )
const setting = (settings: ResearchSettings, key: (typeof switches)[number], on: boolean) =>
  run(
    { kind: 'settings.update', payload: { [key]: on, revision: settings.revision } },
    t('researchSources.settingsSaved'),
  )
</script>

<template>
  <PageHeader :title="t('researchSources.title')" :back="{ name: 'admin' }">
    <template #actions>
      <Button
        variant="ghost"
        size="icon"
        :aria-label="t('researchSources.add')"
        @click="edit(null)"
      >
        <Plus class="size-5" />
      </Button>
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <p class="text-muted-foreground text-sm">{{ t('researchSources.text') }}</p>
    <RefreshFailed :query="registry" />
    <LoadingState v-if="registry.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="registry.isLoadingError.value"
      :error="registry.error.value"
      @retry="registry.refetch()"
    />
    <Tabs v-else-if="registry.data.value" default-value="sources">
      <TabsList>
        <TabsTrigger value="sources">{{ t('researchSources.sources') }}</TabsTrigger>
        <TabsTrigger value="pending">{{
          t('researchSources.pending', { count: pending.length })
        }}</TabsTrigger>
        <TabsTrigger value="settings">{{ t('researchSources.settings') }}</TabsTrigger>
      </TabsList>
      <TabsContent value="sources" class="space-y-5 pt-3">
        <section v-for="group in groups" :key="group.topic" class="space-y-2">
          <h2 class="font-medium">{{ topicLabel(group.topic) }}</h2>
          <ul class="bg-card divide-y rounded-3xl border">
            <li v-for="source in group.sources" :key="source.id">
              <button
                type="button"
                class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3 text-left"
                @click="edit(source)"
              >
                <span class="min-w-0 flex-1">
                  <span class="block font-medium">{{ source.name }}</span>
                  <span class="text-muted-foreground block text-sm">{{
                    source.domains.join(', ')
                  }}</span>
                </span>
                <ChevronRight class="text-muted-foreground size-4" />
              </button>
            </li>
          </ul>
        </section>
        <details v-if="retired.length">
          <summary class="cursor-pointer text-sm">
            {{ t('researchSources.retired', { count: retired.length }) }}
          </summary>
          <ul class="mt-2 space-y-1 text-sm">
            <li v-for="source in retired" :key="source.id" class="flex items-center gap-2">
              <span class="flex-1">{{ source.name }} · {{ source.domains.join(', ') }}</span>
              <Button
                size="sm"
                variant="outline"
                :disabled="busy"
                @click="decide(source, 'source.restore', t('researchSources.restored'))"
                >{{ t('researchSources.restore') }}</Button
              >
            </li>
          </ul>
        </details>
      </TabsContent>
      <TabsContent value="pending" class="space-y-3 pt-3">
        <EmptyState
          v-if="!pending.length"
          :icon="Globe"
          :title="t('researchSources.nothingPending')"
          :description="t('researchSources.privacy')"
        />
        <article
          v-for="source in pending"
          :key="source.id"
          class="bg-card space-y-2 rounded-3xl border p-4"
          data-testid="pending-source"
        >
          <p class="font-medium">{{ source.name }}</p>
          <p class="text-muted-foreground text-sm">
            {{ source.domains.join(', ') }} · {{ t(`researchSources.origin.${source.origin}`) }}
          </p>
          <div class="flex flex-wrap gap-2">
            <Button
              size="sm"
              :disabled="busy"
              @click="decide(source, 'candidate.approve', t('researchSources.approved'))"
              >{{ t('researchSources.approve') }}</Button
            >
            <Button
              size="sm"
              variant="outline"
              :disabled="busy"
              @click="decide(source, 'candidate.reject', t('researchSources.rejected'))"
              >{{ t('researchSources.reject') }}</Button
            >
          </div>
        </article>
        <p v-if="pending.length" class="text-muted-foreground text-sm">
          {{ t('researchSources.privacy') }}
        </p>
      </TabsContent>
      <TabsContent value="settings" class="space-y-4 pt-3">
        <Label
          v-for="key in switches"
          :key="key"
          class="bg-card flex items-start gap-3 rounded-3xl border p-4 font-normal"
        >
          <Switch
            :model-value="registry.data.value.settings[key]"
            :disabled="busy"
            :aria-label="t(`researchSources.switch.${key}.title`)"
            @update:model-value="(on: boolean) => setting(registry.data.value!.settings, key, on)"
          />
          <span class="space-y-1">
            <span class="block font-medium">{{ t(`researchSources.switch.${key}.title`) }}</span>
            <span class="text-muted-foreground block text-sm">{{
              t(`researchSources.switch.${key}.text`)
            }}</span>
          </span>
        </Label>
      </TabsContent>
    </Tabs>
  </div>

  <Panel
    v-model:open="editing"
    :title="form.id ? form.name : t('researchSources.add')"
    :description="t('researchSources.editText')"
  >
    <form class="space-y-4" novalidate @submit.prevent="save">
      <div class="space-y-2">
        <Label for="source-name">{{ t('researchSources.name') }}</Label>
        <Input id="source-name" v-model="form.name" maxlength="200" />
      </div>
      <div class="space-y-2">
        <Label for="source-domains">{{ t('researchSources.domains') }}</Label>
        <Input id="source-domains" v-model="form.domains" placeholder="cbr.ru" />
      </div>
      <div class="space-y-2">
        <Label for="source-entry">{{ t('researchSources.entry') }}</Label>
        <Input id="source-entry" v-model="form.entry_url" placeholder="https://" />
      </div>
      <fieldset class="space-y-1.5">
        <legend class="mb-1 text-sm font-medium">{{ t('researchSources.topics') }}</legend>
        <Label
          v-for="topic in registry.data.value?.topics.filter((item) => item.status === 'active') ??
          []"
          :key="topic.slug"
          class="flex items-center gap-2 font-normal"
        >
          <Checkbox
            :model-value="form.topics.includes(topic.slug)"
            @update:model-value="(on) => (form.topics = toggle(form.topics, topic.slug, on))"
          />{{ topic.label }}
        </Label>
      </fieldset>
      <div class="space-y-2">
        <Label for="source-priority">{{ t('researchSources.priority') }}</Label>
        <Input id="source-priority" v-model="form.priority" type="number" min="0" max="100" />
      </div>
      <div class="space-y-2">
        <Label for="source-best-for">{{ t('researchSources.bestFor') }}</Label>
        <Textarea id="source-best-for" v-model="form.best_for" maxlength="500" rows="3" />
      </div>
      <Button
        type="submit"
        size="lg"
        class="w-full"
        :disabled="busy || !form.name.trim() || !form.domains.trim() || !form.topics.length"
        >{{ t('researchSources.save') }}</Button
      >
      <Button
        v-if="form.id && form.status === 'active'"
        type="button"
        variant="outline"
        class="w-full"
        :disabled="busy"
        @click="
          decide(
            registry.data.value!.sources.find((source) => source.id === form.id)!,
            'source.retire',
            t('researchSources.retiredDone'),
          )
        "
        >{{ t('researchSources.retire') }}</Button
      >
    </form>
  </Panel>
</template>
