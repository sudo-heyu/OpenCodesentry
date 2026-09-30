package app.opencodesentry

/**
 * The bundled Edge TTS voices. Every voice has a pre-recorded clip for every
 * [AlertKind]; regenerate them with `tools/tts/generate_voice.py` when this
 * list changes.
 */
enum class Voice(val id: String, val label: String) {
    XIAOYI("xiaoyi", "晓伊 · 女声"),
    XIAOXIAO("xiaoxiao", "晓晓 · 女声"),
    YUNXI("yunxi", "云希 · 男声"),
    YUNYANG("yunyang", "云扬 · 男声（播报）"),
    ;

    companion object {
        val DEFAULT = XIAOYI

        fun fromId(id: String?): Voice = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
