// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { ConfigurationView, SyncStatus } from '../../plugins/trading/web/contracts'
import { Current, ServerFrame } from '@/calls/api'
import { ApiError } from '@/core/http'
import { Catalog, errorOf, matchUi, outcomeOf, PluginEventWire, resultOf } from '@/plugins/catalog'
import { MODULES } from '@/plugins/modules'
import { Admission, History, toExchange } from '@/sections/assistant/model'
import { Estimate } from '@/sections/food/api'

/** The shared wire catalog (contracts/): Python tests compare the same files with live responses. */
const fixture = (name: string): unknown =>
  JSON.parse(readFileSync(new URL(`../../contracts/${name}.json`, import.meta.url), 'utf8'))

describe('shared wire contracts', () => {
  it('the plugin catalog selects the trading module compiled into this build', () => {
    const catalog = Catalog.parse(fixture('plugin-catalog'))
    const installation = catalog.installations[0]!
    expect(installation.installation_id).toBe('trading-main')
    expect(matchUi(installation, MODULES).ui).toBe(MODULES[0])
  })

  it('results, admissions and lookups become outcomes', () => {
    const completed = outcomeOf(resultOf(fixture('plugin-result')), 'ui:key')
    expect(completed.kind).toBe('completed')
    if (completed.kind === 'completed')
      expect(SyncStatus.safeParse(completed.data).success).toBe(true)
    expect(outcomeOf(resultOf(fixture('plugin-accepted')), 'ui:key')).toMatchObject({
      kind: 'pending',
      state: 'accepted',
    })
    expect(outcomeOf(resultOf(fixture('plugin-lookup')), 'ui:key').kind).not.toBe('unknown')
    // «Not found yet» is not a permission to resend: it names whether the call was dispatched.
    expect(fixture('plugin-lookup-not-found')).toMatchObject({
      status: 'not_found',
      dispatched: 'unknown',
    })
  })

  it('a refusal keeps its recovery and field errors, never argument values', () => {
    const body = fixture('plugin-rejected') as { error: string; detail: unknown }
    expect(errorOf(new ApiError(422, body.error, body.detail, null, null))).toMatchObject({
      code: 'invalid_arguments',
      recovery: 'fix_arguments',
      fieldErrors: [{ path: '/symbol', code: 'type' }],
    })
  })

  it('events, configuration, history, admission and estimates decode', () => {
    expect(PluginEventWire.parse(fixture('plugin-event')).cursor).toBe('e1.42')
    const configuration = ConfigurationView.parse(fixture('plugin-configuration'))
    expect(configuration.secret_state.api_key).toMatchObject({ present: true })
    expect(JSON.stringify(configuration)).not.toMatch(/secret"?:\s*"/)
    const history = History.parse(fixture('chat-history'))
    expect(history.messages.length).toBeGreaterThan(0)
    const photo = toExchange(history.messages.find((message) => message.images?.length)!).images[0]!
    expect(photo.previewUrl).toBe(`${photo.url}/preview?v=1`)
    expect(Admission.parse(fixture('submission')).state).toBe('queued')
    expect(Estimate.safeParse(fixture('portion-estimate')).success).toBe(true)
  })

  it('the current call and every signalling frame the server sends decode', () => {
    expect(Current.parse(fixture('call-current')).call).toMatchObject({
      state: 'ringing',
      role: 'callee',
      outcome: null,
    })
    const frames = fixture('call-signal') as { client: unknown[]; server: unknown[] }
    expect(frames.server.map((frame) => ServerFrame.parse(frame).type)).toEqual([
      'state',
      'peer',
      'offer',
      'pong',
      'error',
    ])
    // Descriptions travel byte for byte, the final CRLF included.
    const offer = frames.client[0] as { sdp: string }
    expect(ServerFrame.parse(frames.server[2])).toEqual({ type: 'offer', sdp: offer.sdp })
    expect(offer.sdp.endsWith('\r\n')).toBe(true)
  })
})
