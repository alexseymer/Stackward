package dev.stackward.inference

import android.content.Context
import dev.stackward.crypto.SecurePrefs
import java.io.File

/**
 * Tracks the on-device model file path and user preferences.
 */
class ModelRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = SecurePrefs.create(appContext, PREFS_NAME)

    val modelsDirectory: File
        get() = File(appContext.filesDir, MODELS_DIR).also { it.mkdirs() }

    fun getConfiguredModelPath(): String? {
        return prefs.getString(KEY_MODEL_PATH, null)?.takeIf { File(it).exists() }
    }

    fun getConfiguredVariant(): ModelVariant? {
        val raw = prefs.getString(KEY_MODEL_VARIANT, null) ?: return null
        return runCatching { ModelVariant.valueOf(raw) }.getOrNull()
    }

    fun saveModelConfig(path: String, variant: ModelVariant) {
        prefs.edit()
            .putString(KEY_MODEL_PATH, path)
            .putString(KEY_MODEL_VARIANT, variant.name)
            .apply()
    }

    fun clearModelConfig() {
        prefs.edit()
            .remove(KEY_MODEL_PATH)
            .remove(KEY_MODEL_VARIANT)
            .apply()
    }

    fun defaultModelFile(variant: ModelVariant): File {
        return File(modelsDirectory, "gemma-${variant.name.lowercase()}.task")
    }

    fun isModelConfigured(): Boolean = getConfiguredModelPath() != null

    companion object {
        private const val PREFS_NAME = "stackward_model_config"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_MODEL_VARIANT = "model_variant"
        private const val MODELS_DIR = "models"
    }
}
