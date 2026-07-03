package com.netice.myapp.durumari.data

import android.content.Context
import android.content.SharedPreferences
import com.netice.myapp.durumari.model.DurumariDefaults
import com.netice.myapp.durumari.model.ReaderSettings
import com.netice.myapp.durumari.model.SortConfig

class LocalSettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ReaderSettings {
        val defaults = DurumariDefaults.readerSettings()
        return ReaderSettings(
            activeFolderId = prefs.getStringOrNull(KEY_ACTIVE_FOLDER_ID) ?: defaults.activeFolderId,
            fontFamily = prefs.getString(KEY_FONT_FAMILY, defaults.fontFamily) ?: defaults.fontFamily,
            fontSize = prefs.getInt(KEY_FONT_SIZE, defaults.fontSize),
            isBold = prefs.getBoolean(KEY_IS_BOLD, defaults.isBold),
            lineHeight = prefs.getFloat(KEY_LINE_HEIGHT, defaults.lineHeight),
            letterSpacing = prefs.getFloat(KEY_LETTER_SPACING, defaults.letterSpacing),
            paddingTop = prefs.getInt(KEY_PADDING_TOP, defaults.paddingTop),
            paddingBottom = prefs.getInt(KEY_PADDING_BOTTOM, defaults.paddingBottom),
            paddingLeft = prefs.getInt(KEY_PADDING_LEFT, defaults.paddingLeft),
            paddingRight = prefs.getInt(KEY_PADDING_RIGHT, defaults.paddingRight),
            paddingLinked = prefs.getBoolean(KEY_PADDING_LINKED, defaults.paddingLinked),
            pageTurnTouch = prefs.getBoolean(KEY_PAGE_TURN_TOUCH, defaults.pageTurnTouch),
            pageTurnSwipe = prefs.getBoolean(KEY_PAGE_TURN_SWIPE, defaults.pageTurnSwipe),
            pageTurnVolume = prefs.getBoolean(KEY_PAGE_TURN_VOLUME, defaults.pageTurnVolume),
            volumeKeyPaging = prefs.getBoolean(KEY_VOLUME_KEY_PAGING, defaults.volumeKeyPaging),
            pageTurnFeedback = prefs.enumValue(KEY_PAGE_TURN_FEEDBACK, defaults.pageTurnFeedback),
            pageTurnStyle = prefs.enumValue(KEY_PAGE_TURN_STYLE, defaults.pageTurnStyle),
            hideCompleted = prefs.getBoolean(KEY_HIDE_COMPLETED, defaults.hideCompleted),
            theme = prefs.enumValue(KEY_THEME, defaults.theme),
            librarySort = prefs.sortConfig(KEY_LIBRARY_SORT_COLUMN, KEY_LIBRARY_SORT_DIRECTION, defaults.librarySort),
            historySort = prefs.sortConfig(KEY_HISTORY_SORT_COLUMN, KEY_HISTORY_SORT_DIRECTION, defaults.historySort),
            bookmarksSort = prefs.sortConfig(KEY_BOOKMARKS_SORT_COLUMN, KEY_BOOKMARKS_SORT_DIRECTION, defaults.bookmarksSort),
        )
    }

    fun save(settings: ReaderSettings) {
        prefs.edit()
            .putStringOrRemove(KEY_ACTIVE_FOLDER_ID, settings.activeFolderId)
            .putString(KEY_FONT_FAMILY, settings.fontFamily)
            .putInt(KEY_FONT_SIZE, settings.fontSize)
            .putBoolean(KEY_IS_BOLD, settings.isBold)
            .putFloat(KEY_LINE_HEIGHT, settings.lineHeight)
            .putFloat(KEY_LETTER_SPACING, settings.letterSpacing)
            .putInt(KEY_PADDING_TOP, settings.paddingTop)
            .putInt(KEY_PADDING_BOTTOM, settings.paddingBottom)
            .putInt(KEY_PADDING_LEFT, settings.paddingLeft)
            .putInt(KEY_PADDING_RIGHT, settings.paddingRight)
            .putBoolean(KEY_PADDING_LINKED, settings.paddingLinked)
            .putBoolean(KEY_PAGE_TURN_TOUCH, settings.pageTurnTouch)
            .putBoolean(KEY_PAGE_TURN_SWIPE, settings.pageTurnSwipe)
            .putBoolean(KEY_PAGE_TURN_VOLUME, settings.pageTurnVolume)
            .putBoolean(KEY_VOLUME_KEY_PAGING, settings.volumeKeyPaging)
            .putString(KEY_PAGE_TURN_FEEDBACK, settings.pageTurnFeedback.name)
            .putString(KEY_PAGE_TURN_STYLE, settings.pageTurnStyle.name)
            .putBoolean(KEY_HIDE_COMPLETED, settings.hideCompleted)
            .putString(KEY_THEME, settings.theme.name)
            .putSortConfig(KEY_LIBRARY_SORT_COLUMN, KEY_LIBRARY_SORT_DIRECTION, settings.librarySort)
            .putSortConfig(KEY_HISTORY_SORT_COLUMN, KEY_HISTORY_SORT_DIRECTION, settings.historySort)
            .putSortConfig(KEY_BOOKMARKS_SORT_COLUMN, KEY_BOOKMARKS_SORT_DIRECTION, settings.bookmarksSort)
            .apply()
    }

    fun loadResumeDocumentId(): String? {
        return prefs.getStringOrNull(KEY_RESUME_DOCUMENT_ID)
    }

    fun saveResumeDocumentId(documentId: String?) {
        prefs.edit()
            .putStringOrRemove(KEY_RESUME_DOCUMENT_ID, documentId)
            .apply()
    }

    private fun SharedPreferences.getStringOrNull(key: String): String? {
        return if (contains(key)) getString(key, null) else null
    }

    private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(key: String, default: T): T {
        return getString(key, null)?.let { value ->
            runCatching { enumValueOf<T>(value) }.getOrNull()
        } ?: default
    }

    private fun SharedPreferences.sortConfig(
        columnKey: String,
        directionKey: String,
        default: SortConfig,
    ): SortConfig {
        return SortConfig(
            column = getString(columnKey, default.column) ?: default.column,
            direction = enumValue(directionKey, default.direction),
        )
    }

    private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?): SharedPreferences.Editor {
        return if (value == null) remove(key) else putString(key, value)
    }

    private fun SharedPreferences.Editor.putSortConfig(
        columnKey: String,
        directionKey: String,
        value: SortConfig,
    ): SharedPreferences.Editor {
        return putString(columnKey, value.column).putString(directionKey, value.direction.name)
    }

    private companion object {
        private const val PREFS_NAME = "durumari.settings"
        private const val KEY_ACTIVE_FOLDER_ID = "activeFolderId"
        private const val KEY_FONT_FAMILY = "fontFamily"
        private const val KEY_FONT_SIZE = "fontSize"
        private const val KEY_IS_BOLD = "isBold"
        private const val KEY_LINE_HEIGHT = "lineHeight"
        private const val KEY_LETTER_SPACING = "letterSpacing"
        private const val KEY_PADDING_TOP = "paddingTop"
        private const val KEY_PADDING_BOTTOM = "paddingBottom"
        private const val KEY_PADDING_LEFT = "paddingLeft"
        private const val KEY_PADDING_RIGHT = "paddingRight"
        private const val KEY_PADDING_LINKED = "paddingLinked"
        private const val KEY_PAGE_TURN_TOUCH = "pageTurnTouch"
        private const val KEY_PAGE_TURN_SWIPE = "pageTurnSwipe"
        private const val KEY_PAGE_TURN_VOLUME = "pageTurnVolume"
        private const val KEY_VOLUME_KEY_PAGING = "volumeKeyPaging"
        private const val KEY_PAGE_TURN_FEEDBACK = "pageTurnFeedback"
        private const val KEY_PAGE_TURN_STYLE = "pageTurnStyle"
        private const val KEY_HIDE_COMPLETED = "hideCompleted"
        private const val KEY_THEME = "theme"
        private const val KEY_LIBRARY_SORT_COLUMN = "librarySort.column"
        private const val KEY_LIBRARY_SORT_DIRECTION = "librarySort.direction"
        private const val KEY_HISTORY_SORT_COLUMN = "historySort.column"
        private const val KEY_HISTORY_SORT_DIRECTION = "historySort.direction"
        private const val KEY_BOOKMARKS_SORT_COLUMN = "bookmarksSort.column"
        private const val KEY_BOOKMARKS_SORT_DIRECTION = "bookmarksSort.direction"
        private const val KEY_RESUME_DOCUMENT_ID = "resume.documentId"
    }
}
