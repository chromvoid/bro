package assistant.mobile.core.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.sqrt

data class BodySurface(val node: String, val role: String, val material: String)
data class BodyNormalTextures(val soft: Int, val neutral: Int, val defined: Int)
data class BodyReliefContract(val normalTextures: BodyNormalTextures, val maskTexture: Int,
    val gridWeights: List<List<Double>>, val regionChannels: List<String>)
data class BodyAssetContract(
    val version: String,
    val bodyMorphNames: List<String>,
    val bodySurfaceNodes: List<String>,
    val framingBodySurfaceNodes: List<String>,
    val surfaces: List<BodySurface>,
    val poseCorrectives: List<PoseCorrective>,
    val relief: BodyReliefContract,
)

/** The same shipped v3 metadata governs surfaces, all 34 targets and relief in every renderer. */
fun decodeBodyAssetContract(asset: JsonObject, textureCount: Int): BodyAssetContract {
    fun text(row: JsonObject, key: String) = requireNotNull((row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        .also { require(it.isNotEmpty()) }
    fun obj(row: JsonObject, key: String) = requireNotNull(row[key] as? JsonObject)
    fun array(row: JsonObject, key: String) = requireNotNull(row[key] as? JsonArray)
    fun texts(row: JsonObject, key: String) = array(row, key).map { value ->
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }?.content).also { require(it.isNotEmpty()) } }
    fun number(row: JsonObject, key: String) = requireNotNull((row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull)
        .also { require(it.isFinite()) }
    fun numbers(value: JsonArray, length: Int): List<Double> {
        require(value.size == length)
        return value.map { requireNotNull((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.doubleOrNull).also { n -> require(n.isFinite()) } }
    }
    require(text(asset, "version") == "body-assets-v3")
    val names = texts(asset, "body_morph_names"); require(names == BODY_SHAPE_NAMES)
    val nodes = texts(asset, "body_surface_nodes"); require(nodes.isNotEmpty() && nodes.distinct().size == nodes.size)
    val surfaces = array(asset, "surfaces").map { value ->
        val row = requireNotNull(value as? JsonObject)
        val surface = BodySurface(text(row, "node"), text(row, "role"), text(row, "material"))
        require(surface.role in listOf("skin", "head_skin", "eye", "hair", "cloth", "accessory"))
        require(surface.role != "skin" || surface.node in nodes)
        require(surface.node !in nodes || surface.role in listOf("skin", "head_skin"))
        surface
    }
    require(surfaces.isNotEmpty() && surfaces.map { it.node to it.material }.distinct().size == surfaces.size)
    require(nodes.all { node -> surfaces.any { it.node == node && it.role == "skin" } })
    val framing = texts(obj(asset, "framing"), "body_surface_nodes")
    require(framing.isNotEmpty() && framing.distinct().size == framing.size && framing.all { node -> surfaces.any { it.node == node } })
    require(surfaces.all { (it.role == "skin" || it.role == "head_skin") == (it.node in framing) })
    val corrections = array(asset, "pose_correctives").mapIndexed { index, value ->
        val row = requireNotNull(value as? JsonObject)
        require(text(row, "name") == BODY_CORRECTION_NAMES.getOrNull(index))
        val axis = numbers(array(row, "axis"), 3)
        require(abs(sqrt(axis.sumOf { it * it }) - 1) <= 0.0001)
        val sign = number(row, "sign"); val start = number(row, "start_radians"); val end = number(row, "end_radians")
        val scales = numbers(array(row, "composition_scales"), 30)
        require(abs(sign) == 1.0 && start >= 0 && end > start && end <= kotlin.math.PI && scales.all { it in 0.0..2.0 })
        PoseCorrective(text(row, "name"), text(row, "bone"), axis, sign, start, end, scales)
    }
    require(corrections.size == BODY_CORRECTION_NAMES.size)
    val relief = obj(asset, "relief"); val textures = obj(relief, "normal_textures")
    fun texture(row: JsonObject, key: String) = requireNotNull((row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
        .also { require(it in 0 until textureCount) }
    val normals = BodyNormalTextures(texture(textures, "soft"), texture(textures, "neutral"), texture(textures, "defined"))
    val mask = texture(relief, "mask_texture")
    require(listOf(normals.soft, normals.neutral, normals.defined, mask).distinct().size == 4)
    val grid = array(relief, "grid_weights").map { value -> numbers(requireNotNull(value as? JsonArray), 3).also { weights ->
        require(weights.all { it in 0.0..1.0 } && abs(weights.sum() - 1) <= 0.0001) } }
    require(grid.size == 30)
    val channels = texts(relief, "region_channels"); require(channels == listOf("torso", "arms", "legs"))
    return BodyAssetContract("body-assets-v3", names, nodes, framing, surfaces, corrections, BodyReliefContract(normals, mask, grid, channels))
}
