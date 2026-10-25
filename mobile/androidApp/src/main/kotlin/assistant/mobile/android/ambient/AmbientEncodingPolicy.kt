package assistant.mobile.android.ambient

/** The server must explicitly accept a compressed format before the microphone opens. */
object AmbientEncodingPolicy {
    fun preferred(accepted: Set<AmbientAudioFormat>, supported: Set<AmbientAudioFormat>, apiLevel: Int): List<AmbientAudioFormat> =
        listOf(AmbientAudioFormat.OGG_OPUS, AmbientAudioFormat.AAC_ADTS)
            .filter { it in accepted && it in supported && (it != AmbientAudioFormat.OGG_OPUS || apiLevel >= 29) }
}
