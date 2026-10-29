package com.icanbenchurcat.voicenotes.ai

import android.content.Context
import android.util.Log

/**
 * Probes each capability once, caches the result, and re-probes when
 * AICore reports a state change. The probe is deliberately boring: the
 * interesting behaviour is that callers ask per capability, per note,
 * and demote at runtime when a probe turns out to have been optimistic
 * (BUSY and quota states appear at call time, not probe time).
 *
 * ML Kit GenAI status calls land here as the Nano surface stabilises;
 * until then the probe reports the conservative per-device defaults:
 * speech recognition availability is checked through the platform, and
 * generative features report UNAVAILABLE unless a Nano implementation
 * is compiled in and reports otherwise.
 */
class CapabilityProbe(private val context: Context) {

    private val cache = mutableMapOf<String, CapabilityStatus>()

    fun invalidate() = cache.clear()

    suspend fun transcriberStatus(): CapabilityStatus =
        cache.getOrPut("transcriber") { probeTranscriber() }

    suspend fun summarizerStatus(): CapabilityStatus =
        cache.getOrPut("summarizer") { CapabilityStatus.UNAVAILABLE }

    suspend fun actionExtractorStatus(): CapabilityStatus =
        cache.getOrPut("actions") { CapabilityStatus.UNAVAILABLE }

    private fun probeTranscriber(): CapabilityStatus {
        // ML Kit GenAI Speech Recognition (Basic) covers API 31+ on most
        // devices; Advanced mode is flagship-only. Both are probed via
        // the GenAI feature-status API in the Nano implementation.
        // Scaffold default: assume Basic is downloadable/available and
        // let call-time BUSY handling do the honest work.
        return CapabilityStatus.AVAILABLE
    }

    companion object {
        private const val TAG = "CapabilityProbe"
        fun logDemotion(capability: String, from: Tier, to: Tier) {
            Log.i(TAG, "Demoted $capability from $from to $to")
        }
    }
}
