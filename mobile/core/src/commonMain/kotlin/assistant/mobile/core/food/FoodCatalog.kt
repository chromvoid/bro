package assistant.mobile.core.food

/** Catalog snapshots and portions are immutable sources; nutrition is calculated by the server. */
data class FoodPortion(val id: String, val name: String, val quantity: String, val unit: String, val revision: Long? = null) {
    val saved: Boolean get() = revision != null
}

data class FoodCatalogItem(
    val foodId: String,
    val snapshotId: String,
    val name: String,
    val brand: String?,
    val basisQuantity: String,
    val basisUnit: String,
    val nutrients: Map<String, String?> = emptyMap(),
    val portions: List<FoodPortion> = emptyList(),
    val savedPortions: List<FoodPortion> = emptyList(),
    val personal: Boolean = false,
    val sourceProvider: String? = null,
)

data class FoodRemembered(
    val foodId: String,
    val name: String,
    val brand: String?,
    val favorite: Boolean,
    val preferenceRevision: Long,
    val lastEaten: String?,
    val lastQuantity: String?,
    val lastUnit: String?,
    val snapshotId: String?,
    val basisQuantity: String?,
    val basisUnit: String?,
    val energyKcal: String?,
) {
    val repeatable: Boolean get() = snapshotId != null && lastQuantity != null && lastUnit != null
    fun catalogItem(): FoodCatalogItem? {
        val snapshot = snapshotId ?: return null
        return FoodCatalogItem(foodId, snapshot, name, brand, basisQuantity ?: return null,
            basisUnit ?: return null, mapOf("energy_kcal" to energyKcal))
    }
}

data class FoodCompositionComponent(val label: String, val quantity: String, val unit: String)
data class FoodComposition(val id: String, val name: String, val components: List<FoodCompositionComponent>)
data class FoodPortionDraft(
    val food: FoodCatalogItem,
    val quantity: String = food.basisQuantity,
    val unit: String = food.basisUnit,
    val portion: FoodPortion? = null,
    val count: String = "1",
)
data class FoodEstimateItem(val nutrients: Map<String, String?>, val estimated: Boolean)
data class FoodEstimate(val items: List<FoodEstimateItem>, val totals: Map<String, FoodTotal>, val recorded: Boolean = false)
data class FoodAddState(
    val open: Boolean = false,
    val query: String = "",
    val foods: FoodQuery<List<FoodCatalogItem>> = FoodQuery(),
    val memory: FoodQuery<List<FoodRemembered>> = FoodQuery(),
    val compositions: FoodQuery<List<FoodComposition>> = FoodQuery(),
    val selection: FoodPortionDraft? = null,
    val chosenComposition: FoodComposition? = null,
    val compositionCount: String = "1",
    val mealKind: FoodMealKind? = null,
    val estimate: FoodQuery<FoodEstimate> = FoodQuery(),
) {
    val recent: List<FoodRemembered> get() = memory.data.orEmpty().filter { it.lastEaten != null }.sortedByDescending { it.lastEaten }
    val favorites: List<FoodRemembered> get() = memory.data.orEmpty().filter { it.favorite }
}
