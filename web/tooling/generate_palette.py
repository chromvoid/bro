#!/usr/bin/env python3
"""Resolve the CSS palette into formats that cannot consume CSS custom properties.

The input grammar is deliberately narrow: direct :root / @variant dark declarations,
opaque OKLCH colors, var(--token) aliases and static color-mix effects. Nested gamut
overrides and the contextual currentColor shimmer stay web-only.
Run from any directory; --check reports stale outputs without changing them.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import sys
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[2]
SOURCE = 'web/src/design/tokens.css'
TEXT_SCALE_SOURCE = 'web/src/styles.css'
COMMAND = 'python3 web/tooling/generate_palette.py'
GENERATED = f'Generated from {SOURCE} by {COMMAND}; do not edit palette values.'

# The keys retain existing native resource names; the values are semantic CSS roles.
NATIVE_ROLES = {
    'background': 'background',
    'surface': 'card',
    'text': 'foreground',
    'muted': 'muted-foreground',
    'primary': 'primary',
    'on_primary': 'primary-foreground',
    'primary_container': 'accent',
    'on_primary_container': 'accent-foreground',
    'secondary_container': 'secondary',
    'surface_muted': 'muted',
    'border': 'border',
    'input_border': 'input',
    'destructive': 'destructive',
    'on_destructive': 'destructive-foreground',
    'splash_background': 'splash-background',
    'splash_foreground': 'splash-foreground',
}
IOS_ROLES = {
    'KaiBackground': 'background',
    'KaiSurface': 'card',
    'KaiAccent': 'primary',
    'KaiOnAccent': 'primary-foreground',
    'KaiDestructive': 'destructive',
    'KaiForeground': 'foreground',
    'KaiSplashBackground': 'splash-background',
    'KaiSplashForeground': 'splash-foreground',
}
# The launch screen (shell/README.md «Сплеш»): the 256-unit mark at 192 dp/pt/px, centred on the
# 288 dp canvas of an Android 12 splash icon, which shows only its inner 192 dp circle.
SPLASH_CANVAS = 288
SPLASH_MARK = 192
MERGE_MS = 320  # MERGE_MS in web/src/components/brandMood.ts
BREATH_MS = 600  # each half of a dot's breath; the dots start 200 ms apart (web/src/boot.css)
NUMBER = r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?'
OKLCH = re.compile(rf'oklch\(\s*({NUMBER})(%?)\s+({NUMBER})(%?)\s+({NUMBER})(?:deg)?\s*\)')
ALIAS = re.compile(r'var\(\s*(--[\w-]+)\s*\)')
# These are dimensions, compound shadows or a contextual color, rather than static colors.
NON_COLORS = {'--radius', '--toast-shadow', '--toast-focus-shadow', '--toast-action-focus-shadow',
              '--shimmer-highlight'}


class PaletteError(ValueError):
    """The source or a compatibility output does not match the supported contract."""


def block(css: str, opening: int) -> str:
    depth = 1
    for index in range(opening + 1, len(css)):
        if css[index] == '{':
            depth += 1
        elif css[index] == '}':
            depth -= 1
            if depth == 0:
                return css[opening + 1:index]
    raise PaletteError('Unclosed CSS palette block')


def declarations(body: str) -> dict[str, str]:
    # Keep direct declarations only: P3 and other nested blocks cannot leak into sRGB.
    direct = []
    depth = 0
    for character in body:
        if character == '{':
            depth += 1
        elif character == '}':
            depth -= 1
        elif depth == 0:
            direct.append(character)
    return dict(re.findall(r'(--[\w-]+)\s*:\s*([^;{}]+);', ''.join(direct)))


def read_themes(css: str) -> dict[str, dict[str, str]]:
    css = re.sub(r'/\*.*?\*/', '', css, flags=re.DOTALL)
    roots = list(re.finditer(r':root\s*\{', css))
    if len(roots) != 1:
        raise PaletteError('Expected one :root palette block')
    root = block(css, roots[0].end() - 1)
    dark_blocks = list(re.finditer(r'@variant\s+dark\s*\{', root))
    if len(dark_blocks) != 1:
        raise PaletteError('Expected one @variant dark palette block')
    light = declarations(root)
    dark = declarations(block(root, dark_blocks[0].end() - 1))
    return {'light': light, 'dark': {**light, **dark}}


def resolve(tokens: dict[str, str], name: str, chain: tuple[str, ...] = ()) -> tuple[float, float, float]:
    if name in chain:
        raise PaletteError('Palette alias cycle: ' + ' -> '.join((*chain, name)))
    if name not in tokens:
        raise PaletteError(f'Missing palette token {name}')
    value = tokens[name].strip()
    if alias := ALIAS.fullmatch(value):
        return resolve(tokens, alias[1], (*chain, name))
    match = OKLCH.fullmatch(value)
    if not match:
        raise PaletteError(f'{name} must use opaque oklch(...) or var(--token), got {value!r}')
    lightness = float(match[1]) / (100 if match[2] else 1)
    chroma = float(match[3]) * (0.004 if match[4] else 1)
    hue = float(match[5])
    if not all(map(math.isfinite, (lightness, chroma, hue))) or not 0 <= lightness <= 1 or chroma < 0:
        raise PaletteError(f'Invalid OKLCH components in {name}')
    return lightness, chroma, hue


def rgb(color: tuple[float, float, float]) -> tuple[int, int, int]:
    """OKLCH / D65 → sRGB bytes, allowing only decimal conversion noise at the gamut edge."""
    lightness, chroma, hue = color
    a = chroma * math.cos(math.radians(hue))
    b = chroma * math.sin(math.radians(hue))
    l = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s = (lightness - 0.0894841775 * a - 1.291485548 * b) ** 3
    linear = (
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
    )
    if any(value < -0.0001 or value > 1.0001 for value in linear):
        raise PaletteError(f'Baseline OKLCH color {color} is outside sRGB; use a web-only P3 override')
    encoded = []
    for channel in linear:
        channel = min(1, max(0, channel))
        value = channel * 12.92 if channel <= 0.0031308 else 1.055 * channel ** (1 / 2.4) - 0.055
        encoded.append(math.floor(value * 255 + 0.5))
    return tuple(encoded)


def color_hex(tokens: dict[str, str], name: str) -> str:
    return '#' + ''.join(f'{channel:02X}' for channel in rgb(resolve(tokens, '--' + name)))


def static_color(tokens: dict[str, str], name: str,
                 chain: tuple[str, ...] = ()) -> tuple[float, float, float, float]:
    """Return OKLCH + alpha for the palette's static CSS color-mix subset.

    Interpolation uses premultiplied alpha and the CSS shorter hue path. Keeping
    transparent effects as alpha colors avoids baking a light surface into dark UI.
    """
    if name in chain:
        raise PaletteError('Palette alias cycle: ' + ' -> '.join((*chain, name)))
    if name not in tokens:
        raise PaletteError(f'Missing palette token {name}')
    value = tokens[name].strip()
    if alias := ALIAS.fullmatch(value):
        return static_color(tokens, alias[1], (*chain, name))
    if OKLCH.fullmatch(value):
        return (*resolve(tokens, name), 1.0)
    mix = re.fullmatch(r'color-mix\(in (oklab|oklch),\s*(var\(--[\w-]+\))\s+'
                       rf'({NUMBER})%,\s*(transparent|var\(--[\w-]+\))\)', value)
    if not mix:
        raise PaletteError(f'Unsupported static color {name}: {value!r}')
    first = static_color(tokens, ALIAS.fullmatch(mix[2])[1], (*chain, name))
    weight = float(mix[3]) / 100
    if not 0 <= weight <= 1:
        raise PaletteError(f'Invalid color-mix weight in {name}')
    if mix[4] == 'transparent':
        return (*first[:3], first[3] * weight)
    second = static_color(tokens, ALIAS.fullmatch(mix[4])[1], (*chain, name))
    alpha = first[3] * weight + second[3] * (1 - weight)
    ratio = first[3] * weight / alpha if alpha else 0
    lightness = first[0] * ratio + second[0] * (1 - ratio)
    if mix[1] == 'oklab':
        a = (first[1] * math.cos(math.radians(first[2])) * ratio
             + second[1] * math.cos(math.radians(second[2])) * (1 - ratio))
        b = (first[1] * math.sin(math.radians(first[2])) * ratio
             + second[1] * math.sin(math.radians(second[2])) * (1 - ratio))
        return lightness, math.hypot(a, b), math.degrees(math.atan2(b, a)), alpha
    hue_delta = (first[2] - second[2] + 180) % 360 - 180
    return (lightness, first[1] * ratio + second[1] * (1 - ratio),
            second[2] + hue_delta * ratio, alpha)


def argb(tokens: dict[str, str], name: str) -> str:
    color = static_color(tokens, name)
    return f'{math.floor(color[3] * 255 + 0.5):02X}' + ''.join(f'{value:02X}' for value in rgb(color[:3]))


def mobile_palette(themes: dict[str, dict[str, str]]) -> dict[str, str]:
    names = sorted(set(themes['light']) - NON_COLORS)
    lines = [f'// {GENERATED}', 'package assistant.mobile.design', '',
             'import androidx.compose.ui.graphics.Color', '',
             '/** Static semantic, chart, status, photo and splash colors, including aliases. */',
             'object GeneratedColors {']
    for scheme, tokens in themes.items():
        lines.append(f'    val {scheme}: Map<String, Color> = mapOf(')
        lines.extend(f'        "{name[2:]}" to Color(0x{argb(tokens, name)}),' for name in names)
        lines.append('    )')
    lines.append('}')
    output = {'kotlin/assistant/mobile/design/GeneratedPalette.kt': '\n'.join(lines) + '\n'}
    for scheme, tokens in themes.items():
        colors = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {GENERATED} -->', '<resources>']
        colors.extend(f'    <color name="{name[2:].replace("-", "_")}">#{argb(tokens, name)}</color>'
                      for name in names)
        colors.append('</resources>')
        folder = 'values' if scheme == 'light' else 'values-night'
        output[f'res/{folder}/colors.xml'] = '\n'.join(colors) + '\n'
    return output


def text_scale_constants(css: str) -> tuple[float, float, float]:
    """Normalize the web's static html font-size formula to the browser's 16px base."""
    css = re.sub(r'/\*.*?\*/', '', css, flags=re.DOTALL)
    html = list(re.finditer(r'(?:^|[{}])\s*html\s*\{', css))
    if len(html) != 1:
        raise PaletteError('Expected one html text-scale rule in ' + TEXT_SCALE_SOURCE)
    body = block(css, html[0].end() - 1)
    if '{' in body or '}' in body:
        raise PaletteError('Nested html text-scale rules are unsupported')
    sizes = re.findall(r'(?<![-\w])font-size\s*:\s*([^;{}]+);', body)
    formula = (rf'min\(\s*calc\(\s*\(\s*100%\s*\*\s*var\(\s*--system-text-scale\s*\)'
               rf'\s*\+\s*({NUMBER})px\s*\)\s*/\s*({NUMBER})\s*\)\s*,\s*({NUMBER})px\s*\)')
    match = re.fullmatch(formula, sizes[0].strip()) if len(sizes) == 1 else None
    if not match:
        raise PaletteError('Unsupported html font-size formula in ' + TEXT_SCALE_SOURCE)
    offset, divisor, maximum = map(float, match.groups())
    if not all(map(math.isfinite, (offset, divisor, maximum))) or offset < 0 or divisor <= 0 or maximum <= 0:
        raise PaletteError('Invalid html font-size formula constants')
    return offset / 16, divisor, maximum / 16


def mobile_text_scale(css: str) -> str:
    values = text_scale_constants(css)
    lines = [f'// Generated from {TEXT_SCALE_SOURCE} by {COMMAND}; do not edit.',
             'package assistant.mobile.core', '',
             '/** Web html font-size policy normalized to the default browser reference of 16px. */',
             'object GeneratedTextScale {']
    lines.extend(f'    const val {name}: Float = {value:.9g}f'
                 for name, value in zip(('offset', 'divisor', 'maximum'), values))
    return '\n'.join([*lines, '}']) + '\n'


def ios_colors(themes: dict[str, dict[str, str]], token: str) -> str:
    entries = []
    for scheme, tokens in themes.items():
        channels = rgb(resolve(tokens, '--' + token))
        entry = {'idiom': 'universal'}
        if scheme == 'dark':
            entry['appearances'] = [{'appearance': 'luminosity', 'value': 'dark'}]
        entry['color'] = {
            'color-space': 'srgb',
            'components': {**{key: f'{value / 255:.9f}' for key, value in zip(('red', 'green', 'blue'), channels)},
                           'alpha': '1.000'},
        }
        entries.append(entry)
    return json.dumps({'colors': entries, 'info': {'author': GENERATED, 'version': 1}}, indent=2) + '\n'


def metadata(html: str, colors: dict[str, str]) -> str:
    beginning = '    <!-- BEGIN GENERATED PALETTE META -->'
    end = '    <!-- END GENERATED PALETTE META -->'
    generated = '\n'.join([
        beginning,
        f'    <!-- {GENERATED} -->',
        *(f'    <meta name="theme-color" media="(prefers-color-scheme: {scheme})" content="{value.lower()}" />'
          for scheme, value in colors.items()),
        end,
    ])
    marked = re.compile(r'^    <!-- BEGIN GENERATED PALETTE META -->.*?^    <!-- END GENERATED PALETTE META -->', re.MULTILINE | re.DOTALL)
    if marked.search(html):
        result, count = marked.subn(lambda _: generated, html)
    else:
        pattern = re.compile(r'^    <meta name="theme-color" media="\(prefers-color-scheme: light\)" content="[^"]*" />\n'
                             r'    <meta name="theme-color" media="\(prefers-color-scheme: dark\)" content="[^"]*" />', re.MULTILINE)
        result, count = pattern.subn(lambda _: generated, html)
    if count != 1:
        raise PaletteError('Expected one light/dark theme-color metadata pair in web/index.html')
    return result


def splash_group(moods: dict) -> tuple[str, list[str]]:
    """The thinking mark: B and its three dots, as the launch screens show it while Bro loads."""
    if moods.get('viewBox') != '0 0 256 256' or len(moods.get('thinking', [])) != 3 or len(moods.get('merge', [])) != 3:
        raise PaletteError('Expected the 256px Bro moods with three thinking dots and three merge pieces')
    return moods['b'], moods['thinking']


def android_splash_mark(moods: dict) -> str:
    body, dots = splash_group(moods)
    offset, scale = (SPLASH_CANVAS - SPLASH_MARK) / 2, SPLASH_MARK / 256
    paths = [f'        <path android:fillColor="@color/splash_foreground" android:fillType="evenOdd" android:pathData="{body}" />',
             *(f'        <path android:fillColor="@color/splash_foreground" android:pathData="{dot}" />' for dot in dots)]
    return '\n'.join([
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- {GENERATED} The mark is web/src/assets/bro/moods.json. -->',
        '<!-- 288 dp is the size Android 12 gives a splash icon, larger than lint likes for vectors. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"',
        f'    tools:ignore="VectorRaster" android:width="{SPLASH_CANVAS}dp" android:height="{SPLASH_CANVAS}dp"',
        f'    android:viewportWidth="{SPLASH_CANVAS}" android:viewportHeight="{SPLASH_CANVAS}">',
        f'    <group android:translateX="{offset:g}" android:translateY="{offset:g}" android:scaleX="{scale:g}" android:scaleY="{scale:g}">',
        *paths,
        '    </group>',
        '</vector>',
    ]) + '\n'


def ios_splash_svg(moods: dict, color: str) -> str:
    body, dots = splash_group(moods)
    offset, scale = (SPLASH_CANVAS - SPLASH_MARK) / 2, SPLASH_MARK / 256
    svg = ElementTree.Element('svg', {'xmlns': 'http://www.w3.org/2000/svg', 'width': str(SPLASH_CANVAS),
                                      'height': str(SPLASH_CANVAS), 'viewBox': f'0 0 {SPLASH_CANVAS} {SPLASH_CANVAS}'})
    svg.append(ElementTree.Comment(GENERATED))
    group = ElementTree.SubElement(svg, 'g', {'fill': color.lower(),
                                              'transform': f'translate({offset:g} {offset:g}) scale({scale:g})'})
    ElementTree.SubElement(group, 'path', {'d': body, 'fill-rule': 'evenodd'})
    for dot in dots:
        ElementTree.SubElement(group, 'path', {'d': dot})
    return ElementTree.tostring(svg, encoding='unicode') + '\n'


def ios_splash_imageset() -> str:
    images = [{'filename': 'splash-mark.svg', 'idiom': 'universal'},
              {'appearances': [{'appearance': 'luminosity', 'value': 'dark'}], 'filename': 'splash-mark-dark.svg',
               'idiom': 'universal'}]
    return json.dumps({'images': images, 'info': {'author': GENERATED, 'version': 1},
                       'properties': {'preserves-vector-representation': True}}, indent=2) + '\n'


def polyline(path: str) -> list[tuple[float, float]]:
    """The points of a resampled merge piece: M, then L only, then Z."""
    tokens = path.replace('M', ' M ').replace('L', ' L ').replace('Z', ' Z ').split()
    if tokens[0] != 'M' or tokens[-1] != 'Z' or set(tokens[3:-1:3]) != {'L'}:
        raise PaletteError('Merge pieces must be M/L polylines')
    numbers = [token for token in tokens[:-1] if token not in ('M', 'L')]
    return [(float(numbers[i]), float(numbers[i + 1])) for i in range(0, len(numbers), 2)]


def ios_splash_geometry(moods: dict) -> str:
    def points(path):
        return '[' + ', '.join(f'.init(x: {x:g}, y: {y:g})' for x, y in polyline(path)) + ']'
    pieces = [(polyline(piece['from']), polyline(piece['to'])) for piece in moods['merge']]
    if any(len(start) != len(end) for start, end in pieces):
        raise PaletteError('Each merge piece must keep its number of points')
    lines = [f'// {GENERATED} The mark is web/src/assets/bro/moods.json.', 'import CoreGraphics', '',
             '/// The dots and the thirds of ")" they flow into, in the mark\'s 256-unit box (SplashOverlay).',
             'enum SplashGeometry {',
             f'    static let mergeSeconds = {MERGE_MS / 1000:g}',
             f'    static let breathSeconds = {BREATH_MS / 1000:g}',
             '    static let pieces: [(from: [CGPoint], to: [CGPoint])] = [']
    lines += [f'        (from: {points(piece["from"])},\n         to: {points(piece["to"])}),' for piece in moods['merge']]
    return '\n'.join(lines + ['    ]', '}']) + '\n'


def ios_part_svg(path: str, color: str, evenodd: bool) -> str:
    svg = ElementTree.Element('svg', {'xmlns': 'http://www.w3.org/2000/svg', 'width': str(SPLASH_MARK),
                                      'height': str(SPLASH_MARK), 'viewBox': '0 0 256 256'})
    svg.append(ElementTree.Comment(GENERATED))
    attributes = {'d': path, 'fill': color.lower()}
    if evenodd:
        attributes['fill-rule'] = 'evenodd'
    ElementTree.SubElement(svg, 'path', attributes)
    return ElementTree.tostring(svg, encoding='unicode') + '\n'


def ios_imageset(name: str) -> str:
    images = [{'filename': f'{name}.svg', 'idiom': 'universal'},
              {'appearances': [{'appearance': 'luminosity', 'value': 'dark'}], 'filename': f'{name}-dark.svg',
               'idiom': 'universal'}]
    return json.dumps({'images': images, 'info': {'author': GENERATED, 'version': 1},
                       'properties': {'preserves-vector-representation': True}}, indent=2) + '\n'


def boot_mark(html: str, moods: dict) -> str:
    """index.html #boot: the same thinking mark, plus the smile and the pieces the dots flow into.

    Each piece is its dot (d) and names its third of the smile (data-to); boot.ts moves the points
    (brandMerge.ts). No SMIL: WebKit's page process died as an <animate> of d ended."""
    body, dots = splash_group(moods)
    beginning, end = '      <!-- BEGIN GENERATED BOOT MARK -->', '      <!-- END GENERATED BOOT MARK -->'
    generated = '\n'.join([
        beginning,
        f'      <!-- {GENERATED} The mark is web/src/assets/bro/moods.json; boot.ts plays it. -->',
        '      <!-- prettier-ignore -->',
        f'      <svg viewBox="0 0 256 256" width="{SPLASH_MARK}" height="{SPLASH_MARK}" focusable="false">',
        f'        <path d="{body}" fill-rule="evenodd" />',
        '        <g class="dots">',
        *(f'          <path d="{dot}" />' for dot in dots),
        '        </g>',
        f'        <path class="smile" d="{moods["ok"]}" />',
        '        <g class="merge">',
        *(f'          <path d="{piece["from"]}" data-to="{piece["to"]}" />' for piece in moods['merge']),
        '        </g>',
        '      </svg>',
        end,
    ])
    marked = re.compile(r'^      <!-- BEGIN GENERATED BOOT MARK -->.*?^      <!-- END GENERATED BOOT MARK -->', re.MULTILINE | re.DOTALL)
    if marked.search(html):
        result, count = marked.subn(lambda _: generated, html)
    else:
        result, count = re.subn(r'^      <img src="/icon-192\.png"[^\n]*/>', lambda _: generated, html, flags=re.MULTILINE)
    if count != 1:
        raise PaletteError('Expected one boot mark inside #boot in web/index.html')
    return result


def manifest_colors(source: str, color: str, splash: str) -> str:
    """theme_color is the app's background; background_color is the launch screen an installed PWA opens on."""
    json.loads(source)
    for key, value in (('background_color', splash), ('theme_color', color)):
        source, count = re.subn(rf'("{key}"\s*:\s*")[^"]*(")', lambda match: match[1] + value.lower() + match[2], source)
        if count != 1:
            raise PaletteError(f'Expected one {key} in web/public/manifest.webmanifest')
    marker = '  "_generated_palette": ' + json.dumps(GENERATED) + ','
    pattern = re.compile(r'^  "_generated_palette":.*,$', re.MULTILINE)
    if pattern.search(source):
        source = pattern.sub(lambda _: marker, source)
    else:
        source = source.replace('{\n', '{\n' + marker + '\n', 1)
    return source


def expected_outputs(root: Path) -> dict[Path, str]:
    themes = read_themes((root / SOURCE).read_text())
    icon = {scheme: color_hex(tokens, 'icon-background') for scheme, tokens in themes.items()}
    if icon['light'] != icon['dark']:
        raise PaletteError('--icon-background must stay fixed across themes')
    icon_foreground = {scheme: color_hex(tokens, 'icon-foreground') for scheme, tokens in themes.items()}
    if icon_foreground['light'] != icon_foreground['dark']:
        raise PaletteError('--icon-foreground must stay fixed across themes')
    boundaries = {scheme: {token: color_hex(tokens, token) for token in NATIVE_ROLES.values()}
                  for scheme, tokens in themes.items()}
    outputs: dict[Path, str] = {}
    for name, token in IOS_ROLES.items():
        outputs[root / f'shell/ios/AssistantShell/Assets.xcassets/{name}.colorset/Contents.json'] = ios_colors(themes, token)
    outputs[root / 'web/src/design/palette.generated.json'] = json.dumps(
        {'_generated': GENERATED, 'icon_background': icon['light'],
         'icon_foreground': icon_foreground['light'], **boundaries}, indent=2) + '\n'
    # A browser favicon cannot inherit the application's CSS tokens. Export both
    # palette roles around the outlined small-size master rather than storing colors there.
    symbol = ElementTree.parse(root / 'web/src/assets/bro/symbol-small.svg').getroot()
    paths = list(symbol.iter('{http://www.w3.org/2000/svg}path'))
    if len(paths) != 1 or symbol.get('viewBox') != '0 0 256 256':
        raise PaletteError('Expected one outlined path on the 256px Bro small-size canvas')
    favicon = ElementTree.Element('svg', {'xmlns': 'http://www.w3.org/2000/svg',
                                         'viewBox': '0 0 256 256'})
    ElementTree.SubElement(favicon, 'title').text = 'Bro'
    favicon.append(ElementTree.Comment(GENERATED))
    ElementTree.SubElement(favicon, 'rect', {'width': '256', 'height': '256', 'rx': '52',
                                           'fill': icon['light']})
    ElementTree.SubElement(favicon, 'path', {'d': paths[0].get('d', ''), 'fill-rule': 'evenodd',
                                           'fill': icon_foreground['light'],
                                           'transform': 'translate(6.4 6.4) scale(0.95)'})
    outputs[root / 'web/public/favicon.svg'] = ElementTree.tostring(favicon, encoding='unicode') + '\n'
    moods = json.loads((root / 'web/src/assets/bro/moods.json').read_text())
    html = root / 'web/index.html'
    outputs[html] = boot_mark(metadata(html.read_text(), {scheme: values['background'] for scheme, values in boundaries.items()}),
                              moods)
    manifest = root / 'web/public/manifest.webmanifest'
    outputs[manifest] = manifest_colors(manifest.read_text(), boundaries['light']['background'],
                                        boundaries['light']['splash-background'])
    imageset = root / 'shell/ios/AssistantShell/Assets.xcassets/SplashMark.imageset'
    outputs[imageset / 'Contents.json'] = ios_splash_imageset()
    outputs[imageset / 'splash-mark.svg'] = ios_splash_svg(moods, boundaries['light']['splash-foreground'])
    outputs[imageset / 'splash-mark-dark.svg'] = ios_splash_svg(moods, boundaries['dark']['splash-foreground'])
    # The launch overlay draws B and the smile from these and the dots from SplashGeometry.swift.
    catalog = root / 'shell/ios/AssistantShell/Assets.xcassets'
    for name, part, evenodd in (('splash-body', moods['b'], True), ('splash-smile', moods['ok'], False)):
        folder = catalog / ('SplashBody.imageset' if name == 'splash-body' else 'SplashSmile.imageset')
        outputs[folder / 'Contents.json'] = ios_imageset(name)
        outputs[folder / f'{name}.svg'] = ios_part_svg(part, boundaries['light']['splash-foreground'], evenodd)
        outputs[folder / f'{name}-dark.svg'] = ios_part_svg(part, boundaries['dark']['splash-foreground'], evenodd)
    outputs[root / 'shell/ios/AssistantShell/SplashGeometry.swift'] = ios_splash_geometry(moods)
    mobile = root / 'mobile/design/src/main'
    outputs.update({mobile / path: content for path, content in mobile_palette(themes).items()})
    outputs[mobile / 'res/drawable/splash_mark.xml'] = android_splash_mark(moods)
    outputs[root / 'mobile/core/src/commonMain/kotlin/assistant/mobile/core/GeneratedTextScale.kt'] = mobile_text_scale(
        (root / TEXT_SCALE_SOURCE).read_text())
    return outputs


def synchronize(outputs: dict[Path, str], check: bool) -> list[Path]:
    stale = []
    for path, expected in outputs.items():
        if path.exists() and path.read_text() == expected:
            continue
        stale.append(path)
        if not check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected)
    return stale


def mobile_color_violations(root: Path) -> list[str]:
    """All native color boundaries are generated; authored UI refers to token roles."""
    generated = {
        'mobile/design/src/main/kotlin/assistant/mobile/design/GeneratedPalette.kt',
        'mobile/design/src/main/res/values/colors.xml',
        'mobile/design/src/main/res/values-night/colors.xml',
    }
    violations = []
    for path in sorted((root / 'mobile').rglob('*')):
        if (path.suffix not in ('.kt', '.xml') or 'src' not in path.relative_to(root / 'mobile').parts
                or {'build', '.gradle'} & set(path.parts)):
            continue
        relative = path.relative_to(root).as_posix()
        if relative in generated:
            continue
        source = path.read_text()
        names = ['Color', *re.findall(r'import\s+(?:androidx\.compose\.ui\.graphics|android\.graphics)\.Color\s+as\s+(\w+)', source)]
        constructors = r'\b(?:' + '|'.join(map(re.escape, names)) + r')\s*(?:\(|\.(?:rgb|argb|parseColor|valueOf)\s*\()'
        constants = r'\b(?:' + '|'.join(map(re.escape, names)) + r')\.(?:Black|DarkGray|Gray|LightGray|White|Red|Green|Blue|Yellow|Cyan|Magenta|BLACK|WHITE|RED|GREEN|BLUE|GRAY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA)\b'
        for number, line in enumerate(source.splitlines(), 1):
            manual = (re.search(constructors, line) or re.search(constants, line)) if path.suffix == '.kt' else (
                re.search(r'#[0-9a-fA-F]{3,8}\b|@android:color/', line))
            if manual:
                violations.append(f'{relative}:{number}: authored color; use a generated token')
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail on stale generated palette data without writing')
    args = parser.parse_args()
    try:
        stale = synchronize(expected_outputs(ROOT), args.check)
        if args.check and (violations := mobile_color_violations(ROOT)):
            raise PaletteError('\n'.join(violations))
    except (PaletteError, OSError, json.JSONDecodeError) as error:
        print(f'Palette error: {error}', file=sys.stderr)
        return 1
    if args.check and stale:
        print(f'Stale palette outputs; run {COMMAND}:', file=sys.stderr)
        for path in stale:
            print('  ' + str(path.relative_to(ROOT)), file=sys.stderr)
        return 1
    print(f'Palette {"check passed" if args.check else "generated"}: {len(stale)} {"stale" if args.check else "updated"} outputs')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
