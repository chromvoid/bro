import './styles.css'
import { VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { leaveBoot } from './app/boot'
import { updates } from './core/client'
import { queryClient } from './core/query'
import { useShell } from './core/shell'
import { applyTheme, syncShellTheme, theme } from './core/theme'
import { i18n } from './i18n'
import { router } from './router'
import { useSession } from './stores/session'

applyTheme(theme.value)
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && router.currentRoute.value.name !== 'join')
    void updates.returned()
})

const app = createApp(App)
  .use(createPinia())
  .use(i18n)
  .use(router)
  .use(VueQueryPlugin, { queryClient })
if (router.resolve(location.pathname).name !== 'join') void useSession().load()
app.mount('#app')
// The launch screen (index.html #boot) stays until the first screen has rendered, not just the empty
// shell; a failed first navigation ends it too, and the app shows its own error state. Naming the
// theme afterwards tells the iOS app the first view is ready: their own splash and
// launch-coloured bars stay until then, so in an app the page's launch screen is never seen.
const unveil = async () => {
  await leaveBoot(document.getElementById('boot'), (await useShell().ready()) !== null)
  syncShellTheme()
}
router.isReady().then(unveil, unveil)
