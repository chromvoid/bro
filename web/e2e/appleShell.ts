import type { Page } from '@playwright/test'

export interface AppleShellOptions {
  capabilities?: Record<string, unknown>
  /** Replies by request type; missing commands fail as unknown_type. */
  replies?: Record<string, unknown>
}

/** The WKWebView reply handler and event callback on the assistant's own origin. */
export async function appleShell(page: Page, options: AppleShellOptions = {}) {
  await page.addInitScript((options: AppleShellOptions) => {
    const sent: string[] = []
    const capabilities = { shell_api: 1, platform: 'ios', ...options.capabilities }
    Object.assign(window, {
      shellSent: sent,
      shellEmit(event: string, payload: unknown) {
        const receiver = (window as unknown as { __assistantShellEvent?: (data: unknown) => void })
          .__assistantShellEvent
        receiver?.(JSON.stringify({ event, payload }))
      },
      webkit: {
        messageHandlers: {
          AssistantShell: {
            async postMessage(message: string) {
              sent.push(message)
              const { id, type } = JSON.parse(message) as { id: string; type: string }
              const replies = options.replies ?? {}
              if (type in replies) return JSON.stringify({ id, ok: true, result: replies[type] })
              if (type === 'shell.capabilities')
                return JSON.stringify({ id, ok: true, result: capabilities })
              return JSON.stringify({ id, ok: false, error: 'unknown_type' })
            },
          },
        },
      },
    })
  }, options)
}

export async function sentTypes(page: Page) {
  return page.evaluate(() =>
    (window as unknown as { shellSent: string[] }).shellSent.map(
      (message) => (JSON.parse(message) as { type: string }).type,
    ),
  )
}
