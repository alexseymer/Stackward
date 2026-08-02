package dev.stackward.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelNotLoadedException(message: String = "On-device model is not loaded") :
    Exception(message)

/**
 * Wraps MediaPipe LLM Inference for Gemma models (.task / .litertlm).
 *
 * No cloud fallback — if the model is missing or fails to load, callers must degrade.
 */
class GemmaInferenceEngine(
    private val context: Context,
) {

    @Volatile
    private var llmInference: LlmInference? = null

    @Volatile
    private var loadedPath: String? = null

    @Synchronized
    fun load(modelPath: String, maxTokens: Int = 1024) {
        require(File(modelPath).exists()) {
            "Model file not found: $modelPath"
        }
        if (loadedPath == modelPath && llmInference != null) {
            return
        }
        unload()

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setMaxTopK(40)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        loadedPath = modelPath
    }

    @Synchronized
    fun unload() {
        llmInference?.close()
        llmInference = null
        loadedPath = null
    }

    fun isLoaded(): Boolean = llmInference != null

    fun loadedModelPath(): String? = loadedPath

    suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        val engine = llmInference ?: throw ModelNotLoadedException()
        engine.generateResponse(prompt)
    }
}
