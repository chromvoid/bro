import { describe, expect, it } from 'vitest'
import { ApiError } from '@/core/http'
import { PLUGIN_API_VERSION, type PluginUi } from './api'
import { Catalog, capabilitiesOf, errorOf, matchUi, outcomeOf, resultOf } from './catalog'

const module: PluginUi = {
  packageId: 'trading',
  contributionId: 'trading.home',
  pluginApi: PLUGIN_API_VERSION,
  requiredTools: { 'portfolio.get': 1 },
  component: async () => ({}),
}

function installation(changes: Record<string, unknown> = {}) {
  const [parsed] = Catalog.parse({
    installations: [
      {
        installation_id: 'trading-main',
        package: { id: 'trading', version: '1.0.0' },
        title: 'Торговля',
        status: 'ready',
        tools: [
          { id: 'portfolio.get', version: '1.2.0', effect: 'read' },
          { id: 'sync.refill', version: '1.0.0', effect: 'write' },
        ],
        ui: [
          {
            id: 'trading.home',
            kind: 'native',
            title: 'Торговля',
            client_api: 1,
            requires_tools: { 'portfolio.get': 1 },
          },
        ],
        configuration: { writable: false },
        events: [{ type: 'trading.data.changed', version: '1.0.0' }],
        ...changes,
      },
    ],
  }).installations
  return parsed!
}

describe('plugin catalog', () => {
  it('skips a malformed installation instead of failing the catalog', () => {
    const catalog = Catalog.parse({ installations: [{ installation_id: '../x' }, installation()] })
    expect(catalog.installations.map((item) => item.installation_id)).toEqual(['trading-main'])
  })

  it('describes what the installation allows', () => {
    const capabilities = capabilitiesOf(installation())
    expect(capabilities.status).toBe('ready')
    expect(Object.keys(capabilities.tools)).toEqual(['portfolio.get', 'sync.refill'])
    expect(capabilities.configurationWritable).toBe(false)
    expect(capabilities.events).toEqual(['trading.data.changed'])
    expect(capabilitiesOf(installation({ configuration: null })).configurationWritable).toBeNull()
    expect(capabilitiesOf(installation({ status: 'brand-new' })).status).toBe('unknown')
  })

  it('shows a module only for its contribution, client API and tool majors', () => {
    expect(matchUi(installation(), [module])).toEqual({ ui: module, fallback: null })
    expect(matchUi(installation(), [])).toEqual({ ui: null, fallback: 'no_ui' })
    expect(matchUi(installation({ status: 'disabled' }), [module]).fallback).toBe('unavailable')
    // Degraded and even unavailable installations may still be dispatched.
    expect(matchUi(installation({ status: 'unavailable' }), [module]).ui).toBe(module)
    const newer = installation({
      ui: [{ id: 'trading.home', kind: 'native', title: 'T', client_api: 2 }],
    })
    expect(matchUi(newer, [module]).fallback).toBe('client_api')
    const major = installation({
      tools: [{ id: 'portfolio.get', version: '2.0.0', effect: 'read' }],
    })
    expect(matchUi(major, [module]).fallback).toBe('missing_tools')
    const web = installation({
      ui: [{ id: 'trading.home', kind: 'web', title: 'T', client_api: 1 }],
    })
    expect(matchUi(web, [module]).fallback).toBe('no_ui')
  })

  it('reads results from invocations and lookups alike', () => {
    const completed = { contract: 'assistant.result/v1', state: 'completed', data: { ok: 1 } }
    expect(outcomeOf(resultOf(completed), null)).toMatchObject({
      kind: 'completed',
      data: { ok: 1 },
    })
    const lookup = { contract: 'assistant.lookup/v1', status: 'found', result: completed }
    expect(outcomeOf(resultOf(lookup), 'key')).toMatchObject({
      kind: 'completed',
      clientKey: 'key',
    })
    const failed = {
      state: 'failed',
      error: { code: 'not_configured', message: 'Ключ не подключён', recovery: 'none' },
    }
    expect(outcomeOf(resultOf(failed), null)).toMatchObject({
      kind: 'failed',
      error: { code: 'not_configured', recovery: 'none' },
    })
    expect(outcomeOf(resultOf({ state: 'running' }), 'key')).toMatchObject({ kind: 'pending' })
    // A read never runs in the background: a running read is not a known outcome.
    expect(outcomeOf(resultOf({ state: 'running' }), null).kind).toBe('unknown')
    expect(outcomeOf(resultOf('garbage'), null).kind).toBe('unknown')
  })

  it('keeps the structured refusal and the HTTP facts', () => {
    const detail = {
      code: 'invalid_arguments',
      message: 'Проверьте аргументы',
      recovery: 'fix_arguments',
      field_errors: [{ path: '/symbol', code: 'unknown' }],
    }
    expect(errorOf(new ApiError(422, 'invalid_arguments', detail, null, null))).toEqual({
      code: 'invalid_arguments',
      message: 'Проверьте аргументы',
      recovery: 'fix_arguments',
      fieldErrors: [{ path: '/symbol', code: 'unknown' }],
      retryAfterMs: null,
    })
    expect(
      errorOf(new ApiError(429, 'quota', { code: 'quota_exceeded' }, '3', null)),
    ).toMatchObject({
      retryAfterMs: 3000,
    })
    expect(errorOf(new ApiError(504, null, null, null, null))).toMatchObject({
      code: 'http_504',
      recovery: 'lookup',
    })
  })
})
