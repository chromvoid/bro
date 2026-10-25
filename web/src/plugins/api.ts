/**
 * `PluginHost/v1` for client modules (docs/plugin-contract-spec.md §10.1): everything a plugin
 * screen may use. The shell owns the session, CSRF, the write journal and event streams; a module
 * sees only this interface and never receives credentials, other installations or shell state.
 * A module runs inside the app, so this boundary is a contract, not a security sandbox.
 *
 * Ports absent from v1, as in the Kotlin host: requestConfirmation (every v1 write is an explicit
 * action on the plugin's own screen), openResource (no attachments) and theme (modules use the
 * shell's design tokens and components).
 */
import type { Component, Ref } from 'vue'

/** The `client_api` a UI contribution must declare to be rendered by this shell. */
export const PLUGIN_API_VERSION = 1

export type Recovery = 'fix_arguments' | 'reauthenticate' | 'refresh_state' | 'lookup' | 'none'

/** A stable code and a message safe to show; field errors never carry argument values. */
export interface PluginError {
  code: string
  message: string
  recovery: Recovery
  fieldErrors: Array<{ path: string; code: string }>
  retryAfterMs: number | null
}

/**
 * The outcome of a tool call or a configuration change. Protocol and network failures are
 * values, never exceptions. `clientKey` names a journaled write; it is null for reads.
 */
export type InvocationOutcome =
  /** Terminal success; `data` is the tool output. */
  | { kind: 'completed'; data: unknown; receipts: unknown[]; clientKey: string | null }
  /** Terminal domain failure after admission (failed or cancelled). */
  | { kind: 'failed'; error: PluginError; receipts: unknown[]; clientKey: string | null }
  /** Admitted and still running; resolve through events and `lookup`. */
  | { kind: 'pending'; clientKey: string; state: string; progress: unknown }
  /**
   * Not known: the response was lost. Only `lookup` may clarify it; `notFound` (the last lookup
   * found nothing yet) is what permits an explicit `retry`. For a read it means no data.
   */
  | { kind: 'unknown'; clientKey: string | null; notFound: boolean; error: PluginError | null }
  /** Refused before admission: nothing happened. `status` is null for a local refusal. */
  | { kind: 'rejected'; error: PluginError; status: number | null; clientKey: string | null }

export type WriteState = 'prepared' | 'sending' | 'uncertain' | 'accepted' | 'resolved'

/** A journaled write the module has not acknowledged yet. */
export interface PendingWrite {
  clientKey: string
  tool: string
  arguments: Record<string, unknown>
  state: WriteState
  createdAt: number
  /** The last lookup found nothing; only then may the module offer to resend. */
  notFound: boolean
  /** Admitted or finished on the server: nothing may be resent. */
  admitted: boolean
}

export type PluginEvent =
  /** Stream opened at the current head: data loaded before may be stale, re-read it. */
  | { kind: 'ready'; cursor: string }
  /** The previous cursor expired: reload every view, then continue. */
  | { kind: 'resync'; cursor: string }
  /** A signal to re-read through tools, not a change log; may repeat across reconnects. */
  | {
      kind: 'delivered'
      eventId: string
      type: string
      occurredAt: string
      cursor: string
      payload: Record<string, unknown>
      operationKey: string | null
    }
  /** Access to events ended (session or grant); the host does not reconnect. */
  | { kind: 'denied'; status: number }

export type InstallationStatus =
  | 'starting'
  | 'ready'
  | 'degraded'
  | 'unavailable'
  | 'incompatible'
  | 'rejected'
  | 'disabled'
  | 'unknown'

export interface PluginTool {
  id: string
  version: string
  effect: string
  scopes: string[]
  surfaces: string[]
}

export interface PluginCapabilities {
  status: InstallationStatus
  note: string | null
  grantedScopes: string[]
  tools: Record<string, PluginTool>
  /** Null when the user may not read the configuration; otherwise whether it may be changed. */
  configurationWritable: boolean | null
  events: string[]
}

/** A route inside one plugin: a name and opaque ids, never credentials or personal payload. */
export interface PluginRoute {
  name: string
  ids: Record<string, string>
}

export type PluginLifecycle = 'foreground' | 'background' | 'closed'

/**
 * Configuration of this installation (`assistant.configuration/v1`), sent directly and never
 * journaled or kept. After an `unknown` outcome read `get` again: the revision tells.
 */
export interface PluginConfiguration {
  get(): Promise<InvocationOutcome>
  /** Secrets exist only in this request body; `expectedRevision` once configured. */
  put(
    settings: Record<string, unknown>,
    secrets?: Record<string, string> | null,
    expectedRevision?: number | null,
    options?: Record<string, unknown> | null,
  ): Promise<InvocationOutcome>
  /** Disconnects; `purge` also removes the data the plugin stored for this user. */
  delete(expectedRevision: number, purge?: boolean): Promise<InvocationOutcome>
}

export interface PluginHost {
  readonly installationId: string
  readonly packageId: string
  /** BCP 47 language of the shell UI. */
  readonly locale: string
  readonly capabilities: Readonly<Ref<PluginCapabilities>>
  /** Background and closed: stop streams and polling. Closed is final. */
  readonly lifecycle: Readonly<Ref<PluginLifecycle>>
  readonly route: Readonly<Ref<PluginRoute>>
  /** Unacknowledged journaled writes of this installation, oldest first. */
  readonly pendingWrites: Readonly<Ref<PendingWrite[]>>
  readonly configuration: PluginConfiguration

  /**
   * Calls a tool by id at the catalog's version. Reads go straight to the server and may repeat;
   * a write gets a fresh key and its exact request is journaled before it is sent.
   */
  invoke(tool: string, args?: Record<string, unknown>): Promise<InvocationOutcome>
  /** Looks up a journaled write by its key; never sends it again. */
  lookup(clientKey: string): Promise<InvocationOutcome>
  /** Explicit user action after a lookup found nothing: the same key and bytes once more. */
  retry(clientKey: string): Promise<InvocationOutcome>
  /** Forgets a write whose terminal outcome the module has shown. */
  acknowledge(clientKey: string): Promise<void>
  /**
   * Follows this installation's events until the returned stop is called. Subscribe only while
   * the lifecycle is foreground; events are de-duplicated and reconnects back off.
   */
  observe(listener: (event: PluginEvent) => void): () => void
  navigate(route: PluginRoute): void
  close(): void
}

/**
 * A client module compiled into the app (`kind: native` in the manifest). The shell renders it
 * only for a catalog contribution with the same package and contribution id, `client_api` equal
 * to [PLUGIN_API_VERSION] and every required tool at the declared major version.
 */
export interface PluginUi {
  packageId: string
  contributionId: string
  pluginApi: number
  /** Tool id to the major version this UI was written against. */
  requiredTools: Record<string, number>
  /** The navigation icon; the shell uses a neutral one without it. */
  icon?: Component
  /** Loads the component (taking the `host` prop) when the screen opens: `() => import(…)`. */
  component: () => Promise<Component | { default: Component }>
}

export const ROUTE_NAME = /^[a-z][a-z0-9._-]{0,63}$/
export const ROUTE_KEY = /^[a-z][a-z0-9_]{0,31}$/
export const ROUTE_ID = /^[A-Za-z0-9._:-]{1,128}$/

export function isRoute(route: PluginRoute): boolean {
  const ids = Object.entries(route.ids)
  return (
    ROUTE_NAME.test(route.name) &&
    ids.length <= 8 &&
    ids.every(([key, value]) => ROUTE_KEY.test(key) && ROUTE_ID.test(value))
  )
}
