import { describe, expect, it } from 'vitest'
import { imageFiles, pictures } from './clipboard'

const png = () => new File([new Uint8Array([1])], 'screenshot.png', { type: 'image/png' })
const pdf = () => new File([new Uint8Array([1])], 'scan.pdf', { type: 'application/pdf' })

function clipboard(text: string, files: File[], items: File[] = []) {
  return {
    getData: (type: string) => (type === 'text/plain' ? text : ''),
    files,
    items: items.map((file) => ({ kind: 'file', getAsFile: () => file })),
  } as unknown as DataTransfer
}

describe('pictures on the clipboard', () => {
  it('pastes a screenshot or a copied image as pictures', () => {
    const shot = png()
    expect(imageFiles(clipboard('', [shot, pdf()]))).toEqual([shot])
    expect(imageFiles(clipboard('  ', [shot]))).toEqual([shot])
  })

  it('pastes cells or a document fragment as their text, not their rendering', () => {
    expect(imageFiles(clipboard('A1\tB1', [png()]))).toEqual([])
  })

  it('reads pictures from items where a browser does not list files', () => {
    const shot = png()
    expect(imageFiles(clipboard('', [], [shot]))).toEqual([shot])
    expect(imageFiles(null)).toEqual([])
  })

  it('keeps only images among picked files, in order', () => {
    const [first, second] = [png(), png()]
    expect(pictures([first, pdf(), second])).toEqual([first, second])
  })
})
