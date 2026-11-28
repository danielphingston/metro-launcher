package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The phone's own AI model (Gemini Nano, through Android's AICore), used by the tile editor to
 * write a tile's setup from a prompt. Nothing here starts until the AI button is tapped: ML Kit
 * isn't set up when the launcher starts (its start-up provider is removed in the manifest), and
 * the model is closed when the editor closes. Behind an interface so tests use a stand-in.
 */
internal interface TileAi {
    /** Whether the model can run here: one of [AiState]'s ready, download or unavailable states. */
    suspend fun state(): AiState

    /** Downloads the model (AICore does the download; this follows it while the editor is open). */
    fun download(): Flow<AiDownload>

    /** The model's answer to [prompt] under [system]; throws [TileAiException] when it can't. */
    suspend fun generate(system: String, prompt: String): String

    /** How many tokens the model takes in, or null when it doesn't say. */
    suspend fun tokenLimit(): Int?

    /** How many tokens [system] and [prompt] come to, or null when it can't count. */
    suspend fun countTokens(system: String, prompt: String): Int?

    /** Which model the phone gave, as the editor shows it (its name, and whether it's a preview), or null when it doesn't say. */
    suspend fun modelName(): String? = null

    fun close()
}

/** Where the on-device model stands, as the editor shows it. */
internal sealed interface AiState {
    data object Ready : AiState
    data object Downloadable : AiState
    data object Downloading : AiState
    data class Unavailable(val reason: String) : AiState
}

internal sealed interface AiDownload {
    data class Started(val bytes: Long) : AiDownload
    data class Progress(val bytes: Long) : AiDownload
    data object Done : AiDownload
    data class Failed(val reason: String) : AiDownload
}

internal class TileAiException(message: String) : Exception(message)

internal object TileAis {
    /** For tests: a stand-in model. */
    @Volatile
    var override: (() -> TileAi)? = null

    fun open(context: Context): TileAi = override?.invoke() ?: MlKitTileAi(context.applicationContext)
}

/**
 * Gemini Nano through ML Kit's GenAI Prompt API. Every call is caught: the editor shows a reason, never a crash.
 *
 * For a quick test it asks AICore for its preview model first (the fast one, as AICore's own test
 * screen offers on a phone with the AICore developer preview on). Where the phone has no preview
 * model it falls back to the stable one and says so.
 */
private class MlKitTileAi(private val context: Context) : TileAi {
    private var client: GenerativeModel? = null

    /** Which release the open client asked for: shown beside the model's name. */
    private var stage = PREVIEW

    private fun model(): GenerativeModel = client ?: open(preview = true)

    private fun open(preview: Boolean): GenerativeModel {
        // Set up on the first tap, since the manifest removes ML Kit's own start-up provider.
        MlKitContext.initializeIfNeeded(context)
        val model = if (preview) {
            runCatching {
                Generation.getClient(
                    generationConfig {
                        modelConfig = modelConfig {
                            releaseStage = ModelReleaseStage.PREVIEW
                            preference = ModelPreference.FAST
                        }
                    },
                )
            }.getOrNull()
        } else {
            null
        }
        stage = if (model != null) PREVIEW else STABLE_FALLBACK
        return (model ?: Generation.getClient()).also { client = it }
    }

    override suspend fun state(): AiState = try {
        var status = model().checkStatus()
        if (status == FeatureStatus.UNAVAILABLE && stage == PREVIEW) {
            // No preview model on this phone: try the stable one before saying it isn't offered.
            runCatching { client?.close() }
            client = null
            open(preview = false)
            status = model().checkStatus()
        }
        when (status) {
            FeatureStatus.AVAILABLE -> AiState.Ready
            FeatureStatus.DOWNLOADABLE -> AiState.Downloadable
            FeatureStatus.DOWNLOADING -> AiState.Downloading
            else -> AiState.Unavailable("this phone doesn't offer it (Gemini Nano isn't supported here)")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GenAiException) {
        AiState.Unavailable(describe(e))
    } catch (e: Throwable) {
        AiState.Unavailable("this phone doesn't offer it (${e.javaClass.simpleName})")
    }

    override suspend fun modelName(): String? {
        val name = runCatchingNotCancel { model().getBaseModelName() }?.takeIf { it.isNotBlank() } ?: "name not given"
        return "$name, $stage"
    }

    override fun download(): Flow<AiDownload> = flow {
        emitAll(
            model().download().map { s ->
                when (s) {
                    is DownloadStatus.DownloadStarted -> AiDownload.Started(s.bytesToDownload)
                    is DownloadStatus.DownloadProgress -> AiDownload.Progress(s.totalBytesDownloaded)
                    is DownloadStatus.DownloadCompleted -> AiDownload.Done
                    is DownloadStatus.DownloadFailed -> AiDownload.Failed(describe(s.e))
                    else -> AiDownload.Progress(0)
                }
            },
        )
    }.catch { e ->
        if (e is CancellationException) throw e
        emit(AiDownload.Failed(if (e is GenAiException) describe(e) else "the download didn't start (${e.javaClass.simpleName})"))
    }

    override suspend fun generate(system: String, prompt: String): String = try {
        val request = generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
            temperature = 0.1f
            maxOutputTokens = MAX_OUTPUT
        }
        model().generateContent(request).candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: throw TileAiException("the model gave no answer")
    } catch (e: CancellationException) {
        throw e
    } catch (e: TileAiException) {
        throw e
    } catch (e: GenAiException) {
        throw TileAiException(describe(e))
    } catch (e: Throwable) {
        throw TileAiException("the model failed (${e.javaClass.simpleName})")
    }

    override suspend fun tokenLimit(): Int? = runCatchingNotCancel { model().getTokenLimit() }

    override suspend fun countTokens(system: String, prompt: String): Int? = runCatchingNotCancel {
        model().countTokens(generateContentRequest(SystemInstruction(system), TextPart(prompt)) {}).totalTokens
    }

    override fun close() {
        runCatching { client?.close() }
        client = null
    }

    private suspend fun <T> runCatchingNotCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    companion object {
        const val MAX_OUTPUT = 512
        const val PREVIEW = "preview"
        const val STABLE_FALLBACK = "stable (no preview model here)"

        fun describe(e: GenAiException): String = when (e.errorCode) {
            GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED -> "this phone doesn't offer it (code ${e.errorCode})"
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "AICore needs an update from the Play Store (code ${e.errorCode})"
            GenAiException.ErrorCode.BUSY -> "the model is busy: try again in a moment"
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "Android paused it to save battery: try again later"
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "it only runs with the editor on screen"
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "not enough free space for the model"
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "the answer has too many fields for the model: try a shorter prompt"
            else -> "the model failed (code ${e.errorCode}${e.message?.let { ": " + it.take(80) }.orEmpty()})"
        }
    }
}

/**
 * Asks [ai] for a tile's setup: the format, the answer's fields (those the prompt's words name
 * first, as many as fit the model), and [want]. What comes back is only read as a setup; the
 * editor then checks it against the answer ([ApiImport.check]) before showing it.
 */
internal suspend fun askTileAi(ai: TileAi, root: Any?, url: String, want: String): ApiImport.Parsed {
    val system = "You write JSON setups for tiles that show data from a JSON API. Reply with only one JSON object and nothing else.\n\n" + ApiImport.FORMAT
    val words = ApiSuggest.words(want)
    val all = runCatching { io.github.danielphingston.metro.live.api.ApiJson.fields(root) }.getOrDefault(emptyList())
    // Fields the prompt names first, so they survive when the list is cut to fit.
    // Then the shallowest, so a long list deep in the answer doesn't push out its top-level fields.
    val ranked = all.sortedWith(
        compareBy<io.github.danielphingston.metro.live.api.ApiJson.Field>({ f -> if (words.any { w -> f.path.lowercase().contains(w.removeSuffix("s")) }) 0 else 1 })
            .thenBy { ApiImport.depth(it.path) },
    )
    val limit = ai.tokenLimit()?.let { it - LocalTileAi.OUTPUT_ROOM }
    var count = LocalTileAi.FIELDS
    var prompt: String
    while (true) {
        val fields = ranked.take(count).joinToString("\n") { f -> if (f.list) "${f.path} = list of ${f.count}" else "${f.path} = ${f.sample}" }
        prompt = "The API: $url\nIts answer has these fields (path = sample):\n$fields\n\nWhat the tile should show: ${want.ifBlank { "the most useful fields" }}"
        val tokens = if (limit == null) null else ai.countTokens(system, prompt)
        if (tokens == null || limit == null || tokens <= limit || count <= 10) break
        count /= 2
    }
    val answer = ai.generate(system, prompt)
    return ApiImport.parse(answer)
}

internal object LocalTileAi {
    /** Fields offered to the model before cutting to fit. */
    const val FIELDS = 80

    /** Tokens kept free for the model's answer. */
    const val OUTPUT_ROOM = 600
}
