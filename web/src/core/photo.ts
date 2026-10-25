/**
 * Prepares a photo for upload on the device: JPEG at quality 0.88, the long side at most
 * 1600 px, white behind transparency. Redrawing on a canvas drops EXIF and GPS metadata.
 */
import { readCssColor } from '../design/colors'

export const MAX_SIDE = 1600
export const MAX_BYTES = 10 * 1024 * 1024
const QUALITY = 0.88

export class PhotoError extends Error {
  constructor(readonly problem: 'unsupported' | 'too_large') {
    super(problem)
  }
}

/** The size that fits within [max] on the long side, keeping the aspect ratio; never enlarges. */
/** The message for a photo that could not be prepared, wherever a photo is picked. */
export function photoProblemKey(
  error: unknown,
): 'assistant.photoTooLarge' | 'assistant.photoUnsupported' {
  return error instanceof PhotoError && error.problem === 'too_large'
    ? 'assistant.photoTooLarge'
    : 'assistant.photoUnsupported'
}

export function fitWithin(
  width: number,
  height: number,
  max = MAX_SIDE,
): { width: number; height: number } {
  const scale = Math.min(1, max / Math.max(width, height))
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

/** The photo's size as shown (rotation applied), read from its header without decoding pixels. */
function shownSize(file: Blob): Promise<{ width: number; height: number }> {
  const url = URL.createObjectURL(file)
  const image = new Image()
  return new Promise<{ width: number; height: number }>((resolve, reject) => {
    image.onload = () =>
      image.naturalWidth && image.naturalHeight
        ? resolve({ width: image.naturalWidth, height: image.naturalHeight })
        : reject(new PhotoError('unsupported'))
    // Formats the browser cannot read (HEIC in Chrome).
    image.onerror = () => reject(new PhotoError('unsupported'))
    image.src = url
  }).finally(() => URL.revokeObjectURL(url))
}

/** One decode, straight at the size the photo is sent in; a small photo keeps its own. */
export async function decode(file: Blob): Promise<ImageBitmap> {
  const shown = await shownSize(file)
  const { width, height } = fitWithin(shown.width, shown.height)
  try {
    if (width === shown.width) return await createImageBitmap(file)
    // Only the long side: a browser that rotates after resizing still keeps the proportions.
    const side = width >= height ? { resizeWidth: width } : { resizeHeight: height }
    return await createImageBitmap(file, { ...side, resizeQuality: 'high' })
  } catch {
    // Decoders without resize options: the canvas scales it instead.
    try {
      return await createImageBitmap(file)
    } catch {
      throw new PhotoError('unsupported')
    }
  }
}

export async function normalizePhoto(file: Blob): Promise<Blob> {
  const bitmap = await decode(file)
  try {
    const { width, height } = fitWithin(bitmap.width, bitmap.height)
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) throw new PhotoError('unsupported')
    context.fillStyle = readCssColor('--photo-background')
    context.fillRect(0, 0, width, height)
    context.imageSmoothingQuality = 'high'
    context.drawImage(bitmap, 0, 0, width, height)
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', QUALITY),
    )
    if (!blob) throw new PhotoError('unsupported')
    if (blob.size > MAX_BYTES) throw new PhotoError('too_large')
    return blob
  } finally {
    bitmap.close()
  }
}
