package assistant.mobile.android.body

import assistant.mobile.core.body.BodyComposition
import assistant.mobile.core.body.BodyQuaternion
import assistant.mobile.core.body.PoseCorrective
import assistant.mobile.core.body.correctiveActivation
import assistant.mobile.core.body.compositionWeights
import assistant.mobile.core.body.decodeBodyAssetContract
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Reads only the shipped, versioned geometry contract; gltfio owns geometry and animation decoding. */
internal class BodyGlb(val bytes: ByteArray) {
    val json: JSONObject
    private val binaryOffset: Int
    val bodyAsset: JSONObject
    val morphNodes: List<String>
    val framingNodes: List<String>
    val joints: List<String>
    val rootJoints: List<String>
    val corrections: List<Correction>
    val reliefGrid: List<List<Double>>
    val reliefTextureIndices: List<Int>
    val surfaces: List<Surface>

    init {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x46546c67 && buffer.int == 2 && buffer.int == bytes.size) { "Invalid GLB header" }
        val jsonLength = buffer.int
        require(buffer.int == 0x4e4f534a && jsonLength > 0 && jsonLength <= bytes.size - 28) { "Invalid GLB JSON" }
        json = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
        buffer.position(20 + jsonLength)
        val binaryLength = buffer.int
        require(buffer.int == 0x004e4942 && binaryLength >= 0 && buffer.remaining() == binaryLength) { "Invalid GLB buffer" }
        binaryOffset = buffer.position()
        bodyAsset = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0))
            .getJSONObject("extras").getJSONObject("bodyAsset")
        val contract = decodeBodyAssetContract(Json.parseToJsonElement(bodyAsset.toString()).jsonObject,
            json.getJSONArray("textures").length())
        morphNodes = contract.bodySurfaceNodes
        framingNodes = contract.framingBodySurfaceNodes
        val nodes = json.getJSONArray("nodes")
        joints = json.getJSONArray("skins").let { skins ->
            (0 until skins.length()).flatMap { skin ->
                val indices = skins.getJSONObject(skin).getJSONArray("joints")
                List(indices.length()) { nodes.getJSONObject(indices.getInt(it)).getString("name") }
            }.distinct()
        }
        val framingSkins = (0 until nodes.length()).map(nodes::getJSONObject)
            .filter { it.optString("name") in framingNodes && it.has("skin") }.map { it.getInt("skin") }.distinct()
        val skins = json.getJSONArray("skins")
        val jointIndices = framingSkins.flatMap { skin ->
            val indices = skins.getJSONObject(skin).getJSONArray("joints")
            List(indices.length()) { indices.getInt(it) }
        }.toSet()
        val childJoints = jointIndices.flatMap { parent ->
            val children = nodes.getJSONObject(parent).optJSONArray("children")
            if (children == null) emptyList() else List(children.length()) { children.getInt(it) }
        }.toSet()
        rootJoints = (jointIndices - childJoints).map { nodes.getJSONObject(it).getString("name") }
        corrections = contract.poseCorrectives.map { definition ->
                val bone = definition.bone
                require(bone in joints)
                val node = (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("name") == bone }
                val rotation = node.optJSONArray("rotation")
                Correction(definition.name, bone, definition,
                    BodyQuaternion(rotation?.getDouble(0) ?: 0.0, rotation?.getDouble(1) ?: 0.0,
                        rotation?.getDouble(2) ?: 0.0, rotation?.getDouble(3) ?: 1.0))
        }
        reliefGrid = contract.relief.gridWeights
        reliefTextureIndices = with(contract.relief) { listOf(normalTextures.soft, normalTextures.neutral, normalTextures.defined, maskTexture) }
        surfaces = contract.surfaces.map { Surface(it.node, it.material, it.role) }
    }

    fun embeddedTexture(texture: Int): ByteArray {
        val image = json.getJSONArray("images").getJSONObject(json.getJSONArray("textures").getJSONObject(texture).getInt("source"))
        val view = json.getJSONArray("bufferViews").getJSONObject(image.getInt("bufferView"))
        require(view.optInt("buffer", 0) == 0)
        val start = binaryOffset + view.optInt("byteOffset", 0)
        val length = view.getInt("byteLength")
        require(start >= binaryOffset && length > 0 && start <= bytes.size - length)
        return bytes.copyOfRange(start, start + length)
    }

    fun material(name: String): JSONObject {
        val materials = json.getJSONArray("materials")
        return (0 until materials.length()).map(materials::getJSONObject).first { it.optString("name") == name }
    }

    fun primitiveMaterials(node: String): List<String> {
        val nodes = json.getJSONArray("nodes")
        val mesh = (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("name") == node }.getInt("mesh")
        val primitives = json.getJSONArray("meshes").getJSONObject(mesh).getJSONArray("primitives")
        val materials = json.getJSONArray("materials")
        return List(primitives.length()) { materials.getJSONObject(primitives.getJSONObject(it).getInt("material")).getString("name") }
    }

    data class Surface(val node: String, val material: String, val role: String)
    data class Correction(val name: String, val bone: String, val definition: PoseCorrective, val rest: BodyQuaternion) {
        fun weight(rotation: BodyQuaternion, composition: BodyComposition): Double =
            correctiveActivation(rotation, rest, definition) * compositionWeights(composition)
                .mapIndexed { index, weight -> weight * definition.compositionScales[index] }.sum()
    }
}
