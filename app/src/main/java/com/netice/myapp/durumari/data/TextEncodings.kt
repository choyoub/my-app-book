package com.netice.myapp.durumari.data

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class TextEncodingOption(
    val value: String,
    val label: String,
)

internal object TextEncodings {
    const val UTF8 = "utf8"
    const val UTF8_BOM = "utf8-bom"
    const val UTF16_LE = "utf16-le"
    const val UTF16_BE = "utf16-be"
    const val EUC_KR = "euc-kr"
    const val CP949 = "cp949"
    const val NOT_APPLICABLE = "not-applicable"
    const val MIXED = "mixed"

    val selectableOptions = listOf(
        TextEncodingOption(UTF8, "UTF8"),
        TextEncodingOption(EUC_KR, "EUC-KR"),
        TextEncodingOption(CP949, "CP949"),
        TextEncodingOption(UTF16_LE, "UTF16-LE"),
        TextEncodingOption(UTF16_BE, "UTF16-BE"),
    )

    fun normalize(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)?.replace("_", "-") ?: return null
        return when (normalized) {
            "utf8", "utf-8", "utf8-bom", "utf-8-bom" -> UTF8
            "euc-kr", "euckr" -> EUC_KR
            "cp949", "ms949", "windows-949", "x-windows-949" -> CP949
            "utf16-le", "utf-16le", "utf-16-le" -> UTF16_LE
            "utf16-be", "utf-16be", "utf-16-be" -> UTF16_BE
            else -> normalized
        }
    }

    fun displayName(value: String?): String {
        return normalize(value)?.uppercase(Locale.ROOT) ?: "UTF8"
    }

    fun same(left: String?, right: String?): Boolean {
        return normalize(left) == normalize(right)
    }

    fun singleOrMixed(values: Set<String>): String {
        val normalized = values.mapNotNull { normalize(it) }.toSet()
        return when (normalized.size) {
            0 -> NOT_APPLICABLE
            1 -> normalized.first()
            else -> MIXED
        }
    }

    fun charsetFor(label: String): Charset {
        return when (normalize(label) ?: label) {
            UTF8, UTF8_BOM -> StandardCharsets.UTF_8
            UTF16_LE -> StandardCharsets.UTF_16LE
            UTF16_BE -> StandardCharsets.UTF_16BE
            EUC_KR -> Charset.forName("EUC-KR")
            CP949 -> runCatching { Charset.forName("MS949") }.getOrElse { Charset.forName("x-windows-949") }
            else -> Charset.forName(label)
        }
    }

    fun autoDetectCandidates(): List<Pair<String, Charset>> {
        return listOf(
            UTF8 to StandardCharsets.UTF_8,
            EUC_KR to charsetFor(EUC_KR),
            CP949 to charsetFor(CP949),
        )
    }

    fun bom(bytes: ByteArray): Bom? {
        return when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
                Bom(byteCount = 3, encoding = UTF8_BOM, charset = StandardCharsets.UTF_8)
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                Bom(byteCount = 2, encoding = UTF16_LE, charset = StandardCharsets.UTF_16LE)
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                Bom(byteCount = 2, encoding = UTF16_BE, charset = StandardCharsets.UTF_16BE)
            else -> null
        }
    }

    fun stripBom(bytes: ByteArray, encoding: String): ByteArray {
        val bom = bom(bytes) ?: return bytes
        return if (same(encoding, bom.encoding)) bytes.copyOfRange(bom.byteCount, bytes.size) else bytes
    }

    data class Bom(
        val byteCount: Int,
        val encoding: String,
        val charset: Charset,
    )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
