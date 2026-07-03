package com.netice.myapp.durumari.ui

import android.graphics.Color
import com.netice.myapp.durumari.model.ThemeName

data class ThemeTokens(
    val name: ThemeName,
    val bg: Int,
    val outer: Int,
    val card: Int,
    val statusBar: Int,
    val navigationBar: Int,
    val darkStatusBarIcons: Boolean,
    val darkNavigationBarIcons: Boolean,
    val text: Int,
    val secondary: Int,
    val border: Int,
    val accent: Int,
    val accentText: Int,
    val accentForeground: Int,
    val danger: Int,
    val unread: Int,
    val reading: Int,
    val completed: Int,
)

object DurumariThemes {
    val paper = ThemeTokens(
        name = ThemeName.PAPER,
        bg = cssColor("#f2ead3"),
        outer = cssColor("#cfbe90"),
        card = cssColor("#eae0c4"),
        statusBar = cssColor("#cfbe90"),
        navigationBar = cssColor("#cfbe90"),
        darkStatusBarIcons = true,
        darkNavigationBarIcons = true,
        text = cssColor("#2a2a2a"),
        secondary = cssColor("#2a2a2a80"),
        border = cssColor("#d5c5a0"),
        accent = cssColor("#9a5a10"),
        accentText = cssColor("#9a5a10"),
        accentForeground = cssColor("#FFFFFF"),
        danger = cssColor("#B3342D"),
        unread = cssColor("#2a2a2a80"),
        reading = cssColor("#9a5a10"),
        completed = cssColor("#476B3C"),
    )

    val light = ThemeTokens(
        name = ThemeName.LIGHT,
        bg = cssColor("#f8f4ed"),
        outer = cssColor("#e2dbcc"),
        card = cssColor("#FFFFFF"),
        statusBar = cssColor("#e2dbcc"),
        navigationBar = cssColor("#e2dbcc"),
        darkStatusBarIcons = true,
        darkNavigationBarIcons = true,
        text = cssColor("#1a1a2e"),
        secondary = cssColor("#1a1a2e80"),
        border = cssColor("#e0d8c8"),
        accent = cssColor("#2563eb"),
        accentText = cssColor("#2563eb"),
        accentForeground = cssColor("#FFFFFF"),
        danger = cssColor("#B3261E"),
        unread = cssColor("#1a1a2e80"),
        reading = cssColor("#2563eb"),
        completed = cssColor("#217A3C"),
    )

    val dark = ThemeTokens(
        name = ThemeName.DARK,
        bg = cssColor("#121212"),
        outer = cssColor("#090909"),
        card = cssColor("#1e1e1e"),
        statusBar = cssColor("#090909"),
        navigationBar = cssColor("#090909"),
        darkStatusBarIcons = false,
        darkNavigationBarIcons = false,
        text = cssColor("#e0e0e0"),
        secondary = cssColor("#e0e0e080"),
        border = cssColor("#2d2d2d"),
        accent = cssColor("#8ab4f8"),
        accentText = cssColor("#8ab4f8"),
        accentForeground = cssColor("#121212"),
        danger = cssColor("#FFB4AB"),
        unread = cssColor("#e0e0e080"),
        reading = cssColor("#8ab4f8"),
        completed = cssColor("#72C48A"),
    )

    val chalk = ThemeTokens(
        name = ThemeName.CHALK,
        bg = cssColor("#183b32"),
        outer = cssColor("#0d241f"),
        card = cssColor("#21483e"),
        statusBar = cssColor("#0d241f"),
        navigationBar = cssColor("#0d241f"),
        darkStatusBarIcons = false,
        darkNavigationBarIcons = false,
        text = cssColor("#f1ead0"),
        secondary = cssColor("#f1ead094"),
        border = cssColor("#3b665b"),
        accent = cssColor("#f3c969"),
        accentText = cssColor("#f3c969"),
        accentForeground = cssColor("#183b32"),
        danger = cssColor("#F1A6A6"),
        unread = cssColor("#f1ead094"),
        reading = cssColor("#f3c969"),
        completed = cssColor("#B7D7A8"),
    )

    val all: List<ThemeTokens> = listOf(paper, light, dark, chalk)

    fun tokens(name: ThemeName): ThemeTokens = when (name) {
        ThemeName.PAPER -> paper
        ThemeName.LIGHT -> light
        ThemeName.DARK -> dark
        ThemeName.CHALK -> chalk
    }
}

private fun cssColor(value: String): Int {
    val hex = value.removePrefix("#")
    return when (hex.length) {
        6 -> Color.rgb(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
        )
        8 -> Color.argb(
            hex.substring(6, 8).toInt(16),
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
        )
        else -> Color.TRANSPARENT
    }
}
