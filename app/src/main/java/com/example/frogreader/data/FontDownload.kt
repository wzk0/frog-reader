package com.example.frogreader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Fetches one typeface over HTTPS and files it in the app's font library.
 *
 * The only network request FrogReader makes. It is always a direct answer to a
 * tap on a named font, never a background job, and nothing about the library,
 * the reading or the user leaves the device: the request is a GET for a public
 * font file and carries no identifier beyond a generic User-Agent.
 *
 * Streamed to a scratch file rather than into memory — the CJK typefaces worth
 * downloading are 7–26 MB — and validated by [storeFontFile]'s table check, so a
 * captive portal's HTML error page can never end up in the font list.
 *
 * Returns the stored font's path, or null when the request failed, the body was
 * not a font, or the transfer was cancelled.
 */
internal suspend fun downloadFontFile(
    cacheDir: File,
    fontsDir: File,
    url: String,
    fileName: String,
    /** Entry to unpack when the release ships a zip rather than a bare font. */
    archiveEntry: String? = null,
    maxBytes: Long = MAX_FONT_DOWNLOAD_BYTES,
    onProgress: (Float) -> Unit = {},
): String? = withContext(Dispatchers.IO) {
    val temp = File(cacheDir, "font-${System.nanoTime()}.tmp")
    val unpacked = File(cacheDir, "font-${System.nanoTime()}-entry.tmp")
    var connection: HttpURLConnection? = null
    try {
        val opened = (URL(url).openConnection() as HttpURLConnection).apply {
            // GitHub answers a release asset with a redirect to its storage host.
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "FrogReader")
            setRequestProperty("Accept", "application/octet-stream, */*")
        }
        connection = opened
        if (opened.responseCode !in 200..299) return@withContext null

        val total = opened.contentLengthLong
        opened.inputStream.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    // Closing the sheet mid-download stops the transfer here.
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > maxBytes) return@withContext null
                    output.write(buffer, 0, read)
                    onProgress(if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f)
                }
            }
        }
        if (archiveEntry == null) {
            storeFontFile(fontsDir, temp, fileName)
        } else {
            // 得意黑 is published as a zip of four formats. The TTF inside it is
            // the one worth keeping, and the archive itself is discarded.
            if (!unpackZipEntry(temp, archiveEntry, unpacked, maxBytes)) return@withContext null
            storeFontFile(fontsDir, unpacked, fileName)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // No connection, a timeout, a certificate problem: all the same answer
        // to the caller, which reports it as "couldn't download this font".
        null
    } finally {
        connection?.disconnect()
        temp.delete()
        unpacked.delete()
    }
}

/** Copies one named entry out of a zip, refusing anything larger than a font should be. */
private fun unpackZipEntry(zip: File, entryName: String, target: File, maxBytes: Long): Boolean =
    runCatching {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null && entry.name != entryName) entry = input.nextEntry
            if (entry == null || entry.isDirectory) return@runCatching false
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > maxBytes) return@runCatching false
                    output.write(buffer, 0, read)
                }
            }
            true
        }
    }.getOrDefault(false)

/** Enough for any single CJK face; stops a wrong URL from filling the disk. */
private const val MAX_FONT_DOWNLOAD_BYTES = 48L * 1024 * 1024