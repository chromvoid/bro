import { describe, expect, it } from 'vitest'
import { ChangeClaims } from './changes'

const event = { id: 1, name: 'assistant.changed', topic: 'assistant.changed', data: null }

describe('ChangeClaims', () => {
  it('takes a domain only when every claim accepts the event, and never on a failing claim', () => {
    const claims = new ChangeClaims()
    expect(claims.unclaimed(event, ['conversation', 'community'])).toEqual([
      'conversation',
      'community',
    ])
    const release = claims.claim('conversation', () => true)
    expect(claims.unclaimed(event, ['conversation', 'community'])).toEqual(['community'])
    const failing = claims.claim('conversation', () => {
      throw new Error('synthetic failure')
    })
    expect(claims.unclaimed(event, ['conversation'])).toEqual(['conversation'])
    failing()
    release()
    expect(claims.unclaimed(event, ['conversation'])).toEqual(['conversation'])
  })
})
