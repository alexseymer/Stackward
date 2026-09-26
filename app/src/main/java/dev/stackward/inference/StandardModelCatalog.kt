package dev.stackward.inference

/**
 * Curated on-device models that Stackward can download without leaving the app.
 * Files come from the public LiteRT Community Hugging Face org (no token required).
 */
object StandardModelCatalog {

    const val HUGGING_FACE_BROWSE_URL =
        "https://huggingface.co/litert-community"

    const val HUGGING_FACE_SEARCH_URL =
        "https://huggingface.co/models?other=litertlm&sort=trending"

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "gemma4-e2b-gpu",
            variant = ModelVariant.E2B,
            displayName = "Gemma 4 E2B (GPU)",
            description = "Recommended for most phones (~4 GB+ RAM).",
            fileName = "gemma-4-E2B-it-gpu.litertlm",
            downloadUrl =
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
                    "resolve/main/gemma-4-E2B-it-gpu.litertlm",
            repoPageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            approximateSizeBytes = 2_008_432_640L,
        ),
        CatalogModel(
            id = "gemma4-e4b-gpu",
            variant = ModelVariant.E4B,
            displayName = "Gemma 4 E4B (GPU)",
            description = "Higher quality on phones with ~6 GB+ RAM.",
            fileName = "gemma-4-E4B-it-gpu.litertlm",
            downloadUrl =
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/" +
                    "resolve/main/gemma-4-E4B-it-gpu.litertlm",
            repoPageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            approximateSizeBytes = 2_969_059_328L,
        ),
    )

    fun forVariant(variant: ModelVariant): CatalogModel =
        models.first { it.variant == variant }

    fun recommended(capability: DeviceCapability?): CatalogModel {
        val variant = capability?.recommendedVariant ?: ModelVariant.E2B
        return forVariant(variant)
    }
}

data class CatalogModel(
    val id: String,
    val variant: ModelVariant,
    val displayName: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val repoPageUrl: String,
    val approximateSizeBytes: Long,
) {
    val approximateSizeLabel: String
        get() {
            val gib = approximateSizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return String.format("%.1f GB", gib)
        }
}
