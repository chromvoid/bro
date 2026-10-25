/**
 * What a call learns about the network, in codes only: the kinds of this device's addresses, the
 * selected candidate pair and a few numbers. Addresses, ports, ufrag and candidate lines never
 * leave this module: not in the summary, the screen or the logs.
 */
import {
  KINDS,
  PROTOCOLS,
  TYPES,
  type CandidateType,
  type Client,
  type Kind,
  type Protocol,
  type Selected,
  type Summary,
} from './api'

const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/

function octets(address: string): number[] | null {
  const match = IPV4.exec(address)
  if (!match) return null
  const parts = match.slice(1).map(Number)
  return parts.every((part) => part <= 255) ? parts : null
}

function classify4([a, b]: number[]): Kind {
  if (a === undefined || b === undefined) return 'other'
  // Tailscale gives every device an address from the carrier-grade NAT range.
  if (a === 100 && b >= 64 && b <= 127) return 'tailnet4'
  if (a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)) return 'lan4'
  // This host, link-local, multicast and reserved ranges reach nobody.
  if (a === 0 || a === 127 || (a === 169 && b === 254) || a >= 224) return 'other'
  return 'public4'
}

/** The eight 16-bit groups of an IPv6 address, or null when it is not one. */
function groups(address: string): number[] | null {
  const halves = address.split('::')
  if (halves.length > 2) return null
  const split = (half: string | undefined) => (half ? half.split(':') : [])
  const head = split(halves[0])
  const tail = split(halves[1])
  const missing = 8 - head.length - tail.length
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null
  const all = [...head, ...Array<string>(halves.length === 2 ? missing : 0).fill('0'), ...tail]
  if (!all.every((group) => /^[0-9a-f]{1,4}$/i.test(group))) return null
  return all.map((group) => parseInt(group, 16))
}

/** The network an address belongs to (docs/calls-spec.md). */
export function classify(address?: string | null): Kind {
  const value = (address ?? '').trim().toLowerCase()
  if (!value) return 'unknown'
  if (value.endsWith('.local')) return 'mdns'
  const four = octets(value)
  if (four) return classify4(four)
  const bare = value.replace(/^\[|\]$/g, '').replace(/%.*$/, '')
  // An IPv4 address carried in IPv6 (::ffff:192.168.1.7) is classified as IPv4.
  const mapped = /^::ffff:([\d.]+)$/.exec(bare)
  const embedded = mapped ? octets(mapped[1]!) : null
  if (embedded) return classify4(embedded)
  const six = groups(bare)
  if (!six) return 'other'
  const [first = 0, second = 0, third = 0] = six
  if (first === 0xfd7a && second === 0x115c && third === 0xa1e0) return 'tailnet6'
  if ((first & 0xfe00) === 0xfc00 || (first & 0xffc0) === 0xfe80) return 'lan6'
  if ((first & 0xe000) === 0x2000) return 'public6'
  return 'other'
}

const ordered = <T>(values: Set<T>, order: readonly T[]) => order.filter((item) => values.has(item))

/** The local candidates of a description, by kind, type and protocol; in a fixed order. */
export function parseCandidates(sdp: string | null | undefined): {
  kinds: Kind[]
  types: CandidateType[]
  protocols: Protocol[]
} {
  const kinds = new Set<Kind>()
  const types = new Set<CandidateType>()
  const protocols = new Set<Protocol>()
  for (const line of (sdp ?? '').split(/\r?\n/)) {
    if (!line.startsWith('a=candidate:')) continue
    const tokens = line.trim().split(/\s+/)
    kinds.add(classify(tokens[4]))
    const typ = tokens.indexOf('typ')
    const type = tokens[typ + 1] as CandidateType
    if (typ > 0 && TYPES.includes(type)) types.add(type)
    const protocol = tokens[2]?.toLowerCase() as Protocol
    if (PROTOCOLS.includes(protocol)) protocols.add(protocol)
  }
  return {
    kinds: ordered(kinds, KINDS),
    types: ordered(types, TYPES),
    protocols: ordered(protocols, PROTOCOLS),
  }
}

type Stat = Record<string, unknown> & { id: string; type: string }

/** The candidate pair carrying the media: the transport's choice, else the busiest nominated. */
function pairOf(report: RTCStatsReport): { pair: Stat; byId: Map<string, Stat> } | null {
  const stats = [...(report as unknown as Map<string, Stat>).values()]
  const byId = new Map(stats.map((stat) => [stat.id, stat]))
  const chosen = stats
    .filter((stat) => stat.type === 'transport' && typeof stat.selectedCandidatePairId === 'string')
    .map((stat) => byId.get(stat.selectedCandidatePairId as string))
    .find((stat) => stat?.type === 'candidate-pair')
  if (chosen) return { pair: chosen, byId }
  const pairs = stats.filter((stat) => stat.type === 'candidate-pair')
  const received = (stat: Stat) => (typeof stat.bytesReceived === 'number' ? stat.bytesReceived : 0)
  const fallback =
    pairs.find((stat) => stat.selected === true) ??
    pairs
      .filter((stat) => stat.nominated === true && stat.state === 'succeeded')
      .sort((a, b) => received(b) - received(a))[0]
  return fallback ? { pair: fallback, byId } : null
}

/** The selected pair as codes; null until there is one or when a field is not a known code. */
export function selectedPair(report: RTCStatsReport): Selected | null {
  const found = pairOf(report)
  if (!found) return null
  const local = found.byId.get(String(found.pair.localCandidateId))
  const remote = found.byId.get(String(found.pair.remoteCandidateId))
  if (!local || !remote) return null
  const local_type = local.candidateType as CandidateType
  const remote_type = remote.candidateType as CandidateType
  const protocol = String(local.protocol ?? '').toLowerCase() as Protocol
  if (!TYPES.includes(local_type) || !TYPES.includes(remote_type)) return null
  if (!PROTOCOLS.includes(protocol)) return null
  const address = (local.address ?? local.ip) as string | undefined
  return { local_type, remote_type, local_kind: classify(address), protocol }
}

/** What the call collected from getStats() so far. */
export interface Sample {
  selected: Selected | null
  /** Round-trip times in milliseconds, one per sample. */
  rtt: number[]
  /** Cumulative inbound audio loss in percent. */
  loss: number | null
}

export const NO_SAMPLE: Sample = { selected: null, rtt: [], loss: null }

/** Reads the connection's statistics once and adds them to the previous sample. */
export async function sampleStats(pc: RTCPeerConnection, prev: Sample): Promise<Sample> {
  const report = await pc.getStats()
  const found = pairOf(report)
  const seconds = found?.pair.currentRoundTripTime
  const rtt =
    typeof seconds === 'number' && Number.isFinite(seconds)
      ? [...prev.rtt, Math.round(seconds * 1000)]
      : prev.rtt
  let loss = prev.loss
  for (const stat of (report as unknown as Map<string, Stat>).values()) {
    if (stat.type !== 'inbound-rtp' || (stat.kind ?? stat.mediaType) !== 'audio') continue
    const lost = Math.max(0, Number(stat.packetsLost) || 0)
    const received = Math.max(0, Number(stat.packetsReceived) || 0)
    if (lost + received) loss = Math.round((lost / (lost + received)) * 100)
  }
  return { selected: selectedPair(report) ?? prev.selected, rtt, loss }
}

function median(values: number[]): number | null {
  if (!values.length) return null
  const sorted = values.toSorted((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2
    ? sorted[middle]!
    : Math.round((sorted[middle - 1]! + sorted[middle]!) / 2)
}

const clamp = (value: number, max: number) => Math.min(max, Math.max(0, Math.round(value)))

export interface SummaryState {
  client: Client
  /** The furthest ICE state reached. */
  ice: Summary['ice']
  /** This side's local description, read only for its candidate lines. */
  sdp: string | null | undefined
  sample: Sample
  autoplayBlocked: boolean
}

/** The summary for `end`: only the keys the server accepts, never an address or a port. */
export function buildSummary(state: SummaryState): Summary {
  const { kinds, types } = parseCandidates(state.sdp)
  const summary: Summary = {
    client: state.client,
    ice: state.ice,
    selected: state.sample.selected,
    autoplay_blocked: state.autoplayBlocked,
  }
  if (kinds.length) summary.local_kinds = kinds
  if (types.length) summary.local_types = types
  const rtt = median(state.sample.rtt)
  if (rtt !== null) summary.rtt_ms = clamp(rtt, 60_000)
  if (state.sample.loss !== null) summary.loss_pct = clamp(state.sample.loss, 100)
  return summary
}

export interface Timers {
  setTimeout(handler: () => void, ms: number): ReturnType<typeof setTimeout>
  clearTimeout(timer: ReturnType<typeof setTimeout> | undefined): void
}

const BROWSER_TIMERS: Timers = {
  setTimeout: (handler, ms) => setTimeout(handler, ms),
  clearTimeout: (timer) => clearTimeout(timer),
}

/**
 * Waits until ICE gathering completes or [ms] pass. Without STUN only host candidates exist,
 * so this normally takes well under a second.
 */
export function gathered(pc: RTCPeerConnection, ms: number, timers: Timers): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise((resolve) => {
    const done = () => {
      timers.clearTimeout(timer)
      pc.onicegatheringstatechange = null
      resolve()
    }
    const timer = timers.setTimeout(done, ms)
    pc.onicegatheringstatechange = () => {
      if (pc.iceGatheringState === 'complete') done()
    }
  })
}

export interface MediaDeps {
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream>
  createPeerConnection(configuration: RTCConfiguration): RTCPeerConnection
  timers?: Timers
}

/** Direct connections only: no STUN or TURN, so media never passes through anyone's server. */
export const RTC_CONFIG: RTCConfiguration = {
  iceServers: [],
  bundlePolicy: 'max-bundle',
  rtcpMuxPolicy: 'require',
  iceCandidatePoolSize: 0,
}
export const AUDIO: MediaStreamConstraints = {
  audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  video: false,
}
export const PROBE_GATHER_MS = 3_000

/**
 * The developer's network self-check: which kinds of addresses this device would offer in a
 * call. It sends nothing to the server.
 */
export async function probeNetwork(deps: MediaDeps) {
  // Without capture the browser hides host addresses, exactly as in a call.
  const stream = await deps.getUserMedia(AUDIO)
  let pc: RTCPeerConnection | null = null
  try {
    pc = deps.createPeerConnection(RTC_CONFIG)
    const [track] = stream.getAudioTracks()
    if (track) pc.addTransceiver(track, { direction: 'sendrecv', streams: [stream] })
    await pc.setLocalDescription(await pc.createOffer())
    await gathered(pc, PROBE_GATHER_MS, deps.timers ?? BROWSER_TIMERS)
    return parseCandidates(pc.localDescription?.sdp)
  } finally {
    for (const track of stream.getTracks()) track.stop()
    pc?.close()
  }
}
