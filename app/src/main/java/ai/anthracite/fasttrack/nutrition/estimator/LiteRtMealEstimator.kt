package ai.anthracite.fasttrack.nutrition.estimator

import ai.anthracite.fasttrack.data.local.JournalCategory
import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.ItemCategory
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit
import ai.anthracite.fasttrack.nutrition.model.SizeBasis
import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.File

/** Structured result of summarizing a journal conversation on-device. */
data class JournalSummary(
    val ok: Boolean,
    val title: String,
    val summary: String,
    val categories: List<JournalCategory>,
    val mood: String?,
    val elapsedMs: Long,
    val error: String?
)

/**
 * On-device LLM estimator built on the LiteRT-LM Kotlin API (Google AI Edge).
 *
 * The concrete model is selected via [model] (see [LlmModel]); the default ships against Gemma 3
 * 1B in the `.litertlm` format. Switching models — including to a multimodal one for photo
 * logging, or FunctionGemma for structured output — is a one-line change.
 *
 * The engine is created lazily and cached (loading can take seconds, so this only ever runs off
 * the main thread). It tries the GPU backend first and transparently falls back to CPU. Until the
 * weights exist on device this reports NEEDS_MODEL and returns null, so the app falls back to the
 * heuristic estimator.
 */
class LiteRtMealEstimator(
    private val appContext: Context,
    val model: LlmModel = LlmModel.DEFAULT
) : MealEstimator {

    override val engineName: String = "LiteRT-LM · ${model.displayName}"

    /** Expected on-device model location; install target for first-run setup. */
    val modelFile: File get() = File(appContext.filesDir, "models/${model.fileName}")

    val isModelPresent: Boolean get() = modelFile.exists()

    @Volatile private var engine: Engine? = null
    // A single live conversation per chat session, kept open across turns so the model keeps
    // context in its KV cache (true multi-turn memory) instead of being re-primed cold each time.
    @Volatile private var chat: Conversation? = null
    @Volatile private var chatSession: String? = null
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastElapsedMs: Long = 0
        private set
    @Volatile var busy: Boolean = false
        private set

    private val lock = Mutex()

    override suspend fun status(): EstimatorStatus = when {
        !isModelPresent -> EstimatorStatus(
            EstimatorState.NEEDS_MODEL,
            "Set up ${model.displayName} (~${model.sizeLabel}) to enable",
            engineName
        )
        busy -> EstimatorStatus(EstimatorState.READY, "$engineName · running (${lastElapsedMs}ms so far)", engineName)
        lastError != null -> EstimatorStatus(
            EstimatorState.READY,
            "$engineName · last run failed in ${lastElapsedMs}ms: $lastError (engine reset)",
            engineName
        )
        lastElapsedMs > 0 -> EstimatorStatus(
            EstimatorState.READY,
            "$engineName · last reply ${lastElapsedMs}ms",
            engineName
        )
        else -> EstimatorStatus(EstimatorState.READY, "$engineName · ready", engineName)
    }

    private val systemInstruction = """
        You are a nutrition estimator. For the meal the user describes, reply with ONLY compact
        JSON and no other text, in exactly this shape:
        {"name": string, "calories": int, "protein_g": number, "carbs_g": number, "fat_g": number}
    """.trimIndent()

    /**
     * Function-call style structured output: turn a free-text meal into a list of itemized foods
     * and drinks. Used by [parseMeal] to drive the multi-item review UI.
     */
    private val parseInstruction = """
        You are a nutrition parsing function. Convert the meal the user describes into ONLY compact
        JSON and no other text, in exactly this shape:
        {"items":[{"name":string,"canonical_name":string,"category":"food"|"beverage"|"supplement","food_type":string,"quantity":number,"unit":"serving"|"item"|"gram"|"milliliter"|"fluid_ounce"|"ounce"|"cup","portion_amount":number,"portion_unit":string,"calories":{"estimate":int,"low":int,"high":int},"protein_g":number,"carbs_g":number,"fat_g":number,"alcohol_g":number,"is_alcoholic":boolean,"is_liquid":boolean,"confidence":"low"|"medium"|"high"}]}
        Rules:
        - Output one object per distinct food or drink.
        - "quantity" is HOW MANY units were consumed; "calories" and the macros are PER ONE unit.
        - Give a sensible low/high calorie band around each estimate.
        - Use null (not 0) when a macro is unknown. Use 0 only when the value is truly zero.
        - "canonical_name" is a lowercase_snake_case identifier (e.g. "hard_cider").
    """.trimIndent()

    /** Lazily creates (and caches) the LiteRT-LM engine. Heavy — always call off the main thread. */
    private fun engine(): Engine {
        engine?.let { return it }
        return synchronized(this) {
            engine ?: buildEngine().also { engine = it }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun buildEngine(): Engine {
        // Multi-Token Prediction (speculative decoding) — up to ~2.2x faster decode on GPU and
        // ~1.5x on CPU with no quality loss. Only enable it for models that bundle a
        // `tf_lite_mtp_drafter` section (e.g. Gemma 4); enabling it on a model without one (e.g.
        // Qwen) fails engine creation. Must be set before the engine is initialized.
        ExperimentalFlags.enableSpeculativeDecoding = model.supportsMtp

        fun create(backend: Backend, vision: Backend?) = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                visionBackend = vision,
                maxNumImages = if (vision != null) 1 else null,
                cacheDir = appContext.cacheDir.path
            )
        ).apply { initialize() }

        // Prefer GPU for speed; fall back to CPU where GPU isn't available. Vision models also
        // get an image encoder so photo logging works; if that can't load, text still does.
        if (!model.supportsVision) {
            return runCatching { create(Backend.GPU(), null) }.getOrElse { create(Backend.CPU(), null) }
        }
        val attempts = listOf<() -> Engine>(
            { create(Backend.GPU(), Backend.GPU()) },
            { create(Backend.GPU(), Backend.CPU()) },
            { create(Backend.CPU(), Backend.CPU()) }
        )
        for (attempt in attempts) {
            runCatching { attempt() }.onSuccess { visionReady = true; return it }
        }
        visionReady = false
        return create(Backend.CPU(), null)
    }

    /** True once the engine loaded with an image encoder. */
    @Volatile var visionReady: Boolean = false
        private set

    /**
     * Itemize a meal from a photo (JPEG bytes). [hint] is any text the user typed, e.g.
     * "half the plate" — it helps with portions the camera can't judge.
     */
    suspend fun parseMealPhoto(jpeg: ByteArray, hint: String): ParsedMeal {
        val prompt = buildString {
            append("Identify every food and drink in this photo and estimate realistic portions.")
            if (hint.isNotBlank()) append(" The user adds: \"$hint\".")
        }
        val result = withEngine { engine ->
            if (!visionReady) error("This model can't read photos on this device")
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(parseInstruction),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.2),
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            ).use { conversation ->
                conversation.sendMessage(
                    Contents.of(Content.ImageBytes(jpeg), Content.Text(prompt))
                ).toString()
            }
        }
        val raw = result.getOrNull().orEmpty()
        val items = parseItems(raw)
        return ParsedMeal(
            ok = items.isNotEmpty(),
            engine = engineName,
            elapsedMs = lastElapsedMs,
            items = items,
            rawPreview = raw.replace('\n', ' ').take(300),
            error = when {
                result.isFailure -> result.exceptionOrNull()?.message ?: lastError
                items.isEmpty() -> "Couldn't find any food in that photo"
                else -> null
            }
        )
    }

    /** Rewrite the computed weekly stats as a short, warm coach note. Null on failure. */
    suspend fun writeWeeklyReview(facts: String): String? {
        val sys = """
            You are FastTrack Coach writing a weekly check-in for one user. Using ONLY the facts
            given, write 4-6 short sentences in second person: open with the biggest win, name one
            honest watch-out, mention a pattern if one is listed, and end with the single focus for
            next week. No lists, no headings, no medical claims, no invented numbers.
        """.trimIndent()
        val result = withEngine { engine ->
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(sys),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.6),
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            ).use { conversation -> conversation.sendMessage(facts).toString() }
        }
        return stripRoleLabel(result.getOrNull()?.trim().orEmpty()).ifBlank { null }
    }

    /**
     * On-device coach turn. Keeps a live [Conversation] per [sessionId] so the model remembers the
     * whole back-and-forth (KV cache) across turns instead of being re-primed cold each time.
     */
    suspend fun coachReply(
        sessionId: String,
        history: List<Pair<String, String>>,
        context: String
    ): NlpProbeResult = chatTurn(
        sessionId = sessionId,
        persona = "You are FastTrack Coach, a helpful assistant for this fasting and fitness app. " +
            "Answer questions about how to use FastTrack, fasting, food, workouts, and the user's plan. " +
            "Keep replies short, practical, and encouraging. Never medical advice. Prefer one next action. " +
            "Reply in plain conversational text with NO speaker label — never begin a message with " +
            "\"Coach:\" or the user's name.\n\n$context",
        history = history,
        sampler = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.7),
        fallbackOpener = "Give me one useful tip for today."
    )

    /**
     * On-device journaling turn. A warm, reflective companion that asks gentle follow-up questions
     * and helps the user think out loud. Never diagnoses; never gives medical advice. Memory is kept
     * across the session like [coachReply].
     */
    suspend fun journalReply(
        sessionId: String,
        history: List<Pair<String, String>>,
        context: String
    ): NlpProbeResult = chatTurn(
        sessionId = sessionId,
        persona = "You are FastTrack's journaling companion. The user is writing a private journal " +
            "and thinking out loud about their health, mood, fasting, training, and life. " +
            "Listen, reflect back what you hear, and ask ONE open, caring follow-up question " +
            "to help them go deeper. Warm, brief, non-judgmental. Never diagnose or give " +
            "medical advice; if they mention crisis or self-harm, gently encourage reaching " +
            "out to a professional or hotline. Reply in plain text with NO speaker label.\n\n$context",
        history = history,
        sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
        fallbackOpener = "I'd like to start journaling. Help me begin."
    )

    /**
     * Shared chat implementation. Reuses the open conversation when [sessionId] is unchanged
     * (sending only the newest user turn — fast, and the engine keeps context). When the session
     * changes (new chat, mode switch, or after an engine reset) it rebuilds the conversation and
     * primes it with the prior transcript so context is restored.
     */
    private suspend fun chatTurn(
        sessionId: String,
        persona: String,
        history: List<Pair<String, String>>,
        sampler: SamplerConfig,
        fallbackOpener: String
    ): NlpProbeResult {
        val result = withEngine { engine ->
            val newlyCreated = sessionId != chatSession || chat == null
            if (newlyCreated) {
                chat?.close()
                chat = engine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(persona),
                        samplerConfig = sampler,
                        // Skip reasoning tokens (e.g. Qwen's <think> channel) so replies are fast and
                        // we don't leak chain-of-thought. No-op on non-thinking models.
                        thinkingConfig = ThinkingConfig(enableThinking = false)
                    )
                )
                chatSession = sessionId
            }
            val conversation = chat!!
            val latest = history.lastOrNull()
            val message = when {
                latest == null || latest.first != "user" -> fallbackOpener
                newlyCreated && history.size > 1 -> primer(history.dropLast(1)) + "\n\n" + latest.second
                else -> latest.second
            }
            conversation.sendMessage(message).toString()
        }
        val text = stripRoleLabel(result.getOrNull()?.trim().orEmpty())
        return NlpProbeResult(
            ok = result.isSuccess && text.isNotBlank(),
            engine = engineName,
            elapsedMs = lastElapsedMs,
            estimate = null,
            rawPreview = text.take(600),
            error = result.exceptionOrNull()?.message ?: lastError,
            engineReset = result.isFailure
        )
    }

    /** Condense the prior turns into a short context block when (re)opening a conversation. */
    private fun primer(prior: List<Pair<String, String>>): String = buildString {
        appendLine("Here is our conversation so far, for context:")
        prior.takeLast(12).forEach { (role, text) ->
            appendLine("${if (role == "user") "User" else "You"}: $text")
        }
        append("Now continue naturally. Reply only with your next message and no name prefix.")
    }

    /** Defensively strip a leaked speaker label (e.g. "Coach:") the model sometimes prepends. */
    private fun stripRoleLabel(raw: String): String {
        var s = raw.trim()
        val labels = listOf(
            "FastTrack Coach:", "Coach:", "Companion:", "Assistant:", "You:", "Athlete:", "Me:", "User:"
        )
        var changed = true
        while (changed) {
            changed = false
            for (label in labels) {
                if (s.startsWith(label, ignoreCase = true)) {
                    s = s.removeRange(0, label.length).trimStart()
                    changed = true
                }
            }
        }
        return s
    }

    /**
     * Summarize and categorize a finished journal conversation. Returns structured JSON mapped to
     * [JournalSummary]; on failure, [JournalSummary.ok] is false and a fallback summary is provided.
     */
    suspend fun summarizeJournal(history: List<Pair<String, String>>): JournalSummary {
        val userText = history.filter { it.first == "user" }.joinToString(" ") { it.second }
        val transcript = history.joinToString("\n") { (role, text) ->
            val who = if (role == "user") "Me" else "Companion"
            "$who: $text"
        }
        val categoryIds = JournalCategory.entries.joinToString(", ") { it.id }
        val sys = """
            You summarize a personal journal conversation. Reply with ONLY compact JSON and no other
            text, in exactly this shape:
            {"title": string, "summary": string, "mood": string, "categories": [string]}
            - "title": at most 6 words.
            - "summary": 2-3 sentences, written in the first person ("I ...").
            - "mood": a single lowercase word.
            - "categories": 1-3 items, each EXACTLY one of: $categoryIds
        """.trimIndent()
        val result = withEngine { engine ->
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(sys),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.2),
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            ).use { conversation ->
                conversation.sendMessage("Journal:\n$transcript").toString()
            }
        }
        val raw = result.getOrNull().orEmpty()
        val obj = run {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) null
            else runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
        }
        val fallbackSummary = userText.take(240).ifBlank { "Journal entry" }
        if (obj == null) {
            return JournalSummary(
                ok = false,
                title = "Journal entry",
                summary = fallbackSummary,
                categories = listOf(JournalCategory.OTHER),
                mood = null,
                elapsedMs = lastElapsedMs,
                error = result.exceptionOrNull()?.message ?: lastError ?: "Couldn't parse summary"
            )
        }
        val cats = mutableListOf<JournalCategory>()
        obj.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                cats += JournalCategory.fromId(arr.optString(i))
            }
        }
        val categories = cats.distinct().ifEmpty { listOf(JournalCategory.OTHER) }.take(3)
        return JournalSummary(
            ok = true,
            title = obj.optString("title").trim().ifBlank { "Journal entry" },
            summary = obj.optString("summary").trim().ifBlank { fallbackSummary },
            categories = categories,
            mood = obj.optString("mood").trim().ifBlank { null },
            elapsedMs = lastElapsedMs,
            error = null
        )
    }

    override suspend fun parseMeal(description: String): ParsedMeal {
        val result = withEngine { engine ->
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(parseInstruction),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.2),
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            ).use { conversation ->
                conversation.sendMessage("Meal: \"$description\"").toString()
            }
        }
        val raw = result.getOrNull().orEmpty()
        val items = parseItems(raw)
        return ParsedMeal(
            ok = items.isNotEmpty(),
            engine = engineName,
            elapsedMs = lastElapsedMs,
            items = items,
            rawPreview = raw.replace('\n', ' ').take(300),
            error = when {
                result.isFailure -> result.exceptionOrNull()?.message ?: lastError
                items.isEmpty() -> "Model replied but no items parsed"
                else -> null
            }
        )
    }

    /** Extract the `{"items":[...]}` payload from the model's raw text into [FoodItem]s. */
    private fun parseItems(raw: String?): List<FoodItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val obj = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<FoodItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cals = o.optJSONObject("calories")
            val calEstimate = cals?.optDoubleOrNull("estimate") ?: o.optDoubleOrNull("calories")
            val unit = MeasurementUnit.fromId(o.optString("unit"))
            val qty = o.optDouble("quantity", 1.0).let { if (it <= 0) 1.0 else it }
            out += MealEstimators.item(
                name = o.optString("name").ifBlank { "Item" },
                canonicalName = o.optString("canonical_name"),
                category = ItemCategory.fromId(o.optString("category")),
                foodType = o.optString("food_type"),
                quantity = qty,
                unit = unit,
                portionAmount = o.optDouble("portion_amount", 1.0).let { if (it <= 0) 1.0 else it },
                portionUnit = o.optString("portion_unit").takeIf { it.isNotBlank() }
                    ?.let { MeasurementUnit.fromId(it) } ?: unit,
                sizeBasis = SizeBasis.STANDARD_SERVING,
                calories = calEstimate,
                caloriesLow = cals?.optDoubleOrNull("low"),
                caloriesHigh = cals?.optDoubleOrNull("high"),
                proteinG = o.optDoubleOrNull("protein_g"),
                carbsG = o.optDoubleOrNull("carbs_g"),
                fatG = o.optDoubleOrNull("fat_g"),
                alcoholG = o.optDoubleOrNull("alcohol_g"),
                isAlcoholic = o.optBoolean("is_alcoholic", false),
                isLiquid = o.optBoolean("is_liquid", false),
                confidence = Confidence.fromId(o.optString("confidence")),
                source = ai.anthracite.fasttrack.nutrition.model.NutritionSource.MODEL_ESTIMATE
            )
        }
        return out
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    override suspend fun estimate(description: String): MealEstimate? = probe(description).estimate

    override suspend fun probe(description: String): NlpProbeResult {
        val result = withEngine { engine ->
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.3),
                    thinkingConfig = ThinkingConfig(enableThinking = false)
                )
            ).use { conversation ->
                conversation.sendMessage("Meal: \"$description\"").toString()
            }
        }
        val raw = result.getOrNull().orEmpty()
        val parsed = parse(raw, description)
        return NlpProbeResult(
            ok = parsed != null,
            engine = engineName,
            elapsedMs = lastElapsedMs,
            estimate = parsed,
            rawPreview = raw.replace('\n', ' ').take(240),
            error = when {
                result.isFailure -> result.exceptionOrNull()?.message ?: lastError
                parsed == null -> "Model replied but JSON didn't parse"
                else -> null
            },
            engineReset = result.isFailure
        )
    }

    /**
     * One inference at a time. On timeout or crash we close the engine so the next call
     * starts clean instead of hanging on a broken conversation.
     */
    private suspend fun <T> withEngine(block: (Engine) -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!isModelPresent) {
            return@withContext Result.failure(IllegalStateException("Model not installed"))
        }
        lock.withLock {
            busy = true
            val started = SystemClock.elapsedRealtime()
            try {
                val value = withTimeout(60_000) { block(engine()) }
                lastElapsedMs = SystemClock.elapsedRealtime() - started
                lastError = null
                Result.success(value)
            } catch (t: TimeoutCancellationException) {
                lastElapsedMs = SystemClock.elapsedRealtime() - started
                lastError = "Timed out after ${lastElapsedMs}ms — engine reset"
                close()
                Result.failure(t)
            } catch (t: Exception) {
                lastElapsedMs = SystemClock.elapsedRealtime() - started
                lastError = t.message ?: t.javaClass.simpleName
                close()
                Result.failure(t)
            } finally {
                busy = false
            }
        }
    }

    /** Extracts the first JSON object from the model's raw text and maps it to a [MealEstimate]. */
    private fun parse(raw: String?, description: String): MealEstimate? {
        if (raw.isNullOrBlank()) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = JSONObject(raw.substring(start, end + 1))
        val calories = json.optInt("calories", -1)
        if (calories < 0) return null
        return MealEstimate(
            name = json.optString("name").ifBlank { description.trim() },
            calories = calories,
            proteinG = json.optDouble("protein_g", 0.0),
            carbsG = json.optDouble("carbs_g", 0.0),
            fatG = json.optDouble("fat_g", 0.0),
            confidence = 0.8f,
            engine = engineName
        )
    }

    fun close() {
        synchronized(this) {
            chat?.close()
            chat = null
            chatSession = null
            engine?.close()
            engine = null
        }
    }
}
