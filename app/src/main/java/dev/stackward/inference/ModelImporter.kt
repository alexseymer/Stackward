package dev.stackward.inference

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class ModelImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Copies or downloads model files into app-private storage and records the active config.
 */
class ModelImporter(
    private val context: Context,
    private val modelRepository: ModelRepository,
) {

    suspend fun importFromUri(uri: Uri, variant: ModelVariant): String = withContext(Dispatchers.IO) {
        val destination = modelRepository.defaultModelFile(variant, extensionHint = uri.lastPathSegment)
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

    /**
     * Downloads a curated catalog model into app-private storage.
     * [onProgress] receives bytes downloaded and total bytes (or -1 if unknown).
     */
    suspend fun downloadCatalogModel(
        model: CatalogModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val destination = File(modelRepository.modelsDirectory, model.fileName)
        val partial = File(modelRepository.modelsDirectory, "${model.fileName}.partial")
        if (partial.exists()) {
            partial.delete()
        }

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(model.downloadUrl)
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ModelImportException(
                    "Download failed (HTTP $code). " +
                        "Open Hugging Face in a browser if the file requires accepting a license.",
                )
            }

            val total = connection.contentLengthLong.takeIf { it > 0 }
                ?: model.approximateSizeBytes
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var downloaded = 0L
                    var lastReported = -PROGRESS_REPORT_BYTES
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= PROGRESS_REPORT_BYTES || downloaded == total) {
                            onProgress(downloaded, total)
                            lastReported = downloaded
                        }
                    }
                    onProgress(downloaded, total)
                }
            }

            if (partial.length() <= 0L) {
                partial.delete()
                throw ModelImportException("Downloaded model file is empty")
            }

            if (destination.exists()) {
                destination.delete()
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }

            modelRepository.saveModelConfig(destination.absolutePath, model.variant)
            destination.absolutePath
        } catch (error: Exception) {
            partial.delete()
            if (error is ModelImportException) throw error
            throw ModelImportException(error.message ?: "Model download failed", error)
        } finally {
            connection?.disconnect()
        }
    }

    private fun openFollowingRedirects(urlString: String): HttpURLConnection {
        var current = urlString
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                // Prefer identity so Content-Length stays meaningful for progress.
                setRequestProperty("Accept-Encoding", "identity")
            }
            when (val code = connection.responseCode) {
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw ModelImportException("Redirect without Location (HTTP $code)")
                    connection.disconnect()
                    current = URL(URL(current), location).toString()
                }
                else -> return connection
            }
        }
        throw ModelImportException("Too many redirects while downloading model")
    }

    companion object {
        private const val DEFAULT_BUFFER = 256 * 1024
        private const val PROGRESS_REPORT_BYTES = 2L * 1024L * 1024L
        private const val MAX_REDIRECTS = 8
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val USER_AGENT = "Stackward/1.0 (Android; on-device model download)"
    }
}
