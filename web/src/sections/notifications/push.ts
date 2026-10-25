/**
 * Delivery to this browser while the app is closed: Web Push through the browser's push service,
 * encrypted for this browser only (docs/notifications-spec.md §6). The phone apps use their shell.
 */
import { api } from '@/core/client'
import { z } from '@/core/zod'

export const PushConfig = z.object({
  webpush: z.object({ public_key: z.string() }).nullable(),
  kinds: z.array(z.string()),
})
export type PushConfig = z.infer<typeof PushConfig>

export const NotificationSettings = z.object({
  quiet_start: z.string().nullable(),
  quiet_end: z.string().nullable(),
  muted_topics: z.array(z.string()),
  revision: z.number(),
  topics: z.array(z.object({ topic: z.string(), severity: z.string() })),
})
export type NotificationSettings = z.infer<typeof NotificationSettings>

export type WebPushState = 'unsupported' | 'denied' | 'off' | 'on'

export class PushError extends Error {}

/** The VAPID key as the Push API wants it. */
export function keyBytes(value: string): Uint8Array<ArrayBuffer> {
  const base64 =
    value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (value.length % 4)) % 4)
  const raw = atob(base64)
  const bytes = new Uint8Array(new ArrayBuffer(raw.length))
  for (let index = 0; index < raw.length; index++) bytes[index] = raw.charCodeAt(index)
  return bytes
}

export function supported(scope: object = globalThis): boolean {
  const navigator = (scope as { navigator?: object }).navigator
  return (
    !!navigator && 'serviceWorker' in navigator && 'PushManager' in scope && 'Notification' in scope
  )
}

async function registration(): Promise<ServiceWorkerRegistration | undefined> {
  return navigator.serviceWorker.getRegistration('/')
}

export async function webPushState(): Promise<WebPushState> {
  if (!supported()) return 'unsupported'
  if (Notification.permission === 'denied') return 'denied'
  const subscription = await (await registration())?.pushManager.getSubscription()
  return subscription && Notification.permission === 'granted' ? 'on' : 'off'
}

export async function enableWebPush(publicKey: string): Promise<void> {
  if ((await Notification.requestPermission()) !== 'granted') throw new PushError('denied')
  const worker = await navigator.serviceWorker.register('/sw.js', { scope: '/' })
  await navigator.serviceWorker.ready
  const subscription =
    (await worker.pushManager.getSubscription()) ??
    (await worker.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: keyBytes(publicKey),
    }))
  const { endpoint, keys } = subscription.toJSON()
  if (!endpoint || !keys?.p256dh || !keys.auth) throw new PushError('subscription')
  await api.post('/v1/push/channels', {
    kind: 'webpush',
    address: endpoint,
    public_key: keys.p256dh,
    auth_secret: keys.auth,
  })
}

export async function disableWebPush(): Promise<void> {
  await (await (await registration())?.pushManager.getSubscription())?.unsubscribe()
  await api.post('/v1/push/channels/remove', {})
}
