import { useOnline } from '@vueuse/core'
import { computed, ref, watch, type ComputedRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { nativeAmbientAudio } from '@/ambient/native'
import { calls } from '@/calls/store'
import type { CallModel } from '@/calls/useCalls'
import { speechPlayback } from '@/speech/playback'
import { useSession } from '@/stores/session'
import { ambientStatus, callStatus, connectionStatus, speechStatus } from './sources'
import { isSticky, ranked } from './statusModel'

/** The live status items of this page, its top one and whether the line stays on screen. */
export function useShellStatus(call: ComputedRef<CallModel>) {
  const { t } = useI18n()
  const translate = (key: string, values?: Record<string, unknown>) =>
    values ? t(key, values) : t(key)
  const online = useOnline()
  const native = nativeAmbientAudio()
  const session = useSession()
  /** The sheet with every item and its actions. */
  const open = ref(false)
  const quietly = (promise: Promise<unknown>) => void promise.catch(() => undefined)

  const items = computed(() =>
    ranked([
      callStatus(call.value, translate, (current) => quietly(calls.hangUpElsewhere(current))),
      ambientStatus(
        {
          supported: native.supported.value,
          state: native.state.value,
          failure: native.failure.value,
          busy: native.busy.value,
          signedIn: session.state === 'ready',
        },
        translate,
        { open: () => quietly(native.open()), stop: () => quietly(native.stop()) },
      ),
      connectionStatus(online.value, translate),
      speechStatus(speechPlayback.state.value, translate, () => speechPlayback.stop()),
    ]),
  )
  const top = computed(() => items.value[0] ?? null)
  const sticky = computed(() => isSticky(items.value))
  watch(items, (list) => {
    if (!list.length) open.value = false
  })
  const setOpen = (value: boolean) => (open.value = value)
  return { items, top, sticky, open, setOpen }
}

export type ShellStatus = ReturnType<typeof useShellStatus>
