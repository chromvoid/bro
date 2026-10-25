/// <reference types="vitest/config" />
import { randomBytes } from 'node:crypto'
import { fileURLToPath, URL } from 'node:url'
import VueI18nPlugin from '@intlify/unplugin-vue-i18n/vite'
import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { defineConfig, type Plugin, type ProxyOptions } from 'vite'
import { bodyAssets } from './tooling/bodyAssets.ts'
import { configuredZod } from './tooling/configuredZod.ts'
import { precompressedAssets } from './tooling/precompressedAssets.ts'
import { servedAssets } from './tooling/servedAssets.ts'

// The development backend (npm run dev) on loopback; Vite proxies the API to it.
const backend = `http://127.0.0.1:${process.env.DEV_PORT ?? '8766'}`

/** Publishes one build identity to the bundle and to /build.json for update detection. */
function buildInfo(): Plugin {
  const id =
    process.env.ASSISTANT_BUILD_ID ?? `${Date.now().toString(36)}-${randomBytes(4).toString('hex')}`
  return {
    name: 'assistant-build-info',
    config: () => ({ define: { __ASSISTANT_BUILD__: JSON.stringify(id) } }),
    generateBundle() {
      this.emitFile({
        type: 'asset',
        fileName: 'build.json',
        source: `${JSON.stringify({ build: id })}\n`,
      })
    },
  }
}

// The server accepts only its own public origin, and development login needs its Host.
const toBackend: ProxyOptions = {
  target: backend,
  changeOrigin: true,
  ws: true,
  configure(proxy) {
    proxy.on('proxyReq', (request) => request.setHeader('origin', backend))
    proxy.on('proxyReqWs', (request) => request.setHeader('origin', backend))
  },
}

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    VueI18nPlugin({
      include: [fileURLToPath(new URL('./src/locales/**', import.meta.url))],
      runtimeOnly: true,
      compositionOnly: true,
      dropMessageCompiler: true,
    }),
    buildInfo(),
    bodyAssets(),
    configuredZod(fileURLToPath(new URL('./src/core/zod.ts', import.meta.url))),
    precompressedAssets(),
    servedAssets(),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    // Plugin modules live in plugins/<id>/web, outside this package: their imports of shared
    // libraries resolve here, so the app and every module use one copy.
    dedupe: ['vue', 'zod', '@lucide/vue', '@vueuse/core', 'reka-ui', 'vitest'],
  },
  build: {
    // The Content-Security-Policy forbids data: URIs and inline scripts.
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    sourcemap: false,
    // Bundled dependencies ship with their notices; the server publishes /licenses.txt.
    license: { fileName: 'licenses.txt' },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    fs: { allow: ['..'] },
    proxy: { '/v1': toBackend, '/health': { target: backend, changeOrigin: true } },
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.test.ts', 'tooling/**/*.test.ts', '../plugins/*/web/**/*.test.ts'],
  },
})
