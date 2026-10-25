#!/usr/bin/env node
/** Generate Android VectorDrawables from the locked Lucide package's __iconData. */
import { mkdir, readFile, readdir, unlink, writeFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'

const root = fileURLToPath(new URL('../..', import.meta.url))
const drawableDirectory = resolve(root, 'mobile/design/src/main/res/drawable')
const assetDirectory = resolve(root, 'mobile/androidApp/src/main/assets')
const licensePath = resolve(assetDirectory, 'licenses/Lucide-LICENSE.txt')
const command = 'node web/tooling/generate_android_icons.mjs'
const icons = [
  'house',
  'utensils',
  'mic',
  'list-todo',
  'message-circle',
  'arrow-left',
  'monitor-smartphone',
  'bell',
  'chevron-right',
  'timer',
  'audio-lines',
  'users',
  'dumbbell',
  'user',
  'route',
  'settings',
  'blocks',
  'monitor',
  'sun',
  'moon',
  'log-out',
  'scale',
  'chevron-left',
  'check',
  'search',
  'refresh-cw',
  'ellipsis',
  'rotate-ccw-clock',
  'user-round',
  'plug',
  'goal',
  'share-2',
  'bell-ring',
  'map-pin',
  'layout-grid',
  'shield-check',
  'external-link',
  'calendar-days',
  'plus',
  'lock',
  'chevron-up',
  'reply',
  'paperclip',
  'send-horizontal',
  'x',
  'circle-alert',
  'chevron-down',
  'clock-3',
  'copy',
  'volume-2',
  'library',
  'info',
  'sliders-horizontal',
  'arrow-up',
  'columns-3',
  'list',
  'archive',
  'calendar-clock',
  'image-plus',
]

function number(attributes, key, fallback) {
  const value = attributes[key] ?? fallback
  if (value === undefined || value === '' || !Number.isFinite(Number(value))) {
    throw new Error(`Expected a finite number for ${key}, got ${value}`)
  }
  return Number(value)
}

function format(value) {
  return String(Number(value.toFixed(12)))
}

function ellipse(cx, cy, rx, ry) {
  if (rx < 0 || ry < 0) throw new Error('Ellipse radii must be nonnegative')
  const left = format(cx - rx)
  const right = format(cx + rx)
  const y = format(cy)
  const radii = `${format(rx)} ${format(ry)}`
  return `M${right} ${y} A${radii} 0 1 0 ${left} ${y} A${radii} 0 1 0 ${right} ${y} Z`
}

function rectangle(attributes) {
  const x = number(attributes, 'x', 0)
  const y = number(attributes, 'y', 0)
  const width = number(attributes, 'width')
  const height = number(attributes, 'height')
  if (width < 0 || height < 0) throw new Error('Rectangle dimensions must be nonnegative')
  const rx = Math.min(number(attributes, 'rx', attributes.ry ?? 0), width / 2)
  const ry = Math.min(number(attributes, 'ry', attributes.rx ?? 0), height / 2)
  if (rx < 0 || ry < 0) throw new Error('Rectangle radii must be nonnegative')
  const right = x + width
  const bottom = y + height
  if (rx === 0 || ry === 0) {
    return `M${format(x)} ${format(y)} H${format(right)} V${format(bottom)} H${format(x)} Z`
  }
  const arc = `A${format(rx)} ${format(ry)} 0 0 1`
  return [
    `M${format(x + rx)} ${format(y)}`,
    `H${format(right - rx)} ${arc} ${format(right)} ${format(y + ry)}`,
    `V${format(bottom - ry)} ${arc} ${format(right - rx)} ${format(bottom)}`,
    `H${format(x + rx)} ${arc} ${format(x)} ${format(bottom - ry)}`,
    `V${format(y + ry)} ${arc} ${format(x + rx)} ${format(y)} Z`,
  ].join(' ')
}

function pointsPath(points, closed) {
  if (typeof points !== 'string' || !points.trim()) throw new Error('Expected SVG points')
  const values = points
    .trim()
    .split(/[\s,]+/)
    .map(Number)
  if (values.length < 4 || values.length % 2 !== 0 || !values.every(Number.isFinite)) {
    throw new Error(`Invalid SVG points: ${points}`)
  }
  const pairs = []
  for (let index = 0; index < values.length; index += 2) {
    pairs.push(`${format(values[index])} ${format(values[index + 1])}`)
  }
  return `M${pairs.join(' L')}${closed ? ' Z' : ''}`
}

function pathData([tag, attributes]) {
  const allowed = {
    path: ['d'],
    line: ['x1', 'y1', 'x2', 'y2'],
    circle: ['cx', 'cy', 'r'],
    ellipse: ['cx', 'cy', 'rx', 'ry'],
    rect: ['x', 'y', 'width', 'height', 'rx', 'ry'],
    polyline: ['points'],
    polygon: ['points'],
  }[tag]
  if (!allowed) throw new Error(`Unsupported Lucide node: ${tag}`)
  for (const key of Object.keys(attributes)) {
    if (key !== 'key' && !allowed.includes(key)) {
      throw new Error(`Unsupported Lucide ${tag} attribute: ${key}`)
    }
  }
  switch (tag) {
    case 'path':
      if (typeof attributes.d !== 'string' || !attributes.d.trim()) {
        throw new Error('Expected nonempty SVG path data')
      }
      return attributes.d
    case 'line':
      return [
        `M${format(number(attributes, 'x1', 0))} ${format(number(attributes, 'y1', 0))}`,
        `L${format(number(attributes, 'x2', 0))} ${format(number(attributes, 'y2', 0))}`,
      ].join(' ')
    case 'circle': {
      const radius = number(attributes, 'r')
      return ellipse(number(attributes, 'cx', 0), number(attributes, 'cy', 0), radius, radius)
    }
    case 'ellipse':
      return ellipse(
        number(attributes, 'cx', 0),
        number(attributes, 'cy', 0),
        number(attributes, 'rx'),
        number(attributes, 'ry'),
      )
    case 'rect':
      return rectangle(attributes)
    case 'polyline':
    case 'polygon':
      return pointsPath(attributes.points, tag === 'polygon')
  }
}

function xmlAttribute(value) {
  return value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;')
}

function drawable(icon, version) {
  if (icon.size !== 24 || !Array.isArray(icon.node) || icon.node.length === 0) {
    throw new Error(`Expected 24px Lucide geometry for ${icon.name}`)
  }
  const paths = icon.node.map(
    (node) => `    <path
        android:strokeColor="@color/foreground"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="${xmlAttribute(pathData(node))}" />`,
  )
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from @lucide/vue ${version}, ${icon.name}, by ${command}; do not edit.
     Lucide ISC and Feather MIT notices: androidApp/src/main/assets/licenses/Lucide-LICENSE.txt -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
${paths.join('\n')}
</vector>
`
}

async function readExisting(path) {
  try {
    return await readFile(path, 'utf8')
  } catch (error) {
    if (error.code === 'ENOENT') return null
    throw error
  }
}

async function main() {
  const { values } = parseArgs({
    options: { check: { type: 'boolean' }, lucide: { type: 'string' } },
  })
  const lucide = resolve(values.lucide ?? resolve(root, 'web/node_modules/@lucide/vue'))
  const lock = JSON.parse(await readFile(resolve(root, 'web/package-lock.json'), 'utf8'))
  const expectedVersion = lock.packages['node_modules/@lucide/vue']?.version
  const metadata = JSON.parse(await readFile(resolve(lucide, 'package.json'), 'utf8'))
  if (
    !expectedVersion ||
    metadata.name !== '@lucide/vue' ||
    metadata.version !== expectedVersion ||
    metadata.license !== 'ISC'
  ) {
    throw new Error(`Expected @lucide/vue ${expectedVersion} (ISC), got ${metadata.version}`)
  }
  const outputs = new Map()
  for (const name of icons) {
    const moduleUrl = pathToFileURL(resolve(lucide, `dist/esm/icons/${name}.mjs`))
    const { __iconData } = await import(moduleUrl.href)
    if (__iconData?.name !== name) throw new Error(`Unexpected Lucide icon data for ${name}`)
    const filename = `lucide_${name.replaceAll('-', '_')}.xml`
    outputs.set(resolve(drawableDirectory, filename), drawable(__iconData, expectedVersion))
  }
  outputs.set(licensePath, await readFile(resolve(lucide, 'LICENSE'), 'utf8'))
  const stale = []
  for (const [path, expected] of outputs) {
    if ((await readExisting(path)) === expected) continue
    stale.push(path)
    if (!values.check) {
      await mkdir(dirname(path), { recursive: true })
      await writeFile(path, expected)
    }
  }
  const files = await readdir(drawableDirectory)
  for (const filename of files) {
    const path = resolve(drawableDirectory, filename)
    const generatedIcon = filename.startsWith('lucide_') && filename.endsWith('.xml')
    if (!generatedIcon || outputs.has(path)) continue
    stale.push(path)
    if (!values.check) await unlink(path)
  }
  if (values.check && stale.length > 0) {
    const paths = stale.map((path) => relative(root, path)).join('\n')
    throw new Error(`Stale Android icons; run ${command}:\n${paths}`)
  }
  console.log(
    `Android icons ${values.check ? 'check passed' : 'generated'}: ` +
      `${icons.length} icons, ${stale.length} ${values.check ? 'stale' : 'updated'} outputs`,
  )
}

main().catch((error) => {
  console.error(`Icon generation error: ${error.message}`)
  process.exitCode = 1
})
