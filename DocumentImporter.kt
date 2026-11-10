package com.sawtiyat.app.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import java.util.zip.ZipInputStream

object DocumentImporter {
    fun read(resolver: ContentResolver, uri: Uri, displayName: String): String {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".text") ->
                resolver.openInputStream(uri)?.use { input -> decodeText(input.readBytes()) }
                    ?: error("تعذر فتح الملف النصي")

            lower.endsWith(".pdf") -> {
                PDFBoxResourceLoader.init(resolverContext(resolver))
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("تعذر فتح ملف PDF")
                PDDocument.load(bytes).use { PDFTextStripper().getText(it) }
            }

            lower.endsWith(".epub") -> readEpub(resolver, uri)
            else -> error("صيغة الملف غير مدعومة حالياً")
        }
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
        // Legacy Arabic text fallback. The original bytes are preserved; decoding is best-effort.
        return runCatching { String(bytes, java.nio.charset.Charset.forName("windows-1256")) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
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

    // PDFBox Android needs an Android Context. It is supplied by the app through initPdfBox().
    private var appContext: android.content.Context? = null
    fun initPdfBox(context: android.content.Context) {
        appContext = context.applicationContext
        PDFBoxResourceLoader.init(appContext)
    }
    private fun resolverContext(@Suppress("UNUSED_PARAMETER") resolver: ContentResolver) =
        appContext ?: error("لم تتم تهيئة قارئ PDF")

    private fun readEpub(resolver: ContentResolver, uri: Uri): String {
        val entries = mutableMapOf<String, ByteArray>()
        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
        } ?: error("تعذر فتح EPUB")

        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: error("ملف EPUB غير صالح: container.xml غير موجود")
        val opfPath = Regex("""full-path=["']([^"']+)["']""")
            .find(container)?.groupValues?.get(1) ?: error("تعذر تحديد ملف OPF")
        val opf = entries[opfPath]?.toString(Charsets.UTF_8) ?: error("ملف OPF غير موجود")
        val doc = Jsoup.parse(opf, "", org.jsoup.parser.Parser.xmlParser())
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = doc.select("manifest item")
            .associate { it.attr("id") to it.attr("href") }
        val spine = doc.select("spine itemref").map { it.attr("idref") }
        return spine.mapNotNull { id ->
            val href = manifest[id] ?: return@mapNotNull null
            val path = if (base.isBlank()) href else "$base/$href"
            entries[path]?.toString(Charsets.UTF_8)?.let { Jsoup.parse(it).text() }
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
