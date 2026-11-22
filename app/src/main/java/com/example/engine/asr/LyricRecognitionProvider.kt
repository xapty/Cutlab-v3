package com.example.engine.asr

import com.example.engine.text.LyricLineDraft

data class LyricAlignmentAvailability(
    val isAvailable: Boolean,
    val isExperimental: Boolean,
    val providerName: String,
    val statusNote: String,
)

/**
 * Provider boundary for future experimental singing-lyric transcription or forced alignment.
 *
 * Per CUTLAB Task 003 §8: ordinary speech ASR (Vosk) is NOT a reliable singing-voice lyric
 * transcriber because vocals are mixed with polyphonic instruments, reverb, pitch modulation,
 * and harmonies. Manual Lyric Editor & Synchronization remains 100% independent of this provider.
 */
interface LyricRecognitionProvider {
    fun checkAvailability(): LyricAlignmentAvailability

    suspend fun attemptExperimentalLyricAlignment(
        assetId: String,
        suppliedLyrics: List<LyricLineDraft>,
    ): Result<List<LyricLineDraft>>
}

class DeferredExperimentalLyricProvider : LyricRecognitionProvider {
    override fun checkAvailability(): LyricAlignmentAvailability = LyricAlignmentAvailability(
        isAvailable = false,
        isExperimental = true,
        providerName = "On-Device Singing Lyric Recognition (Deferred)",
        statusNote = "Automatic singing transcription & forced lyric alignment are deferred because " +
            "speech ASR models degrade severely on mixed vocals and music. Use Manual Lyric Studio " +
            "to import .txt lyrics, snap to waveform/beats, and sync word timing.",
    )

    override suspend fun attemptExperimentalLyricAlignment(
        assetId: String,
        suppliedLyrics: List<LyricLineDraft>,
    ): Result<List<LyricLineDraft>> = Result.failure(
        UnsupportedOperationException(checkAvailability().statusNote),
    )
}
