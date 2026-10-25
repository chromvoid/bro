<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ChevronRight, Mic, Plus, Square, Volume2 } from '@lucide/vue'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import AmbientSettings from '@/ambient/AmbientSettings.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { api } from '@/core/client'
import { describeError } from '@/core/errors'
import { ApiError, NetworkError, writeAttempt } from '@/core/http'
import { speechPlayback } from '@/speech/playback'
import { useSession } from '@/stores/session'
import {
  missingSelection,
  SpeechSnapshot,
  speechSettingsClient,
  usableConnections,
  usableModel,
  type SpeechCatalogState,
  type SpeechConnection,
  type SpeechPreferences,
} from './api'
import SpeechConnectionCatalog from './SpeechConnectionCatalog.vue'
import SpeechModelSelect from './SpeechModelSelect.vue'

const props = withDefaults(defineProps<{ admin?: boolean }>(), { admin: false })
const { t } = useI18n()
const session = useSession()
const queries = useQueryClient()
const owner = computed(() => session.me?.id)
const queryKey = computed(() => ['speech', owner.value, props.admin ? 'admin' : 'me'])
const endpoint = computed(() => `/v1/${props.admin ? 'admin' : 'me'}/speech`)
const settings = useQuery({
  queryKey,
  enabled: computed(() => !!owner.value),
  queryFn: async ({ signal }) => SpeechSnapshot.parse(await api.get(endpoint.value, signal)),
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
})
const preferences = ref<SpeechPreferences>()
const busy = ref(false)
const failure = ref('')
const editing = ref(false)
const deleting = ref(false)
const catalogs = reactive<Record<string, SpeechCatalogState>>({})
const catalogControllers = new Map<string, AbortController>()
let pendingVoiceChoice: string | null = null
let controller = new AbortController()
const form = reactive({
  id: '',
  revision: 0,
  provider: '',
  label: '',
  enabled: true,
  api_key: '',
  has_key: false,
})
const managed = computed(
  () =>
    settings.data.value?.connections.filter(
      (item) => item.ownership === (props.admin ? 'shared' : 'personal'),
    ) ?? [],
)
const shared = computed(() =>
  props.admin
    ? []
    : (settings.data.value?.connections.filter((item) => item.ownership === 'shared') ?? []),
)
const providers = computed(
  () => settings.data.value?.providers.filter((item) => item.connection_kind === 'api_key') ?? [],
)
const readyCatalogs = computed(() =>
  Object.fromEntries(
    Object.entries(catalogs)
      .filter(([, value]) => value.phase === 'ready')
      .map(([id, value]) => [id, value.models]),
  ),
)
const asr = computed(() => usableConnections(settings.data.value, 'asr', readyCatalogs.value))
const tts = computed(() => usableConnections(settings.data.value, 'tts', readyCatalogs.value))
const backup = asr
const autoCatalog = computed<SpeechCatalogState>(() => ({
  revision: 0,
  phase: 'ready',
  models:
    settings.data.value?.providers.find((item) => item.connection_kind === 'subscription')
      ?.models ?? [],
  failure: '',
}))
// The server's own recognizer: listed only when the server has it, and needs no connection.
const localProvider = computed(() =>
  settings.data.value?.providers.find((item) => item.connection_kind === 'local'),
)
const localCatalog = computed<SpeechCatalogState>(() => ({
  revision: 0,
  phase: 'ready',
  models: localProvider.value?.models ?? [],
  failure: '',
}))
const asrMissing = computed(() =>
  preferences.value?.asr === 'local'
    ? !localProvider.value
    : missingSelection(preferences.value?.asr ?? null, asr.value),
)
const asrChoice = computed({
  get: () => preferences.value?.asr ?? 'auto',
  set: (value: string) => {
    if (!preferences.value || preferences.value.asr === value) return
    preferences.value.asr = value
    preferences.value.asr_model = null
  },
})
const ttsChoice = computed({
  get: () => preferences.value?.tts ?? '',
  set: (value: string) => {
    if (!preferences.value || preferences.value.tts === (value || null)) return
    stopPreview()
    preferences.value.tts = value || null
    preferences.value.tts_model = null
    pendingVoiceChoice = value || null
  },
})
const backupChoice = computed({
  get: () => preferences.value?.fallback_asr ?? '',
  set: (value: string) => {
    if (!preferences.value || preferences.value.fallback_asr === (value || null)) return
    preferences.value.fallback_asr = value || null
    preferences.value.fallback_asr_model = null
  },
})
const asrCatalog = computed(() => catalogFor(preferences.value?.asr))
const backupCatalog = computed(() => catalogFor(preferences.value?.fallback_asr))
const ttsCatalog = computed(() => catalogFor(preferences.value?.tts))
const selectedAsrModel = computed(() =>
  choiceModel(asrCatalog.value, preferences.value?.asr_model, 'asr'),
)
const excludedBackupModel = computed(() =>
  preferences.value?.asr === preferences.value?.fallback_asr
    ? selectedAsrModel.value?.id
    : undefined,
)
const selectedBackupModel = computed(() =>
  choiceModel(
    backupCatalog.value,
    preferences.value?.fallback_asr_model,
    'asr',
    excludedBackupModel.value,
  ),
)
const selectedModel = computed(() =>
  choiceModel(ttsCatalog.value, preferences.value?.tts_model, 'tts'),
)
const asrModelChoice = computed({
  get: () => preferences.value?.asr_model ?? selectedAsrModel.value?.id ?? null,
  set: (value: string | null) => {
    if (preferences.value) preferences.value.asr_model = value
  },
})
const backupModelChoice = computed({
  get: () => preferences.value?.fallback_asr_model ?? selectedBackupModel.value?.id ?? null,
  set: (value: string | null) => {
    if (preferences.value) preferences.value.fallback_asr_model = value
  },
})
const ttsModelChoice = computed({
  get: () => preferences.value?.tts_model ?? selectedModel.value?.id ?? null,
  set: (value: string | null) => {
    if (!preferences.value || preferences.value.tts_model === value) return
    stopPreview()
    preferences.value.tts_model = value
    const model = selectedModel.value
    if (model && !model.voices.includes(preferences.value.voice) && model.default_voice)
      preferences.value.voice = model.default_voice
  },
})
const voiceChoice = computed({
  get: () => preferences.value?.voice ?? '',
  set: (value: string) => {
    if (!preferences.value || preferences.value.voice === value) return
    stopPreview()
    preferences.value.voice = value
  },
})
const voices = computed(() => selectedModel.value?.voices ?? [])
const validTts = computed(
  () =>
    !!preferences.value?.tts &&
    !missingSelection(preferences.value.tts, tts.value) &&
    ttsCatalog.value?.phase === 'ready' &&
    !!selectedModel.value &&
    usableModel(selectedModel.value, 'tts') &&
    voices.value.includes(preferences.value.voice),
)
const dirty = computed(
  () => JSON.stringify(preferences.value) !== JSON.stringify(settings.data.value?.preferences),
)
const valid = computed(
  () =>
    preferences.value &&
    !asrMissing.value &&
    asrCatalog.value?.phase === 'ready' &&
    !!selectedAsrModel.value &&
    usableModel(selectedAsrModel.value, 'asr') &&
    !missingSelection(preferences.value.fallback_asr, backup.value) &&
    (!preferences.value.fallback_asr ||
      (backupCatalog.value?.phase === 'ready' &&
        !!selectedBackupModel.value &&
        selectedBackupModel.value.id !== excludedBackupModel.value &&
        usableModel(selectedBackupModel.value, 'asr'))) &&
    (!preferences.value.tts || validTts.value),
)
const validConnection = computed(
  () => !!form.provider && !!form.label.trim() && (form.has_key || !!form.api_key.trim()),
)
const playingPreview = computed(
  () =>
    speechPlayback.state.value.key === 'preview' && speechPlayback.state.value.status !== 'idle',
)
const readiness = computed(() => {
  const status = settings.data.value?.asr
  if (!status || status.state === 'configured') return ''
  if (status.code === 'not_configured') return 'speechSettings.notConfigured'
  if (status.code === 'no_access' || status.code === 'access_denied')
    return 'speechSettings.noAccess'
  if (status.code === 'auth_required') return 'speechSettings.authRequired'
  return 'speechSettings.unavailable'
})
const ttsReadiness = computed(() => {
  if (!preferences.value?.tts) return ''
  if (missingSelection(preferences.value.tts, tts.value)) {
    const available = settings.data.value?.connections.some(
      (item) => item.id === preferences.value?.tts && item.enabled && item.has_key,
    )
    return available ? '' : 'speechSettings.noAccess'
  }
  // The API reports readiness for the saved selection, not the unsaved form choice.
  const saved = settings.data.value?.preferences
  if (
    preferences.value.tts !== saved?.tts ||
    preferences.value.tts_model !== saved?.tts_model ||
    preferences.value.voice !== saved?.voice
  )
    return ''
  const status = settings.data.value?.tts
  if (!status || status.state === 'configured') return ''
  if (
    preferences.value.tts === 'auto' &&
    ['not_configured', 'no_access', 'access_denied'].includes(status.code ?? '')
  )
    return 'speechSettings.codexTtsUnavailable'
  if (status.code === 'not_configured') return 'speechPlayback.errors.not_configured'
  if (status.code === 'no_access' || status.code === 'access_denied')
    return 'speechPlayback.errors.no_access'
  if (status.code === 'auth_required') return 'speechSettings.authRequired'
  if (status.code === 'execution_disabled') return 'speechPlayback.errors.execution_disabled'
  return 'speechSettings.ttsUnavailable'
})

watch(
  () => settings.data.value?.preferences,
  (value) => {
    if (value && !preferences.value) preferences.value = { ...value }
  },
  { immediate: true },
)
watch(
  () => settings.data.value,
  () => syncCatalogs(),
  { immediate: true },
)
watch(
  () => [ttsChoice.value, selectedModel.value],
  () => {
    const model = selectedModel.value
    if (preferences.value && model && pendingVoiceChoice === ttsChoice.value) {
      if (!model.voices.includes(preferences.value.voice) && model.default_voice)
        preferences.value.voice = model.default_voice
      pendingVoiceChoice = null
    }
  },
)
watch(editing, (open) => {
  if (!open) form.api_key = ''
})
watch(
  owner,
  () => {
    controller.abort()
    clearCatalogs()
    pendingVoiceChoice = null
    controller = new AbortController()
    form.api_key = ''
    editing.value = false
    deleting.value = false
    preferences.value = undefined
    failure.value = ''
    busy.value = false
    speechPlayback.stop()
  },
  { flush: 'sync' },
)
onBeforeUnmount(() => {
  form.api_key = ''
  controller.abort()
  clearCatalogs()
})

function stopPreview() {
  if (speechPlayback.state.value.key === 'preview') speechPlayback.stop()
}

function client() {
  if (!owner.value) throw new DOMException('Signed out', 'AbortError')
  return speechSettingsClient(owner.value, () => owner.value, controller.signal, props.admin)
}
function catalogFor(choice: string | null | undefined) {
  if (!choice) return undefined
  if (choice === 'auto') return autoCatalog.value
  if (choice === 'local') return localProvider.value ? localCatalog.value : undefined
  return catalogs[choice]
}
function choiceModel(
  catalog: SpeechCatalogState | undefined,
  selected: string | null | undefined,
  capability: 'asr' | 'tts',
  excluded?: string,
) {
  const available = catalog?.models.filter((model) => model.capabilities.includes(capability)) ?? []
  return selected
    ? available.find((model) => model.id === selected)
    : available.find((model) => model.id !== excluded && usableModel(model, capability))
}
function clearCatalogs() {
  for (const pending of catalogControllers.values()) pending.abort()
  catalogControllers.clear()
  for (const id of Object.keys(catalogs)) delete catalogs[id]
}
function syncCatalogs() {
  const connections = settings.data.value?.connections ?? []
  const active = new Set(
    connections.filter((item) => item.enabled && item.has_key).map((item) => item.id),
  )
  for (const id of Object.keys(catalogs)) {
    if (!active.has(id)) {
      catalogControllers.get(id)?.abort()
      catalogControllers.delete(id)
      delete catalogs[id]
      speechPlayback.invalidateConnection(id)
    }
  }
  if (!owner.value) return
  for (const connection of connections) {
    if (!active.has(connection.id)) continue
    const provider = settings.data.value?.providers.find((item) => item.id === connection.provider)
    const current = catalogs[connection.id]
    if (current?.revision === connection.revision) {
      if (!provider?.dynamic_models && current.phase === 'ready')
        current.models = provider?.models ?? []
      continue
    }
    if (provider?.dynamic_models) void loadCatalog(connection)
    else {
      catalogControllers.get(connection.id)?.abort()
      catalogControllers.delete(connection.id)
      catalogs[connection.id] = {
        revision: connection.revision,
        phase: 'ready',
        models: provider?.models ?? [],
        failure: '',
      }
    }
  }
}
async function loadCatalog(connection: SpeechConnection, refresh = false) {
  catalogControllers.get(connection.id)?.abort()
  const pending = new AbortController()
  catalogControllers.set(connection.id, pending)
  const { signal } = pending
  catalogs[connection.id] = {
    revision: connection.revision,
    phase: 'loading',
    models: [],
    failure: '',
  }
  try {
    const models = await client().models(connection.id, signal, refresh)
    if (signal.aborted) return
    catalogs[connection.id] = { revision: connection.revision, phase: 'ready', models, failure: '' }
  } catch (error) {
    if (!signal.aborted)
      catalogs[connection.id] = {
        revision: connection.revision,
        phase: 'error',
        models: [],
        failure: modelError(error),
      }
  } finally {
    if (catalogControllers.get(connection.id) === pending) catalogControllers.delete(connection.id)
  }
}
function refreshCatalog(choice: string | null | undefined) {
  const connection = settings.data.value?.connections.find((item) => item.id === choice)
  if (connection?.enabled && connection.has_key) void loadCatalog(connection, true)
}
function modelError(error: unknown) {
  if (error instanceof NetworkError)
    return `speechSettings.catalogErrors.${error.timedOut ? 'timeout' : 'connection'}`
  if (error instanceof ApiError) {
    if (error.status === 401) return 'speechPlayback.errors.session_expired'
    if (error.code === 'auth_required') return 'speechSettings.authRequired'
    if (error.code === 'no_access' || error.code === 'access_denied')
      return 'speechSettings.noAccess'
    if (error.code === 'speech_key_unavailable') return 'speechSettings.keyStoreUnavailable'
    if (error.code === 'provider_error') return 'speechSettings.modelsUnavailable'
    if (error.code === 'execution_disabled') return 'speechPlayback.errors.execution_disabled'
    if (
      [
        'application_limit',
        'provider_limit',
        'busy',
        'timeout',
        'connection',
        'secret_unavailable',
      ].includes(error.code ?? '')
    )
      return `speechSettings.catalogErrors.${error.code}`
  }
  return 'speechSettings.modelsUnavailable'
}
function edit(connection?: SpeechConnection) {
  failure.value = ''
  Object.assign(form, {
    id: connection?.id ?? crypto.randomUUID(),
    revision: connection?.revision ?? 0,
    provider: connection?.provider ?? providers.value[0]?.id ?? '',
    label: connection?.label ?? '',
    enabled: connection?.enabled ?? true,
    api_key: '',
    has_key: connection?.has_key ?? false,
  })
  editing.value = true
}
function errorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 409) return t('speechSettings.changed')
  if (error instanceof ApiError && error.code === 'speech_key_unavailable')
    return t('speechSettings.keyStoreUnavailable')
  if (error instanceof ApiError && error.status >= 400 && error.status < 500)
    return t(describeError(error))
  if (writeAttempt(error) === 'unknown') return t('speechSettings.unknown')
  // API keys must never be echoed by an upstream validation message.
  return t(describeError(error))
}
async function reload() {
  const signal = controller.signal
  failure.value = ''
  const result = await settings.refetch()
  if (signal.aborted) return
  if (result.data?.preferences) preferences.value = { ...result.data.preferences }
}
function reloadEditor() {
  editing.value = false
  void reload()
}
async function write(action: () => Promise<void>, success: string) {
  const signal = controller.signal
  busy.value = true
  failure.value = ''
  try {
    await action()
    if (signal.aborted) return
    editing.value = false
    deleting.value = false
    toast.success(t(success))
    await queries.invalidateQueries({ queryKey: ['speech'] })
    await reload()
    if (props.admin) await queries.invalidateQueries({ queryKey: ['accounts'] })
  } catch (error) {
    if (!signal.aborted) failure.value = errorMessage(error)
  } finally {
    if (!signal.aborted) busy.value = false
  }
}
async function saveConnection() {
  if (busy.value || !validConnection.value) return
  const input = {
    provider: form.provider,
    label: form.label.trim(),
    enabled: form.enabled,
    revision: form.revision,
    ...(form.api_key.trim() ? { api_key: form.api_key.trim() } : {}),
  }
  const id = form.id
  form.api_key = ''
  await write(async () => {
    await client().saveConnection(id, input)
    if (input.api_key || !input.enabled) speechPlayback.invalidateConnection(id)
  }, 'speechSettings.saved')
}
async function removeConnection() {
  if (busy.value) return
  const id = form.id
  await write(async () => {
    await client().deleteConnection(id, form.revision)
    speechPlayback.invalidateConnection(id)
  }, 'speechSettings.removed')
}
async function savePreferences() {
  if (busy.value || !preferences.value || !valid.value) return
  const value = {
    ...preferences.value,
    asr_model: selectedAsrModel.value?.id ?? null,
    fallback_asr_model: preferences.value.fallback_asr
      ? (selectedBackupModel.value?.id ?? null)
      : null,
    tts_model: preferences.value.tts ? (selectedModel.value?.id ?? null) : null,
  }
  await write(() => client().savePreferences(value), 'speechSettings.preferencesSaved')
}
function preview() {
  if (playingPreview.value) speechPlayback.stop()
  else if (preferences.value?.tts && validTts.value)
    void speechPlayback.preview(
      preferences.value.tts,
      preferences.value.voice,
      selectedModel.value?.id,
    )
}
function connectionLabel(connection: SpeechConnection) {
  const name =
    settings.data.value?.providers.find((provider) => provider.id === connection.provider)?.label ??
    connection.provider
  return `${connection.label} · ${name}`
}
</script>

<template>
  <PageHeader
    :title="t(admin ? 'speechSettings.adminTitle' : 'speechSettings.title')"
    :back="{ name: admin ? 'admin' : 'settings' }"
  />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <AmbientSettings v-if="!admin" />
    <RefreshFailed :query="settings" />
    <LoadingState v-if="settings.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="settings.isLoadingError.value"
      :error="settings.error.value"
      @retry="reload"
    />
    <template v-else-if="settings.data.value">
      <div
        v-if="failure"
        class="bg-destructive/10 text-destructive space-y-3 rounded-2xl p-4"
        role="alert"
      >
        <p>{{ failure }}</p>
        <Button variant="outline" :disabled="busy" @click="reload">{{
          t('speechSettings.reload')
        }}</Button>
      </div>
      <section
        v-if="!admin && preferences"
        class="bg-card space-y-5 rounded-3xl border p-5"
        aria-labelledby="speech-preferences"
      >
        <h2 id="speech-preferences" class="font-semibold">
          {{ t('speechSettings.selectionTitle') }}
        </h2>
        <p v-if="readiness" class="text-warning text-sm" role="status">{{ t(readiness) }}</p>
        <div class="space-y-2">
          <Label for="speech-asr">{{ t('speechSettings.asr') }}</Label>
          <NativeSelect id="speech-asr" v-model="asrChoice" :disabled="busy">
            <option value="auto">{{ t('speechSettings.auto') }}</option>
            <option v-if="localProvider" value="local">{{ localProvider.label }}</option>
            <option v-if="asrMissing" :value="preferences.asr" disabled>
              {{ t('speechSettings.unavailableChoice') }}
            </option>
            <option v-for="connection in asr" :key="connection.id" :value="connection.id">
              {{ connectionLabel(connection) }}
            </option>
          </NativeSelect>
          <p v-if="preferences.asr === 'auto'" class="text-muted-foreground text-sm">
            {{ t('speechSettings.autoHint') }}
          </p>
          <RouterLink
            v-if="session.me?.system_admin && preferences.asr === 'auto'"
            :to="{ name: 'accounts' }"
            class="text-primary text-sm underline underline-offset-4"
            >{{ t('speechSettings.codexAccounts') }}</RouterLink
          >
        </div>
        <SpeechModelSelect
          id="speech-asr-model"
          v-model="asrModelChoice"
          :label="t('speechSettings.asrModel')"
          capability="asr"
          :catalog="asrCatalog"
          :disabled="busy"
          :refreshable="!['auto', 'local'].includes(preferences.asr) && !asrMissing"
          @refresh="refreshCatalog(preferences.asr)"
        />
        <div class="space-y-2">
          <Label for="speech-backup">{{ t('speechSettings.backup') }}</Label>
          <NativeSelect id="speech-backup" v-model="backupChoice" :disabled="busy">
            <option value="">{{ t('speechSettings.noBackup') }}</option>
            <option
              v-if="missingSelection(preferences.fallback_asr, backup)"
              :value="preferences.fallback_asr!"
              disabled
            >
              {{ t('speechSettings.unavailableChoice') }}
            </option>
            <option v-for="connection in backup" :key="connection.id" :value="connection.id">
              {{ connectionLabel(connection) }}
            </option>
          </NativeSelect>
          <p class="text-muted-foreground text-sm">{{ t('speechSettings.backupHint') }}</p>
        </div>
        <SpeechModelSelect
          v-if="preferences.fallback_asr"
          id="speech-backup-model"
          v-model="backupModelChoice"
          :label="t('speechSettings.backupModel')"
          capability="asr"
          :catalog="backupCatalog"
          :excluded-model="excludedBackupModel"
          :disabled="busy"
          :refreshable="!missingSelection(preferences.fallback_asr, backup)"
          @refresh="refreshCatalog(preferences.fallback_asr)"
        />
        <div class="space-y-2 border-t pt-4">
          <Label for="speech-tts">{{ t('speechSettings.tts') }}</Label>
          <NativeSelect id="speech-tts" v-model="ttsChoice" :disabled="busy">
            <option value="auto">{{ t('speechSettings.auto') }}</option>
            <option value="">{{ t('speechSettings.noTts') }}</option>
            <option
              v-if="missingSelection(preferences.tts, tts)"
              :value="preferences.tts!"
              disabled
            >
              {{ t('speechSettings.unavailableChoice') }}
            </option>
            <option v-for="connection in tts" :key="connection.id" :value="connection.id">
              {{ connectionLabel(connection) }}
            </option>
          </NativeSelect>
          <p class="text-muted-foreground text-sm">{{ t('speechSettings.ttsHint') }}</p>
          <p v-if="preferences.tts === 'auto'" class="text-muted-foreground text-sm">
            {{ t('speechSettings.autoTtsHint') }}
          </p>
          <p v-if="ttsReadiness" class="text-warning text-sm" role="status">
            {{ t(ttsReadiness) }}
          </p>
        </div>
        <SpeechModelSelect
          v-if="preferences.tts"
          id="speech-tts-model"
          v-model="ttsModelChoice"
          :label="t('speechSettings.model')"
          capability="tts"
          :catalog="ttsCatalog"
          :disabled="busy"
          :refreshable="preferences.tts !== 'auto' && !missingSelection(preferences.tts, tts)"
          @refresh="refreshCatalog(preferences.tts)"
        />
        <div v-if="preferences.tts && voices.length" class="space-y-2">
          <Label for="speech-voice">{{ t('speechSettings.voice') }}</Label>
          <NativeSelect
            id="speech-voice"
            :key="`${preferences.tts}:${selectedModel?.id ?? ''}:${voices.join('|')}`"
            v-model="voiceChoice"
            :disabled="busy"
          >
            <option v-if="!voices.includes(preferences.voice)" :value="preferences.voice" disabled>
              {{ t('speechSettings.unavailableVoice') }}
            </option>
            <option v-for="voice in voices" :key="voice" :value="voice">
              {{ voice === 'default' ? t('speechSettings.defaultVoice') : voice }}
            </option>
          </NativeSelect>
          <p v-if="voices.length === 1" class="text-muted-foreground text-sm">
            {{ t('speechSettings.singleVoiceHint') }}
          </p>
          <Button type="button" variant="outline" :disabled="busy || !validTts" @click="preview"
            ><Square v-if="playingPreview" /><Volume2 v-else />{{
              t(playingPreview ? 'speechSettings.stop' : 'speechSettings.preview')
            }}</Button
          >
          <p
            v-if="
              speechPlayback.state.value.key === 'preview' && speechPlayback.state.value.problem
            "
            class="text-destructive text-sm"
            role="alert"
          >
            {{ t(`speechPlayback.errors.${speechPlayback.state.value.problem}`) }}
          </p>
        </div>
        <Button :disabled="busy || !dirty || !valid" @click="savePreferences">{{
          t('speechSettings.save')
        }}</Button>
      </section>

      <section class="space-y-3" aria-labelledby="speech-connections">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2 id="speech-connections" class="font-semibold">
            {{ t(admin ? 'speechSettings.adminTitle' : 'speechSettings.personalTitle') }}
          </h2>
          <Button variant="outline" :disabled="busy || !providers.length" @click="edit()"
            ><Plus />{{ t('speechSettings.add') }}</Button
          >
        </div>
        <p class="text-muted-foreground text-sm">
          {{ t(admin ? 'speechSettings.sharedHint' : 'speechSettings.personalHint') }}
        </p>
        <RouterLink
          v-if="admin"
          :to="{ name: 'admin-users' }"
          class="text-primary inline-block text-sm underline underline-offset-4"
          >{{ t('speechSettings.access') }}</RouterLink
        >
        <div v-if="!managed.length" class="bg-card space-y-2 rounded-3xl border p-5">
          <p class="font-medium">
            {{ t(admin ? 'speechSettings.sharedEmpty' : 'speechSettings.empty') }}
          </p>
          <p v-if="!admin" class="text-muted-foreground text-sm">
            {{ t('speechSettings.emptyHint') }}
          </p>
        </div>
        <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="speech-connections">
          <li v-for="connection in managed" :key="connection.id">
            <button
              type="button"
              class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3.5 text-left"
              :disabled="busy"
              @click="edit(connection)"
            >
              <Mic class="text-muted-foreground size-5" />
              <span class="min-w-0 flex-1"
                ><span class="block font-medium">{{ connectionLabel(connection) }}</span
                ><span class="text-muted-foreground block text-sm">{{
                  t(
                    connection.enabled
                      ? connection.has_key
                        ? 'speechSettings.keyPresent'
                        : 'speechSettings.keyMissing'
                      : 'speechSettings.disabled',
                  )
                }}</span></span
              >
              <ChevronRight class="text-muted-foreground size-4" />
            </button>
            <SpeechConnectionCatalog
              v-if="connection.enabled && connection.has_key"
              :id="connection.id"
              :catalog="catalogs[connection.id]"
              :disabled="busy"
              @refresh="refreshCatalog(connection.id)"
            />
          </li>
        </ul>
      </section>
      <section v-if="shared.length" class="space-y-3" aria-labelledby="speech-shared">
        <h2 id="speech-shared" class="font-semibold">{{ t('speechSettings.sharedTitle') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border">
          <li v-for="connection in shared" :key="connection.id" class="px-4 py-3.5">
            <span class="block font-medium">{{ connectionLabel(connection) }}</span>
            <p v-if="!connection.enabled" class="text-muted-foreground text-sm">
              {{ t('speechSettings.disabled') }}
            </p>
            <SpeechConnectionCatalog
              v-if="connection.enabled && connection.has_key"
              :id="connection.id"
              :catalog="catalogs[connection.id]"
              :disabled="busy"
              @refresh="refreshCatalog(connection.id)"
            />
          </li>
        </ul>
      </section>
    </template>
  </div>

  <Panel
    v-model:open="editing"
    :title="t(form.revision ? 'speechSettings.edit' : 'speechSettings.add')"
  >
    <form class="space-y-4" autocomplete="off" @submit.prevent="saveConnection">
      <div v-if="failure" class="space-y-2" role="alert">
        <p class="text-destructive text-sm">{{ failure }}</p>
        <Button type="button" variant="outline" :disabled="busy" @click="reloadEditor">{{
          t('speechSettings.reload')
        }}</Button>
      </div>
      <div class="space-y-2">
        <Label for="speech-provider">{{ t('speechSettings.provider') }}</Label
        ><NativeSelect
          id="speech-provider"
          v-model="form.provider"
          :disabled="busy || !!form.revision"
          ><option v-for="provider in providers" :key="provider.id" :value="provider.id">
            {{ provider.label }}
          </option></NativeSelect
        >
      </div>
      <div class="space-y-2">
        <Label for="speech-label">{{ t('speechSettings.label') }}</Label
        ><Input id="speech-label" v-model="form.label" :disabled="busy" maxlength="100" required />
      </div>
      <p
        v-if="providers.find((item) => item.id === form.provider)?.dynamic_models"
        class="text-muted-foreground text-sm"
      >
        {{ t('speechSettings.openrouterHint') }}
      </p>
      <div class="space-y-2">
        <Label for="speech-key">{{
          t(form.has_key ? 'speechSettings.replaceKey' : 'speechSettings.key')
        }}</Label>
        <Input
          id="speech-key"
          v-model="form.api_key"
          type="password"
          autocomplete="new-password"
          :disabled="busy"
          :required="!form.has_key"
          spellcheck="false"
          autocapitalize="off"
        />
        <p v-if="form.has_key" class="text-muted-foreground text-sm">
          {{ t('speechSettings.keepKey') }}
        </p>
        <p class="text-muted-foreground text-sm">{{ t('speechSettings.secretHint') }}</p>
      </div>
      <Label class="flex items-center gap-2"
        ><Checkbox v-model="form.enabled" :disabled="busy" />{{
          t('speechSettings.enabled')
        }}</Label
      >
      <Button type="submit" class="w-full" :disabled="busy || !validConnection">{{
        t('speechSettings.save')
      }}</Button>
      <Button
        v-if="form.revision"
        type="button"
        variant="ghost"
        class="text-destructive w-full"
        :disabled="busy"
        @click="deleting = true"
        >{{ t('speechSettings.remove') }}</Button
      >
    </form>
  </Panel>
  <Dialog v-model:open="deleting"
    ><DialogContent
      ><DialogHeader
        ><DialogTitle>{{ t('speechSettings.removeTitle') }}</DialogTitle
        ><DialogDescription>{{ t('speechSettings.removeText') }}</DialogDescription></DialogHeader
      ><DialogFooter
        ><Button variant="outline" :disabled="busy" @click="deleting = false">{{
          t('speechSettings.cancel')
        }}</Button
        ><Button variant="destructive" :disabled="busy" @click="removeConnection">{{
          t('speechSettings.remove')
        }}</Button></DialogFooter
      ></DialogContent
    ></Dialog
  >
</template>
