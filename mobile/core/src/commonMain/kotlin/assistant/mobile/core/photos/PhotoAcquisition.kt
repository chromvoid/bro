package assistant.mobile.core.photos

/** A selected local source; acquisition never uploads or invokes the assistant. */
interface ImageAcquisition {
    suspend fun camera(): String?
    suspend fun picker(): String?
}

/** Platform decoders produce a metadata-free JPEG before anything leaves the device. */
fun interface PhotoNormalizer {
    suspend fun normalize(source: String): NormalizedPhoto
}
