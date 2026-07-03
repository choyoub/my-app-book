package com.netice.myapp.durumari.model

enum class BookKind {
    TXT,
    EPUB,
    ZIP,
    GZ,
}

enum class ReadingStatus {
    UNREAD,
    READING,
    COMPLETED,
}

enum class ThemeName(val displayName: String) {
    PAPER("한지"),
    LIGHT("화이트"),
    DARK("다크"),
    CHALK("칠판"),
}

enum class TextEncodingSource {
    AUTO,
    MANUAL,
}

enum class SortDirection {
    ASC,
    DESC,
    NONE,
}

data class SortConfig(
    val column: String,
    val direction: SortDirection,
)

data class ReaderSettings(
    val activeFolderId: String? = null,
    val fontFamily: String = "GowunDodum, sans-serif",
    val fontSize: Int = 18,
    val isBold: Boolean = false,
    val lineHeight: Float = 1.6f,
    val letterSpacing: Float = 0f,
    val paddingTop: Int = 40,
    val paddingBottom: Int = 0,
    val paddingLeft: Int = 20,
    val paddingRight: Int = 20,
    val paddingLinked: Boolean = true,
    val pageTurnTouch: Boolean = true,
    val pageTurnSwipe: Boolean = true,
    val pageTurnVolume: Boolean = true,
    val volumeKeyPaging: Boolean = true,
    val pageTurnFeedback: PageTurnFeedback = PageTurnFeedback.VIBRATION,
    val pageTurnStyle: PageTurnStyle = PageTurnStyle.CURL,
    val hideCompleted: Boolean = false,
    val theme: ThemeName = ThemeName.PAPER,
    val librarySort: SortConfig = SortConfig("modifiedAt", SortDirection.DESC),
    val historySort: SortConfig = SortConfig("openedAt", SortDirection.DESC),
    val bookmarksSort: SortConfig = SortConfig("createdAt", SortDirection.DESC),
)

enum class PageTurnFeedback {
    NONE,
    VIBRATION,
    SOUND,
}

enum class PageTurnStyle {
    NONE,
    CURL,
    SLIDE,
}

data class FolderRecord(
    val folderId: String,
    val treeUri: String,
    val displayName: String,
    val createdAt: Long,
    val lastSyncedAt: Long? = null,
    val permissionStatus: FolderPermissionStatus,
)

enum class FolderPermissionStatus {
    GRANTED,
    REQUIRED,
    FAILED,
}

data class TocEntry(
    val label: String,
    val href: String,
    val charOffset: Int,
)

data class DocumentRecord(
    val documentId: String,
    val folderId: String,
    val sourceUri: String,
    val archiveEntryPath: String? = null,
    val title: String,
    val kind: BookKind,
    val fileSize: Long,
    val modifiedAt: Long,
    val contentHash: String,
    val text: String? = null,
    val toc: List<TocEntry>? = null,
    val textEncoding: String? = null,
    val textEncodingSource: TextEncodingSource? = null,
    val detectedTextEncoding: String? = null,
)

data class ReadingRecord(
    val documentId: String,
    val lastPage: Int,
    val totalPages: Int,
    val progress: Float,
    val openedAt: Long,
    val completed: Boolean,
    val completedAt: Long? = null,
    val anchorOffset: Int? = null,
)

data class BookmarkRecord(
    val bookmarkId: String,
    val documentId: String,
    val page: Int,
    val totalPages: Int,
    val progress: Float,
    val preview: String,
    val createdAt: Long,
    val anchorOffset: Int? = null,
)

data class LibraryRow(
    val document: DocumentRecord,
    val folderName: String,
    val reading: ReadingRecord? = null,
)

data class ViewerOpenTarget(
    val bookmarkId: String,
)

object DurumariDefaults {
    fun readerSettings(): ReaderSettings = ReaderSettings()
}

fun readingStatus(reading: ReadingRecord?): ReadingStatus {
    if (reading == null) return ReadingStatus.UNREAD
    if (reading.completed) return ReadingStatus.COMPLETED
    if (reading.lastPage >= reading.totalPages && reading.totalPages > 1) return ReadingStatus.COMPLETED
    if (reading.lastPage <= 1) return ReadingStatus.UNREAD
    return ReadingStatus.READING
}
