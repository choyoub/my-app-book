package com.netice.myapp.durumari.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.netice.myapp.durumari.model.BookKind
import com.netice.myapp.durumari.model.DocumentRecord
import java.util.Locale

class DocumentScanner(private val contentResolver: ContentResolver) {
    fun scanFolder(folderId: String, treeUri: Uri): List<DocumentRecord> {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val results = mutableListOf<DocumentRecord>()
        scanChildren(treeUri, rootDocumentId, folderId, results)
        return results.sortedByDescending { it.modifiedAt }
    }

    private fun scanChildren(
        treeUri: Uri,
        parentDocumentId: String,
        folderId: String,
        results: MutableList<DocumentRecord>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        contentResolver.query(childrenUri, DOCUMENT_COLUMNS, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex) ?: continue
                val displayName = cursor.getString(nameIndex) ?: continue
                val mimeType = cursor.getString(mimeIndex)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanChildren(treeUri, documentId, folderId, results)
                    continue
                }
                val kind = kindFromName(displayName) ?: continue
                val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                val modifiedAt = cursor.getLong(modifiedIndex).let { if (it > 0L) it else System.currentTimeMillis() }
                val stableDocumentId = stableId(sourceUri.toString())
                val contentHash = stableId("${sourceUri}|${size}|${modifiedAt}")
                results.add(
                    DocumentRecord(
                        documentId = stableDocumentId,
                        folderId = folderId,
                        sourceUri = sourceUri.toString(),
                        title = displayName.removeKnownExtension(),
                        kind = kind,
                        fileSize = size,
                        modifiedAt = modifiedAt,
                        contentHash = contentHash,
                    ),
                )
            }
        }
    }

    private fun kindFromName(name: String): BookKind? {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".txt") -> BookKind.TXT
            lower.endsWith(".epub") -> BookKind.EPUB
            lower.endsWith(".zip") -> BookKind.ZIP
            lower.endsWith(".gz") -> BookKind.GZ
            else -> null
        }
    }

    private fun String.removeKnownExtension(): String {
        return replace(KNOWN_EXTENSION_REGEX, "")
    }

    private companion object {
        private val KNOWN_EXTENSION_REGEX = Regex("(?i)\\.(txt|epub|zip|gz)$")
        private val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
