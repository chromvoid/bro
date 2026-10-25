/**
 * The pictures a paste or a picker brings. A paste counts as pictures only when the clipboard has
 * no plain text: a screenshot or «Copy image» pastes a picture, while cells from a spreadsheet or
 * a document, which also carry a rendering of themselves, paste as their text.
 */
export function imageFiles(data: DataTransfer | null): File[] {
  if (!data || data.getData('text/plain').trim()) return []
  const files = data.files.length
    ? Array.from(data.files)
    : Array.from(data.items ?? [])
        .filter((item) => item.kind === 'file')
        .map((item) => item.getAsFile())
        .filter((file): file is File => file !== null)
  return pictures(files)
}

/** The image files among [files], in their order. */
export function pictures(files: Iterable<File>): File[] {
  return Array.from(files).filter((file) => file.type.startsWith('image/'))
}
