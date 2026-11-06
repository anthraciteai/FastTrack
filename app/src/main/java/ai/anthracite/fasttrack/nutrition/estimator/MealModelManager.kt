package ai.anthracite.fasttrack.nutrition.estimator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Lifecycle of the on-device model weights. */
sealed interface ModelState {
    /** Weights are on device and ready for inference. */
    data object Ready : ModelState
    /** Weights exist (bundled asset or remote URL) but aren't installed into app storage yet. */
    data class Available(val sizeLabel: String, val bundled: Boolean) : ModelState
    /** Install/copy/download in progress, [percent] 0..100 (-1 when total size is unknown). */
    data class Preparing(val percent: Int) : ModelState
    /** No model bundled and no download URL configured — the heuristic estimator is used. */
    data object NotConfigured : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Installs and manages the food-log LLM weights, exposing a [state] the UI observes.
 *
 * Preferred path: the model ships **bundled** in `assets/models/<file>` and is copied into app
 * storage on first launch — the user never downloads anything or enters any key. If no bundled
 * asset is present, an optional developer-set [downloadUrl] (BuildConfig.MEAL_MODEL_URL) is used
 * as a fallback. If neither exists, the app silently uses the built-in heuristic estimator.
 *
 * Installs stream to a temp file and are atomically renamed on success, so a partial install is
 * never mistaken for a valid model.
 */
class MealModelManager(
    private val appContext: Context,
    val model: LlmModel,
    private val downloadUrl: String
) {
    private val assetPath = "models/${model.fileName}"
    private val modelFile = File(appContext.filesDir, "models/${model.fileName}")

    private val _state = MutableStateFlow<ModelState>(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    @Volatile private var cancelled = false

    val isReady: Boolean get() = modelFile.exists()

    private fun hasBundledAsset(): Boolean =
        runCatching { appContext.assets.open(assetPath).use { } }.isSuccess

    private fun initialState(): ModelState = when {
        modelFile.exists() -> ModelState.Ready
        hasBundledAsset() -> ModelState.Available(model.sizeLabel, bundled = true)
        downloadUrl.isNotBlank() -> ModelState.Available(model.sizeLabel, bundled = false)
        else -> ModelState.NotConfigured
    }

    fun refresh() { _state.value = initialState() }

    fun cancel() { cancelled = true }

    /**
     * Makes sure the model is installed if a source exists. Safe to call on every app start —
     * it no-ops when the model is already present. Prefers the bundled asset over the URL.
     */
    suspend fun ensureInstalled() {
        when {
            modelFile.exists() -> _state.value = ModelState.Ready
            hasBundledAsset() -> installFromAsset()
            downloadUrl.isNotBlank() -> download()
            else -> _state.value = ModelState.NotConfigured
        }
    }

    private suspend fun installFromAsset() {
        cancelled = false
        _state.value = ModelState.Preparing(-1)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                modelFile.parentFile?.mkdirs()
                val tmp = File(modelFile.parentFile, "${model.fileName}.part")
                if (tmp.exists()) tmp.delete()
                appContext.assets.open(assetPath).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                finalize(tmp)
            }
        }
        publishResult(result)
    }

    /** Streams the weights from [downloadUrl] to disk, publishing progress. */
    suspend fun download() {
        if (modelFile.exists()) { _state.value = ModelState.Ready; return }
        if (downloadUrl.isBlank()) { _state.value = ModelState.NotConfigured; return }

        cancelled = false
        _state.value = ModelState.Preparing(-1)

        val result = withContext(Dispatchers.IO) {
            runCatching {
                modelFile.parentFile?.mkdirs()
                val tmp = File(modelFile.parentFile, "${model.fileName}.part")
                if (tmp.exists()) tmp.delete()

                val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) error("Server returned HTTP ${conn.responseCode}")

                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            if (cancelled) error("Cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _state.value = ModelState.Preparing(pct)
                                }
                            }
                        }
                    }
                }
                finalize(tmp)
            }
        }
        publishResult(result)
    }

    private fun finalize(tmp: File) {
        if (!tmp.renameTo(modelFile)) {
            tmp.copyTo(modelFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun publishResult(result: Result<Unit>) {
        _state.value = result.fold(
            onSuccess = { ModelState.Ready },
            onFailure = { e ->
                runCatching { File(modelFile.parentFile, "${model.fileName}.part").delete() }
                if (cancelled) initialState() else ModelState.Failed(e.message ?: "Install failed")
            }
        )
    }

    fun delete() {
        modelFile.delete()
        refresh()
    }
}
