package dev.stackward.inference

import dev.stackward.logs.LogTruncate
import dev.stackward.permissions.ActionProposal

data class SummarizationResult(
    val summary: String,
    val proposals: List<ActionProposal>,
    val usedOnDeviceModel: Boolean,
    val unavailableReason: String? = null,
) {
    companion object {
        fun unavailable(reason: String) = SummarizationResult(
            summary = "",
            proposals = emptyList(),
            usedOnDeviceModel = false,
            unavailableReason = reason,
        )
    }
}

/**
 * Runs on-device summarization over fetched logs. Never calls a cloud API.
 */
class LogSummarizer(
    private val engine: GemmaInferenceEngine,
    private val modelRepository: ModelRepository,
) {

    suspend fun summarize(
        logs: String,
        userQuestion: String? = null,
    ): SummarizationResult {
        val modelPath = modelRepository.getConfiguredModelPath()
            ?: return SummarizationResult.unavailable(
                "No on-device model configured. Import a Gemma .task or .litertlm file first.",
            )

        return try {
            if (!engine.isLoaded()) {
                engine.load(modelPath)
            }

            val (inputLogs, _) = LogTruncate.truncate(logs, maxChars = 12_000)
            val prompt = PromptBuilder.buildLogSummaryPrompt(inputLogs, userQuestion)
            val rawOutput = engine.generate(prompt)
            val proposals = ActionProposalParser.parse(rawOutput)

            SummarizationResult(
                summary = stripProposalJson(rawOutput).ifBlank { rawOutput.trim() },
                proposals = proposals,
                usedOnDeviceModel = true,
            )
        } catch (error: ModelNotLoadedException) {
            SummarizationResult.unavailable(error.message ?: "Model not loaded")
        } catch (error: Exception) {
            SummarizationResult.unavailable(
                error.message ?: "On-device inference failed",
            )
        }
    }

    private fun stripProposalJson(text: String): String {
        return text.replace(Regex("```(?:json)?\\s*[\\s\\S]*?```", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
