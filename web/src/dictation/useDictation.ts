/** Dictation into a draft: recognized text fills the field, the user reviews and sends. */
import { useEventListener } from '@vueuse/core'
import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { api, updates } from '@/core/client'
import { useSession } from '@/stores/session'
import { BrowserMicrophone, type AudioSource } from './audio'
import { DictationDraft } from './draft'
import {
  DictationSession,
  type DictationFailure,
  type DictationProblem,
  type DictationState,
} from './session'

export function useDictation(
  draft: Ref<string>,
  source: () => AudioSource = () => new BrowserMicrophone(),
) {
  const state = ref<DictationState>('idle')
  const problem = ref<DictationProblem | null>(null)
  const failure = ref<DictationFailure>({})
  const limitReached = ref(false)
  /** The draft may be missing the end of what was said (interruption after partial text). */
  const incomplete = ref(false)
  const transcriptionId = ref<string | null>(null)
  const elapsed = ref(0)
  const canRetry = ref(false)
  const account = useSession()
  let session: DictationSession | null = null
  let merge: DictationDraft | null = null
  let heardSomething = false
  let started = 0
  let ticker: ReturnType<typeof setInterval> | undefined
  let release: (() => void) | null = null
  // The recognizer revises the running text several times a second, each time in full. The draft
  // takes the newest revision once per frame: a busy screen skips revisions instead of queueing
  // them, so the text never falls further behind the longer someone speaks.
  let revision: string | null = null
  let frame = 0

  function showRevision() {
    cancelAnimationFrame(frame)
    frame = 0
    const text = revision
    revision = null
    if (text !== null && merge) draft.value = merge.update(draft.value, text)
  }

  function dropRevision() {
    cancelAnimationFrame(frame)
    frame = 0
    revision = null
  }

  const active = computed(() => ['permission', 'recording', 'finalizing'].includes(state.value))

  function start() {
    if (active.value) return
    session?.cancel()
    canRetry.value = false
    problem.value = null
    failure.value = {}
    limitReached.value = false
    incomplete.value = false
    heardSomething = false
    merge = new DictationDraft(draft.value)
    release = updates.hold()
    session = new DictationSession(
      source(),
      {
        state(next, reason, detail) {
          canRetry.value = session?.canRetry ?? false
          failure.value = detail ?? {}
          if (reason === 'limit') {
            limitReached.value = true
            return
          }
          state.value = next
          if (next === 'recording') {
            started = Date.now()
            ticker = setInterval(
              () => (elapsed.value = Math.floor((Date.now() - started) / 1000)),
              250,
            )
          }
          if (!['permission', 'recording', 'finalizing'].includes(next)) finish(next, reason)
        },
        partial(text) {
          heardSomething = heardSomething || !!text.trim()
          revision = text
          frame ||= requestAnimationFrame(showRevision)
        },
        final(result) {
          dropRevision()
          if (merge) draft.value = merge.update(draft.value, result.text)
          // The id travels with the message even when the user edited the text afterwards.
          transcriptionId.value = result.transcriptionId
        },
      },
      { preflight: (signal) => api.get('/v1/me/speech/readiness?capability=asr', signal) },
    )
    void session.start()
  }

  function finish(next: DictationState, reason: DictationProblem | undefined) {
    clearInterval(ticker)
    elapsed.value = 0
    release?.()
    release = null
    // What was heard before an interruption stays in the draft; a cancelled dictation leaves none.
    if (next === 'cancelled') dropRevision()
    else showRevision()
    if (next === 'cancelled' && merge) draft.value = merge.cancel(draft.value)
    if (next === 'interrupted' || next === 'failed') {
      problem.value = reason ?? 'server'
      incomplete.value = heardSomething
    }
    // WebSocket failures do not pass through Api.request's HTTP 401 handler.
    if (reason === 'session_expired') api.hooks.unauthorized?.()
  }

  /** The text was sent or cleared: the transcription no longer describes the draft. */
  function reset() {
    if (!active.value) {
      session?.cancel()
      session = null
    }
    canRetry.value = false
    transcriptionId.value = null
    problem.value = null
    incomplete.value = false
    if (!active.value) state.value = 'idle'
  }

  function retry() {
    if (!canRetry.value) return
    problem.value = null
    failure.value = {}
    incomplete.value = false
    heardSomething = false
    release = updates.hold()
    session?.retry()
  }

  function cancel() {
    session?.cancel()
    canRetry.value = false
  }

  watch(
    () => account.me,
    (next, previous) => {
      if (!previous || (next?.id === previous.id && next?.device_id === previous.device_id)) return
      cancel()
      reset()
    },
    { flush: 'sync' },
  )

  // Leaving the page stops the recording; nothing resumes on return.
  useEventListener(document, 'visibilitychange', () => {
    if (document.visibilityState === 'hidden') session?.interrupt('backgrounded')
  })
  useEventListener(window, 'pagehide', () => session?.interrupt('backgrounded'))
  onBeforeUnmount(cancel)

  return {
    state,
    problem,
    failure,
    limitReached,
    incomplete,
    transcriptionId,
    elapsed,
    active,
    canRetry,
    start,
    retry,
    stop: () => void session?.finish(),
    cancel,
    reset,
  }
}
