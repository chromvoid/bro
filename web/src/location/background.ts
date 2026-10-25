import { useShell } from '@/core/shell'
import { z } from '@/core/zod'

/** Background movement recording in the phone app (docs/location-weather-spec.md §3.1); null elsewhere. */
export const BackgroundState = z.object({
  enabled: z.boolean(),
  permission: z.enum(['granted', 'prompt', 'denied']),
  queued: z.number().int(),
})
export type BackgroundState = z.infer<typeof BackgroundState>

export interface BackgroundRecording {
  status(user: string): Promise<BackgroundState>
  /** Asks for «Всегда» when needed, enrols this phone and starts recording. */
  enable(user: string): Promise<BackgroundState>
  disable(): Promise<BackgroundState>
}

export async function backgroundRecording(): Promise<BackgroundRecording | null> {
  const native = useShell()
  const capabilities = await native.ready()
  if (!capabilities?.location?.background) return null
  const ask = async (type: string, payload: Record<string, unknown>, timeout?: number) =>
    BackgroundState.parse(await native.request(type, payload, timeout))
  return {
    status: (user) => ask('location.background.status', { user_id: user }),
    // The person may pass through the system's settings to choose «Всегда».
    enable: (user) => ask('location.background.enable', { user_id: user }, 120_000),
    disable: () => ask('location.background.disable', {}, 30_000),
  }
}
