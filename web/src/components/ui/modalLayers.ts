import { injectDialogRootContext } from 'reka-ui'
import { computed, inject, onScopeDispose, provide, shallowRef, watch } from 'vue'
import type { ComputedRef, InjectionKey } from 'vue'

const modalLayerKey: InjectionKey<ComputedRef<symbol | undefined>> = Symbol('modal-layer')
const openModalLayers = shallowRef<symbol[]>([])

/** Register a modal content layer and preserve its identity through portals. */
export function provideModalLayer(): void {
  const { open, modal } = injectDialogRootContext()
  const parentLayer = inject(modalLayerKey, undefined)
  const layer = Symbol('modal-layer')
  const active = computed(() => open.value && modal.value)
  provide(
    modalLayerKey,
    computed(() => (active.value ? layer : parentLayer?.value)),
  )

  const remove = () => {
    openModalLayers.value = openModalLayers.value.filter((entry) => entry !== layer)
  }

  watch(
    active,
    (isOpenModal) => {
      if (isOpenModal) openModalLayers.value = [...openModalLayers.value, layer]
      else remove()
    },
    { immediate: true },
  )
  onScopeDispose(remove)
}

/** Content inside the top modal stays visible; all lower layers are occluded. */
export function useModalOcclusion(): ComputedRef<boolean> {
  const ownLayer = inject(modalLayerKey, undefined)
  return computed(() => {
    const topLayer = openModalLayers.value.at(-1)
    return topLayer !== undefined && topLayer !== ownLayer?.value
  })
}
