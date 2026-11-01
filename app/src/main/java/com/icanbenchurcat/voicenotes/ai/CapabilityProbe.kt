// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ai

import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Abstraction over the raw device/ML Kit availability checks so that
 * [CapabilityProbe] can be deterministically unit-tested across all
 * ladder states (AVAILABLE, DOWNLOADABLE, UNAVAILABLE, BUSY) and
 * AICore/app version changes without requiring flagship hardware.
 */
interface CapabilityChecker {
    suspend fun checkTranscriber(): CapabilityStatus
    suspend fun checkSummarizer(): CapabilityStatus
    suspend fun checkActionExtractor(): CapabilityStatus
    fun versionFingerprint(): String
}

/**
 * Production [CapabilityChecker] that queries ML Kit GenAI feature status
 * and platform [SpeechRecognizer] availability on the running device.
 */
class DeviceCapabilityChecker(private val context: Context) : CapabilityChecker {

    override suspend fun checkTranscriber(): CapabilityStatus {
        val advancedStatus = probeMlKitSpeechMode(SpeechRecognizerOptions.Mode.MODE_ADVANCED)
        if (advancedStatus == CapabilityStatus.AVAILABLE) {
            return CapabilityStatus.AVAILABLE
        }

        val basicStatus = probeMlKitSpeechMode(SpeechRecognizerOptions.Mode.MODE_BASIC)
        if (basicStatus == CapabilityStatus.AVAILABLE) {
            return CapabilityStatus.AVAILABLE
        }

        val platformAvailable = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
                SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
        if (platformAvailable) {
            return CapabilityStatus.AVAILABLE
        }

        if (advancedStatus == CapabilityStatus.DOWNLOADABLE || basicStatus == CapabilityStatus.DOWNLOADABLE) {
            return CapabilityStatus.DOWNLOADABLE
        }

        return CapabilityStatus.UNAVAILABLE
    }

    override suspend fun checkSummarizer(): CapabilityStatus = probeFoundationModelStatus()

    override suspend fun checkActionExtractor(): CapabilityStatus = probeFoundationModelStatus()

    override fun versionFingerprint(): String {
        val pm = context.packageManager
        val appVersion = try {
            pm.getPackageInfo(context.packageName, 0).longVersionCode.toString()
        } catch (_: Throwable) {
            "unknown"
        }
        val aicoreVersion = try {
            pm.getPackageInfo(AICORE_PACKAGE, 0).longVersionCode.toString()
        } catch (_: PackageManager.NameNotFoundException) {
            "none"
        } catch (_: Throwable) {
            "none"
        }
        return "app=$appVersion|aicore=$aicoreVersion"
    }

    private suspend fun probeFoundationModelStatus(): CapabilityStatus {
        if (!isAiCoreInstalledAndEnabled()) {
            return CapabilityStatus.UNAVAILABLE
        }
        return probeMlKitSpeechMode(SpeechRecognizerOptions.Mode.MODE_ADVANCED)
    }

    private fun isAiCoreInstalledAndEnabled(): Boolean = try {
        val info = context.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
        info.applicationInfo?.enabled != false
    } catch (_: Throwable) {
        false
    }

    private suspend fun probeMlKitSpeechMode(mode: Int): CapabilityStatus {
        val client = try {
            SpeechRecognition.getClient(
                speechRecognizerOptions {
                    locale = Locale.getDefault()
                    preferredMode = mode
                }
            )
        } catch (_: Throwable) {
            return CapabilityStatus.UNAVAILABLE
        }
        return try {
            when (client.checkStatus()) {
                FeatureStatus.AVAILABLE -> CapabilityStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> CapabilityStatus.DOWNLOADABLE
                else -> CapabilityStatus.UNAVAILABLE
            }
        } catch (_: Throwable) {
            // Verified in Issue #41: SpeechRecognizer.checkStatus() can throw an
            // uncaught gRPC StatusException (zzaxh: PERMISSION_DENIED) when
            // Speech Services rejects access on emulators/unsupported targets.
            CapabilityStatus.UNAVAILABLE
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val AICORE_PACKAGE = "com.google.android.aicore"
    }
}

/**
 * Probes each capability through [CapabilityChecker], caches the result in
 * memory and in [DataStore] keyed by the app + AICore version fingerprint,
 * and re-probes when AICore/app versions change, when [invalidate] is called,
 * or when a capability was previously in a non-terminal state
 * ([CapabilityStatus.DOWNLOADABLE] or [CapabilityStatus.BUSY]) so a model
 * finishing download transitions cleanly to [CapabilityStatus.AVAILABLE].
 */
class CapabilityProbe(
    private val checker: CapabilityChecker,
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(
        checker = DeviceCapabilityChecker(context.applicationContext),
        dataStore = defaultDataStore(context.applicationContext)
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CapabilityStatus>()
    private var cachedFingerprint: String? = null

    suspend fun invalidate() {
        mutex.withLock {
            cache.clear()
            cachedFingerprint = null
            dataStore.edit { it.clear() }
        }
    }

    suspend fun transcriberStatus(): CapabilityStatus =
        getOrProbe(KEY_TRANSCRIBER) { checker.checkTranscriber() }

    suspend fun summarizerStatus(): CapabilityStatus =
        getOrProbe(KEY_SUMMARIZER) { checker.checkSummarizer() }

    suspend fun actionExtractorStatus(): CapabilityStatus =
        getOrProbe(KEY_ACTIONS) { checker.checkActionExtractor() }

    /**
     * Resolves the highest ladder tier the device currently supports from
     * probed capability statuses.
     */
    suspend fun resolveDeviceTier(): Tier {
        val transcriber = transcriberStatus()
        val summarizer = summarizerStatus()
        val actions = actionExtractorStatus()
        return when {
            transcriber == CapabilityStatus.AVAILABLE &&
                (summarizer == CapabilityStatus.AVAILABLE || actions == CapabilityStatus.AVAILABLE) -> Tier.FULL
            transcriber == CapabilityStatus.AVAILABLE || transcriber == CapabilityStatus.DOWNLOADABLE -> Tier.CORE
            else -> Tier.MINIMAL
        }
    }

    private suspend fun getOrProbe(
        capabilityKey: String,
        probeFn: suspend () -> CapabilityStatus
    ): CapabilityStatus = mutex.withLock {
        val currentFingerprint = checker.versionFingerprint()
        val prefKey = stringPreferencesKey("cap_${capabilityKey}_$currentFingerprint")

        // 1. Ensure in-memory cache matches current app + AICore version fingerprint
        if (cachedFingerprint != null && cachedFingerprint != currentFingerprint) {
            cache.clear()
            cachedFingerprint = currentFingerprint
        }

        // 2. Check in-memory cache; re-probe if status was transient (DOWNLOADABLE or BUSY)
        val inMemory = cache[capabilityKey]
        if (inMemory != null && isTerminalCacheable(inMemory)) {
            return@withLock inMemory
        }

        // 3. Check persisted DataStore preferences if fingerprint matches
        val prefs = dataStore.data.first()
        val storedFingerprint = prefs[PREF_FINGERPRINT]
        if (storedFingerprint != null && storedFingerprint != currentFingerprint) {
            // App or AICore updated: invalidate stale persisted entries
            dataStore.edit { it.clear() }
        } else if (storedFingerprint == currentFingerprint && inMemory == null) {
            val persistedRaw = prefs[prefKey]
            val persisted = persistedRaw?.let { runCatching { CapabilityStatus.valueOf(it) }.getOrNull() }
            if (persisted != null && isTerminalCacheable(persisted)) {
                cachedFingerprint = currentFingerprint
                cache[capabilityKey] = persisted
                return@withLock persisted
            }
        }

        // 4. Execute live probe and persist result keyed with current fingerprint
        val probed = probeFn()
        cachedFingerprint = currentFingerprint
        cache[capabilityKey] = probed
        dataStore.edit { mutablePrefs ->
            mutablePrefs[PREF_FINGERPRINT] = currentFingerprint
            mutablePrefs[prefKey] = probed.name
        }
        probed
    }

    private fun isTerminalCacheable(status: CapabilityStatus): Boolean =
        status == CapabilityStatus.AVAILABLE || status == CapabilityStatus.UNAVAILABLE

    companion object {
        private const val TAG = "CapabilityProbe"
        private const val DATASTORE_NAME = "capability_probe_cache"
        private const val KEY_TRANSCRIBER = "transcriber"
        private const val KEY_SUMMARIZER = "summarizer"
        private const val KEY_ACTIONS = "actions"
        private val PREF_FINGERPRINT = stringPreferencesKey("version_fingerprint")

        @Volatile
        private var instance: CapabilityProbe? = null

        @Volatile
        private var sharedDataStore: DataStore<Preferences>? = null

        private fun defaultDataStore(appContext: Context): DataStore<Preferences> =
            sharedDataStore ?: synchronized(this) {
                sharedDataStore ?: PreferenceDataStoreFactory.create(
                    produceFile = { appContext.preferencesDataStoreFile(DATASTORE_NAME) }
                ).also { sharedDataStore = it }
            }

        fun get(context: Context): CapabilityProbe =
            instance ?: synchronized(this) {
                instance ?: CapabilityProbe(context.applicationContext).also { instance = it }
            }

        fun logDemotion(capability: String, from: Tier, to: Tier) {
            runCatching { Log.i(TAG, "Demoted $capability from $from to $to") }
        }
    }
}
