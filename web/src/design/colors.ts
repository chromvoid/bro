export type ColorToken = `--${string}`

/** Read the authoritative CSS color without copying a palette into runtime code. */
export function readCssColor(
  token: ColorToken,
  element: Element = document.documentElement,
): string {
  const value = getComputedStyle(element).getPropertyValue(token).trim()
  if (!value || !CSS.supports('color', value)) throw new Error(`Missing or invalid color ${token}`)
  return value
}

let converter: CanvasRenderingContext2D | null = null

/** THREE accepts sRGB colors, so let the browser resolve OKLCH/P3 into its sRGB canvas boundary. */
export function readCssSrgbColor(
  token: ColorToken,
  element: Element = document.documentElement,
): string {
  const value = readCssColor(token, element)
  if (!converter) {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 1
    converter = canvas.getContext('2d', { colorSpace: 'srgb', willReadFrequently: true })
    if (!converter) throw new Error('CSS color conversion unavailable')
  }
  converter.clearRect(0, 0, 1, 1)
  converter.fillStyle = value
  converter.fillRect(0, 0, 1, 1)
  const pixels = converter.getImageData(0, 0, 1, 1).data
  if (pixels[3] !== 255) throw new Error(`Expected opaque color ${token}`)
  return `#${Array.from(pixels.subarray(0, 3), (channel) =>
    channel.toString(16).padStart(2, '0'),
  ).join('')}`
}
