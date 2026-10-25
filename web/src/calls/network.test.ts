import { describe, expect, it, vi } from 'vitest'
import {
  NO_SAMPLE,
  RTC_CONFIG,
  buildSummary,
  classify,
  parseCandidates,
  probeNetwork,
  sampleStats,
  selectedPair,
} from './network'

const SDP = [
  'v=0',
  'o=- 4611731400430051336 2 IN IP4 127.0.0.1',
  'm=audio 9 UDP/TLS/RTP/SAVPF 111',
  'a=candidate:1 1 udp 2122260223 192.168.1.7 50000 typ host generation 0',
  'a=candidate:2 1 udp 2122194687 100.101.102.103 50001 typ host generation 0',
  'a=candidate:3 1 tcp 1518280447 fd7a:115c:a1e0::5 9 typ host tcptype active',
  'a=candidate:4 1 udp 1686052607 203.0.113.9 50002 typ srflx raddr 192.168.1.7 rport 50000',
  'a=candidate:5 1 udp 2122129151 0b1c2d3e.local 50003 typ host',
  'a=ice-ufrag:abcd',
  'a=ice-pwd:synthetic-password-0000000000',
  '',
].join('\r\n')

const report = (stats: Array<Record<string, unknown>>) =>
  new Map(stats.map((stat) => [stat.id as string, stat])) as unknown as RTCStatsReport

const candidates = [
  {
    id: 'L1',
    type: 'local-candidate',
    candidateType: 'host',
    protocol: 'udp',
    address: '192.168.1.7',
  },
  { id: 'L2', type: 'local-candidate', candidateType: 'host', protocol: 'udp', ip: '100.64.1.2' },
  { id: 'R1', type: 'remote-candidate', candidateType: 'prflx', protocol: 'udp' },
]

describe('classify', () => {
  it.each([
    ['100.64.1.2', 'tailnet4'],
    ['100.127.255.254', 'tailnet4'],
    ['fd7a:115c:a1e0::5', 'tailnet6'],
    ['192.168.1.7', 'lan4'],
    ['10.2.3.4', 'lan4'],
    ['172.20.0.1', 'lan4'],
    ['x.local', 'mdns'],
    ['2001:db8::1', 'public6'],
    ['203.0.113.9', 'public4'],
    ['100.128.0.1', 'public4'],
    ['127.0.0.1', 'other'],
    ['169.254.1.1', 'other'],
    ['fd00::1', 'lan6'],
    ['fe80::1%en0', 'lan6'],
    ['::1', 'other'],
    ['::ffff:192.168.1.7', 'lan4'],
    ['999.1.1.1', 'other'],
    ['', 'unknown'],
    [undefined, 'unknown'],
  ] as const)('%s is %s', (address, kind) => {
    expect(classify(address)).toBe(kind)
  })
})

describe('parseCandidates', () => {
  it('reads kinds, types and protocols from the candidate lines only', () => {
    expect(parseCandidates(SDP)).toEqual({
      kinds: ['lan4', 'tailnet4', 'tailnet6', 'public4', 'mdns'],
      types: ['host', 'srflx'],
      protocols: ['udp', 'tcp'],
    })
    expect(parseCandidates(null)).toEqual({ kinds: [], types: [], protocols: [] })
  })
})

describe('selectedPair', () => {
  it("follows the transport's selected pair", () => {
    const stats = report([
      { id: 'T', type: 'transport', selectedCandidatePairId: 'P2' },
      { id: 'P1', type: 'candidate-pair', localCandidateId: 'L1', remoteCandidateId: 'R1' },
      { id: 'P2', type: 'candidate-pair', localCandidateId: 'L2', remoteCandidateId: 'R1' },
      ...candidates,
    ])
    expect(selectedPair(stats)).toEqual({
      local_type: 'host',
      remote_type: 'prflx',
      local_kind: 'tailnet4',
      protocol: 'udp',
    })
  })

  it('falls back to the busiest nominated pair, or one marked selected', () => {
    const nominated = report([
      ...[
        ['P1', 'L1', 10],
        ['P2', 'L2', 900],
      ].map(([id, local, bytes]) => ({
        id,
        type: 'candidate-pair',
        localCandidateId: local,
        remoteCandidateId: 'R1',
        nominated: true,
        state: 'succeeded',
        bytesReceived: bytes,
      })),
      ...candidates,
    ])
    expect(selectedPair(nominated)?.local_kind).toBe('tailnet4')
    const marked = report([
      {
        id: 'P1',
        type: 'candidate-pair',
        localCandidateId: 'L1',
        remoteCandidateId: 'R1',
        selected: true,
      },
      ...candidates,
    ])
    expect(selectedPair(marked)?.local_kind).toBe('lan4')
    expect(selectedPair(report(candidates))).toBeNull()
  })
})

describe('sampleStats and the summary', () => {
  const stats = (rtt: number, lost: number, received: number) =>
    report([
      { id: 'T', type: 'transport', selectedCandidatePairId: 'P1' },
      {
        id: 'P1',
        type: 'candidate-pair',
        localCandidateId: 'L1',
        remoteCandidateId: 'R1',
        currentRoundTripTime: rtt,
      },
      { id: 'I', type: 'inbound-rtp', kind: 'audio', packetsLost: lost, packetsReceived: received },
      ...candidates,
    ])

  it('converts the round trip from seconds and keeps the cumulative loss', async () => {
    const pc = { getStats: vi.fn() }
    pc.getStats
      .mockResolvedValueOnce(stats(0.012, 1, 99))
      .mockResolvedValueOnce(stats(0.03, 4, 196))
    const first = await sampleStats(pc as unknown as RTCPeerConnection, NO_SAMPLE)
    expect(first).toMatchObject({ rtt: [12], loss: 1, selected: { local_kind: 'lan4' } })
    const second = await sampleStats(pc as unknown as RTCPeerConnection, first)
    expect(second).toMatchObject({ rtt: [12, 30], loss: 2 })
  })

  it('holds only allowed keys and never an address, a port or a candidate line', () => {
    const summary = buildSummary({
      client: 'ios',
      ice: 'connected',
      sdp: SDP,
      sample: {
        selected: { local_type: 'host', remote_type: 'prflx', local_kind: 'lan4', protocol: 'udp' },
        rtt: [40, 12, 30],
        loss: 2,
      },
      autoplayBlocked: false,
    })
    expect(Object.keys(summary).sort()).toEqual(
      [
        'autoplay_blocked',
        'client',
        'ice',
        'local_kinds',
        'local_types',
        'loss_pct',
        'rtt_ms',
        'selected',
      ].sort(),
    )
    expect(summary.rtt_ms).toBe(30)
    const text = JSON.stringify(summary)
    expect(text).not.toMatch(/\d+\.\d+\.\d+\.\d+/)
    expect(text).not.toMatch(/[0-9a-f]{1,4}:[0-9a-f]{0,4}:/i)
    expect(text).not.toMatch(/5000\d|candidate:|ufrag|ice-pwd|\.local/)
    expect(
      buildSummary({
        client: 'browser',
        ice: 'new',
        sdp: null,
        sample: NO_SAMPLE,
        autoplayBlocked: true,
      }),
    ).toEqual({
      client: 'browser',
      ice: 'new',
      selected: null,
      autoplay_blocked: true,
    })
  })
})

describe('probeNetwork', () => {
  it('gathers with the call configuration and releases everything', async () => {
    const track = { stop: vi.fn() }
    const stream = { getAudioTracks: () => [track], getTracks: () => [track] }
    const pc = {
      iceGatheringState: 'complete',
      localDescription: { sdp: SDP },
      addTransceiver: vi.fn(),
      createOffer: vi.fn(async () => ({ type: 'offer', sdp: SDP })),
      setLocalDescription: vi.fn(async () => undefined),
      close: vi.fn(),
    }
    const createPeerConnection = vi.fn(() => pc as unknown as RTCPeerConnection)
    const result = await probeNetwork({
      getUserMedia: async () => stream as unknown as MediaStream,
      createPeerConnection,
    })
    expect(createPeerConnection).toHaveBeenCalledWith(RTC_CONFIG)
    expect(RTC_CONFIG.iceServers).toEqual([])
    expect(result.kinds).toContain('tailnet6')
    expect(track.stop).toHaveBeenCalled()
    expect(pc.close).toHaveBeenCalled()
  })
})
