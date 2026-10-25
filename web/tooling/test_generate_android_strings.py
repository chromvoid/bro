import importlib.util
from pathlib import Path
import json
import sys
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('generate_android_strings', Path(__file__).with_name('generate_android_strings.py'))
strings = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = strings
spec.loader.exec_module(strings)


def source(overlays='...base, ...overlay'):
    return '''
import { createI18n } from 'vue-i18n'
import base from './locales/base.json'
import overlay from './locales/overlay.json'
const russian = new Intl.PluralRules('ru')
const forms = { one: 0, few: 1, many: 2, other: 2, zero: 2, two: 2 } as const
''' + strings.RUSSIAN_RULE + '''
export const i18n = createI18n({
  legacy: false, locale: 'ru', fallbackLocale: 'ru',
  messages: { ru: { ''' + overlays + ''' } }, pluralRules: { ru: russianPlural },
})
'''


class AndroidStringGenerationTests(unittest.TestCase):
    def fixture(self, root, base, overlay):
        locales = root / 'web/src/locales'
        locales.mkdir(parents=True)
        (root / strings.SOURCE).write_text(source())
        (locales / 'base.json').write_text(json.dumps(base))
        (locales / 'overlay.json').write_text(json.dumps(overlay))
        for native_locale in strings.NATIVE_LOCALE_SOURCES:
            (root / native_locale).write_text('{}')
        # An unimported locale is deliberately absent from the effective tree.
        (locales / 'unused.json').write_text('{"unused":"must not be generated"}')
        trading = root / strings.TRADING_SOURCE
        trading.parent.mkdir(parents=True)
        trading.write_text(json.dumps({'total': 'Стоимость портфеля'}))

    def locale(self, root, filename, messages):
        path = root / 'web/src/locales' / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(messages))
        return path

    def component(self, root, filename, imports):
        path = root / 'web/src' / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<script setup lang="ts">\n' + imports + '\n</script>\n<template><p /></template>')
        return path

    def test_effective_import_order_is_shallow_and_array_leaves_are_kept(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}, 'section': {'kept': 'old', 'removed': 'old'}},
                         {'section': {'kept': 'new'}, 'samples': ['first', 'second']})
            effective = strings.flatten(strings.effective_tree(root))
            self.assertEqual(effective, {'app.title': 'Bro', 'section.kept': 'new',
                                         'samples.0': 'first', 'samples.1': 'second'})

    def test_scoped_nested_namespace_extends_the_effective_shallow_global_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}, 'personal': {'removed': 'old'}},
                         {'personal': {'title': 'Личное', 'dashboard': {'global': 'Осталось'}}})
            self.locale(root, 'dashboard-body-ru.json', {'personal': {'dashboard': {'bodySubtitle': 'Последние замеры'}}})
            self.component(root, 'sections/personal/DashboardBody.vue',
                           "import dashboardBodyRu from '@/locales/dashboard-body-ru.json'")
            effective = strings.flatten(strings.effective_tree(root))
            self.assertEqual(effective, {'app.title': 'Bro', 'personal.title': 'Личное',
                                         'personal.dashboard.global': 'Осталось',
                                         'personal.dashboard.bodySubtitle': 'Последние замеры'})
            xml = strings.expected_outputs(root)[root / 'mobile/design/src/main/res/values/strings.xml']
            self.assertIn('name="personal_dashboard_body_subtitle"', xml)

    def test_shared_native_locale_survives_without_a_vue_importer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            self.locale(root, 'ring-live-workout-ru.json',
                        {'ring': {'workout': {'totalDuration': 'Всего: {duration}'}}})
            self.assertEqual(strings.scoped_locale_imports(root), {})
            self.assertEqual(strings.flatten(strings.effective_tree(root)),
                             {'app.title': 'Bro', 'ring.workout.totalDuration': 'Всего: {duration}'})
            outputs = strings.expected_outputs(root)
            common = outputs[root / 'mobile/core/src/commonMain/kotlin/assistant/mobile/core/GeneratedStrings.kt']
            xml = outputs[root / 'mobile/design/src/main/res/values/strings.xml']
            self.assertIn('key = StringKey("ring.workout.totalDuration", "ring_workout_total_duration", listOf("duration"), 1)', common)
            self.assertIn('<string name="ring_workout_total_duration">"Всего: %1$s"</string>', xml)

    def test_equal_global_and_scoped_leaves_can_be_shared_without_renaming(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}, 'personal': {'title': 'Личное', 'samples': ['Первый', 'Второй']}}, {})
            for filename in ('first.json', 'second.json'):
                self.locale(root, filename, {'personal': {'title': 'Личное', 'samples': ['Первый', 'Второй']}})
                self.component(root, filename + '.vue', f"import messages from '@/locales/{filename}'")
            self.assertEqual(strings.flatten(strings.effective_tree(root)),
                             {'app.title': 'Bro', 'personal.title': 'Личное',
                              'personal.samples.0': 'Первый', 'personal.samples.1': 'Второй'})

    def test_conflicting_scoped_leaf_names_the_key_and_both_locale_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            for filename, title in (('first.json', 'Личное'), ('second.json', 'Профиль')):
                self.locale(root, filename, {'personal': {'title': title}})
                self.component(root, filename + '.vue', f"import messages from '@/locales/{filename}'")
            with self.assertRaises(strings.StringsError) as raised:
                strings.effective_tree(root)
            message = str(raised.exception)
            for expected in ('personal.title', 'web/src/locales/first.json', 'web/src/locales/second.json',
                             'web/src/first.json.vue', 'web/src/second.json.vue'):
                self.assertIn(expected, message)

    def test_conflicting_global_and_scoped_text_names_the_global_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}, 'personal': {'title': 'Личное'}}, {})
            self.locale(root, 'scoped.json', {'personal': {'title': 'Профиль'}})
            self.component(root, 'Profile.vue', "import messages from '@/locales/scoped.json'")
            with self.assertRaises(strings.StringsError) as raised:
                strings.effective_tree(root)
            for expected in ('personal.title', strings.SOURCE, 'web/src/locales/scoped.json'):
                self.assertIn(expected, str(raised.exception))

    def test_structural_collisions_do_not_replace_a_namespace_or_an_array(self):
        for global_value, scoped_value in (('Личное', {'title': 'Личное'}),
                                            ({'title': 'Личное'}, 'Личное'),
                                            (['Личное'], {'title': 'Личное'}),
                                            (['Личное'], ['Личное', 'Профиль'])):
            with self.subTest(global_value=global_value, scoped_value=scoped_value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root, {'app': {'title': 'Bro'}, 'personal': global_value}, {})
                self.locale(root, 'scoped.json', {'personal': scoped_value})
                self.component(root, 'Profile.vue', "import messages from '@/locales/scoped.json'")
                with self.assertRaisesRegex(strings.StringsError, r'structural collision at personal:'):
                    strings.effective_tree(root)

    def test_alias_and_relative_imports_are_deduplicated_and_components_are_sorted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            locale = self.locale(root, 'shared.json', {'shared': {'label': 'Общее'}})
            second = self.component(root, 'sections/personal/Second.vue',
                                    'import /* locale */ second from "../../locales/shared.json";')
            first = self.component(root, 'First.vue', "import first from '@/locales/shared.json'")
            self.assertEqual(strings.scoped_locale_imports(root), {locale: [first, second]})
            self.assertEqual(strings.flatten(strings.effective_tree(root)), {'app.title': 'Bro', 'shared.label': 'Общее'})

    def test_unimported_files_template_text_comments_and_other_json_are_not_messages(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            self.locale(root, 'unimported-scoped.json', {'personal': {'notImported': 'Не добавлять'}})
            component = self.component(root, 'Example.vue', """
                // import ignored from '@/locales/unused.json'
                const text = "import ignored from '@/locales/unused.json'"
                import fixture from './fixture.json'
            """)
            component.write_text(component.read_text() + """
                <template><p>import ignored from '@/locales/unused.json'</p></template>
                <!-- <script>import ignored from '@/locales/unused.json'</script> -->
            """)
            self.assertEqual(strings.scoped_locale_imports(root), {})
            self.assertEqual(strings.flatten(strings.effective_tree(root)), {'app.title': 'Bro'})

    def test_unsupported_locale_imports_fail_instead_of_dropping_their_messages(self):
        for declaration in ("import { title } from '@/locales/scoped.json'",
                            "import * as messages from '@/locales/scoped.json'",
                            "import messages, { title } from '@/locales/scoped.json'",
                            "import '@/locales/scoped.json'",
                            "const load = import('@/locales/scoped.json')",
                            "import messages from '@/locales/scoped.ts'"):
            with self.subTest(declaration=declaration), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root, {'app': {'title': 'Bro'}}, {})
                self.locale(root, 'scoped.json', {'personal': {'title': 'Личное'}})
                self.component(root, 'Profile.vue', declaration)
                with self.assertRaisesRegex(strings.StringsError, 'static default JSON locale import'):
                    strings.effective_tree(root)

    def test_explicit_locale_alias_cannot_escape_the_locale_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            self.component(root, 'Profile.vue', "import messages from '@/locales/../../outside.json'")
            with self.assertRaisesRegex(strings.StringsError, 'Locale import escaped'):
                strings.effective_tree(root)

    def test_named_and_list_parameters_literals_and_percent_are_preserved(self):
        forms, parameters = strings.parse_message("{name} ({'@'}{login}): {0} · 10 % · {name}")
        self.assertEqual(parameters, ['name', 'login', '0'])
        self.assertEqual(strings.android_text(forms[0], parameters),
                         '"%1$s (\\@%2$s): %3$s · 10 %% · %1$s"')

    def test_all_russian_forms_and_zero_remain_addressable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}, 'items': 'нет | {count} один | {count} мало | {count} много'}, {})
            outputs = strings.expected_outputs(root)
            self.assertEqual([path for path in outputs if path.suffix == '.xml'],
                             [root / 'mobile/design/src/main/res/values/strings.xml',
                              root / 'plugins/trading/mobile/src/androidMain/res/values/trading_strings.xml'])
            xml = outputs[root / 'mobile/design/src/main/res/values/strings.xml']
            self.assertIn('<item quantity="zero">"нет"</item>', xml)
            self.assertIn('<item quantity="one">"%1$s один"</item>', xml)
            self.assertIn('<item quantity="few">"%1$s мало"</item>', xml)
            self.assertIn('<item quantity="many">"%1$s много"</item>', xml)
            self.assertIn('<item quantity="other">"%1$s много"</item>', xml)
            self.assertIn('<string name="items_form_0">"нет"</string>', xml)
            common = outputs[root / 'mobile/core/src/commonMain/kotlin/assistant/mobile/core/GeneratedStrings.kt']
            self.assertIn('fun `items`(quantity: Double, `count`: String)', common)

    def test_resource_collisions_fail_instead_of_silently_dropping_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}, 'fooBar': 'one', 'foo_bar': 'two'}, {})
            with self.assertRaisesRegex(strings.StringsError, 'resource collision'):
                strings.expected_outputs(root)

    def test_dynamic_spread_and_changed_plural_rule_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            path = root / strings.SOURCE
            path.write_text(source('...base, ...loadFromNetwork()'))
            with self.assertRaisesRegex(strings.StringsError, 'spread must be'):
                strings.effective_tree(root)
            path.write_text(source().replace('choice === 0', 'choice === 2'))
            with self.assertRaisesRegex(strings.StringsError, 'russianPlural changed'):
                strings.effective_tree(root)

    def test_plural_constructor_options_require_a_native_selector_update(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            path = root / strings.SOURCE
            for options in ('{ maximumFractionDigits: 0 }', '{ type: "ordinal" }'):
                path.write_text(source().replace("new Intl.PluralRules('ru')",
                                                "new Intl.PluralRules('ru', " + options + ')'))
                with self.subTest(options=options), self.assertRaisesRegex(strings.StringsError, 'default options'):
                    strings.effective_tree(root)

    def test_stale_check_is_read_only_and_catches_every_generated_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            outputs = strings.expected_outputs(root)
            self.assertEqual(strings.synchronize(outputs, check=True), list(outputs))
            self.assertTrue(all(not path.exists() for path in outputs))
            strings.synchronize(outputs, check=False)
            common = root / 'mobile/core/src/commonMain/kotlin/assistant/mobile/core/GeneratedStrings.kt'
            common.write_text('manually changed')
            self.assertEqual(strings.synchronize(outputs, check=True), [common])
            self.assertEqual(common.read_text(), 'manually changed')

    def test_unsupported_message_syntax_is_not_emitted_as_visible_template(self):
        for message in ('{unfinished', '@:linked.key', '{not a parameter}', 'a|b|c|d|e'):
            with self.subTest(message=message), self.assertRaises(strings.StringsError):
                strings.parse_message(message)

    def test_trading_printf_arguments_keep_order_repetition_and_literal_message_syntax(self):
        forms, parameters = strings.parse_trading_message('%2$s · %1$s · %2$s · 10% | {literal}')
        self.assertEqual(parameters, ['arg1', 'arg2'])
        self.assertEqual(strings.android_text(forms[0], parameters),
                         '"%2$s · %1$s · %2$s · 10%% | {literal}"')
        with self.assertRaisesRegex(strings.StringsError, 'contiguous'):
            strings.parse_trading_message('%2$s')

    def test_trading_outputs_have_shared_source_provenance_keys_and_argument_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            (root / strings.TRADING_SOURCE).write_text(json.dumps({
                'total': 'Стоимость портфеля', 'fresh_live': 'онлайн · %1$s',
                'earn_FlexibleSaving': 'гибкие сбережения',
            }))
            outputs = strings.trading_outputs(root)
            xml = outputs[root / 'plugins/trading/mobile/src/androidMain/res/values/trading_strings.xml']
            native = outputs[root / 'plugins/trading/mobile/src/androidMain/kotlin/assistant/mobile/trading/GeneratedTradingStrings.kt']
            self.assertIn(strings.TRADING_SOURCE, xml)
            self.assertIn('name="trading_fresh_live">"онлайн · %1$s"</string>', xml)
            self.assertIn('name="trading_earn_u0046lexibleu0053aving"', xml)
            self.assertIn('"fresh_live" to StringSpec("trading_fresh_live", 1)', native)
            self.assertIn('require(arguments.size == spec(key).argumentCount)', native)
            self.assertIn('fun resourceName(key: String): String = spec(key).resourceName', native)
            self.assertIn('package assistant.mobile.trading', native)
            self.assertNotIn('assistant.mobile.core', native)

    def test_trading_duplicate_nonstring_and_colliding_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'app': {'title': 'Bro'}}, {})
            path = root / strings.TRADING_SOURCE
            for source in ('{"total":"one","total":"two"}', '{"total":42}',
                           '{"filled":"one","u0046illed":"two","Filled":"three"}'):
                path.write_text(source)
                with self.subTest(source=source), self.assertRaises(strings.StringsError):
                    strings.trading_outputs(root)


if __name__ == '__main__':
    unittest.main()
