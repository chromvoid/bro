import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Ref } from 'vue'
import { api } from '@/core/client'
import { ApiError } from '@/core/http'
import { i18n } from '@/i18n'
import { speechPlayback, type PlaybackState } from '@/speech/playback'
import { useSession } from '@/stores/session'
import SpeechSettings from './SpeechSettings.vue'
import type { SpeechModel } from './api'

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn() } }))
vi.mock('@/speech/playback', async () => {
  const { ref } = await import('vue')
  return {
    speechPlayback: {
      state: ref({ key: null, status: 'idle', problem: null }),
      stop: vi.fn(),
      preview: vi.fn(),
      invalidateConnection: vi.fn(),
    },
  }
})

const model = (
  id: string,
  capabilities: SpeechModel['capabilities'],
  extra: Partial<SpeechModel> = {},
): SpeechModel => ({
  id,
  name: id,
  capabilities,
  asr_mode: capabilities.includes('asr') ? 'streaming' : null,
  voices: [],
  default_voice: null,
  tts_segment_chars: capabilities.includes('tts') ? 3500 : null,
  selectable: true,
  unavailable_reason: null,
  ...extra,
})
const openAiVoices = [
  'alloy',
  'ash',
  'ballad',
  'coral',
  'echo',
  'fable',
  'onyx',
  'nova',
  'sage',
  'shimmer',
  'verse',
  'marin',
  'cedar',
]
const snapshot = {
  providers: [
    {
      id: 'codex',
      label: 'Codex',
      capabilities: ['asr', 'tts'],
      connection_kind: 'subscription',
      voices: ['default'],
      default_voice: 'default',
      models: [
        model('codex-dictation', ['asr']),
        model('codex-pronunciation', ['tts'], { voices: ['default'], default_voice: 'default' }),
      ],
    },
    {
      id: 'mistral',
      label: 'Mistral',
      capabilities: ['asr'],
      connection_kind: 'api_key',
      models: [model('voxtral', ['asr'])],
    },
    {
      id: 'openai',
      label: 'OpenAI API',
      capabilities: ['asr', 'tts'],
      connection_kind: 'api_key',
      voices: [
        'alloy',
        'ash',
        'ballad',
        'coral',
        'echo',
        'fable',
        'onyx',
        'nova',
        'sage',
        'shimmer',
        'verse',
        'marin',
        'cedar',
      ],
      default_voice: 'marin',
      models: [
        model('gpt-live-transcribe', ['asr']),
        model('gpt-transcribe', ['asr'], { asr_mode: 'file' }),
        model('gpt-4o-mini-tts', ['tts'], { voices: openAiVoices, default_voice: 'marin' }),
      ],
    },
    {
      id: 'openrouter',
      label: 'OpenRouter',
      capabilities: ['asr', 'tts'],
      connection_kind: 'api_key',
      dynamic_models: true,
      voices: [],
      default_voice: null,
      models: [],
    },
  ],
  connections: [
    {
      id: 'personal',
      ownership: 'personal',
      provider: 'openai',
      label: 'My key',
      enabled: true,
      has_key: true,
      revision: 1,
      capabilities: ['asr', 'tts'],
    },
    {
      id: 'shared',
      ownership: 'shared',
      provider: 'mistral',
      label: 'Family key',
      enabled: true,
      has_key: true,
      revision: 2,
      capabilities: ['asr'],
    },
  ],
  preferences: { revision: 0, asr: 'auto', fallback_asr: null, tts: 'auto', voice: 'default' },
  asr: { state: 'unavailable', code: 'not_configured' },
  tts: { state: 'configured', provider: 'codex' },
}

const instances: VueWrapper[] = []
const playbackState = speechPlayback.state as Ref<PlaybackState>
const routerConnection = {
  id: 'router',
  ownership: 'personal',
  provider: 'openrouter',
  label: 'My OpenRouter',
  enabled: true,
  has_key: true,
  revision: 1,
  capabilities: ['asr', 'tts'],
}
const modelCatalog = {
  models: [
    model('synthetic/alternate-tts', ['tts'], {
      name: 'Alternate speech',
      voices: ['Alpha', 'Beta'],
      default_voice: 'Alpha',
    }),
    model('google/gemini-3.8-flash-tts', ['tts'], {
      name: 'Gemini 3.8 Flash TTS',
      voices: ['Kore', 'Puck'],
      default_voice: 'Kore',
    }),
  ],
}
function withOpenRouter(preferences = {}) {
  return {
    ...structuredClone(snapshot),
    connections: [...structuredClone(snapshot.connections), { ...routerConnection }],
    preferences: { ...snapshot.preferences, ...preferences },
  }
}
function selectValue(wrapper: VueWrapper, selector: string) {
  return (wrapper.get(selector).element as HTMLSelectElement).value
}
function saveButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find((button) => button.text() === 'Сохранить')!
}
function isModelsPath(path: string) {
  return /\/models\?capability=all(?:&refresh=1)?$/.test(path)
}
function refreshButton(wrapper: VueWrapper, id = 'router') {
  return wrapper
    .get(`[data-testid="speech-catalog-${id}"]`)
    .findAll('button')
    .find((button) => button.text() === 'Обновить модели')!
}
async function screen(admin = false) {
  const pinia = createPinia()
  const session = useSession(pinia)
  session.me = {
    id: 'u',
    name: 'User',
    login: 'user',
    locale: 'ru',
    timezone: 'UTC',
    system_admin: admin,
    revision: 1,
    scopes: [],
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = mount(SpeechSettings, {
    props: { admin },
    global: {
      plugins: [pinia, i18n, [VueQueryPlugin, { queryClient }]],
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        PageHeader: true,
        Panel: {
          name: 'Panel',
          props: ['open'],
          template: '<div v-if="open" data-test="editor"><slot /></div>',
        },
        Dialog: { props: ['open'], template: '<div v-if="open"><slot /></div>' },
      },
    },
  })
  instances.push(wrapper)
  await flushPromises()
  return { wrapper, session, queryClient }
}

beforeEach(() => {
  vi.clearAllMocks()
  playbackState.value = { key: null, status: 'idle', problem: null }
  vi.mocked(speechPlayback.stop).mockImplementation(() => {
    playbackState.value = { key: null, status: 'idle', problem: null }
  })
  vi.mocked(speechPlayback.preview).mockImplementation(async () => {
    playbackState.value = { key: 'preview', status: 'playing', problem: null }
  })
  vi.spyOn(api, 'get').mockResolvedValue(structuredClone(snapshot))
})
afterEach(() => {
  for (const wrapper of instances.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})

describe('voice settings', () => {
  it('keeps the form being edited, with a retry, when a refresh fails', async () => {
    const { wrapper, queryClient } = await screen()
    await wrapper.get('#speech-tts').setValue('personal')
    vi.mocked(api.get).mockRejectedValue(new Error('offline'))
    await queryClient.invalidateQueries({ queryKey: ['speech'] })
    await flushPromises()
    expect(selectValue(wrapper, '#speech-tts')).toBe('personal')
    expect(wrapper.find('[data-state="error"]').exists()).toBe(false)
    vi.mocked(api.get).mockResolvedValue(structuredClone(snapshot))
    await wrapper.get('[data-state="refresh-failed"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-state="refresh-failed"]').exists()).toBe(false)
    expect(selectValue(wrapper, '#speech-tts')).toBe('personal')
  })

  it('preserves a playing answer while settings mount, load and refresh catalogs, then unmount', async () => {
    const data = withOpenRouter({
      tts: 'router',
      tts_model: 'google/gemini-3.8-flash-tts',
      voice: 'Puck',
    })
    let finish!: (value: unknown) => void
    vi.mocked(api.get).mockImplementation((path) =>
      isModelsPath(path)
        ? new Promise((resolve) => {
            finish = resolve
          })
        : Promise.resolve(data),
    )
    playbackState.value = { key: 'turn:ongoing', status: 'playing', problem: null }
    const { wrapper } = await screen()
    expect(speechPlayback.stop).not.toHaveBeenCalled()
    finish(modelCatalog)
    await flushPromises()
    await refreshButton(wrapper).trigger('click')
    finish(modelCatalog)
    await flushPromises()
    wrapper.unmount()
    expect(speechPlayback.stop).not.toHaveBeenCalled()
    expect(speechPlayback.invalidateConnection).not.toHaveBeenCalled()
    expect(playbackState.value.key).toBe('turn:ongoing')
  })

  it('keeps the current answer pinned when future TTS or ASR preferences are saved', async () => {
    const send = vi.spyOn(api, 'send').mockResolvedValue({ status: 200, data: {} })
    playbackState.value = { key: 'turn:ongoing', status: 'playing', problem: null }
    const { wrapper } = await screen()
    await wrapper.get('#speech-tts').setValue('personal')
    await wrapper.get('#speech-voice').setValue('cedar')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    await wrapper.get('#speech-asr').setValue('personal')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(send).toHaveBeenCalledTimes(2)
    expect(speechPlayback.stop).not.toHaveBeenCalled()
    expect(speechPlayback.invalidateConnection).not.toHaveBeenCalled()
    expect(playbackState.value.key).toBe('turn:ongoing')
  })

  it('preserves a preview across mounting and leaving settings, but stops it for a new choice', async () => {
    playbackState.value = { key: 'preview', status: 'playing', problem: null }
    const { wrapper } = await screen()
    expect(speechPlayback.stop).not.toHaveBeenCalled()
    await wrapper.get('#speech-tts').setValue('personal')
    expect(speechPlayback.stop).toHaveBeenCalledOnce()
    playbackState.value = { key: 'preview', status: 'playing', problem: null }
    wrapper.unmount()
    expect(speechPlayback.stop).toHaveBeenCalledOnce()
  })

  it('invalidates only the edited connection after key replacement succeeds, not on label edits', async () => {
    const send = vi.spyOn(api, 'send').mockResolvedValue({ status: 200, data: {} })
    playbackState.value = { key: 'turn:ongoing', status: 'playing', problem: null }
    const { wrapper } = await screen()
    const edit = () => wrapper.get('[data-testid="speech-connections"] button').trigger('click')
    await edit()
    await wrapper.get('#speech-label').setValue('Renamed')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(speechPlayback.invalidateConnection).not.toHaveBeenCalled()
    await edit()
    await wrapper.get('#speech-key').setValue('synthetic-replacement')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(send).toHaveBeenCalledTimes(2)
    expect(speechPlayback.invalidateConnection).toHaveBeenCalledExactlyOnceWith('personal')
    expect(speechPlayback.stop).not.toHaveBeenCalled()
  })

  it('defaults ASR and TTS to automatic Codex, with no fallback and explicit voice preview', async () => {
    const { wrapper } = await screen()
    expect((wrapper.get('#speech-asr').element as HTMLSelectElement).value).toBe('auto')
    expect((wrapper.get('#speech-backup').element as HTMLSelectElement).value).toBe('')
    expect((wrapper.get('#speech-tts').element as HTMLSelectElement).value).toBe('auto')
    expect((wrapper.get('#speech-voice').element as HTMLSelectElement).value).toBe('default')
    expect(wrapper.get('#speech-voice').text()).toContain('Стандартный')
    expect(wrapper.text()).toContain('У этого провайдера пока доступен один голос')
    expect(speechPlayback.preview).not.toHaveBeenCalled()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Послушать голос'))!
      .trigger('click')
    expect(speechPlayback.preview).toHaveBeenCalledExactlyOnceWith(
      'auto',
      'default',
      'codex-pronunciation',
    )
    expect(wrapper.text()).toContain('Распознавание не настроено')
    expect(wrapper.text()).toContain('Добавить провайдера')
    expect(wrapper.text()).toContain('Family key')
    await wrapper.get('#speech-asr').setValue('personal')
    expect(
      wrapper
        .get('#speech-backup')
        .findAll('option')
        .map((option) => option.attributes('value')),
    ).toContain('personal')
    await wrapper.get('#speech-backup').setValue('personal')
    expect(selectValue(wrapper, '#speech-backup-model')).toBe('gpt-transcribe')
  })

  it('preserves an existing OpenAI voice and uses provider defaults when switching providers', async () => {
    vi.mocked(api.get).mockResolvedValue({
      ...snapshot,
      preferences: { ...snapshot.preferences, tts: 'personal', voice: 'cedar' },
      tts: { state: 'configured', provider: 'openai' },
    })
    const { wrapper } = await screen()
    expect((wrapper.get('#speech-tts').element as HTMLSelectElement).value).toBe('personal')
    expect((wrapper.get('#speech-voice').element as HTMLSelectElement).value).toBe('cedar')
    expect(wrapper.get('#speech-voice').findAll('option')).toHaveLength(13)
    await wrapper.get('#speech-tts').setValue('auto')
    expect(wrapper.get('#speech-voice').findAll('option')).toHaveLength(1)
    expect((wrapper.get('#speech-voice').element as HTMLSelectElement).value).toBe('default')
    await wrapper.get('#speech-tts').setValue('personal')
    expect((wrapper.get('#speech-voice').element as HTMLSelectElement).value).toBe('marin')
    await wrapper.get('#speech-voice').setValue('alloy')
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Послушать голос'))!
      .trigger('click')
    expect(speechPlayback.preview).toHaveBeenCalledExactlyOnceWith(
      'personal',
      'alloy',
      'gpt-4o-mini-tts',
    )
    vi.mocked(speechPlayback.stop).mockClear()
    await wrapper.get('#speech-voice').setValue('marin')
    expect(speechPlayback.stop).toHaveBeenCalledOnce()
  })

  it('persists an explicit off selection without starting playback', async () => {
    const send = vi.spyOn(api, 'send').mockResolvedValue({ status: 200, data: {} })
    const { wrapper } = await screen()
    await wrapper.get('#speech-tts').setValue('')
    expect(wrapper.find('#speech-voice').exists()).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сохранить')!
      .trigger('click')
    expect(send).toHaveBeenCalledOnce()
    expect(send.mock.calls[0]?.[1]).toBe('/v1/me/speech/preferences')
    expect(JSON.parse(send.mock.calls[0]![2]!)).toMatchObject({ tts: null })
    expect(speechPlayback.preview).not.toHaveBeenCalled()
    await flushPromises()
  })

  it('keeps an explicit off selection and lets the user save automatic Codex', async () => {
    vi.mocked(api.get).mockResolvedValue({
      ...snapshot,
      preferences: { ...snapshot.preferences, tts: null, voice: 'marin' },
    })
    const send = vi.spyOn(api, 'send').mockResolvedValue({ status: 200, data: {} })
    const { wrapper } = await screen()
    expect((wrapper.get('#speech-tts').element as HTMLSelectElement).value).toBe('')
    expect(wrapper.find('#speech-voice').exists()).toBe(false)
    await wrapper.get('#speech-tts').setValue('auto')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Сохранить')!
      .trigger('click')
    expect(JSON.parse(send.mock.calls[0]![2]!)).toMatchObject({ tts: 'auto', voice: 'default' })
    await flushPromises()
  })

  it('offers the server’s local recognizer with a cloud reserve only when the server has it', async () => {
    const asrValues = (wrapper: VueWrapper) =>
      wrapper
        .get('#speech-asr')
        .findAll('option')
        .map((option) => option.attributes('value'))
    const { wrapper: plain } = await screen()
    expect(asrValues(plain)).not.toContain('local')
    vi.mocked(api.get).mockResolvedValue({
      ...structuredClone(snapshot),
      providers: [
        ...structuredClone(snapshot.providers),
        {
          id: 'local',
          label: 'Локальная Voxtral',
          capabilities: ['asr'],
          connection_kind: 'local',
          models: [model('voxtral-mini-4b-realtime', ['asr'])],
        },
      ],
    })
    const send = vi.spyOn(api, 'send').mockResolvedValue({ status: 200, data: {} })
    const { wrapper } = await screen()
    expect(wrapper.get('#speech-asr option[value="local"]').text()).toBe('Локальная Voxtral')
    await wrapper.get('#speech-asr').setValue('local')
    expect(selectValue(wrapper, '#speech-asr-model')).toBe('voxtral-mini-4b-realtime')
    await wrapper.get('#speech-backup').setValue('shared')
    expect(asrValues(wrapper)).toContain('local')
    expect(
      wrapper
        .get('#speech-backup')
        .findAll('option')
        .map((option) => option.attributes('value')),
    ).not.toContain('local')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(JSON.parse(send.mock.calls[0]![2]!)).toMatchObject({
      asr: 'local',
      asr_model: 'voxtral-mini-4b-realtime',
      fallback_asr: 'shared',
      fallback_asr_model: 'voxtral',
    })
  })

  it('marks a saved local recognizer the server no longer has as unavailable', async () => {
    vi.mocked(api.get).mockResolvedValue({
      ...structuredClone(snapshot),
      preferences: { ...snapshot.preferences, asr: 'local' },
    })
    const { wrapper } = await screen()
    const option = wrapper.get('#speech-asr option[value="local"]')
    expect(option.text()).toBe('Выбранное подключение недоступно')
    expect(option.attributes('disabled')).toBeDefined()
    await wrapper.get('#speech-tts').setValue('')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('shows missing Codex access while leaving the default choice visible', async () => {
    vi.mocked(api.get).mockResolvedValue({
      ...snapshot,
      tts: { state: 'unavailable', code: 'no_access' },
    })
    const { wrapper } = await screen()
    expect((wrapper.get('#speech-tts').element as HTMLSelectElement).value).toBe('auto')
    expect(wrapper.text()).toContain('Нет доступной подписки Codex для озвучки')
    await wrapper.get('#speech-tts').setValue('personal')
    expect(wrapper.text()).not.toContain('Нет доступной подписки Codex для озвучки')
  })

  it('uses catalog changes without silently replacing a removed voice or revoked connection', async () => {
    const data = {
      ...snapshot,
      preferences: { ...snapshot.preferences, tts: 'personal', voice: 'cedar' },
    }
    vi.mocked(api.get).mockResolvedValue(data)
    const { wrapper, queryClient } = await screen()
    queryClient.setQueryData(['speech', 'u', 'me'], {
      ...data,
      providers: data.providers.map((provider) => ({
        ...provider,
        voices: provider.voices?.filter((voice) => voice !== 'cedar'),
        models: provider.models.map((entry) => ({
          ...entry,
          voices: entry.voices.filter((voice) => voice !== 'cedar'),
        })),
      })),
    })
    await flushPromises()
    expect((wrapper.get('#speech-voice').element as HTMLSelectElement).value).toBe('cedar')
    expect(wrapper.get('#speech-voice').text()).toContain('Выбранный голос больше недоступен')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text().includes('Послушать голос'))!
        .attributes('disabled'),
    ).toBeDefined()
    await wrapper.get('#speech-voice').setValue('marin')
    queryClient.setQueryData(['speech', 'u', 'me'], { ...data, connections: [] })
    await flushPromises()
    expect((wrapper.get('#speech-tts').element as HTMLSelectElement).value).toBe('personal')
    expect(wrapper.get('#speech-tts').text()).toContain('Выбранное подключение недоступно')
    expect(wrapper.text()).toContain('Нет доступа к выбранному подключению')
    expect(wrapper.find('#speech-voice').exists()).toBe(false)
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text() === 'Сохранить')!
        .attributes('disabled'),
    ).toBeDefined()
  })

  it('keeps keys ephemeral and never offers a subscription as an API-key provider', async () => {
    const { wrapper, session } = await screen()
    const add = wrapper
      .findAll('button')
      .find((button) => button.text().includes('Добавить провайдера'))!
    await add.trigger('click')
    expect(
      wrapper
        .get('#speech-provider')
        .findAll('option')
        .map((option) => option.attributes('value')),
    ).toEqual(['mistral', 'openai', 'openrouter'])
    await wrapper.get('#speech-key').setValue('synthetic-secret')
    const editor = wrapper.findComponent({ name: 'Panel' })
    editor.vm.$emit('update:open', false)
    await flushPromises()
    await add.trigger('click')
    expect((wrapper.get('#speech-key').element as HTMLInputElement).value).toBe('')
    await wrapper.get('#speech-key').setValue('other-synthetic-secret')
    session.me = { ...session.me!, id: 'another-user' }
    await flushPromises()
    expect(wrapper.find('#speech-key').exists()).toBe(false)
  })

  it('clears the key before waiting for the direct save result', async () => {
    let finish!: () => void
    const send = vi.spyOn(api, 'send').mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = () => resolve({ status: 200, data: {} })
        }),
    )
    const { wrapper } = await screen()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Добавить провайдера'))!
      .trigger('click')
    await wrapper.get('#speech-label').setValue('New key')
    await wrapper.get('#speech-key').setValue('synthetic-secret')
    await wrapper.get('form').trigger('submit')
    expect(send).toHaveBeenCalledOnce()
    expect(send.mock.calls[0]?.[1]).toMatch(/^\/v1\/me\/speech\/connections\//)
    expect(JSON.parse(send.mock.calls[0]![2]!)).toMatchObject({
      api_key: 'synthetic-secret',
      revision: 0,
    })
    expect(send.mock.calls[0]?.[3]?.expectedUser).toBe('u')
    expect((wrapper.get('#speech-key').element as HTMLInputElement).value).toBe('')
    finish()
    await flushPromises()
    expect(wrapper.find('#speech-key').exists()).toBe(false)
  })

  it('shows a safe storage error and never echoes a provider message carrying the key', async () => {
    vi.spyOn(api, 'send').mockRejectedValue(
      new ApiError(503, 'speech_key_unavailable', null, null, null, 'synthetic-secret'),
    )
    const { wrapper } = await screen()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Добавить провайдера'))!
      .trigger('click')
    await wrapper.get('#speech-label').setValue('New key')
    await wrapper.get('#speech-key').setValue('synthetic-secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Хранилище ключей голоса недоступно')
    expect(wrapper.text()).not.toContain('synthetic-secret')
    expect((wrapper.get('#speech-key').element as HTMLInputElement).value).toBe('')
  })

  it('loads saved dynamic catalogs immediately while Codex remains the default', async () => {
    const data = withOpenRouter()
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path) ? modelCatalog : data,
    )
    const { wrapper } = await screen()
    expect(api.get).toHaveBeenCalledWith(
      '/v1/me/speech/connections/router/models?capability=all',
      expect.any(AbortSignal),
    )
    expect(selectValue(wrapper, '#speech-tts')).toBe('auto')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('codex-pronunciation')
    expect(selectValue(wrapper, '#speech-asr-model')).toBe('codex-dictation')
    expect(wrapper.get('#speech-asr').text()).not.toContain('My OpenRouter')
    const card = wrapper.get('[data-testid="speech-catalog-router"]')
    expect(card.text()).toContain('2')
    await card.get('summary').trigger('click')
    expect(card.text()).toContain('Alternate speech')
    expect(card.text()).toContain('Gemini 3.8 Flash TTS')
    expect(speechPlayback.preview).not.toHaveBeenCalled()
  })

  it('derives ASR and TTS choices from models on the same key and saves explicit selections', async () => {
    const data = withOpenRouter()
    const mixedCatalog = {
      models: [
        model('synthetic/file-asr', ['asr'], { name: 'File recognition', asr_mode: 'file' }),
        model('synthetic/stream-asr', ['asr'], { name: 'Live recognition' }),
        ...modelCatalog.models,
      ],
    }
    // Provider-level capability declarations are deliberately insufficient: models are authoritative.
    data.connections = data.connections.map((item) =>
      item.id === 'router' ? { ...item, capabilities: ['tts'] } : item,
    )
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path) ? mixedCatalog : data,
    )
    const send = vi.spyOn(api, 'send').mockResolvedValue({ status: 200, data: {} })
    const { wrapper } = await screen()
    await wrapper.get('#speech-asr').setValue('router')
    expect(selectValue(wrapper, '#speech-asr-model')).toBe('synthetic/file-asr')
    expect(wrapper.text()).toContain('Текст появится после остановки записи.')
    expect(wrapper.get('#speech-asr-model').text()).not.toContain('Alternate speech')
    expect(wrapper.get('#speech-backup').text()).toContain('My OpenRouter')
    await wrapper.get('#speech-backup').setValue('router')
    expect(selectValue(wrapper, '#speech-backup-model')).toBe('synthetic/stream-asr')
    await wrapper.get('#speech-tts').setValue('router')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('synthetic/alternate-tts')
    expect(selectValue(wrapper, '#speech-voice')).toBe('Alpha')
    expect(wrapper.get('#speech-tts-model').text()).not.toContain('File recognition')
    await wrapper.get('#speech-tts-model').setValue('google/gemini-3.8-flash-tts')
    await wrapper.get('#speech-voice').setValue('Puck')
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Послушать голос'))!
      .trigger('click')
    expect(speechPlayback.preview).toHaveBeenCalledExactlyOnceWith(
      'router',
      'Puck',
      'google/gemini-3.8-flash-tts',
    )
    await saveButton(wrapper).trigger('click')
    expect(JSON.parse(send.mock.calls[0]![2]!)).toMatchObject({
      asr: 'router',
      asr_model: 'synthetic/file-asr',
      fallback_asr: 'router',
      fallback_asr_model: 'synthetic/stream-asr',
      tts: 'router',
      tts_model: 'google/gemini-3.8-flash-tts',
      voice: 'Puck',
    })
    await flushPromises()
  })

  it('preserves saved model and voice choices without selecting a recommended model', async () => {
    const data = withOpenRouter({
      tts: 'router',
      tts_model: 'google/gemini-3.8-flash-tts',
      voice: 'Puck',
    })
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path) ? modelCatalog : data,
    )
    const { wrapper } = await screen()
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('google/gemini-3.8-flash-tts')
    expect(selectValue(wrapper, '#speech-voice')).toBe('Puck')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    expect(speechPlayback.preview).not.toHaveBeenCalled()
    await wrapper.get('#speech-tts').setValue('auto')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('codex-pronunciation')
    expect(selectValue(wrapper, '#speech-voice')).toBe('default')
  })

  it('updates native model and voice values when switching between loaded connections', async () => {
    const data = withOpenRouter()
    data.connections.push({ ...routerConnection, id: 'other-router', label: 'Other Router' })
    vi.mocked(api.get).mockImplementation(async (path) => {
      if (path.includes('/connections/other-router/models'))
        return {
          models: [
            model('synthetic/other-speech', ['tts'], {
              voices: ['Gamma', 'Delta'],
              default_voice: 'Delta',
            }),
          ],
        }
      return isModelsPath(path) ? modelCatalog : data
    })
    const { wrapper } = await screen()
    await wrapper.get('#speech-tts').setValue('router')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('synthetic/alternate-tts')
    expect(selectValue(wrapper, '#speech-voice')).toBe('Alpha')
    await wrapper.get('#speech-tts').setValue('other-router')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('synthetic/other-speech')
    expect(selectValue(wrapper, '#speech-voice')).toBe('Delta')
    await wrapper.get('#speech-tts').setValue('router')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('synthetic/alternate-tts')
    expect(selectValue(wrapper, '#speech-voice')).toBe('Alpha')
  })

  it('keeps an identical saved primary and backup pair visible until explicitly corrected', async () => {
    vi.mocked(api.get).mockResolvedValue({
      ...snapshot,
      preferences: {
        ...snapshot.preferences,
        asr: 'personal',
        asr_model: 'gpt-live-transcribe',
        fallback_asr: 'personal',
        fallback_asr_model: 'gpt-live-transcribe',
      },
    })
    const send = vi.spyOn(api, 'send').mockResolvedValue({ status: 200, data: {} })
    const { wrapper } = await screen()
    expect(selectValue(wrapper, '#speech-backup')).toBe('personal')
    expect(selectValue(wrapper, '#speech-backup-model')).toBe('gpt-live-transcribe')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    await wrapper.get('#speech-backup-model').setValue('gpt-transcribe')
    await saveButton(wrapper).trigger('click')
    expect(JSON.parse(send.mock.calls[0]![2]!)).toMatchObject({
      asr: 'personal',
      asr_model: 'gpt-live-transcribe',
      fallback_asr: 'personal',
      fallback_asr_model: 'gpt-transcribe',
    })
    await flushPromises()
  })

  it('keeps an unavailable saved model visible until the user selects a replacement', async () => {
    const data = withOpenRouter({ tts: 'router', tts_model: 'synthetic/removed', voice: 'Old' })
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path) ? modelCatalog : data,
    )
    const { wrapper } = await screen()
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('synthetic/removed')
    expect(wrapper.text()).toContain('Выбранная модель больше недоступна')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.find('#speech-voice').exists()).toBe(false)
    await wrapper.get('#speech-tts-model').setValue('google/gemini-3.8-flash-tts')
    expect(selectValue(wrapper, '#speech-voice')).toBe('Kore')
    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('preserves a removed ASR model and blocks saving until explicitly replaced', async () => {
    const data = withOpenRouter({ asr: 'router', asr_model: 'synthetic/removed-asr' })
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path)
        ? { models: [model('synthetic/file-asr', ['asr'], { asr_mode: 'file' })] }
        : data,
    )
    const { wrapper } = await screen()
    expect(selectValue(wrapper, '#speech-asr-model')).toBe('synthetic/removed-asr')
    expect(wrapper.text()).toContain('Выбранная модель больше недоступна')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    await wrapper.get('#speech-asr-model').setValue('synthetic/file-asr')
    expect(saveButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('refreshes a catalog explicitly without silently replacing a removed voice', async () => {
    const data = withOpenRouter({
      tts: 'router',
      tts_model: 'google/gemini-3.8-flash-tts',
      voice: 'Puck',
    })
    let catalog = modelCatalog
    vi.mocked(api.get).mockImplementation(async (path) => (isModelsPath(path) ? catalog : data))
    const { wrapper } = await screen()
    catalog = {
      models: modelCatalog.models.map((entry) => ({ ...entry, voices: [entry.default_voice!] })),
    }
    await refreshButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith(
      '/v1/me/speech/connections/router/models?capability=all&refresh=1',
      expect.any(AbortSignal),
    )
    expect(selectValue(wrapper, '#speech-voice')).toBe('Puck')
    expect(wrapper.text()).toContain('Выбранный голос больше недоступен')
    expect(
      wrapper
        .findAll('button')
        .find((button) => button.text().includes('Послушать голос'))!
        .attributes('disabled'),
    ).toBeDefined()
  })

  it('shows TTS models with unknown voices as unavailable rather than inventing voices', async () => {
    const data = withOpenRouter()
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path)
        ? {
            models: [
              model('synthetic/unknown-voices', ['tts'], {
                name: 'Unknown voices',
                selectable: false,
                unavailable_reason: 'voices_unavailable',
              }),
              ...modelCatalog.models,
            ],
          }
        : data,
    )
    const { wrapper } = await screen()
    await wrapper.get('#speech-tts').setValue('router')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('synthetic/alternate-tts')
    expect(
      wrapper
        .get('#speech-tts-model option[value="synthetic/unknown-voices"]')
        .attributes('disabled'),
    ).toBeDefined()
    expect(wrapper.text()).toContain('Провайдер не сообщил доступные голоса для этой модели.')
  })

  it('keeps an unknown dynamic connection selectable while its catalog loads', async () => {
    let finish!: (value: unknown) => void
    vi.mocked(api.get).mockImplementation((path) =>
      isModelsPath(path)
        ? new Promise((resolve) => (finish = resolve))
        : Promise.resolve(withOpenRouter()),
    )
    const { wrapper } = await screen()
    expect(wrapper.get('#speech-asr').text()).toContain('My OpenRouter')
    await wrapper.get('#speech-tts').setValue('router')
    expect(wrapper.get('[data-testid="speech-catalog-router"]').text()).toContain('Загружаем')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    await wrapper.get('#speech-tts').setValue('personal')
    finish(modelCatalog)
    await flushPromises()
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('gpt-4o-mini-tts')
    expect(selectValue(wrapper, '#speech-voice')).toBe('marin')
    expect(wrapper.get('#speech-asr').text()).not.toContain('My OpenRouter')
  })

  it('shows safe catalog errors and permits an explicit refresh', async () => {
    let attempt = 0
    vi.mocked(api.get).mockImplementation(async (path) => {
      if (!isModelsPath(path)) return withOpenRouter()
      if (attempt++ === 0)
        throw new ApiError(503, 'unknown_code', null, null, null, 'synthetic-secret')
      return { models: [] }
    })
    const { wrapper } = await screen()
    const card = wrapper.get('[data-testid="speech-catalog-router"]')
    expect(card.text()).toContain('Не удалось загрузить модели')
    expect(wrapper.text()).not.toContain('synthetic-secret')
    expect(wrapper.get('#speech-asr').text()).toContain('My OpenRouter')
    await refreshButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith(
      '/v1/me/speech/connections/router/models?capability=all&refresh=1',
      expect.any(AbortSignal),
    )
    expect(wrapper.get('#speech-asr').text()).not.toContain('My OpenRouter')
    expect(wrapper.get('#speech-tts').text()).not.toContain('My OpenRouter')
  })

  it.each([
    ['auth_required', 'Провайдеру нужен повторный вход или новый API-ключ.'],
    ['no_access', 'Нет доступа к выбранному подключению.'],
    ['provider_limit', 'Достигнут лимит провайдера.'],
    ['execution_disabled', 'Внешние голосовые сервисы отключены администратором.'],
  ])('explains a %s catalog refusal safely on its connection card', async (code, expected) => {
    vi.mocked(api.get).mockImplementation(async (path) => {
      if (isModelsPath(path)) throw new ApiError(403, code, null, null, null, 'synthetic-secret')
      return withOpenRouter()
    })
    const { wrapper } = await screen()
    expect(wrapper.get('[data-testid="speech-catalog-router"]').text()).toContain(expected)
    expect(wrapper.text()).not.toContain('synthetic-secret')
  })

  it('loads a newly saved key catalog while leaving the default selections unchanged', async () => {
    let data = structuredClone(snapshot)
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path) ? modelCatalog : data,
    )
    vi.spyOn(api, 'send').mockImplementation(async (_method, path, body) => {
      const id = path.split('/').at(-1)!
      const input = JSON.parse(body!)
      data = {
        ...data,
        connections: [
          ...data.connections,
          {
            ...routerConnection,
            id,
            label: input.label,
            revision: 1,
          },
        ],
      }
      return { status: 200, data: {} }
    })
    const { wrapper } = await screen()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Добавить провайдера'))!
      .trigger('click')
    await wrapper.get('#speech-provider').setValue('openrouter')
    await wrapper.get('#speech-label').setValue('New Router')
    await wrapper.get('#speech-key').setValue('synthetic-secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(vi.mocked(api.get).mock.calls.some(([path]) => isModelsPath(path))).toBe(true)
    expect(wrapper.text()).toContain('New Router')
    expect(wrapper.text()).not.toContain('synthetic-secret')
    expect(selectValue(wrapper, '#speech-tts')).toBe('auto')
    expect(selectValue(wrapper, '#speech-asr')).toBe('auto')
  })

  it('loads and refreshes shared catalogs through admin routes', async () => {
    const data = withOpenRouter()
    data.connections = data.connections.map((item) => ({ ...item, ownership: 'shared' }))
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path) ? modelCatalog : data,
    )
    const { wrapper } = await screen(true)
    expect(api.get).toHaveBeenCalledWith('/v1/admin/speech', expect.any(AbortSignal))
    expect(api.get).toHaveBeenCalledWith(
      '/v1/admin/speech/connections/router/models?capability=all',
      expect.any(AbortSignal),
    )
    await refreshButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith(
      '/v1/admin/speech/connections/router/models?capability=all&refresh=1',
      expect.any(AbortSignal),
    )
    expect(vi.mocked(api.get).mock.calls.some(([path]) => path.startsWith('/v1/me/'))).toBe(false)
  })

  it('says there are no shared connections when only personal ones exist', async () => {
    const data = withOpenRouter()
    data.connections = data.connections.filter((item) => item.ownership === 'personal')
    vi.mocked(api.get).mockImplementation(async (path) =>
      isModelsPath(path) ? modelCatalog : data,
    )
    const { wrapper } = await screen(true)
    expect(wrapper.text()).toContain('Общих подключений нет')
    expect(wrapper.text()).not.toContain('Подключений с API-ключом')
    expect(wrapper.text()).not.toContain('Добавьте подключение')
    expect(wrapper.find('[data-testid="speech-connections"]').exists()).toBe(false)
  })

  it('discards a pending catalog after the user account changes', async () => {
    let finish!: (value: unknown) => void
    let signal: AbortSignal | undefined
    let data = withOpenRouter()
    vi.mocked(api.get).mockImplementation((path, requestSignal) => {
      if (!isModelsPath(path)) return Promise.resolve(data)
      signal = requestSignal
      return new Promise((resolve) => (finish = resolve))
    })
    const { wrapper, session } = await screen()
    await wrapper.get('#speech-tts').setValue('router')
    data = { ...data, connections: [] }
    session.me = { ...session.me!, id: 'other' }
    finish(modelCatalog)
    await flushPromises()
    expect(signal?.aborted).toBe(true)
    expect(selectValue(wrapper, '#speech-tts')).toBe('auto')
    expect(selectValue(wrapper, '#speech-tts-model')).toBe('codex-pronunciation')
    expect(wrapper.find('[data-testid="speech-catalog-router"]').exists()).toBe(false)
  })

  it('aborts an old catalog after key rotation and ignores the previous key result', async () => {
    const data = withOpenRouter()
    const requests: Array<{ finish: (value: unknown) => void; signal?: AbortSignal }> = []
    vi.mocked(api.get).mockImplementation((path, signal) => {
      if (!isModelsPath(path)) return Promise.resolve(data)
      return new Promise((finish) => requests.push({ finish, signal }))
    })
    const { wrapper, queryClient } = await screen()
    expect(requests).toHaveLength(1)
    queryClient.setQueryData(['speech', 'u', 'me'], {
      ...data,
      connections: data.connections.map((entry) =>
        entry.id === 'router' ? { ...entry, revision: 2 } : entry,
      ),
    })
    await flushPromises()
    expect(requests[0]!.signal?.aborted).toBe(true)
    expect(requests).toHaveLength(2)
    requests[1]!.finish({ models: [model('synthetic/rotated-key-asr', ['asr'])] })
    await flushPromises()
    requests[0]!.finish(modelCatalog)
    await flushPromises()
    expect(wrapper.get('#speech-asr').text()).toContain('My OpenRouter')
    expect(wrapper.get('#speech-tts').text()).not.toContain('My OpenRouter')
    const card = wrapper.get('[data-testid="speech-catalog-router"]')
    expect(card.text()).toContain('synthetic/rotated-key-asr')
    expect(card.text()).not.toContain('Gemini 3.8 Flash TTS')
  })

  it('cancels a pending catalog when leaving settings', async () => {
    let signal: AbortSignal | undefined
    vi.mocked(api.get).mockImplementation((path, requestSignal) => {
      if (!isModelsPath(path)) return Promise.resolve(withOpenRouter())
      signal = requestSignal
      return new Promise(() => {})
    })
    const { wrapper } = await screen()
    wrapper.unmount()
    expect(signal?.aborted).toBe(true)
  })
})
