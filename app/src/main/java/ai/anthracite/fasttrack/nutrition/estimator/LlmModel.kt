package ai.anthracite.fasttrack.nutrition.estimator

/**
 * On-device LLMs available through the Google AI Edge / LiteRT-LM runtime, sized from the public
 * benchmark matrix. The food-log estimator reads one of these; switching the model is a one-line
 * change ([LlmModel.DEFAULT]) and the rest of the app is unaffected.
 *
 * Decode speeds below are rough device figures (tokens/sec) to set expectations:
 *  - Gemma 3 1B      : ~33 tk/s CPU on an S24 — best size/quality balance for text.
 *  - FunctionGemma   : ~154 tk/s — tiny, built for structured/function-call output.
 *  - Gemma 3n / 4 E2B: multimodal (text + image) — enables snap-a-photo logging, larger download.
 *  - Qwen2.5 1.5B    : strong text alternative, a touch larger than Gemma 3 1B.
 */
enum class LlmModel(
    val id: String,
    val displayName: String,
    /** Expected weights filename under filesDir/models/. */
    val fileName: String,
    val sizeMb: Int,
    /** True for vision-capable models (photo-based food logging via LiteRT-LM multimodality). */
    val supportsVision: Boolean,
    val note: String,
    /**
     * True only when the `.litertlm` bundles a `tf_lite_mtp_drafter` section. Enabling
     * Multi-Token Prediction (speculative decoding) on a model without it fails engine creation
     * ("tf_lite_mtp_drafter not found in the model"). Gemma 4 ships one; Qwen does not.
     */
    val supportsMtp: Boolean = false
) {
    GEMMA3_1B(
        id = "gemma3-1b",
        displayName = "Gemma 3 1B",
        fileName = "gemma3-1b-it.litertlm",
        sizeMb = 1005,
        supportsVision = false,
        note = "Best size/quality balance for text"
    ),
    FUNCTION_GEMMA(
        id = "function-gemma",
        displayName = "FunctionGemma",
        fileName = "function-gemma.litertlm",
        sizeMb = 289,
        supportsVision = false,
        note = "Tiny & very fast; built for structured output"
    ),
    GEMMA_3N_E2B(
        id = "gemma-3n-e2b",
        displayName = "Gemma 3n E2B",
        fileName = "gemma-3n-E2B-it.litertlm",
        sizeMb = 2965,
        supportsVision = true,
        note = "Multimodal — enables photo meal logging"
    ),
    GEMMA4_E2B(
        id = "gemma4-e2b",
        displayName = "Gemma 4 E2B",
        fileName = "gemma4-e2b-it.litertlm",
        sizeMb = 2583,
        supportsVision = true,
        note = "Newest multimodal, fast on recent phones",
        supportsMtp = true
    ),
    QWEN35_2B(
        id = "qwen3.5-2b",
        displayName = "Qwen 3.5 2B",
        // The VL (vision) build is the only Qwen 3.5 export that ships a separate tf_lite_embedder
        // section, which the LiteRT-LM Android Engine executor requires. The text-only _int8 build
        // inlines its embeddings and fails to load (embedding_lookup == nullptr). Requires runtime
        // 0.17+ (externalized embedder).
        fileName = "qwen3.5-2b-vl.litertlm",
        sizeMb = 3006,
        supportsVision = true,
        note = "Qwen 3.5 — strong text + vision, thinking-capable"
    );

    /** Human-friendly download size, e.g. "1.0 GB" or "289 MB". */
    val sizeLabel: String
        get() = if (sizeMb >= 1024) String.format(java.util.Locale.US, "%.1f GB", sizeMb / 1024.0)
        else "$sizeMb MB"

    companion object {
        /**
         * The model the app ships against today: Gemma 4 E2B — fastest on recent phones (~7s warm
         * with Multi-Token Prediction). Qwen 3.5 2B (the VL build) is available as a selectable
         * alternative; it's a stronger text/thinking model but larger and slower (~12s warm).
         */
        val DEFAULT = GEMMA4_E2B

        fun fromId(id: String?): LlmModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
