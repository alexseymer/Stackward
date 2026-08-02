package dev.stackward.inference

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Copies a user-selected model file into app-private storage.
 */
class ModelImporter(
    private val context: Context,
    private val modelRepository: ModelRepository,
) {

    suspend fun importFromUri(uri: Uri, variant: ModelVariant): String = withContext(Dispatchers.IO) {
        val destination = modelRepository.defaultModelFile(variant)
        if (destination.exists()) {
            destination.delete()
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw ModelImportException("Could not read selected model file")

        if (destination.length() <= 0L) {
            destination.delete()
            throw ModelImportException("Imported model file is empty")
        }

        modelRepository.saveModelConfig(destination.absolutePath, variant)
        destination.absolutePath
    }
}
