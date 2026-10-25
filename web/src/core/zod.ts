import { z } from 'zod'

// Skip zod's new Function() probe: the Content-Security-Policy forbids eval. Zod probes when it
// builds the first object schema, often while a module loads, so modules take z from here and never
// from 'zod': importing this module runs the config first in whatever chunk the bundler puts them.
// A side-effect import at the top of main.ts did not: rolldown evaluated a shared chunk before it.
z.config({ jitless: true })

export { z }
