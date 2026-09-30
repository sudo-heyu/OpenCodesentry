package app.opencodesentry

import androidx.annotation.RawRes

/**
 * The kinds of event worth waking the user up for, and how each one is
 * surfaced (voice clip, notification text, vibration pattern).
 */
enum class AlertKind(
    val voiceKey: String,
    val title: String,
) {
    TASK_DONE("done", "任务完成"),
    PERMISSION("permission", "需要授权"),
    QUESTION("question", "需要你回答"),
    ERROR("error", "任务失败"),
    INTERRUPTED("interrupted", "任务已中断"),
    ;

    /**
     * Bundled pre-recorded Edge TTS clip for this kind and voice.
     *
     * Nested `when`s rather than one over a Pair: this way the compiler proves
     * every combination is covered, so adding a [Voice] fails the build here
     * until a clip is generated for it (`tools/tts/generate_voice.py`).
     */
    @RawRes
    fun clipFor(voice: Voice): Int = when (this) {
        TASK_DONE -> when (voice) {
            Voice.XIAOYI -> R.raw.tts_done_xiaoyi
            Voice.XIAOXIAO -> R.raw.tts_done_xiaoxiao
            Voice.YUNXI -> R.raw.tts_done_yunxi
            Voice.YUNYANG -> R.raw.tts_done_yunyang
        }

        PERMISSION -> when (voice) {
            Voice.XIAOYI -> R.raw.tts_permission_xiaoyi
            Voice.XIAOXIAO -> R.raw.tts_permission_xiaoxiao
            Voice.YUNXI -> R.raw.tts_permission_yunxi
            Voice.YUNYANG -> R.raw.tts_permission_yunyang
        }

        QUESTION -> when (voice) {
            Voice.XIAOYI -> R.raw.tts_question_xiaoyi
            Voice.XIAOXIAO -> R.raw.tts_question_xiaoxiao
            Voice.YUNXI -> R.raw.tts_question_yunxi
            Voice.YUNYANG -> R.raw.tts_question_yunyang
        }

        ERROR -> when (voice) {
            Voice.XIAOYI -> R.raw.tts_error_xiaoyi
            Voice.XIAOXIAO -> R.raw.tts_error_xiaoxiao
            Voice.YUNXI -> R.raw.tts_error_yunxi
            Voice.YUNYANG -> R.raw.tts_error_yunyang
        }

        INTERRUPTED -> when (voice) {
            Voice.XIAOYI -> R.raw.tts_interrupted_xiaoyi
            Voice.XIAOXIAO -> R.raw.tts_interrupted_xiaoxiao
            Voice.YUNXI -> R.raw.tts_interrupted_yunxi
            Voice.YUNYANG -> R.raw.tts_interrupted_yunyang
        }
    }

    /** Vibration pattern in milliseconds, alternating wait/vibrate. */
    val vibrationPattern: LongArray
        get() = when (this) {
            TASK_DONE -> longArrayOf(0, 180, 120, 180)
            PERMISSION -> longArrayOf(0, 350, 150, 350, 150, 350)
            QUESTION -> longArrayOf(0, 250, 120, 250)
            ERROR -> longArrayOf(0, 500, 200, 500, 200, 500)
            INTERRUPTED -> longArrayOf(0, 400, 200, 400)
        }

    companion object {
        /**
         * Maps an OpenCode SSE event type to an alert, or null when the event
         * is not user-facing. OpenCode emits a very chatty stream (every
         * reasoning and text delta), so everything not listed here is dropped.
         */
        fun fromEventType(type: String): AlertKind? = when (type) {
            "session.idle" -> TASK_DONE
            "session.execution.succeeded" -> TASK_DONE
            "permission.asked" -> PERMISSION
            "form.created", "session.form.created" -> QUESTION
            "session.error", "session.execution.failed" -> ERROR
            "session.execution.interrupted" -> INTERRUPTED
            else -> null
        }
    }
}
