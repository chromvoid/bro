import type { BrowserContext, Page } from '@playwright/test'

interface NativeAdapterInfo {
  vendor?: string
  architecture?: string
  device?: string
  description?: string
  isFallbackAdapter?: boolean
}

interface ObservedAdapter {
  info?: NativeAdapterInfo
  isFallbackAdapter?: boolean
  requestAdapterInfo?(): Promise<NativeAdapterInfo>
  requestDevice(...args: unknown[]): Promise<unknown>
}

interface ObservedGpu {
  requestAdapter(...args: unknown[]): Promise<ObservedAdapter | null>
}

interface DeviceRequest {
  acquired: boolean
  error?: string
}

interface AdapterRequest {
  available: boolean
  vendor: string
  architecture: string
  device: string
  description: string
  fallback: boolean | null
  powerPreference: string | null
  forceFallbackAdapter: boolean | null
  devices: DeviceRequest[]
  error?: string
}

interface RendererGpuRequests {
  available: boolean
  adapters: AdapterRequest[]
  instrumentationError?: string
}

type ProofWindow = Window & { __bodyRendererGpu?: RendererGpuRequests }

/** Observe the adapter/device actually requested by the renderer, before navigation.
 * Native objects, arguments and receivers are forwarded unchanged. No separate
 * GPU request is made by this recorder or by the proof reader.
 */
export async function recordBodyRendererGpu(surface: BrowserContext | Page) {
  await surface.addInitScript(() => {
    const target = window as ProofWindow
    const gpu = (navigator as unknown as { gpu?: ObservedGpu }).gpu
    const report: RendererGpuRequests = { available: !!gpu, adapters: [] }
    target.__bodyRendererGpu = report
    if (!gpu) return
    try {
      const originalAdapter = gpu.requestAdapter
      gpu.requestAdapter = async function (this: ObservedGpu, ...args: unknown[]) {
        const options = args[0] as
          { powerPreference?: string; forceFallbackAdapter?: boolean } | undefined
        const record: AdapterRequest = {
          available: false,
          vendor: '',
          architecture: '',
          device: '',
          description: '',
          fallback: null,
          powerPreference: options?.powerPreference ?? null,
          forceFallbackAdapter: options?.forceFallbackAdapter ?? null,
          devices: [],
        }
        report.adapters.push(record)
        let adapter: ObservedAdapter | null
        try {
          adapter = await originalAdapter.apply(this, args)
        } catch (error) {
          record.error = error instanceof Error ? error.message : String(error)
          throw error
        }
        if (!adapter) return adapter
        record.available = true
        try {
          const info = adapter.info ?? (await adapter.requestAdapterInfo?.())
          record.vendor = info?.vendor ?? ''
          record.architecture = info?.architecture ?? ''
          record.device = info?.device ?? ''
          record.description = info?.description ?? ''
          record.fallback = info?.isFallbackAdapter ?? adapter.isFallbackAdapter ?? null
          const originalDevice = adapter.requestDevice
          adapter.requestDevice = async function (this: ObservedAdapter, ...deviceArgs: unknown[]) {
            const request: DeviceRequest = { acquired: false }
            record.devices.push(request)
            try {
              const device = await originalDevice.apply(this, deviceArgs)
              request.acquired = !!device
              return device
            } catch (error) {
              request.error = error instanceof Error ? error.message : String(error)
              throw error
            }
          }
        } catch (error) {
          // Recording must not change the application result; missing proof
          // fails the CI assertion after rendering, instead of altering GPU use.
          report.instrumentationError = error instanceof Error ? error.message : String(error)
        }
        return adapter
      }
    } catch (error) {
      report.instrumentationError = error instanceof Error ? error.message : String(error)
    }
  })
}

export async function bodyRendererGpuRequests(page: Page) {
  return page.evaluate(() => (window as ProofWindow).__bodyRendererGpu ?? null)
}

/** Call only after asserting an actual WebGPU canvas and a rendered frame. */
export async function requireNativeBodyRenderer(page: Page) {
  const report = await bodyRendererGpuRequests(page)
  const acquired = report?.adapters.filter((adapter) =>
    adapter.devices.some((device) => device.acquired),
  )
  if (!report?.available || report.instrumentationError || !acquired?.length)
    throw new Error(`Renderer did not acquire an observed WebGPU device: ${JSON.stringify(report)}`)
  for (const adapter of acquired) {
    const identity = [adapter.vendor, adapter.architecture, adapter.device, adapter.description]
      .join(' ')
      .toLowerCase()
    if (
      !adapter.available ||
      adapter.fallback !== false ||
      /swiftshader|llvmpipe|lavapipe|software/.test(identity) ||
      !/apple|intel|amd|radeon|nvidia/.test(identity)
    )
      throw new Error(`Renderer requires a genuine native adapter: ${JSON.stringify(adapter)}`)
  }
  return {
    ...acquired[acquired.length - 1]!,
    deviceAcquired: true,
    deviceCount: acquired.reduce(
      (sum, adapter) => sum + adapter.devices.filter((device) => device.acquired).length,
      0,
    ),
    requests: report.adapters,
  }
}
