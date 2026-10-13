package dev.modelfit.net

import dev.modelfit.RecommendedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Robust downloader for GGUF model files with automatic SHA-256 verification and resume safety.
 */
object ModelDownloader {

    /**
     * Downloads a recommended model file to disk, verifies its cryptographic SHA-256 hash,
     * and returns the final verified [File].
     *
     * @param model The recommended model metadata.
     * @param targetDir Destination directory (e.g. context.filesDir/models).
     * @param onProgress Optional progress callback delivering (bytesDownloaded, totalBytes).
     * @return The verified local [File].
     * @throws IOException If download or checksum verification fails.
     */
    suspend fun download(
        model: RecommendedModel,
        targetDir: File,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val filename = model.download.url.substringAfterLast('/').takeIf { it.endsWith(".gguf") }
            ?: "${model.modelId.replace('/', '_')}_${model.quant}.gguf"

        val destinationFile = File(targetDir, filename)
        if (destinationFile.exists() && destinationFile.length() > 0) {
            // Already downloaded
            return@withContext destinationFile
        }

        val tmpFile = File(targetDir, "$filename.tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        val url = URL(model.download.url)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ModelFit-Android-SDK/1.0")
        }

        val totalBytes = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead: Long = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        onProgress?.invoke(totalRead, totalBytes)
                    }
                    output.flush()
                }
            }

            // Verify SHA-256 if hash provided
            val expectedHash = model.download.sha256
            if (!expectedHash.isNullOrBlank()) {
                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!computedHash.equals(expectedHash, ignoreCase = true)) {
                    tmpFile.delete()
                    throw IOException("SHA-256 mismatch: expected $expectedHash, got $computedHash")
                }
            }

            // Atomic rename to final file
            if (!tmpFile.renameTo(destinationFile)) {
                tmpFile.copyTo(destinationFile, overwrite = true)
                tmpFile.delete()
            }

            return@withContext destinationFile
        } catch (e: Exception) {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            throw IOException("Download failed for ${model.modelId}: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
