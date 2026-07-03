package com.netice.myapp.durumari.data

import android.content.ContentResolver
import android.net.Uri
import com.netice.myapp.durumari.model.BookKind
import com.netice.myapp.durumari.model.DocumentRecord
import com.netice.myapp.durumari.model.TextEncodingSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class DecodedDocument(
    val document: DocumentRecord,
    val text: String,
)

class DocumentTextLoader(private val contentResolver: ContentResolver) {
    fun load(document: DocumentRecord, forceEncoding: String? = null): DecodedDocument {
        val bytes = readBytes(Uri.parse(document.sourceUri), RAW_LIMIT_BYTES)
        return when (document.kind) {
            BookKind.TXT -> decodeTextDocument(document, bytes, forceEncoding)
            BookKind.GZ -> decodeTextDocument(document, gunzip(bytes), forceEncoding)
            BookKind.ZIP -> decodeZipDocument(document, bytes, forceEncoding)
            BookKind.EPUB -> decodeEpubDocument(document, bytes)
        }
    }

    private fun decodeTextDocument(
        document: DocumentRecord,
        bytes: ByteArray,
        forceEncoding: String?,
    ): DecodedDocument {
        val auto = decodeText(bytes, null)
        val decoded = forceEncoding?.let { decodeText(bytes, it) } ?: auto
        val source = if (TextEncodings.same(decoded.encoding, auto.encoding)) TextEncodingSource.AUTO else TextEncodingSource.MANUAL
        return DecodedDocument(
            document = document.copy(
                text = decoded.text,
                textEncoding = decoded.encoding,
                textEncodingSource = source,
                detectedTextEncoding = auto.encoding,
            ),
            text = decoded.text,
        )
    }

    private fun decodeZipDocument(
        document: DocumentRecord,
        bytes: ByteArray,
        forceEncoding: String?,
    ): DecodedDocument {
        val parts = mutableListOf<String>()
        val encodings = mutableSetOf<String>()
        val detected = mutableSetOf<String>()
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries += 1
                require(entries <= ZIP_ENTRY_LIMIT) { "압축 파일 항목이 너무 많습니다." }
                val name = entry.name.lowercase(Locale.ROOT)
                if (!isSupportedArchiveEntry(name)) continue
                val entryBytes = zip.readEntryBytes()
                if (name.endsWith(".epub")) {
                    val epub = extractEpubText(entryBytes)
                    if (epub.isNotBlank()) parts.add(epub)
                    detected.add(TextEncodings.NOT_APPLICABLE)
                    encodings.add(TextEncodings.NOT_APPLICABLE)
                } else {
                    val payload = if (name.endsWith(".gz")) gunzip(entryBytes) else entryBytes
                    val auto = decodeText(payload, null)
                    val decoded = forceEncoding?.let { decodeText(payload, it) } ?: auto
                    if (decoded.text.isNotBlank()) parts.add(decoded.text)
                    detected.add(auto.encoding)
                    encodings.add(decoded.encoding)
                }
            }
        }
        val text = parts.joinToString("\n\n")
        val detectedEncoding = TextEncodings.singleOrMixed(detected)
        val appliedEncoding = TextEncodings.singleOrMixed(encodings)
        val source = if (TextEncodings.same(appliedEncoding, detectedEncoding)) TextEncodingSource.AUTO else TextEncodingSource.MANUAL
        return DecodedDocument(
            document = document.copy(
                text = text,
                textEncoding = appliedEncoding,
                textEncodingSource = source,
                detectedTextEncoding = detectedEncoding,
            ),
            text = text,
        )
    }

    private fun decodeEpubDocument(document: DocumentRecord, bytes: ByteArray): DecodedDocument {
        val text = extractEpubText(bytes)
        return DecodedDocument(
            document = document.copy(
                text = text,
                textEncoding = TextEncodings.NOT_APPLICABLE,
                textEncodingSource = TextEncodingSource.AUTO,
                detectedTextEncoding = TextEncodings.NOT_APPLICABLE,
            ),
            text = text,
        )
    }

    private fun extractEpubText(bytes: ByteArray): String {
        val entries = mutableListOf<Pair<String, String>>()
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                count += 1
                require(count <= ZIP_ENTRY_LIMIT) { "EPUB 항목이 너무 많습니다." }
                val name = entry.name.lowercase(Locale.ROOT)
                if (!name.endsWith(".xhtml") && !name.endsWith(".html") && !name.endsWith(".htm")) continue
                val html = decodeText(zip.readEntryBytes(), TextEncodings.UTF8).text
                entries.add(entry.name to htmlToText(html))
            }
        }
        return entries.sortedBy { it.first }.joinToString("\n\n") { it.second }.trim()
    }

    private fun decodeText(bytes: ByteArray, requestedEncoding: String?): TextDecodeResult {
        val normalized = TextEncodings.normalize(requestedEncoding)
        if (normalized != null) {
            val payload = TextEncodings.stripBom(bytes, normalized)
            val charset = TextEncodings.charsetFor(normalized)
            val text = decodeWithCharset(payload, charset, strict = false)
            return TextDecodeResult(text, normalized)
        }
        TextEncodings.bom(bytes)?.let { bom ->
            return TextDecodeResult(decodeWithCharset(bytes.dropBytes(bom.byteCount), bom.charset, strict = false), bom.encoding)
        }
        for ((label, charset) in TextEncodings.autoDetectCandidates()) {
            val decoded = runCatching { decodeWithCharset(bytes, charset, strict = true) }.getOrNull()
            if (decoded != null && !decoded.contains('\uFFFD')) return TextDecodeResult(decoded, label)
        }
        return TextDecodeResult(decodeWithCharset(bytes, TextEncodings.charsetFor(TextEncodings.UTF8), strict = false), TextEncodings.UTF8)
    }

    private fun readBytes(uri: Uri, limit: Int): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: error("문서를 열 수 없습니다.")
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "문서 파일이 너무 큽니다." }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
            gzip.readEntryBytes()
        }
    }

    private fun ZipInputStream.readEntryBytes(): ByteArray {
        return readLimitedBytes(INFLATED_LIMIT_BYTES)
    }

    private fun java.io.InputStream.readEntryBytes(): ByteArray {
        return readLimitedBytes(INFLATED_LIMIT_BYTES)
    }

    private fun java.io.InputStream.readLimitedBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "압축 해제 결과가 너무 큽니다." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeWithCharset(bytes: ByteArray, charset: Charset, strict: Boolean): String {
        return if (strict) {
            try {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw error
            }
        } else {
            String(bytes, charset)
        }
    }

    private fun htmlToText(html: String): String {
        return html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun isSupportedArchiveEntry(name: String): Boolean {
        return name.endsWith(".txt") || name.endsWith(".epub") || name.endsWith(".gz")
    }

    private fun ByteArray.dropBytes(count: Int): ByteArray = copyOfRange(count.coerceAtMost(size), size)

    private data class TextDecodeResult(
        val text: String,
        val encoding: String,
    )

    private companion object {
        private const val RAW_LIMIT_BYTES = 100 * 1024 * 1024
        private const val INFLATED_LIMIT_BYTES = 500 * 1024 * 1024
        private const val ZIP_ENTRY_LIMIT = 2_000
    }
}
