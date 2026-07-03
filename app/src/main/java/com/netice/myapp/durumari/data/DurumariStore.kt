package com.netice.myapp.durumari.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.netice.myapp.durumari.model.BookKind
import com.netice.myapp.durumari.model.BookmarkRecord
import com.netice.myapp.durumari.model.DocumentRecord
import com.netice.myapp.durumari.model.FolderPermissionStatus
import com.netice.myapp.durumari.model.FolderRecord
import com.netice.myapp.durumari.model.ReadingRecord
import com.netice.myapp.durumari.model.TextEncodingSource

class DurumariStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createSchema(db)
    }

    fun initStore() {
        val db = writableDatabase
        createSchema(db)
    }

    fun listFolders(): List<FolderRecord> {
        return readableDatabase.queryList("folders", null, null, null, "createdAt ASC") {
            toFolderRecord()
        }
    }

    fun listDocuments(folderId: String? = null, includeText: Boolean = false): List<DocumentRecord> {
        val columns = if (includeText) DOCUMENT_COLUMNS_WITH_TEXT else DOCUMENT_COLUMNS_WITHOUT_TEXT
        val selection = folderId?.let { "folderId = ?" }
        val args = folderId?.let { arrayOf(it) }
        return readableDatabase.queryList("documents", columns, selection, args, "modifiedAt DESC") {
            toDocumentRecord(includeText)
        }
    }

    fun getDocument(documentId: String, includeText: Boolean = true): DocumentRecord? {
        val columns = if (includeText) DOCUMENT_COLUMNS_WITH_TEXT else DOCUMENT_COLUMNS_WITHOUT_TEXT
        return readableDatabase.query(
            "documents",
            columns,
            "documentId = ?",
            arrayOf(documentId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toDocumentRecord(includeText) else null
        }
    }

    fun listReadings(): List<ReadingRecord> {
        return readableDatabase.queryList("readings", null, null, null, "openedAt DESC") {
            toReadingRecord()
        }
    }

    fun listBookmarks(): List<BookmarkRecord> {
        return readableDatabase.queryList("bookmarks", null, null, null, "createdAt DESC") {
            toBookmarkRecord()
        }
    }

    fun upsertFolder(folder: FolderRecord) {
        writableDatabase.insertWithOnConflict("folders", null, folder.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun replaceFolderDocuments(folder: FolderRecord, documents: List<DocumentRecord>) {
        withWritableTransaction { db ->
            db.insertWithOnConflict("folders", null, folder.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            val existing = listDocuments(db, folder.folderId, includeText = false).associateBy { it.documentId }
            val incomingIds = documents.map { it.documentId }.toSet()
            documents.forEach { scanned ->
                val previous = existing[scanned.documentId]
                if (previous != null && previous.contentHash == scanned.contentHash) {
                    db.update("documents", scanned.toMetadataValues(), "documentId = ?", arrayOf(scanned.documentId))
                } else {
                    db.insertWithOnConflict("documents", null, scanned.toValues(includeText = true), SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            existing.keys.minus(incomingIds).forEach { staleId ->
                db.delete("bookmarks", "documentId = ?", arrayOf(staleId))
                db.delete("readings", "documentId = ?", arrayOf(staleId))
                db.delete("documents", "documentId = ?", arrayOf(staleId))
            }
        }
    }

    fun updateDocumentText(document: DocumentRecord) {
        writableDatabase.insertWithOnConflict("documents", null, document.toValues(includeText = true), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertReading(reading: ReadingRecord) {
        writableDatabase.insertWithOnConflict("readings", null, reading.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeReading(documentId: String) {
        withWritableTransaction { db ->
            db.delete("bookmarks", "documentId = ?", arrayOf(documentId))
            db.delete("readings", "documentId = ?", arrayOf(documentId))
        }
    }

    fun toggleBookmark(bookmark: BookmarkRecord): Boolean {
        val existing = readableDatabase.query(
            "bookmarks",
            arrayOf("bookmarkId"),
            "documentId = ? AND page = ?",
            arrayOf(bookmark.documentId, bookmark.page.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return if (existing != null) {
            writableDatabase.delete("bookmarks", "bookmarkId = ?", arrayOf(existing))
            false
        } else {
            writableDatabase.insertWithOnConflict("bookmarks", null, bookmark.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            true
        }
    }

    fun upsertBookmarks(bookmarks: List<BookmarkRecord>) {
        if (bookmarks.isEmpty()) return
        withWritableTransaction { db ->
            bookmarks.forEach { bookmark ->
                db.insertWithOnConflict("bookmarks", null, bookmark.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun removeBookmark(bookmarkId: String) {
        writableDatabase.delete("bookmarks", "bookmarkId = ?", arrayOf(bookmarkId))
    }

    fun removeFolder(folderId: String) {
        withWritableTransaction { db ->
            val ids = listDocuments(db, folderId, includeText = false).map { it.documentId }
            ids.forEach { id ->
                db.delete("bookmarks", "documentId = ?", arrayOf(id))
                db.delete("readings", "documentId = ?", arrayOf(id))
            }
            db.delete("documents", "folderId = ?", arrayOf(folderId))
            db.delete("folders", "folderId = ?", arrayOf(folderId))
        }
    }

    fun clearAllFolders() {
        withWritableTransaction { db ->
            db.delete("bookmarks", null, null)
            db.delete("readings", null, null)
            db.delete("documents", null, null)
            db.delete("folders", null, null)
        }
    }

    private fun listDocuments(db: SQLiteDatabase, folderId: String, includeText: Boolean): List<DocumentRecord> {
        val columns = if (includeText) DOCUMENT_COLUMNS_WITH_TEXT else DOCUMENT_COLUMNS_WITHOUT_TEXT
        return db.queryList("documents", columns, "folderId = ?", arrayOf(folderId), "modifiedAt DESC") {
            toDocumentRecord(includeText)
        }
    }

    private inline fun <T> withWritableTransaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private inline fun <T> SQLiteDatabase.queryList(
        table: String,
        columns: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        orderBy: String?,
        mapper: Cursor.() -> T,
    ): List<T> {
        return query(table, columns, selection, selectionArgs, null, null, orderBy).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.mapper())
            }
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS folders (
              folderId TEXT PRIMARY KEY NOT NULL,
              treeUri TEXT NOT NULL,
              displayName TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              lastSyncedAt INTEGER,
              permissionStatus TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS documents (
              documentId TEXT PRIMARY KEY NOT NULL,
              folderId TEXT NOT NULL,
              sourceUri TEXT NOT NULL,
              archiveEntryPath TEXT,
              title TEXT NOT NULL,
              kind TEXT NOT NULL,
              fileSize INTEGER NOT NULL,
              modifiedAt INTEGER NOT NULL,
              contentHash TEXT NOT NULL,
              text TEXT,
              toc TEXT,
              textEncoding TEXT,
              textEncodingSource TEXT,
              detectedTextEncoding TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS readings (
              documentId TEXT PRIMARY KEY NOT NULL,
              lastPage INTEGER NOT NULL,
              totalPages INTEGER NOT NULL,
              progress REAL NOT NULL,
              openedAt INTEGER NOT NULL,
              completed INTEGER NOT NULL,
              completedAt INTEGER,
              anchorOffset INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
              bookmarkId TEXT PRIMARY KEY NOT NULL,
              documentId TEXT NOT NULL,
              page INTEGER NOT NULL,
              totalPages INTEGER NOT NULL,
              progress REAL NOT NULL,
              preview TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              anchorOffset INTEGER
            )
            """.trimIndent(),
        )
        ensureColumn(db, "documents", "toc", "TEXT")
        ensureColumn(db, "documents", "textEncoding", "TEXT")
        ensureColumn(db, "documents", "textEncodingSource", "TEXT")
        ensureColumn(db, "documents", "detectedTextEncoding", "TEXT")
        ensureColumn(db, "readings", "anchorOffset", "INTEGER")
        ensureColumn(db, "bookmarks", "anchorOffset", "INTEGER")
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_documents_folder_modified ON documents(folderId, modifiedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_readings_opened ON readings(openedAt DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_document_page ON bookmarks(documentId, page)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_created ON bookmarks(createdAt DESC)")
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return
            }
        }
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    private fun FolderRecord.toValues(): ContentValues = ContentValues().apply {
        put("folderId", folderId)
        put("treeUri", treeUri)
        put("displayName", displayName)
        put("createdAt", createdAt)
        lastSyncedAt?.let { put("lastSyncedAt", it) } ?: putNull("lastSyncedAt")
        put("permissionStatus", permissionStatus.name)
    }

    private fun DocumentRecord.toValues(includeText: Boolean): ContentValues = ContentValues().apply {
        put("documentId", documentId)
        put("folderId", folderId)
        put("sourceUri", sourceUri)
        archiveEntryPath?.let { put("archiveEntryPath", it) } ?: putNull("archiveEntryPath")
        put("title", title)
        put("kind", kind.name)
        put("fileSize", fileSize)
        put("modifiedAt", modifiedAt)
        put("contentHash", contentHash)
        if (includeText) text?.let { put("text", it) } ?: putNull("text")
        putNull("toc")
        textEncoding?.let { put("textEncoding", it) } ?: putNull("textEncoding")
        textEncodingSource?.let { put("textEncodingSource", it.name) } ?: putNull("textEncodingSource")
        detectedTextEncoding?.let { put("detectedTextEncoding", it) } ?: putNull("detectedTextEncoding")
    }

    private fun DocumentRecord.toMetadataValues(): ContentValues = ContentValues().apply {
        put("folderId", folderId)
        put("sourceUri", sourceUri)
        archiveEntryPath?.let { put("archiveEntryPath", it) } ?: putNull("archiveEntryPath")
        put("title", title)
        put("kind", kind.name)
        put("fileSize", fileSize)
        put("modifiedAt", modifiedAt)
        put("contentHash", contentHash)
    }

    private fun ReadingRecord.toValues(): ContentValues = ContentValues().apply {
        put("documentId", documentId)
        put("lastPage", lastPage)
        put("totalPages", totalPages)
        put("progress", progress)
        put("openedAt", openedAt)
        put("completed", if (completed) 1 else 0)
        completedAt?.let { put("completedAt", it) } ?: putNull("completedAt")
        anchorOffset?.let { put("anchorOffset", it) } ?: putNull("anchorOffset")
    }

    private fun BookmarkRecord.toValues(): ContentValues = ContentValues().apply {
        put("bookmarkId", bookmarkId)
        put("documentId", documentId)
        put("page", page)
        put("totalPages", totalPages)
        put("progress", progress)
        put("preview", preview)
        put("createdAt", createdAt)
        anchorOffset?.let { put("anchorOffset", it) } ?: putNull("anchorOffset")
    }

    private fun Cursor.toFolderRecord(): FolderRecord {
        return FolderRecord(
            folderId = getStringValue("folderId"),
            treeUri = getStringValue("treeUri"),
            displayName = getStringValue("displayName"),
            createdAt = getLongValue("createdAt"),
            lastSyncedAt = getNullableLong("lastSyncedAt"),
            permissionStatus = enumValue(getStringValue("permissionStatus"), FolderPermissionStatus.REQUIRED),
        )
    }

    private fun Cursor.toDocumentRecord(includeText: Boolean): DocumentRecord {
        return DocumentRecord(
            documentId = getStringValue("documentId"),
            folderId = getStringValue("folderId"),
            sourceUri = getStringValue("sourceUri"),
            archiveEntryPath = getNullableString("archiveEntryPath"),
            title = getStringValue("title"),
            kind = enumValue(getStringValue("kind"), BookKind.TXT),
            fileSize = getLongValue("fileSize"),
            modifiedAt = getLongValue("modifiedAt"),
            contentHash = getStringValue("contentHash"),
            text = if (includeText) getNullableString("text") else null,
            toc = null,
            textEncoding = getNullableString("textEncoding"),
            textEncodingSource = getNullableString("textEncodingSource")?.let { enumValue(it, TextEncodingSource.AUTO) },
            detectedTextEncoding = getNullableString("detectedTextEncoding"),
        )
    }

    private fun Cursor.toReadingRecord(): ReadingRecord {
        return ReadingRecord(
            documentId = getStringValue("documentId"),
            lastPage = getIntValue("lastPage"),
            totalPages = getIntValue("totalPages"),
            progress = getFloatValue("progress"),
            openedAt = getLongValue("openedAt"),
            completed = getIntValue("completed") == 1,
            completedAt = getNullableLong("completedAt"),
            anchorOffset = getNullableInt("anchorOffset"),
        )
    }

    private fun Cursor.toBookmarkRecord(): BookmarkRecord {
        return BookmarkRecord(
            bookmarkId = getStringValue("bookmarkId"),
            documentId = getStringValue("documentId"),
            page = getIntValue("page"),
            totalPages = getIntValue("totalPages"),
            progress = getFloatValue("progress"),
            preview = getStringValue("preview"),
            createdAt = getLongValue("createdAt"),
            anchorOffset = getNullableInt("anchorOffset"),
        )
    }

    private fun Cursor.getStringValue(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }
    private fun Cursor.getIntValue(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.getNullableInt(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getInt(index)
    }
    private fun Cursor.getLongValue(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.getNullableLong(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }
    private fun Cursor.getFloatValue(column: String): Float = getFloat(getColumnIndexOrThrow(column))

    private inline fun <reified T : Enum<T>> enumValue(value: String, default: T): T {
        return runCatching { enumValueOf<T>(value) }.getOrDefault(default)
    }

    private companion object {
        private const val DB_NAME = "durumari.db"
        private const val DB_VERSION = 1
        private val DOCUMENT_COLUMNS_WITHOUT_TEXT = arrayOf(
            "documentId",
            "folderId",
            "sourceUri",
            "archiveEntryPath",
            "title",
            "kind",
            "fileSize",
            "modifiedAt",
            "contentHash",
            "toc",
            "textEncoding",
            "textEncodingSource",
            "detectedTextEncoding",
        )
        private val DOCUMENT_COLUMNS_WITH_TEXT = DOCUMENT_COLUMNS_WITHOUT_TEXT + "text"
    }
}
