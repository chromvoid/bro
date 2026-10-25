import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('generate_palette', Path(__file__).with_name('generate_palette.py'))
palette = importlib.util.module_from_spec(spec)
spec.loader.exec_module(palette)


class PaletteResolutionTests(unittest.TestCase):
    def test_theme_aliases_resolve_after_dark_overrides_without_p3_leaking(self):
        themes = palette.read_themes('''
            /* An ignored comment: :root { --background: invalid; } */
            :root {
                --background: oklch(1 0 0);
                --primary: oklch(0.54593 0.2153 270.7);
                --ring: var(--primary);
                @media (color-gamut: p3) { --primary: oklch(0.54593 0.26 270.7); }
                @variant dark {
                    --background: oklch(0 0 0);
                    --primary: oklch(0.72 0.1445 271);
                    @media (color-gamut: p3) { --primary: oklch(0.72 0.154 271); }
                }
            }
        ''')
        self.assertEqual(palette.color_hex(themes['light'], 'background'), '#FFFFFF')
        self.assertEqual(palette.color_hex(themes['dark'], 'background'), '#000000')
        self.assertEqual(palette.color_hex(themes['light'], 'ring'), '#465CEB')
        self.assertEqual(palette.color_hex(themes['dark'], 'ring'), '#849EFF')

    def test_percentage_oklch_matches_numeric_coordinates(self):
        self.assertEqual(palette.resolve({'--color': 'oklch(50% 25% 90deg)'}, '--color'), (0.5, 0.1, 90))

    def test_missing_alias_cycles_and_unsupported_css_fail_explicitly(self):
        for tokens, message in [
            ({'--a': 'var(--missing)'}, 'Missing palette token'),
            ({'--a': 'var(--b)', '--b': 'var(--a)'}, 'Palette alias cycle'),
            ({'--a': 'oklch(.5 .1 90 / .5)'}, 'must use opaque'),
            ({'--a': 'color-mix(in oklch, white, black)'}, 'must use opaque'),
        ]:
            with self.subTest(tokens=tokens), self.assertRaisesRegex(palette.PaletteError, message):
                palette.resolve(tokens, '--a')

    def test_native_baseline_rejects_p3_colors_instead_of_silently_clipping(self):
        with self.assertRaisesRegex(palette.PaletteError, 'outside sRGB'):
            palette.rgb((0.54593, 0.26, 270.7))

    def test_theme_specific_icon_canvas_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / palette.SOURCE
            source.parent.mkdir(parents=True)
            source.write_text(':root { --icon-background: oklch(0 0 0); '
                              '@variant dark { --icon-background: oklch(1 0 0); } }')
            with self.assertRaisesRegex(palette.PaletteError, 'must stay fixed across themes'):
                palette.expected_outputs(root)

    def test_check_reports_changed_and_missing_outputs_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            current = Path(directory) / 'current'
            stale = Path(directory) / 'stale'
            missing = Path(directory) / 'missing'
            current.write_text('expected')
            stale.write_text('manual edit')
            outputs = {current: 'expected', stale: 'expected', missing: 'expected'}
            self.assertEqual(palette.synchronize(outputs, check=True), [stale, missing])
            self.assertEqual(stale.read_text(), 'manual edit')
            self.assertFalse(missing.exists())
            self.assertEqual(palette.synchronize(outputs, check=False), [stale, missing])
            self.assertEqual(palette.synchronize(outputs, check=True), [])

    def test_theme_specific_icon_foreground_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / palette.SOURCE
            source.parent.mkdir(parents=True)
            source.write_text(':root { --icon-background: oklch(0 0 0); '
                              '--icon-foreground: oklch(1 0 0); '
                              '@variant dark { --icon-foreground: oklch(0 0 0); } }')
            with self.assertRaisesRegex(palette.PaletteError, '--icon-foreground must stay fixed'):
                palette.expected_outputs(root)

    def test_metadata_updates_only_generated_scope(self):
        html = '''<!doctype html>
<title>Kept verbatim</title>
    <meta name="theme-color" media="(prefers-color-scheme: light)" content="#000000" />
    <meta name="theme-color" media="(prefers-color-scheme: dark)" content="#ffffff" />
<body class="kept">Unrelated content</body>
'''
        colors = {'light': '#FFFFFF', 'dark': '#000000'}
        generated = palette.metadata(html, colors)
        self.assertIn('<title>Kept verbatim</title>', generated)
        self.assertIn('<body class="kept">Unrelated content</body>', generated)
        self.assertIn('BEGIN GENERATED PALETTE META', generated)
        self.assertEqual(palette.metadata(generated, colors), generated)

    def test_manifest_preserves_unrelated_fields_and_icon_format(self):
        source = '''{
  "name": "Kept name",
  "background_color": "old",
  "theme_color": "old",
  "icons": [{ "src": "/icon.png", "purpose": "any" }]
}
'''
        generated = palette.manifest_colors(source, '#F4F7FF', '#5863A5')
        self.assertIn('"name": "Kept name"', generated)
        self.assertIn('"icons": [{ "src": "/icon.png", "purpose": "any" }]', generated)
        # An installed PWA opens on the launch colour; the browser chrome takes the app's background.
        self.assertIn('"background_color": "#5863a5"', generated)
        self.assertIn('"theme_color": "#f4f7ff"', generated)
        self.assertEqual(palette.manifest_colors(generated, '#F4F7FF', '#5863A5'), generated)

    def test_boot_mark_replaces_the_old_icon_once_and_stays_stable(self):
        moods = {'viewBox': '0 0 256 256', 'b': 'M0 0Z', 'ok': 'M1 1Z', 'thinking': ['M2 2Z', 'M3 3Z', 'M4 4Z'],
                 'merge': [{'from': f'M{i} 0Z', 'to': f'M{i} 9Z'} for i in range(3)]}
        html = ('<body>\n    <div id="boot" aria-hidden="true">\n'
                '      <img src="/icon-192.png" width="56" height="56" alt="" />\n    </div>\n</body>\n')
        generated = palette.boot_mark(html, moods)
        self.assertNotIn('icon-192', generated)
        # boot.ts moves the points; no SMIL, which WebKit's page process died on.
        self.assertNotIn('<animate', generated)
        self.assertEqual(generated.count(' data-to="'), 3)
        self.assertIn('<path d="M0 0Z" data-to="M0 9Z" />', generated)
        self.assertEqual(palette.boot_mark(generated, moods), generated)
        with self.assertRaisesRegex(palette.PaletteError, 'three thinking dots'):
            palette.boot_mark(generated, {**moods, 'thinking': moods['thinking'][:2]})


    def test_ios_smile_morphs_compatible_pieces(self):
        piece = lambda x, end: 'M' + 'L'.join(f'{x + i} {end + i}' for i in range(4)) + 'Z'
        moods = {'viewBox': '0 0 256 256', 'b': 'M0 0Z', 'ok': 'M1 1Z', 'thinking': ['M2 2Z'] * 3,
                 'merge': [{'from': piece(i, 0), 'to': piece(i, 9)} for i in range(3)]}
        self.assertEqual(palette.ios_splash_geometry(moods).count('(from: [.init'), 3)
        broken = {**moods, 'merge': [{'from': piece(0, 0), 'to': 'M0 0L1 1Z'}] * 3}
        with self.assertRaisesRegex(palette.PaletteError, 'number of points'):
            palette.ios_splash_geometry(broken)
        with self.assertRaisesRegex(palette.PaletteError, 'M/L polylines'):
            palette.ios_splash_geometry({**moods, 'merge': [{'from': 'M0 0A1 1 0 0 1 2 2Z', 'to': 'M0 0Z'}] * 3})

    def test_compose_colors_keep_aliases_dark_roles_and_effect_alpha(self):
        themes = palette.read_themes('''
            :root {
                --primary: oklch(1 0 0);
                --chart-1: var(--primary);
                --overlay-background: color-mix(in oklab, var(--primary) 10%, transparent);
                @variant dark { --primary: oklch(0 0 0); }
            }
        ''')
        outputs = palette.mobile_palette(themes)
        kotlin = outputs['kotlin/assistant/mobile/design/GeneratedPalette.kt']
        self.assertIn('"chart-1" to Color(0xFFFFFFFF)', kotlin)
        self.assertIn('"chart-1" to Color(0xFF000000)', kotlin)
        self.assertIn('"overlay-background" to Color(0x1AFFFFFF)', kotlin)
        self.assertIn('<color name="chart_1">#FF000000</color>', outputs['res/values-night/colors.xml'])

    def test_static_mix_interpolates_in_its_declared_color_space(self):
        tokens = {'--white': 'oklch(1 0 0)', '--black': 'oklch(0 0 0)',
                  '--mix': 'color-mix(in oklab, var(--white) 25%, var(--black))'}
        self.assertEqual(palette.static_color(tokens, '--mix'), (0.25, 0.0, 0.0, 1.0))

    def test_web_text_scale_constants_normalize_pixels_and_follow_source_changes(self):
        css = '''@layer base {
            html { font-size: min(calc((100% * var(--system-text-scale) + 16px) / 2), 20px); }
        }'''
        self.assertEqual(palette.text_scale_constants(css), (1.0, 2.0, 1.25))
        changed = css.replace('16px', '8px').replace('/ 2', '/ 4').replace('20px', '24px')
        self.assertEqual(palette.text_scale_constants(changed), (0.5, 4.0, 1.5))
        generated = palette.mobile_text_scale(changed)
        self.assertIn('const val offset: Float = 0.5f', generated)
        self.assertIn('const val divisor: Float = 4f', generated)
        self.assertIn('const val maximum: Float = 1.5f', generated)
        self.assertNotIn('import android', generated)
        self.assertNotIn('import androidx.compose', generated)

    def test_unknown_web_text_scale_grammar_fails_instead_of_copying_old_constants(self):
        css = 'html { font-size: min(calc((100% * var(--system-text-scale) + 16px) / 2), 20px); }'
        for changed in (css.replace('min(', 'clamp('), css.replace('100%', '90%'),
                        css.replace('20px', '1.25rem'), css.replace('/ 2', '/ 0'),
                        css + '\n' + css, 'html { font-size: var(--custom-scale); }'):
            with self.subTest(css=changed), self.assertRaises(palette.PaletteError):
                palette.text_scale_constants(changed)

    def test_native_authored_colors_fail_but_generated_boundaries_and_tokens_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            authored = root / 'mobile/design/src/main/kotlin/NativeTheme.kt'
            generated = root / 'mobile/design/src/main/kotlin/assistant/mobile/design/GeneratedPalette.kt'
            xml = root / 'mobile/androidApp/src/main/res/values/styles.xml'
            for path in (authored, generated, xml):
                path.parent.mkdir(parents=True, exist_ok=True)
            generated.write_text('val generated = Color(0xFFFFFFFF)')
            authored.write_text('val inherited = GeneratedColors.light.getValue("primary")')
            xml.write_text('<resources><item name="colorAccent">@color/primary</item></resources>')
            self.assertEqual(palette.mobile_color_violations(root), [])
            authored.write_text('import androidx.compose.ui.graphics.Color as Ink\nval literal = Ink(0xFFFFFFFF)\nval named = Color.White')
            xml.write_text('<resources><color name="manual">#FFFFFF</color></resources>')
            violations = palette.mobile_color_violations(root)
            self.assertEqual(len(violations), 3)
            self.assertTrue(any('NativeTheme.kt:2:' in message for message in violations))


if __name__ == '__main__':
    unittest.main()
