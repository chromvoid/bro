package assistant.mobile.core.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BodyAssetContractTest {
    @Test
    fun sameV3ContractCarriesThirtyShapesFourCorrectivesExplicitSurfacesAndRelief() {
        val asset = decodeBodyAssetContract(bodyContractFixture(), 4)
        assertEquals(BODY_SHAPE_NAMES, asset.bodyMorphNames)
        assertEquals(listOf("skin"), asset.bodySurfaceNodes)
        assertEquals(listOf("skin", "head"), asset.framingBodySurfaceNodes)
        assertEquals(BODY_CORRECTION_NAMES, asset.poseCorrectives.map { it.name })
        assertEquals(4, asset.surfaces.size)
        assertEquals(BodyNormalTextures(0, 1, 2), asset.relief.normalTextures)
        assertEquals(3, asset.relief.maskTexture)
        assertEquals(30, asset.relief.gridWeights.size)
    }

    @Test
    fun reliefTextureReferencesAndWeightsCannotEscapeTheShippedAsset() {
        val original = bodyContractFixture()
        assertFailsWith<IllegalArgumentException> { decodeBodyAssetContract(original, 3) }
        val relief = original.getValue("relief") as JsonObject
        val duplicateMask = JsonObject(relief + ("mask_texture" to JsonPrimitive(0)))
        assertFailsWith<IllegalArgumentException> { decodeBodyAssetContract(JsonObject(original + ("relief" to duplicateMask)), 4) }
        val nonConvex = JsonObject(relief + ("grid_weights" to JsonArray(List(30) { JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1), JsonPrimitive(0))) })))
        assertFailsWith<IllegalArgumentException> { decodeBodyAssetContract(JsonObject(original + ("relief" to nonConvex)), 4) }
    }
}

internal fun bodyContractFixture(): JsonObject = buildJsonObject {
    put("version", "body-assets-v3")
    put("body_morph_names", JsonArray(BODY_SHAPE_NAMES.map(::JsonPrimitive)))
    put("body_surface_nodes", JsonArray(listOf(JsonPrimitive("skin"))))
    put("framing", buildJsonObject { put("body_surface_nodes", JsonArray(listOf(JsonPrimitive("skin"), JsonPrimitive("head")))) })
    put("surfaces", JsonArray(listOf("skin" to "skin", "head" to "head_skin", "eyes" to "eye", "cloth" to "cloth").map { (node, role) ->
        buildJsonObject { put("node", node); put("role", role); put("material", "material-$node") } }))
    put("pose_correctives", JsonArray(BODY_CORRECTION_NAMES.mapIndexed { index, name -> buildJsonObject {
        put("name", name); put("bone", "bone-$index"); put("axis", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0), JsonPrimitive(0))))
        put("sign", 1); put("start_radians", 0.5); put("end_radians", 1.5)
        put("composition_scales", JsonArray(List(30) { JsonPrimitive(1) }))
    } }))
    put("relief", buildJsonObject {
        put("normal_textures", buildJsonObject { put("soft", 0); put("neutral", 1); put("defined", 2) })
        put("mask_texture", 3)
        put("grid_weights", JsonArray(List(30) { JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive(0))) }))
        put("region_channels", JsonArray(listOf("torso", "arms", "legs").map(::JsonPrimitive)))
    })
}
