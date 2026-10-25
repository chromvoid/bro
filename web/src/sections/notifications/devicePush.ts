import { computed, onMounted, ref, watch } from 'vue'
import { useShell } from '@/core/shell'
import { nativeNotifications } from './native'
import { webPushState, type WebPushState } from './push'

/**
 * Whether this device hears the assistant while the app is closed: the shell's notifications in
 * the phone apps, Web Push in a browser. `null` until the first read.
 */
export function useDevicePush() {
  const shell = useShell()
  const native = nativeNotifications()
  const isNative = computed(() => shell.capabilities.value?.notifications?.local === true)
  const state = ref<WebPushState | null>(null)
  async function refresh() {
    await shell.ready()
    state.value = await (isNative.value ? native.refreshState() : webPushState()).catch(
      () => 'unsupported' as const,
    )
  }
  onMounted(refresh)
  watch(native.state, (value) => {
    if (isNative.value) state.value = value
  })
  return { native, isNative, state, refresh }
}
