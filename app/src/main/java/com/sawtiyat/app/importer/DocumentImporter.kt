package com.sawtiyat.app.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

object DocumentImporter {
    private const val MAX_TEXT_FILE_BYTES = 10L * 1024 * 1024
    private const val MAX_PDF_FILE_BYTES = 50L * 1024 * 1024
    private const val MAX_EPUB_ARCHIVE_BYTES = 100L * 1024 * 1024
    private const val MAX_EPUB_ENTRY_BYTES = 10L * 1024 * 1024
    private const val MAX_EPUB_TOTAL_UNCOMPRESSED_BYTES = 50L * 1024 * 1024
    private const val MAX_EPUB_ENTRIES = 2_048
    private const val MAX_EPUB_PATH_LENGTH = 4_096
    private const val MAX_EXTRACTED_TEXT_CHARS = 2_000_000
    private const val TRUNCATION_NOTICE = "\n\n[تم اقتطاع النص لبلوغه الحد الآمن]"

    @Volatile
    private var initialized = false

    @Synchronized
    fun initPdfBox(context: android.content.Context) {
        if (!initialized) {
            // Mark initialized only after PDFBox has completed initialization successfully.
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    fun read(resolver: ContentResolver, uri: Uri, displayName: String): String {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".text") -> {
                val bytes = resolver.openInputStream(uri)?.use {
                    readBounded(it, MAX_TEXT_FILE_BYTES, "الملف النصي أكبر من الحد المسموح (10 ميغابايت)")
                } ?: error("تعذر فتح الملف النصي")
                decodeText(bytes)
            }
            lower.endsWith(".pdf") -> {
                val bytes = resolver.openInputStream(uri)?.use {
                    readBounded(it, MAX_PDF_FILE_BYTES, "ملف PDF أكبر من الحد المسموح (50 ميغابايت)")
                } ?: error("تعذر فتح ملف PDF")
                PDDocument.load(bytes).use { document ->
                    val writer = LimitedTextWriter(MAX_EXTRACTED_TEXT_CHARS)
                    PDFTextStripper().writeText(document, writer)
                    val text = writer.result()
                    if (text.isBlank()) {
                        error("لم يُعثر على نص قابل للاستخراج في ملف PDF؛ قد يكون ممسوحاً ضوئياً أو فارغاً")
                    }
                    text
                }
            }
            lower.endsWith(".epub") -> readEpub(resolver, uri)
            else -> error("صيغة الملف غير مدعومة حالياً")
        }
    }

    private fun readBounded(input: InputStream, limit: Long, limitMessage: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64L * 1024L).toInt())
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) error(limitMessage)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching {
            String(bytes, java.nio.charset.Charset.forName("windows-1256"))
        }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "كتاب جديد"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "كتاب جديد"
    }

    private fun readEpub(resolver: ContentResolver, uri: Uri): String {
        val entries = mutableMapOf<String, ByteArray>()
        var totalUncompressed = 0L
        resolver.openInputStream(uri)?.use { source ->
            ZipInputStream(LimitedInputStream(source, MAX_EPUB_ARCHIVE_BYTES)).use { zip ->
                var entryCount = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_EPUB_ENTRIES) error("ملف EPUB يحتوي على عدد كبير من العناصر")
                    if (entry.name.length > MAX_EPUB_PATH_LENGTH) error("مسار عنصر EPUB طويل جداً")
                    val safePath = normalizeArchivePath(entry.name)
                        ?: error("ملف EPUB يحتوي على مسار غير آمن")
                    val output = if (entry.isDirectory) null else ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        entryBytes += count
                        totalUncompressed += count
                        if (entryBytes > MAX_EPUB_ENTRY_BYTES ||
                            totalUncompressed > MAX_EPUB_TOTAL_UNCOMPRESSED_BYTES) {
                            error("محتوى EPUB بعد فك الضغط أكبر من الحد الآمن")
                        }
                        output?.write(buffer, 0, count)
                    }
                    if (output != null) {
                        if (entries.put(safePath, output.toByteArray()) != null) {
                            error("ملف EPUB يحتوي على مسارات مكررة بعد توحيدها")
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("تعذر فتح EPUB")

        val containerBytes = entries["META-INF/container.xml"]
            ?: error("ملف EPUB غير صالح: container.xml غير موجود")
        val container = containerBytes.toString(Charsets.UTF_8)
        val rawOpfPath = Regex("""full-path=[\"']([^\"']+)[\"']""")
            .find(container)?.groupValues?.get(1) ?: error("تعذر تحديد ملف OPF")
        val opfPath = normalizeArchivePath(Uri.decode(rawOpfPath))
            ?: error("ملف EPUB يحتوي على مسار OPF غير آمن")
        val opf = entries[opfPath]?.toString(Charsets.UTF_8) ?: error("ملف OPF غير موجود")
        val doc = Jsoup.parse(opf, "", org.jsoup.parser.Parser.xmlParser())
        val manifest = doc.select("manifest item").associate { it.attr("id") to it.attr("href") }
        val spine = doc.select("spine itemref").map { it.attr("idref") }
        val text = StringBuilder()
        var truncated = false
        for (id in spine) {
            val href = manifest[id] ?: continue
            val path = resolveEpubPath(opfPath, href) ?: continue
            val html = entries[path]?.toString(Charsets.UTF_8) ?: continue
            val chapterText = Jsoup.parse(html).text().trim()
            if (chapterText.isEmpty()) continue
            val separator = if (text.isEmpty()) "" else "\n\n"
            val remaining = MAX_EXTRACTED_TEXT_CHARS - text.length
            if (remaining <= separator.length) {
                truncated = true
                break
            }
            text.append(separator)
            val chapterRoom = MAX_EXTRACTED_TEXT_CHARS - text.length
            if (chapterText.length > chapterRoom) {
                text.append(chapterText, 0, chapterRoom)
                truncated = true
                break
            }
            text.append(chapterText)
        }
        if (text.isBlank()) {
            error("لم يُعثر على نص قابل للاستخراج في EPUB؛ تحقق من محتواه أو من سلامة ملفه")
        }
        if (truncated) text.append(TRUNCATION_NOTICE)
        return text.toString()
    }

    /** Normalize a ZIP path and reject absolute paths or traversal above the archive root. */
    private fun normalizeArchivePath(path: String): String? {
        val unified = path.replace('\\', '/')
        if (unified.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(unified)) return null
        val parts = mutableListOf<String>()
        for (part in unified.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }

    /** Resolve an EPUB href relative to the OPF directory, tolerating dot segments and escaped names. */
    private fun resolveEpubPath(opfPath: String, href: String): String? {
        if (href.isBlank()) return null
        val reference = Uri.parse(href)
        if (!reference.scheme.isNullOrBlank() || !reference.authority.isNullOrBlank()) return null
        val relativePath = reference.path ?: return null // Uri.path excludes query and fragment.
        if (relativePath.startsWith('/') || relativePath.startsWith('\\')) return null
        val base = opfPath.substringBeforeLast('/', "")
        val combined = if (base.isEmpty()) relativePath else "$base/$relativePath"
        return normalizeArchivePath(combined)
    }

    private class LimitedTextWriter(private val limit: Int) : Writer() {
        private val text = StringBuilder(minOf(limit, 64 * 1024))
        private var truncated = false

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (length <= 0) return
            val available = limit - text.length
            val accepted = minOf(available, length)
            if (accepted > 0) text.append(buffer, offset, accepted)
            if (accepted < length) truncated = true
        }

        override fun write(value: String, offset: Int, length: Int) {
            if (length <= 0) return
            val available = limit - text.length
            val accepted = minOf(available, length)
            if (accepted > 0) text.append(value, offset, offset + accepted)
            if (accepted < length) truncated = true
        }

        override fun write(value: Int) {
            if (text.length < limit) text.append(value.toChar()) else truncated = true
        }

        override fun flush() = Unit
        override fun close() = Unit

        fun result(): String = text.toString() + if (truncated) TRUNCATION_NOTICE else ""
    }

    /** Caps bytes read from the compressed EPUB stream, including ZIP headers. */
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            if (count >= limit) {
                val extra = `in`.read()
                if (extra < 0) return -1
                throw IOException("EPUB archive exceeds the compressed-size limit")
            }
            val value = `in`.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (count >= limit) {
                val extra = `in`.read()
                if (extra < 0) return -1
                throw IOException("EPUB archive exceeds the compressed-size limit")
            }
            val allowed = minOf(length.toLong(), limit - count).toInt()
            val read = `in`.read(buffer, offset, allowed)
            if (read > 0) count += read
            return read
        }
    }
}
