package de.aikitori.retagger.convert

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

enum class Target(val extension: String) {
    MP3("mp3"),
    M4A("m4a"),
    OPUS("opus");

    /** MP3 comes with LAME; M4A and Opus need Android's own encoders (Opus from Android 10). */
    val available: Boolean by lazy {
        when (this) {
            MP3 -> true
            M4A -> hasEncoder(MediaFormat.MIMETYPE_AUDIO_AAC)
            OPUS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS)
        }
    }

    private fun hasEncoder(mime: String) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
}

/** [value] is the LAME VBR quality (0–9) for VBR, otherwise the bitrate in kbps. */
enum class AudioQuality(val target: Target, val vbr: Boolean, val value: Int) {
    MP3_V0(Target.MP3, true, 0),
    MP3_V2(Target.MP3, true, 2),
    MP3_320(Target.MP3, false, 320),
    MP3_192(Target.MP3, false, 192),
    MP3_128(Target.MP3, false, 128),
    AAC_256(Target.M4A, false, 256),
    AAC_192(Target.M4A, false, 192),
    AAC_128(Target.M4A, false, 128),
    OPUS_160(Target.OPUS, false, 160),
    OPUS_128(Target.OPUS, false, 128),
    OPUS_96(Target.OPUS, false, 96);

    companion object {
        fun default(target: Target) = when (target) {
            Target.MP3 -> MP3_V2
            Target.M4A -> AAC_192
            Target.OPUS -> OPUS_128
        }
    }
}
