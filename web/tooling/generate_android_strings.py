#!/usr/bin/env python3
"""Generate native strings from web/shared native messages and shared trading JSON.

Global object spreads keep JavaScript's shallow, left-to-right semantics. Component JSON
imports and explicit native locale sources extend their existing namespaces; conflicting
text or structure fails explicitly. Native-only messages survive removal of their Vue UI.
Unsupported dynamic expressions, message syntax and resource-name collisions fail.
Named/list placeholders become ordered String arguments; their already formatted values
are preserved, rather than guessing a numeric type from a Russian sentence.
"""
from __future__ import annotations

import argparse
import ast
from dataclasses import dataclass
from pathlib import Path
import json
import re
import sys
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[2]
SOURCE = 'web/src/i18n.ts'
TRADING_SOURCE = 'plugins/trading/web/labels.json'
# These dictionaries are shared with Compose after the Android web bridge UI was removed.
# Keep their messages independent of whether a Vue component still imports them.
NATIVE_LOCALE_SOURCES = (
    'web/src/locales/app-update-ru.json',
    'web/src/locales/ring-device-ru.json',
    'web/src/locales/ring-events-ru.json',
    'web/src/locales/ring-host-gestures-ru.json',
    'web/src/locales/ring-live-workout-ru.json',
    'web/src/locales/ring-measurement-ru.json',
    'web/src/locales/ring-ota-ru.json',
    'web/src/locales/wearables-ru.json',
)
COMMAND = 'python3 web/tooling/generate_android_strings.py'
GENERATED = f'Generated from {SOURCE} and component-scoped locale imports by {COMMAND}; do not edit.'
TRADING_GENERATED = f'Generated from {TRADING_SOURCE} by {COMMAND}; do not edit.'
LEXEME = re.compile(r'''\s+|//[^\n]*|/\*.*?\*/|'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|\.\.\.|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?|.''', re.DOTALL)
IDENTIFIER = re.compile(r'[A-Za-z_$][\w$]*')
PARAMETER = re.compile(r'(?:[A-Za-z_$][\w$]*|\d+)')
VUE_SCRIPT = re.compile(r'<script\b[^>]*>(.*?)</script\s*>', re.DOTALL | re.IGNORECASE)


class StringsError(ValueError):
    """Source syntax or the generated resource contract changed."""


@dataclass(frozen=True)
class Reference:
    name: str


def tokens(source: str) -> list[str]:
    return [token for token in LEXEME.findall(source)
            if not token.isspace() and not token.startswith(('//', '/*'))]


class StaticObject:
    """A small static object-expression reader, not a JavaScript evaluator."""

    def __init__(self, source: list[str], imports: dict):
        self.source, self.imports, self.index = source, imports, 0

    def take(self, expected: str | None = None) -> str:
        if self.index >= len(self.source):
            raise StringsError('Incomplete static i18n object')
        value = self.source[self.index]
        self.index += 1
        if expected is not None and value != expected:
            raise StringsError(f'Expected {expected!r}, got {value!r} in i18n object')
        return value

    def value(self):
        token = self.take()
        if token == '{':
            result = {}
            while self.source[self.index] != '}':
                name = self.take()
                if name == '...':
                    reference = self.value()
                    if not isinstance(reference, dict):
                        raise StringsError('i18n spread must be an imported JSON object or static object')
                    result.update(reference)
                else:
                    if name.startswith(('"', "'")):
                        name = ast.literal_eval(name)
                    elif not IDENTIFIER.fullmatch(name):
                        raise StringsError(f'Unsupported static i18n property {name!r}')
                    self.take(':')
                    result[name] = self.value()
                if self.source[self.index] == ',':
                    self.take(',')
                elif self.source[self.index] != '}':
                    raise StringsError('Dynamic expressions are not supported in the i18n messages tree')
            self.take('}')
            return result
        if token.startswith(('"', "'")):
            return ast.literal_eval(token)
        if token in ('true', 'false'):
            return token == 'true'
        if token.isdigit():
            return int(token)
        if IDENTIFIER.fullmatch(token):
            return self.imports.get(token, Reference(token))
        raise StringsError(f'Unsupported static i18n expression {token!r}')


RUSSIAN_RULE = '''
export function russianPlural(choice: number, choicesLength: number): number {
  if (choicesLength < 3) return Math.min(choice === 1 ? 0 : 1, choicesLength - 1)
  if (choicesLength === 4) return choice === 0 ? 0 : forms[russian.select(choice)] + 1
  return forms[russian.select(choice)]
}
'''


def effective_tree(root: Path) -> dict:
    source = tokens((root / SOURCE).read_text())
    imports = {}
    for index, token in enumerate(source):
        if token == 'import' and index + 3 < len(source) and source[index + 2] == 'from':
            target = ast.literal_eval(source[index + 3])
            if target.startswith('./locales/') and target.endswith('.json'):
                path = (root / 'web/src' / target).resolve()
                if not path.is_relative_to((root / 'web/src/locales').resolve()):
                    raise StringsError('Locale import escaped web/src/locales')
                imports[source[index + 1]] = json.loads(path.read_text())
    # The common selector below mirrors this source rule. Refuse a changed rule
    # until both implementations are deliberately updated, including zero handling.
    rule = tokens(RUSSIAN_RULE)
    if not any(source[index:index + len(rule)] == rule for index in range(len(source))):
        raise StringsError('russianPlural changed: update the native selector and its contract tests')
    expected_forms = {'one': 0, 'few': 1, 'many': 2, 'other': 2, 'zero': 2, 'two': 2}
    forms_index = next((i + 2 for i in range(len(source) - 2)
                        if source[i:i + 2] == ['forms', '=']), None)
    if forms_index is None or StaticObject(source[forms_index:], imports).value() != expected_forms:
        raise StringsError('Russian plural category mapping changed')
    plural_bindings = [i for i in range(len(source) - 6)
                       if source[i:i + 7] == ['const', 'russian', '=', 'new', 'Intl', '.', 'PluralRules']]
    if (len(plural_bindings) != 1
            or source[plural_bindings[0] + 7:plural_bindings[0] + 10] != ['(', "'ru'", ')']):
        raise StringsError("Expected const russian = new Intl.PluralRules('ru') with default options")
    calls = [i for i in range(len(source) - 2) if source[i:i + 3] == ['createI18n', '(', '{']]
    if len(calls) != 1:
        raise StringsError('Expected one static createI18n({...}) call')
    config = StaticObject(source[calls[0] + 2:], imports).value()
    if config.get('locale') != 'ru' or config.get('fallbackLocale') != 'ru':
        raise StringsError('Native resources currently implement the effective Russian locale')
    messages = config.get('messages', {}).get('ru')
    if not isinstance(messages, dict) or not messages:
        raise StringsError('Expected nonempty messages.ru')
    # A scoped useI18n tree extends names already present in the global tree. The
    # global JavaScript spreads above have already settled their shallow overrides;
    # scoped imports must never silently replace a different global/scoped message.
    flatten(messages)
    origins = {}
    record_origins(messages, (), SOURCE, origins)
    locales = scoped_locale_imports(root)
    for source in NATIVE_LOCALE_SOURCES:
        locales.setdefault((root / source).resolve(), [])
    for path, components in sorted(locales.items()):
        scoped = json.loads(path.read_text())
        if not isinstance(scoped, dict):
            raise StringsError(f'Expected a locale object in {path.relative_to(root)}')
        flatten(scoped)
        usage = ('imported by ' + ', '.join(component.relative_to(root).as_posix() for component in components)
                 if components else 'shared native locale')
        source_name = f'{path.relative_to(root).as_posix()} ({usage})'
        merge_scoped(messages, scoped, (), source_name, origins)
    return messages


def scoped_locale_imports(root: Path) -> dict[Path, list[Path]]:
    """Only default JSON imports inside Vue script blocks contribute scoped messages."""
    root = root.resolve()
    web = root / 'web/src'
    locales = (web / 'locales').resolve()
    imports: dict[Path, set[Path]] = {}
    for component in sorted(web.rglob('*.vue')):
        # Vue comments and template text are not JavaScript import declarations.
        component_source = re.sub(r'<!--.*?-->', '', component.read_text(), flags=re.DOTALL)
        for script in VUE_SCRIPT.findall(component_source):
            for binding, target in script_imports(script):
                if target.startswith('@/'):
                    path = (web / target[2:]).resolve()
                elif target.startswith('.'):
                    path = (component.parent / target).resolve()
                else:
                    continue
                if not path.is_relative_to(locales):
                    if target.startswith('@/locales/'):
                        raise StringsError(f'Locale import escaped web/src/locales in {component.relative_to(root)}: {target}')
                    continue
                location = f'{component.relative_to(root).as_posix()}: {target}'
                if path.suffix != '.json' or len(binding) != 1 or not IDENTIFIER.fullmatch(binding[0]):
                    raise StringsError(f'Expected a static default JSON locale import in {location}')
                imports.setdefault(path, set()).add(component)
    return {path: sorted(components) for path, components in sorted(imports.items())}


def script_imports(script: str):
    """Read import bindings without treating comments or quoted source text as code."""
    source = tokens(script)
    for index, token in enumerate(source):
        if token != 'import' or index + 1 >= len(source):
            continue
        following = source[index + 1]
        if following.startswith(('"', "'")):
            yield [], ast.literal_eval(following)
            continue
        if following == '(':
            if index + 2 < len(source) and source[index + 2].startswith(('"', "'")):
                yield [], ast.literal_eval(source[index + 2])
            continue
        end = index + 1
        while end < len(source) and (IDENTIFIER.fullmatch(source[end]) or source[end] in ('{', '}', '*', ',')):
            if source[end] == 'from':
                if end + 1 < len(source) and source[end + 1].startswith(('"', "'")):
                    yield source[index + 1:end], ast.literal_eval(source[end + 1])
                break
            end += 1


def record_origins(value, path: tuple[str, ...], source_name: str, origins: dict):
    origins.setdefault(path, set()).add(source_name)
    children = value.items() if isinstance(value, dict) else enumerate(value) if isinstance(value, list) else ()
    for name, child in children:
        record_origins(child, (*path, str(name)), source_name, origins)


def merge_scoped(existing, incoming, path: tuple[str, ...], source_name: str, origins: dict):
    """Deep merge independently scoped namespaces, retaining every message's origin."""
    key = '.'.join(path) or '<root>'
    previous = ', '.join(sorted(origins[path]))
    if type(existing) is not type(incoming) or (isinstance(existing, list) and len(existing) != len(incoming)):
        raise StringsError(f'Locale structural collision at {key}: {previous} conflicts with {source_name}')
    if isinstance(existing, dict):
        for name, child in sorted(incoming.items()):
            child_path = (*path, name)
            if name in existing:
                merge_scoped(existing[name], child, child_path, source_name, origins)
            else:
                existing[name] = child
                record_origins(child, child_path, source_name, origins)
    elif isinstance(existing, list):
        for index, child in enumerate(incoming):
            merge_scoped(existing[index], child, (*path, str(index)), source_name, origins)
    elif existing != incoming:
        raise StringsError(f'Conflicting locale text at {key}: {previous} conflicts with {source_name}')
    origins[path].add(source_name)


def flatten(value, path: tuple[str, ...] = ()) -> dict[str, str]:
    if isinstance(value, str):
        return {'.'.join(path): value}
    if isinstance(value, dict):
        result = {}
        for name, child in value.items():
            if not isinstance(name, str) or '.' in name:
                raise StringsError(f'Unsupported locale object key at {path}: {name!r}')
            result.update(flatten(child, (*path, name)))
        return result
    if isinstance(value, list):
        return {key: text for index, child in enumerate(value)
                for key, text in flatten(child, (*path, str(index))).items()}
    raise StringsError(f'Expected a string/object/array at {".".join(path)}')


def resource_name(key: str) -> str:
    name = re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', key).replace('.', '_').replace('-', '_').lower()
    name = ''.join(character if character.isascii() and (character.isalnum() or character == '_')
                   else f'u{ord(character):04x}' for character in name)
    if not re.fullmatch(r'[a-z][a-z0-9_]*', name):
        raise StringsError(f'Invalid Android resource name for {key!r}')
    return name


def parse_message(message: str) -> tuple[list[list[tuple[str, str]]], list[str]]:
    forms: list[list[tuple[str, str]]] = [[]]
    parameters = []
    index = 0
    while index < len(message):
        character = message[index]
        if character == '|':
            forms[-1] = trim_form(forms[-1])
            forms.append([])
            index += 1
            continue
        if character == '{':
            end = message.find('}', index + 1)
            if end == -1:
                raise StringsError(f'Unclosed interpolation in {message!r}')
            expression = message[index + 1:end].strip()
            if expression.startswith("'") and expression.endswith("'"):
                try:
                    literal = ast.literal_eval(expression)
                except (ValueError, SyntaxError) as error:
                    raise StringsError(f'Unsupported literal interpolation {expression!r}') from error
                forms[-1].append(('text', literal))
            elif PARAMETER.fullmatch(expression):
                if expression not in parameters:
                    parameters.append(expression)
                forms[-1].append(('parameter', expression))
            else:
                raise StringsError(f'Unsupported interpolation {expression!r}')
            index = end + 1
            continue
        if character == '}' or message.startswith(('@:', '@.'), index):
            raise StringsError(f'Unsupported message syntax in {message!r}')
        if forms[-1] and forms[-1][-1][0] == 'text':
            forms[-1][-1] = ('text', forms[-1][-1][1] + character)
        else:
            forms[-1].append(('text', character))
        index += 1
    if len(forms) > 1:
        forms[-1] = trim_form(forms[-1])
        forms[0] = trim_form(forms[0])
    if not 1 <= len(forms) <= 4:
        raise StringsError(f'Expected one to four Russian plural forms, got {len(forms)}')
    return forms, parameters


def trim_form(form: list[tuple[str, str]]) -> list[tuple[str, str]]:
    if form and form[0][0] == 'text':
        form[0] = ('text', form[0][1].lstrip())
    if form and form[-1][0] == 'text':
        form[-1] = ('text', form[-1][1].rstrip())
    return form


def android_text(form: list[tuple[str, str]], parameters: list[str]) -> str:
    text = ''.join(f'%{parameters.index(value) + 1}$s' if kind == 'parameter'
                   else value.replace('%', '%%') if parameters else value for kind, value in form)
    text = text.replace('\\', '\\\\').replace('"', '\\"').replace("'", "\\'")
    text = text.replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t').replace('@', '\\@')
    return '"' + escape(text) + '"'


def kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')


def kotlin_identifier(name: str, object_name: bool = False) -> str:
    if name.isdigit():
        return 'Item' + name if object_name else 'item' + name
    if not re.fullmatch(r'[A-Za-z_][\w-]*', name):
        name = 'Key' + ''.join(f'{ord(character):04x}' for character in name)
    if object_name:
        name = name[:1].upper() + name[1:]
    return '`' + name + '`'


def common_kotlin(messages: dict[str, tuple[list, list[str]]]) -> str:
    lines = [f'// {GENERATED}', 'package assistant.mobile.core', '', 'import kotlin.math.abs',
             'import kotlin.math.floor', '',
             'data class StringKey(', '    val webKey: String,', '    val resourceName: String,',
             '    val parameters: List<String>,', '    val pluralForms: Int,', ')', '',
             '/** Placeholder values are caller-formatted text, never guessed numeric types. */',
             'data class LocalizedString(', '    val key: StringKey,',
             '    val arguments: List<String> = emptyList(),', '    val quantity: Double? = null,', ')', '',
             '/** Same choice semantics as russianPlural in web/src/i18n.ts, including explicit zero. */',
             'object RussianPlural {', '    fun index(choice: Double, forms: Int): Int {',
             '        require(choice.isFinite() && forms in 1..4)',
             '        if (forms < 3) return minOf(if (choice == 1.0) 0 else 1, forms - 1)',
             '        if (forms == 4 && choice == 0.0) return 0', '        val absolute = abs(choice)',
             '        val whole = floor(absolute)',
             '        // Intl defaults to 3 fraction digits and halfExpand. Only rounding to',
             '        // an integer changes the Russian category; other fractions stay other.',
             '        // Compare before subtracting/multiplying to keep 1.0005 and huge values exact.',
             '        val rounded = when {', '            absolute == whole -> absolute',
             '            absolute < whole + 0.0005 -> whole',
             '            absolute >= whole + 0.9995 -> whole + 1.0',
             '            else -> absolute', '        }',
             '        val category = when {', '            rounded % 1.0 != 0.0 -> 2',
             '            rounded % 10.0 == 1.0 && rounded % 100.0 != 11.0 -> 0',
             '            rounded % 10.0 in 2.0..4.0 && rounded % 100.0 !in 12.0..14.0 -> 1',
             '            else -> 2', '        }', '        return category + if (forms == 4) 1 else 0',
             '    }', '}', '', '/** Every leaf of the effective web tree, including indexed array entries. */',
             'object GeneratedStrings {']
    tree = {}
    for key in messages:
        node = tree
        parts = key.split('.')
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = key

    def emit(node: dict, level: int):
        indent = '    ' * level
        for name, value in node.items():
            if isinstance(value, dict):
                lines.append(f'{indent}object {kotlin_identifier(name, True)} {{')
                emit(value, level + 1)
                lines.append(f'{indent}}}')
                continue
            forms, parameters = messages[value]
            args = ', '.join(kotlin_identifier(parameter) + ': String' for parameter in parameters)
            if len(forms) > 1:
                args = 'quantity: Double' + (', ' + args if args else '')
            signature = ('fun ' + kotlin_identifier(name) + '(' + args + '): LocalizedString ='
                         if args else 'val ' + kotlin_identifier(name) + ': LocalizedString get() =')
            lines.append(f'{indent}{signature} LocalizedString(')
            placeholders = 'listOf(' + ', '.join(map(kotlin_string, parameters)) + ')' if parameters else 'emptyList()'
            lines.append(f'{indent}    key = StringKey({kotlin_string(value)}, {kotlin_string(resource_name(value))}, {placeholders}, {len(forms)}),')
            if parameters:
                lines.append(f'{indent}    arguments = listOf(' + ', '.join(map(kotlin_identifier, parameters)) + '),')
            if len(forms) > 1:
                lines.append(f'{indent}    quantity = quantity,')
            lines.append(f'{indent})')
    emit(tree, 1)
    return '\n'.join([*lines, '}']) + '\n'


def android_resolver() -> str:
    return f'''// {GENERATED}
package assistant.mobile.design

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.RussianPlural

/** Names and placeholder signatures come from GeneratedStrings, never from user input. */
@SuppressLint("DiscouragedApi")
fun Resources.localizedString(message: LocalizedString, packageName: String): String {{
    require(message.arguments.size == message.key.parameters.size) {{
        "Wrong placeholder count for ${{message.key.webKey}}"
    }}
    val resourceName = if (message.key.pluralForms > 1) {{
        val quantity = requireNotNull(message.quantity) {{ "A plural choice is required" }}
        message.key.resourceName + "_form_" + RussianPlural.index(quantity, message.key.pluralForms)
    }} else {{
        require(message.quantity == null) {{ "A plain string has no plural choice" }}
        message.key.resourceName
    }}
    val id = getIdentifier(resourceName, "string", packageName)
    check(id != 0) {{ "Missing generated string: ${{message.key.webKey}}" }}
    return if (message.arguments.isEmpty()) getString(id)
    else getString(id, *message.arguments.toTypedArray())
}}

fun Context.localizedString(message: LocalizedString): String =
    resources.localizedString(message, packageName)
'''


def parse_trading_message(message: str) -> tuple[list, list[str]]:
    """Trading uses ordered printf strings, not Vue interpolation or plural syntax."""
    parts = []
    indices = set()
    offset = 0
    for match in re.finditer(r'%(\d+)\$s', message):
        parts.append(('text', message[offset:match.start()]))
        index = int(match[1])
        indices.add(index)
        parts.append(('parameter', f'arg{index}'))
        offset = match.end()
    parts.append(('text', message[offset:]))
    if indices and indices != set(range(1, max(indices) + 1)):
        raise StringsError(f'Trading placeholders must be contiguous and start at 1: {message!r}')
    parameters = [f'arg{index}' for index in range(1, len(indices) + 1)]
    return [parts], parameters


def trading_outputs(root: Path) -> dict[Path, str]:
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise StringsError(f'Duplicate trading string key: {key!r}')
            result[key] = value
        return result

    source = json.loads((root / TRADING_SOURCE).read_text(), object_pairs_hook=unique_object)
    if not isinstance(source, dict) or not source:
        raise StringsError('Expected a nonempty trading string object')
    xml = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {TRADING_GENERATED} -->', '<resources>']
    kotlin = [f'// {TRADING_GENERATED}', 'package assistant.mobile.trading', '',
              '/** Keys and printf argument signatures match the trading web module. */',
              'object GeneratedTradingStrings {',
              '    private data class StringSpec(val resourceName: String, val argumentCount: Int)', '',
              '    private val keys: Map<String, StringSpec> = mapOf(']
    names = {}
    for key, message in source.items():
        if not isinstance(message, str):
            raise StringsError(f'Expected a trading string at {key!r}')
        # The source deliberately contains both order_filled and order_Filled.
        # Android names are lowercase: retain case as escaped Unicode codepoints.
        native_key = ''.join(f'u{ord(character):04x}' if character.isupper() else character
                             for character in key)
        name = resource_name('trading.' + native_key)
        if name in names:
            raise StringsError(f'Android trading resource collision: {names[name]} and {key}')
        names[name] = key
        forms, parameters = parse_trading_message(message)
        formatted = '' if parameters else ' formatted="false"'
        xml.append(f'    <string name="{name}"{formatted}>{android_text(forms[0], parameters)}</string>')
        kotlin.append(f'        {kotlin_string(key)} to StringSpec({kotlin_string(name)}, {len(parameters)}),')
    xml.append('</resources>')
    kotlin.extend(['    )', '', '    val webKeys: Set<String> get() = keys.keys', '',
                   '    private fun spec(key: String): StringSpec =',
                   '        requireNotNull(keys[key]) { "Unknown trading string: $key" }', '',
                   '    fun resourceName(key: String): String = spec(key).resourceName', '',
                   '    fun validateArguments(key: String, arguments: Array<out String>) {',
                   '        require(arguments.size == spec(key).argumentCount) {',
                   '            "Wrong trading placeholder count for $key"', '        }',
                   '    }', '}'])
    return {
        root / 'plugins/trading/mobile/src/androidMain/kotlin/assistant/mobile/trading/GeneratedTradingStrings.kt': '\n'.join(kotlin) + '\n',
        root / 'plugins/trading/mobile/src/androidMain/res/values/trading_strings.xml': '\n'.join(xml) + '\n',
    }


def expected_outputs(root: Path) -> dict[Path, str]:
    strings = flatten(effective_tree(root))
    parsed = {key: parse_message(value) for key, value in strings.items()}
    resource_names = {}
    xml = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {GENERATED} -->', '<resources>']
    for key, (forms, parameters) in parsed.items():
        name = resource_name(key)
        if name in resource_names:
            raise StringsError(f'Android resource collision: {resource_names[name]} and {key}')
        resource_names[name] = key
        formatted = '' if parameters else ' formatted="false"'
        if len(forms) == 1:
            xml.append(f'    <string name="{name}"{formatted}>{android_text(forms[0], parameters)}</string>')
            continue
        quantities = {2: ['one', 'other'], 3: ['one', 'few', 'many'], 4: ['zero', 'one', 'few', 'many']}[len(forms)]
        xml.append(f'    <plurals name="{name}">')
        for quantity, form in zip(quantities, forms):
            xml.append(f'        <item quantity="{quantity}">{android_text(form, parameters)}</item>')
        if 'other' not in quantities:
            xml.append(f'        <item quantity="other">{android_text(forms[-1], parameters)}</item>')
        xml.append('    </plurals>')
        # Android's Russian PluralRules never select zero, and two-form Vue
        # messages use choice==1, unlike Android's category "one" (also 21).
        # Keep every source form addressable for the exact common selector.
        for index, form in enumerate(forms):
            xml.append(f'    <string name="{name}_form_{index}"{formatted}>{android_text(form, parameters)}</string>')
    xml.append('</resources>')
    resources = '\n'.join(xml) + '\n'
    common = root / 'mobile/core/src/commonMain/kotlin/assistant/mobile/core/GeneratedStrings.kt'
    design = root / 'mobile/design/src/main'
    return {
        common: common_kotlin(parsed),
        # Russian is the only app locale; defaults also cover other system languages.
        design / 'res/values/strings.xml': resources,
        design / 'kotlin/assistant/mobile/design/GeneratedResourceStrings.kt': android_resolver(),
        **trading_outputs(root),
    }


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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail on stale resources without writing')
    args = parser.parse_args()
    try:
        stale = synchronize(expected_outputs(ROOT), args.check)
    except (StringsError, OSError, json.JSONDecodeError, SyntaxError, IndexError) as error:
        print(f'String generation error: {error}', file=sys.stderr)
        return 1
    if args.check and stale:
        print(f'Stale Android strings; run {COMMAND}:', file=sys.stderr)
        for path in stale:
            print('  ' + str(path.relative_to(ROOT)), file=sys.stderr)
        return 1
    print(f'Android strings {"check passed" if args.check else "generated"}: {len(stale)} {"stale" if args.check else "updated"} outputs')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
