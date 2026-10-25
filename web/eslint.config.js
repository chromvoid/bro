import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import prettier from 'eslint-config-prettier/flat'
import pluginVue from 'eslint-plugin-vue'

export default defineConfigWithVueTs(
  { name: 'app/files', files: ['**/*.{ts,mts,vue}'] },
  {
    name: 'app/ignores',
    ignores: ['dist/**', 'coverage/**', 'playwright-report/**', 'test-results/**'],
  },
  pluginVue.configs['flat/recommended'],
  vueTsConfigs.recommended,
  prettier,
  {
    name: 'app/rules',
    rules: {
      'vue/multi-word-component-names': 'off',
      // Optional props typed in TypeScript are undefined by design.
      'vue/require-default-prop': 'off',
      // The Content-Security-Policy (style-src 'self') blocks static style attributes and v-html.
      'vue/no-restricted-static-attribute': [
        'error',
        { key: 'style', message: 'Use classes; CSP forbids inline style attributes.' },
      ],
      'vue/no-v-html': 'error',
    },
  },
  {
    name: 'app/query-state',
    files: ['**/*.{ts,vue}'],
    ignores: ['**/*.test.ts', 'e2e/**'],
    rules: Object.fromEntries(
      ['no-restricted-syntax', 'vue/no-restricted-syntax'].map((rule) => [
        rule,
        [
          'error',
          {
            selector:
              "MemberExpression[property.name='value'][object.property.name=/^is(Error|Success)$/]",
            message:
              'A failed refetch keeps its data yet sets isError and clears isSuccess. Show ' +
              'ErrorState for isLoadingError, RefreshFailed over kept data, and test data for loaded.',
          },
        ],
      ]),
    ),
  },
)
