import { describe, expect, it } from 'vitest'
import { readInsets, useSafeArea } from './safeArea'

function style(values: Record<string, string>) {
  return { getPropertyValue: (name: string) => values[name] ?? '' } as CSSStyleDeclaration
}

describe('safe area', () => {
  it('reads the registered inset lengths in pixels', () => {
    expect(
      readInsets(
        style({
          '--inset-top': '32px',
          '--inset-right': '0px',
          '--inset-bottom': ' 24.5px',
          '--inset-left': '0px',
        }),
      ),
    ).toEqual({ top: 32, right: 0, bottom: 24.5, left: 0 })
  })

  it('reads an unresolved value as no inset', () => {
    expect(readInsets(style({ '--inset-top': 'env(safe-area-inset-top, 0px)' }))).toEqual({
      top: 0,
      right: 0,
      bottom: 0,
      left: 0,
    })
  })

  it('keeps floating content a gap beyond the bars', () => {
    document.documentElement.style.setProperty('--inset-top', '30px')
    document.documentElement.style.setProperty('--inset-bottom', '20px')
    const area = useSafeArea()
    area.measure()
    expect(area.collisionPadding.value).toEqual({ top: 38, right: 8, bottom: 28, left: 8 })
    document.documentElement.style.removeProperty('--inset-top')
    document.documentElement.style.removeProperty('--inset-bottom')
  })
})
