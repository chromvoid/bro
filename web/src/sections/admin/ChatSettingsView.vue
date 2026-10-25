<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import {
  ChatSettings,
  providers,
  purposes,
  type ChatChoices,
  type ModelChoice,
} from './chatSettings'

const { t } = useI18n()
const queries = useQueryClient()
const queryKey = ['admin', 'chat-settings']
const settings = useQuery({
  queryKey,
  queryFn: async ({ signal }) =>
    ChatSettings.parse(await api.get('/v1/admin/chat-settings', signal)),
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
})
type Form = { providers: ChatChoices; purposes: Record<string, ModelChoice> }
type Section = { id: string; kind: keyof Form; title: string; text?: string }
const form = ref<Form>()
const revision = ref(0)
const busy = ref(false)
const failure = ref<unknown>()
const copyForm = (value: Form): Form => ({
  providers: { codex: { ...value.providers.codex } },
  purposes: Object.fromEntries(
    Object.entries(value.purposes).map(([purpose, choice]) => [purpose, { ...choice }]),
  ),
})
// The chat first, then each Codex call a plugin declared and this client can name.
const sections = computed<Section[]>(() => [
  ...providers.map((id) => ({ id, kind: 'providers' as const, title: t('chatSettings.chat') })),
  ...purposes
    .filter((id) => id in (settings.data.value?.purposes ?? {}))
    .map((id) => ({
      id,
      kind: 'purposes' as const,
      title: t(`chatSettings.purposes.${id}.title`),
      text: t(`chatSettings.purposes.${id}.text`),
    })),
])
const choiceIn = (value: Form, section: Section): ModelChoice =>
  (value[section.kind] as Record<string, ModelChoice>)[section.id]!
watch(
  () => settings.data.value,
  (value) => {
    if (!value) return
    form.value = copyForm(value)
    revision.value = value.revision
  },
  { immediate: true },
)
const models = () => settings.data.value?.catalog.codex ?? []
const efforts = (section: Section) =>
  models().find((model) => model.id === choiceIn(form.value!, section).model)?.reasoning_efforts ??
  []
watch(
  () => sections.value.map((section) => form.value && choiceIn(form.value, section).model),
  () => {
    if (!form.value) return
    for (const section of sections.value) {
      const choice = choiceIn(form.value, section)
      if (!efforts(section).includes(choice.reasoning_effort))
        choice.reasoning_effort = efforts(section)[0] ?? 'low'
    }
  },
)
const valid = computed(
  () =>
    form.value &&
    sections.value.every((section) =>
      efforts(section).includes(choiceIn(form.value!, section).reasoning_effort),
    ),
)
const dirty = computed(() =>
  sections.value.some((section) => {
    if (!form.value || !settings.data.value) return false
    const current = choiceIn(form.value, section)
    const saved = choiceIn(settings.data.value, section)
    return current.model !== saved.model || current.reasoning_effort !== saved.reasoning_effort
  }),
)
function defaults() {
  if (settings.data.value)
    form.value = copyForm({
      providers: settings.data.value.defaults,
      purposes: settings.data.value.purpose_defaults,
    })
}
async function reload() {
  failure.value = undefined
  await settings.refetch()
  // A reload also discards an unsaved draft when the server revision has not changed.
  if (settings.data.value) {
    form.value = copyForm(settings.data.value)
    revision.value = settings.data.value.revision
  }
}
async function save() {
  if (!form.value || busy.value || !valid.value) return
  busy.value = true
  failure.value = undefined
  try {
    const { data } = await api.send(
      'PUT',
      '/v1/admin/chat-settings',
      JSON.stringify({
        providers: form.value.providers,
        purposes: form.value.purposes,
        revision: revision.value,
      }),
    )
    queries.setQueryData(queryKey, ChatSettings.parse(data))
    await queries.invalidateQueries({ queryKey: ['accounts', 'audit'] })
    toast.success(t('chatSettings.saved'))
  } catch (error) {
    failure.value = error
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <PageHeader
    :title="t('chatSettings.title')"
    :description="t('chatSettings.text')"
    :back="{ name: 'admin' }"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <RefreshFailed :query="settings" />
    <LoadingState v-if="settings.isPending.value" :rows="1" />
    <ErrorState
      v-else-if="settings.isLoadingError.value"
      :error="settings.error.value"
      @retry="reload"
    />
    <form v-else-if="form" class="space-y-5" @submit.prevent="save">
      <fieldset
        v-for="section in sections"
        :key="section.id"
        :disabled="busy"
        class="bg-card space-y-4 rounded-3xl border p-5"
      >
        <legend class="px-1 font-semibold">{{ section.title }}</legend>
        <p v-if="section.text" class="text-muted-foreground text-sm">{{ section.text }}</p>
        <div class="space-y-1.5">
          <Label :for="`chat-model-${section.id}`">{{ t('chatSettings.model') }}</Label>
          <NativeSelect :id="`chat-model-${section.id}`" v-model="choiceIn(form, section).model">
            <option
              v-if="!models().some((model) => model.id === choiceIn(form!, section).model)"
              :value="choiceIn(form, section).model"
              disabled
            >
              {{ choiceIn(form, section).model }} · {{ t('chatSettings.unavailable') }}
            </option>
            <option v-for="model in models()" :key="model.id" :value="model.id">
              {{ model.name }}
            </option>
          </NativeSelect>
        </div>
        <div class="space-y-1.5">
          <Label :for="`chat-reasoning-${section.id}`">{{ t('chatSettings.reasoning') }}</Label>
          <NativeSelect
            :id="`chat-reasoning-${section.id}`"
            v-model="choiceIn(form, section).reasoning_effort"
          >
            <option v-for="effort in efforts(section)" :key="effort" :value="effort">
              {{ t(`chatSettings.efforts.${effort}`) }}
            </option>
          </NativeSelect>
        </div>
      </fieldset>
      <p class="text-muted-foreground text-sm">
        {{ t('chatSettings.accountsHint') }}
        <RouterLink :to="{ name: 'accounts' }" class="text-foreground underline">{{
          t('admin.accounts')
        }}</RouterLink>
      </p>
      <ErrorState v-if="failure" :error="failure" @retry="reload" />
      <div class="flex flex-wrap gap-3">
        <Button type="submit" :disabled="busy || !valid || !dirty">{{
          t('chatSettings.save')
        }}</Button>
        <Button type="button" variant="outline" :disabled="busy" @click="defaults">{{
          t('chatSettings.defaults')
        }}</Button>
      </div>
    </form>
  </div>
</template>
