package com.netice.myapp.durumari

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.netice.myapp.durumari.audio.SyntheticSoundPlayer
import com.netice.myapp.durumari.audio.UiSoundKind
import com.netice.myapp.durumari.data.DecodedDocument
import com.netice.myapp.durumari.data.DocumentScanner
import com.netice.myapp.durumari.data.DocumentTextLoader
import com.netice.myapp.durumari.data.DurumariStore
import com.netice.myapp.durumari.data.LocalSettingsStore
import com.netice.myapp.durumari.data.TextEncodings
import com.netice.myapp.durumari.data.stableId
import com.netice.myapp.durumari.model.BookKind
import com.netice.myapp.durumari.model.BookmarkRecord
import com.netice.myapp.durumari.model.DocumentRecord
import com.netice.myapp.durumari.model.DurumariDefaults
import com.netice.myapp.durumari.model.FolderPermissionStatus
import com.netice.myapp.durumari.model.FolderRecord
import com.netice.myapp.durumari.model.PageTurnFeedback
import com.netice.myapp.durumari.model.PageTurnStyle
import com.netice.myapp.durumari.model.ReaderSettings
import com.netice.myapp.durumari.model.ReadingRecord
import com.netice.myapp.durumari.model.SortConfig
import com.netice.myapp.durumari.model.SortDirection
import com.netice.myapp.durumari.model.readingStatus
import com.netice.myapp.durumari.model.ThemeName
import com.netice.myapp.durumari.ui.DurumariThemes
import com.netice.myapp.durumari.ui.DurumariRootLayer
import com.netice.myapp.durumari.ui.ReaderCanvasView
import com.netice.myapp.durumari.ui.ScrollArtworkView
import com.netice.myapp.durumari.ui.ThemeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class MainTab(val label: String, val searchHint: String) {
        LIBRARY("목록", "현재 폴더 제목 검색"),
        HISTORY("열람록", "열람록 제목 검색"),
        BOOKMARKS("책갈피", "책갈피 제목 검색"),
    }

    private enum class SliderTouchMode {
        NONE,
        TRACK,
        MARKER,
    }

    private enum class UiFeedbackKind {
        OPEN,
        CLOSE,
        PRESS,
    }

    private data class MainSortOption(
        val label: String,
        val column: String,
    )

    private data class IntroChrome(
        val background: Int,
        val title: Int,
        val subtitle: Int,
        val status: Int,
        val progress: Int,
        val progressTrack: Int,
        val titleShadow: Int,
        val darkStatusBarIcons: Boolean,
        val darkNavigationBarIcons: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val soundPlayer = SyntheticSoundPlayer()
    private var scrollAnimator: ValueAnimator? = null
    private var settings: ReaderSettings = DurumariDefaults.readerSettings()
    private lateinit var settingsStore: LocalSettingsStore
    private lateinit var appStore: DurumariStore
    private lateinit var documentScanner: DocumentScanner
    private lateinit var documentTextLoader: DocumentTextLoader
    private var activeTab: MainTab = MainTab.LIBRARY
    private var rootLayer: DurumariRootLayer? = null
    private var safeTopInset: Int = 0
    private var safeBottomInset: Int = 0
    private lateinit var appTypeface: Typeface
    @Volatile
    private var activityDestroyed: Boolean = false
    private val readerTypefaceCache = object : LinkedHashMap<String, Typeface>(MAX_READER_TYPEFACE_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean {
            return size > MAX_READER_TYPEFACE_CACHE
        }
    }
    private var folders: List<FolderRecord> = emptyList()
    private var foldersById: Map<String, FolderRecord> = emptyMap()
    private var documents: List<DocumentRecord> = emptyList()
    private var documentsById: Map<String, DocumentRecord> = emptyMap()
    private var readingsById: Map<String, ReadingRecord> = emptyMap()
    private var bookmarks: List<BookmarkRecord> = emptyList()
    private var bookmarkDocumentIds: Set<String> = emptySet()
    private var activeDocument: DocumentRecord? = null
    private var activePages: List<ReaderCanvasView.PageSlice> = emptyList()
    private var activePaginationWidthPx: Int = 0
    private var activePaginationHeightPx: Int = 0
    private var paginationRequestId: Long = 0L
    private var activePageIndex: Int = 0
    private var activeEncoding: String? = null
    private var activeDocumentText: String? = null
    private var activeReaderCanvas: ReaderCanvasView? = null
    private var pendingViewerAnchorOffset: Int? = null
    private var lastMainSwipeAt: Long = 0L
    private var mainTabAnimating: Boolean = false
    private var mainSwipeHost: FrameLayout? = null
    private var mainSwipeCurrentView: View? = null
    private var searchQuery: String = ""
    private var lastWheelTurnAt: Long = 0L
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private data class ReaderFontOption(val family: String, val label: String, val assetPath: String)
    private data class PaginationResult(
        val pages: List<ReaderCanvasView.PageSlice>,
        val widthPx: Int,
        val heightPx: Int,
    )
    private data class ViewerLoadResult(
        val document: DocumentRecord,
        val text: String,
        val pagination: PaginationResult,
        val pageIndex: Int,
        val encoding: String?,
        val anchorOffset: Int?,
    )

    private val readerFontOptions = listOf(
        ReaderFontOption("GowunDodum, sans-serif", "고운 돋음", "fonts/GowunDodum.ttf"),
        ReaderFontOption("NotoSerifKR, serif", "Noto Serif KR", "fonts/NotoSerifKR.ttf"),
        ReaderFontOption("NotoSansKR, sans-serif", "Noto Sans KR", "fonts/NotoSansKR.ttf"),
        ReaderFontOption("KoPubWorldBatang, serif", "KoPub 바탕", "fonts/KoPubWorldBatang.otf"),
        ReaderFontOption("RidiBatang, serif", "리디바탕", "fonts/RidiBatang.otf"),
        ReaderFontOption("MaruBuri, serif", "마루부리", "fonts/MaruBuri.ttf"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeContent()
        initializeSyntheticSounds()
        appTypeface = loadTypeface()
        settingsStore = LocalSettingsStore(this)
        settings = normalizeReaderSettings(settingsStore.load())
        appStore = DurumariStore(this)
        appStore.initStore()
        documentScanner = DocumentScanner(contentResolver)
        documentTextLoader = DocumentTextLoader(contentResolver)
        reloadLibraryState()
        registerBackInvokedCallback()
        showIntroThenMain()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            readerTypefaceCache.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        readerTypefaceCache.clear()
    }

    @Deprecated("Deprecated Android callback is sufficient for this simple native Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_TREE || resultCode != RESULT_OK) return
        val treeUri = data?.data ?: return
        val flags = (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).let {
            if (it == 0) Intent.FLAG_GRANT_READ_URI_PERMISSION else it
        }
        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        showFolderNameSheet(
            theme = DurumariThemes.tokens(settings.theme),
            defaultName = defaultFolderName(treeUri),
        ) { displayName ->
            registerFolder(treeUri, displayName)
        }
    }

    override fun onDestroy() {
        activityDestroyed = true
        handler.removeCallbacksAndMessages(null)
        stopScrollAnimation()
        unregisterBackInvokedCallback()
        soundPlayer.release()
        if (::appStore.isInitialized) appStore.close()
        super.onDestroy()
    }

    override fun onPause() {
        saveViewerResumeIfNeeded()
        super.onPause()
    }

    private fun registerBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backInvokedCallback != null) return
        val callback = OnBackInvokedCallback {
            if (!handleViewerBack()) finish()
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backInvokedCallback = callback
    }

    private fun unregisterBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        backInvokedCallback = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val viewerActive = activeDocument != null && activePages.isNotEmpty()
        if (viewerActive) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) handleViewerBack()
                return true
            }

            if (rootLayer?.isOverlayVisible == true) return super.dispatchKeyEvent(event)

            val shouldTurnOnKeyUp = event.action == KeyEvent.ACTION_UP
            val shouldConsumeKeyDown = event.action == KeyEvent.ACTION_DOWN
            val theme = DurumariThemes.tokens(settings.theme)
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (!settings.volumeKeyPaging) return super.dispatchKeyEvent(event)
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        val delta = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                        turnViewerPage(delta, theme, activeReaderCanvas, ReaderCanvasView.TurnAxis.VERTICAL)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SPACE -> {
                    if (shouldTurnOnKeyUp) turnViewerPage(1, theme, activeReaderCanvas, ReaderCanvasView.TurnAxis.HORIZONTAL)
                    return shouldTurnOnKeyUp || shouldConsumeKeyDown
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (shouldTurnOnKeyUp) turnViewerPage(-1, theme, activeReaderCanvas, ReaderCanvasView.TurnAxis.HORIZONTAL)
                    return shouldTurnOnKeyUp || shouldConsumeKeyDown
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (shouldTurnOnKeyUp) turnViewerPage(1, theme, activeReaderCanvas, ReaderCanvasView.TurnAxis.VERTICAL)
                    return shouldTurnOnKeyUp || shouldConsumeKeyDown
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (shouldTurnOnKeyUp) turnViewerPage(-1, theme, activeReaderCanvas, ReaderCanvasView.TurnAxis.VERTICAL)
                    return shouldTurnOnKeyUp || shouldConsumeKeyDown
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val viewerActive = activeDocument != null && activePages.isNotEmpty()
        val pointerSource = event.source and InputDevice.SOURCE_CLASS_POINTER
        if (
            viewerActive &&
            rootLayer?.isOverlayVisible != true &&
            event.action == MotionEvent.ACTION_SCROLL &&
            pointerSource != 0
        ) {
            val verticalScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (abs(verticalScroll) > 0.1f) {
                val now = System.currentTimeMillis()
                if (now - lastWheelTurnAt >= WHEEL_TURN_THROTTLE_MS) {
                    lastWheelTurnAt = now
                    val delta = if (verticalScroll < 0f) 1 else -1
                    turnViewerPage(delta, DurumariThemes.tokens(settings.theme), activeReaderCanvas, ReaderCanvasView.TurnAxis.VERTICAL)
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Deprecated("Deprecated Android callback is sufficient for this Activity.")
    override fun onBackPressed() {
        if (!handleViewerBack()) {
            finish()
        }
    }

    private fun handleViewerBack(): Boolean {
        if (rootLayer?.isOverlayVisible == true) {
            playUiFeedback(UiFeedbackKind.CLOSE)
            rootLayer?.dismissOverlay()
            return true
        }
        if (activeDocument != null || activePages.isNotEmpty()) {
            playUiFeedback(UiFeedbackKind.CLOSE)
            closeViewerToMain()
            return true
        }
        return false
    }

    private fun closeViewerToMain() {
        saveActiveReading(pendingViewerAnchorOffset)
        clearViewerResume()
        rootLayer?.dismissOverlay()
        activeDocument = null
        activePages = emptyList()
        activePageIndex = 0
        activeDocumentText = null
        activeReaderCanvas = null
        showMainScreen()
    }

    private fun showIntroThenMain() {
        handler.removeCallbacksAndMessages(null)
        stopScrollAnimation()
        val introChrome = resolveIntroChrome()
        configureSystemBars(
            statusBar = introChrome.background,
            navigationBar = introChrome.background,
            darkStatusBarIcons = introChrome.darkStatusBarIcons,
            darkNavigationBarIcons = introChrome.darkNavigationBarIcons,
        )

        val artwork = ScrollArtworkView(this).apply {
            compact = false
            unrollProgress = 0f
            tasselProgress = 0f
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 12
            progressTintList = ColorStateList.valueOf(introChrome.progress)
            progressBackgroundTintList = ColorStateList.valueOf(introChrome.progressTrack)
        }

        val intro = FrameLayout(this).apply {
            setBackgroundColor(introChrome.background)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            translationY = -dp(LOADING_GROUP_OFFSET_Y_DP).toFloat()
        }

        column.addView(artwork, LinearLayout.LayoutParams(match, dp(INTRO_SCROLL_HEIGHT_DP)))
        val introStatus = text(INTRO_STATUS_INIT, introChrome.status, 14f, gravity = Gravity.CENTER)
        column.addView(introTitleText("두루마리", introChrome.title, introChrome.titleShadow), linear(match, wrap, top = LOADING_TITLE_TOP_DP))
        column.addView(text("나만의 디지털 두루마리", introChrome.subtitle, 16f, gravity = Gravity.CENTER), linear(match, wrap, top = 8))
        column.addView(progress, linear(dp(LOADING_PROGRESS_WIDTH_DP), dp(6), top = LOADING_PROGRESS_TOP_DP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(introStatus, linear(match, wrap, top = LOADING_STATUS_TOP_DP))

        intro.addView(column, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER))
        setContentView(intro)

        val startAt = System.currentTimeMillis()
        val resumeDocument = resumeDocumentForLaunch()
        var introDone = false
        var restoreDone = resumeDocument == null
        var restoreResult: Result<ViewerLoadResult>? = null
        var restoreStarted = false
        var restoreProgress = 0
        var introFrameSize: Pair<Int, Int>? = null

        fun setRestoreProgress(value: Int) {
            val next = value.coerceIn(0, 100)
            if (next < restoreProgress) return
            restoreProgress = next
            progress.progress = next
            introStatus.text = viewerStatusWithProgress(VIEWER_LOADING_TITLE, next)
            progress.contentDescription = introStatus.text
        }

        fun updateIntroProgress(elapsed: Long) {
            if (resumeDocument != null && introDone && !restoreDone) {
                setRestoreProgress(restoreProgress)
                return
            }
            val baseProgress = (12 + (elapsed / INTRO_ANIMATION_TOTAL_MS.toFloat() * 84f)).toInt().coerceIn(12, 96)
            progress.progress = when {
                restoreResult?.isSuccess == true -> 100
                else -> baseProgress
            }
            introStatus.text = when {
                resumeDocument != null && restoreResult?.isSuccess == true -> viewerStatusWithProgress(VIEWER_LOADING_TITLE, 100)
                else -> introStatusForElapsed(elapsed, hasResumeDocument = resumeDocument != null)
            }
            progress.contentDescription = introStatus.text
        }

        fun updateIntroFrameSize() {
            introFrameSize = contentFrameSizeForRoot(intro.width, intro.height)
        }

        fun completeIntroIfReady() {
            if (!introDone || !restoreDone) return
            val result = restoreResult
            if (result != null) {
                result.fold(
                    onSuccess = { loaded -> showRestoredViewerAfterIntro(loaded) },
                    onFailure = {
                        clearViewerResume()
                        Toast.makeText(this, it.message ?: "마지막 문서를 복원할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        showMainScreen()
                    },
                )
            } else {
                showMainScreen()
            }
        }

        fun startRestoreIfReady() {
            val document = resumeDocument ?: return
            if (!introDone || restoreDone || restoreStarted) return
            val frameSize = introFrameSize ?: return
            restoreStarted = true
            restoreProgress = 0
            setRestoreProgress(0)
            val (frameWidth, frameHeight) = frameSize
            val requestId = ++paginationRequestId
            val settingsSnapshot = settings
            val typefaceSnapshot = loadReaderTypeface(settingsSnapshot)
            var lastReportedProgress = -1
            var lastReportedAt = 0L

            fun reportProgress(value: Int, force: Boolean = false) {
                val next = value.coerceIn(0, 99)
                val now = System.currentTimeMillis()
                if (!force && (next <= lastReportedProgress || now - lastReportedAt < 80L)) return
                lastReportedProgress = next.coerceAtLeast(lastReportedProgress)
                lastReportedAt = now
                runOnUiThread {
                    if (!activityDestroyed && restoreStarted && !restoreDone && paginationRequestId == requestId) {
                        setRestoreProgress(next)
                    }
                }
            }

            Thread {
                val finalResult = runCatching {
                    loadViewerDocumentForDisplay(
                        document = document,
                        measuredWidthPx = frameWidth,
                        measuredHeightPx = frameHeight,
                        readerSettings = settingsSnapshot,
                        typeface = typefaceSnapshot,
                        progressCallback = { value -> reportProgress(value, force = value <= 2 || value >= 99) },
                    )
                }
                runOnUiThread {
                    if (activityDestroyed || paginationRequestId != requestId || settings != settingsSnapshot) {
                        return@runOnUiThread
                    }
                    restoreResult = finalResult
                    restoreDone = true
                    if (finalResult.isSuccess) {
                        setRestoreProgress(100)
                        handler.postDelayed({ completeIntroIfReady() }, 80L)
                    } else {
                        completeIntroIfReady()
                    }
                }
            }.apply {
                name = "DurumariIntroRestore"
                start()
            }
        }

        intro.setOnApplyWindowInsetsListener { _, insets ->
            safeTopInset = systemTopInset(insets)
            safeBottomInset = systemBottomInset(insets)
            intro.post {
                updateIntroFrameSize()
                startRestoreIfReady()
            }
            insets
        }
        intro.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateIntroFrameSize()
            startRestoreIfReady()
        }
        val animation = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startAt
                updateIntroProgress(elapsed)
                if (!introDone || !restoreDone) handler.postDelayed(this, 85)
            }
        }
        startScrollAnimation(artwork, repeat = false)
        intro.post {
            intro.requestApplyInsets()
            updateIntroFrameSize()
            startRestoreIfReady()
        }
        handler.post(animation)
        handler.postDelayed({
            introDone = true
            startRestoreIfReady()
            completeIntroIfReady()
        }, INTRO_ANIMATION_TOTAL_MS)
    }

    private fun showRestoredViewerAfterIntro(loaded: ViewerLoadResult) {
        val theme = DurumariThemes.tokens(settings.theme)
        configureSystemBars(theme)
        rootLayer = DurumariRootLayer(this).apply {
            applyTheme(theme)
            onUserDismissOverlay = { playUiFeedback(UiFeedbackKind.CLOSE) }
            onInsetsChanged = { top, bottom ->
                safeTopInset = top
                safeBottomInset = bottom
            }
        }
        setContentView(rootLayer)
        applyViewerLoadResult(loaded)
        syncBookmarksForActivePages()
        reloadLibraryState()
        showViewerPage(theme)
    }

    private fun contentFrameSizeForRoot(rootWidth: Int, rootHeight: Int): Pair<Int, Int>? {
        if (rootWidth <= 0 || rootHeight <= 0) return null
        val contentHeight = (rootHeight - safeTopInset - safeBottomInset).coerceAtLeast(1)
        val maxContentWidth = (contentHeight * VIEWER_CONTENT_MAX_ASPECT).toInt().coerceAtLeast(1)
        val contentWidth = rootWidth.coerceAtLeast(1).coerceAtMost(maxContentWidth)
        return contentWidth to contentHeight
    }

    @Suppress("DEPRECATION")
    private fun systemTopInset(insets: WindowInsets): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).top
        } else {
            insets.systemWindowInsetTop
        }
    }

    @Suppress("DEPRECATION")
    private fun systemBottomInset(insets: WindowInsets): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            insets.systemWindowInsetBottom
        }
    }

    private fun showMainScreen() {
        handler.removeCallbacksAndMessages(null)
        stopScrollAnimation()
        activeReaderCanvas = null
        pendingViewerAnchorOffset = null
        val theme = DurumariThemes.tokens(settings.theme)
        configureSystemBars(theme)

        rootLayer = DurumariRootLayer(this).apply {
            applyTheme(theme)
            onUserDismissOverlay = { playUiFeedback(UiFeedbackKind.CLOSE) }
            onInsetsChanged = { top, bottom ->
                safeTopInset = top
                safeBottomInset = bottom
            }
            setScreenContent(createMainContent(theme))
        }
        setContentView(rootLayer)
    }

    private fun createMainContent(theme: ThemeTokens): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bg)
        }
        val fixedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fixedHeader.addView(createTopNavigation(theme), linear(match, wrap, left = 16, top = 8, right = 16))
        fixedHeader.addView(View(this).apply {
            setBackgroundColor(theme.border)
        }, linear(match, 1, top = 8))
        val tabHost = FrameLayout(this).apply {
            setBackgroundColor(theme.bg)
        }
        val tabPage = createMainTabPage(theme, activeTab, attachSwipe = true)
        mainSwipeHost = tabHost
        mainSwipeCurrentView = tabPage
        tabHost.addView(tabPage, FrameLayout.LayoutParams(match, match))
        page.addView(fixedHeader, linear(match, wrap))
        page.addView(tabHost, LinearLayout.LayoutParams(match, 0, 1f))
        return page
    }

    private fun createMainTabPage(
        theme: ThemeTokens,
        tab: MainTab,
        attachSwipe: Boolean,
    ): View {
        val previousTab = activeTab
        activeTab = tab
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.bg)
            setPadding(dp(16), dp(8), dp(16), dp(18))
        }

        if (activeTab == MainTab.LIBRARY) {
            page.addView(createFolderRow(theme), linear(match, wrap))
        }
        val tabContentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        page.addView(
            createSearchAndSort(theme) {
                replaceTabContent(tabContentHolder, theme)
            },
            linear(match, wrap, top = 8),
        )
        replaceTabContent(tabContentHolder, theme)

        val listScroll = ScrollView(this).apply {
            setBackgroundColor(theme.bg)
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        listScroll.addView(tabContentHolder, FrameLayout.LayoutParams(match, wrap))
        page.addView(listScroll, LinearLayout.LayoutParams(match, 0, 1f).apply {
            topMargin = dp(10)
        })
        if (attachSwipe) attachMainSwipeNavigation(listScroll)
        activeTab = previousTab
        return page
    }

    private fun replaceTabContent(container: LinearLayout, theme: ThemeTokens) {
        container.removeAllViews()
        container.addView(createTabContent(theme), linear(match, wrap))
    }

    private fun attachMainSwipeNavigation(target: View) {
        var downX = 0f
        var downY = 0f
        target.setOnTouchListener { _, event ->
            if (mainTabAnimating) return@setOnTouchListener true
            var handled = false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val threshold = dp(76).toFloat()
                    if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.45f) {
                        lastMainSwipeAt = System.currentTimeMillis()
                        switchMainTabBySwipe(if (dx < 0) 1 else -1)
                        handled = true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    downX = 0f
                    downY = 0f
                }
            }
            handled
        }
    }

    private fun switchMainTabBySwipe(delta: Int) {
        val tabs = MainTab.entries
        val current = tabs.indexOf(activeTab)
        val next = (current + delta).coerceIn(0, tabs.lastIndex)
        if (next == current) return
        val host = mainSwipeHost
        val currentView = mainSwipeCurrentView
        if (host != null && currentView != null && currentView.parent === host && host.width > 0) {
            animateMainTabSwipe(host, currentView, tabs[next], delta)
            return
        }
        playUiFeedback(UiFeedbackKind.PRESS)
        activeTab = tabs[next]
        showMainScreen()
    }

    private fun animateMainTabSwipe(
        host: FrameLayout,
        currentView: View,
        nextTab: MainTab,
        delta: Int,
    ) {
        if (mainTabAnimating) return
        playUiFeedback(UiFeedbackKind.PRESS)
        mainTabAnimating = true
        val theme = DurumariThemes.tokens(settings.theme)
        val direction = if (delta > 0) 1 else -1
        val width = host.width.toFloat().coerceAtLeast(1f)
        val nextView = createMainTabPage(theme, nextTab, attachSwipe = false).apply {
            translationX = direction * width
        }
        host.addView(nextView, FrameLayout.LayoutParams(match, match))
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                currentView.translationX = -direction * width * progress
                nextView.translationX = direction * width * (1f - progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mainTabAnimating = false
                    activeTab = nextTab
                    currentView.translationX = 0f
                    nextView.translationX = 0f
                    showMainScreen()
                }

                override fun onAnimationCancel(animation: Animator) {
                    mainTabAnimating = false
                    currentView.translationX = 0f
                    host.removeView(nextView)
                }
            })
            start()
        }
    }

    private fun attachMainCardSwipeGuard(card: View) {
        var downX = 0f
        var downY = 0f
        var consumed = false
        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    consumed = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val threshold = dp(42).toFloat()
                    if (!consumed && abs(dx) > threshold && abs(dx) > abs(dy) * 1.45f) {
                        consumed = true
                        lastMainSwipeAt = System.currentTimeMillis()
                        switchMainTabBySwipe(if (dx < 0) 1 else -1)
                        true
                    } else {
                        consumed
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> consumed
                else -> consumed
            }
        }
    }

    private fun createTopNavigation(theme: ThemeTokens): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedRect(theme.card, 14, strokeColor = theme.border)
        }

        MainTab.entries.forEach { tab ->
            val active = activeTab == tab
            val item = text(
                value = tab.label,
                color = if (active) theme.accentForeground else theme.secondary,
                size = 14f,
                bold = true,
                gravity = Gravity.CENTER,
            ).apply {
                minHeight = dp(34)
                background = roundedRect(if (active) theme.accent else Color.TRANSPARENT, 10)
                setUiClickListener {
                    if (activeTab != tab) {
                        activeTab = tab
                        showMainScreen()
                    }
                }
            }
            tabs.addView(item, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                if (tabs.childCount > 0) leftMargin = dp(4)
            })
        }

        val settingsButton = text("⚙", theme.accentText, 19f, bold = true, gravity = Gravity.CENTER).apply {
            typeface = Typeface.DEFAULT_BOLD
            minWidth = dp(44)
            minHeight = dp(44)
            background = roundedRect(theme.card, 14, strokeColor = theme.border)
            setUiClickListener(UiFeedbackKind.OPEN) { showSettingsOverlay() }
        }

        row.addView(tabs, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(settingsButton, linear(dp(44), dp(44), left = 12))
        return row
    }

    private fun createFolderRow(theme: ThemeTokens): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val activeFolder = folders.firstOrNull { it.folderId == settings.activeFolderId } ?: folders.firstOrNull()
        if (activeFolder != null) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = roundedRect(theme.accent, 14, strokeColor = theme.border)
            }
            chip.addView(text(activeFolder.displayName, theme.accentForeground, 13f, bold = true, gravity = Gravity.CENTER).apply {
                minHeight = dp(30)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, dp(10), 0)
            }, linear(wrap, dp(30)))
            chip.addView(text("×", theme.accentForeground, 16f, bold = true, gravity = Gravity.CENTER).apply {
                background = roundedRect(Color.argb(61, 255, 255, 255), 10)
                setUiClickListener {
                    appStore.removeFolder(activeFolder.folderId)
                    clearViewerResume()
                    settings = settings.copy(activeFolderId = null)
                    settingsStore.save(settings)
                    reloadLibraryState()
                    showMainScreen()
                }
            }, linear(dp(22), dp(22), left = 4, right = 1))
            row.addView(chip, linear(wrap, dp(38)))
        }

        val addFolder = text("+ 폴더", theme.accentText, 13f, bold = true, gravity = Gravity.CENTER).apply {
            minHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedRect(theme.card, 14, strokeColor = theme.border)
            setUiClickListener(UiFeedbackKind.OPEN) { openFolderPicker() }
        }
        row.addView(addFolder, linear(wrap, dp(36), left = if (activeFolder != null) 8 else 0))
        return row
    }

    private fun createSearchAndSort(theme: ThemeTokens, onSearchChanged: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val search = EditText(this).apply {
            setPadding(dp(9), 0, dp(9), dp(1))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            hint = "🔍  ${activeTab.searchHint}"
            setText(searchQuery)
            setSelection(text?.length ?: 0)
            setTextColor(theme.text)
            setHintTextColor(theme.secondary)
            textSize = 13f
            typeface = appTypeface
            inputType = InputType.TYPE_CLASS_TEXT
            includeFontPadding = false
            background = roundedRect(theme.card, 16, strokeColor = theme.border)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textCursorDrawable = null
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val next = s?.toString().orEmpty()
                    if (next != searchQuery) {
                        searchQuery = next
                        onSearchChanged()
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val sort = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedRect(theme.card, 16, strokeColor = theme.border)
        }
        val sortConfig = currentMainSort(activeTab)
        sortOptionsFor(activeTab).forEach { option ->
            val active = sortConfig.column == option.column && sortConfig.direction != SortDirection.NONE
            val label = sortLabel(option, sortConfig)
            sort.addView(
                text(
                    label,
                    if (active) theme.accentForeground else theme.secondary,
                    12f,
                    bold = true,
                    gravity = Gravity.CENTER,
                ).apply {
                    background = roundedRect(if (active) theme.accent else Color.TRANSPARENT, 0)
                    setUiClickListener {
                        updateMainSort(activeTab, nextSortConfig(activeTab, option.column))
                        showMainScreen()
                    }
                },
                LinearLayout.LayoutParams(0, dp(32), 1f),
            )
        }

        row.addView(search, LinearLayout.LayoutParams(0, dp(32), 1f))
        row.addView(sort, linear(dp(148), dp(32), left = 8))
        return row
    }

    private fun sortOptionsFor(tab: MainTab): List<MainSortOption> {
        return when (tab) {
            MainTab.LIBRARY -> listOf(
                MainSortOption("제목", "title"),
                MainSortOption("일자", "modifiedAt"),
                MainSortOption("상태", "status"),
            )
            MainTab.HISTORY -> listOf(
                MainSortOption("제목", "title"),
                MainSortOption("일자", "openedAt"),
                MainSortOption("진행", "progress"),
            )
            MainTab.BOOKMARKS -> listOf(
                MainSortOption("제목", "title"),
                MainSortOption("일자", "createdAt"),
                MainSortOption("위치", "page"),
            )
        }
    }

    private fun sortLabel(option: MainSortOption, current: SortConfig): String {
        if (current.column != option.column || current.direction == SortDirection.NONE) return option.label
        return "${option.label} ${if (current.direction == SortDirection.ASC) "▲" else "▼"}"
    }

    private fun currentMainSort(tab: MainTab): SortConfig {
        return when (tab) {
            MainTab.LIBRARY -> settings.librarySort
            MainTab.HISTORY -> settings.historySort
            MainTab.BOOKMARKS -> settings.bookmarksSort
        }
    }

    private fun nextSortConfig(tab: MainTab, column: String): SortConfig {
        val current = currentMainSort(tab)
        if (current.column != column) return SortConfig(column, SortDirection.ASC)
        val nextDirection = when (current.direction) {
            SortDirection.ASC -> SortDirection.DESC
            SortDirection.DESC -> SortDirection.NONE
            SortDirection.NONE -> SortDirection.ASC
        }
        return SortConfig(column, nextDirection)
    }

    private fun updateMainSort(tab: MainTab, sort: SortConfig) {
        settings = when (tab) {
            MainTab.LIBRARY -> settings.copy(librarySort = sort)
            MainTab.HISTORY -> settings.copy(historySort = sort)
            MainTab.BOOKMARKS -> settings.copy(bookmarksSort = sort)
        }
        settingsStore.save(settings)
    }

    private fun normalizedSearchQuery(): String {
        return searchQuery.trim().lowercase(Locale.KOREA)
    }

    private fun matchesSearch(normalizedQuery: String, vararg values: String?): Boolean {
        if (normalizedQuery.isEmpty()) return true
        return values.any { value ->
            value?.lowercase(Locale.KOREA)?.contains(normalizedQuery) == true
        }
    }

    private fun createTabContent(theme: ThemeTokens): View {
        val normalizedQuery = normalizedSearchQuery()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        when (activeTab) {
            MainTab.LIBRARY -> {
                val visibleDocuments = sortLibraryDocuments(
                    documents.filter { document ->
                        val reading = readingsById[document.documentId]
                        (!settings.hideCompleted || reading?.completed != true) &&
                            matchesSearch(normalizedQuery, document.title, folderName(document.folderId))
                    },
                )
                if (visibleDocuments.isEmpty()) {
                    list.addView(
                        createEmptyState(
                            theme = theme,
                            title = if (searchQuery.isBlank()) "등록된 문서가 없습니다." else "검색 결과가 없습니다.",
                            body = if (searchQuery.isBlank()) "상단의 + 폴더로 소설 폴더를 등록하세요." else "다른 제목으로 검색해보세요.",
                            actionLabel = "+ 폴더 등록",
                            onAction = if (searchQuery.isBlank()) ({ openFolderPicker() }) else null,
                        ),
                        linear(match, wrap),
                    )
                } else {
                    visibleDocuments.forEachIndexed { index, document ->
                        val reading = readingsById[document.documentId]
                        list.addView(
                            createBookCard(
                                theme = theme,
                                title = document.title,
                                meta = folderName(document.folderId),
                                date = formatDate(document.modifiedAt),
                                badge = badgeFor(document.documentId, reading),
                                progress = reading
                                    ?.takeIf { readingStatusForDocument(document.documentId, it) != com.netice.myapp.durumari.model.ReadingStatus.UNREAD }
                                    ?.progress,
                                onOpen = { showViewerLoadingThenPage(theme, document) },
                            ),
                            linear(match, wrap, top = if (index == 0) 0 else 10),
                        )
                    }
                }
            }
            MainTab.HISTORY -> {
                val historyRows = sortHistoryRows(
                    readingsById.values
                    .mapNotNull { reading -> documentsById[reading.documentId]?.let { it to reading } }
                    .filter { (document, _) -> matchesSearch(normalizedQuery, document.title, folderName(document.folderId)) },
                )
                if (historyRows.isEmpty()) {
                    list.addView(
                        createEmptyState(
                            theme = theme,
                            title = if (searchQuery.isBlank()) "열람록이 없습니다." else "검색 결과가 없습니다.",
                            body = if (searchQuery.isBlank()) "문서를 열고 페이지를 넘기면 여기에 기록됩니다." else "다른 제목으로 검색해보세요.",
                        ),
                        linear(match, wrap),
                    )
                } else {
                    historyRows.forEachIndexed { index, (document, reading) ->
                        list.addView(
                            createBookCard(
                                theme = theme,
                                title = document.title,
                                meta = folderName(document.folderId),
                                date = formatDate(reading.openedAt),
                                badge = pageBadgeFor(reading),
                                progress = reading.progress,
                                onOpen = { showViewerLoadingThenPage(theme, document) },
                                onLongPress = {
                                    showDeleteReadingDialog(theme, reading, document.title)
                                },
                            ),
                            linear(match, wrap, top = if (index == 0) 0 else 10),
                        )
                    }
                }
            }
            MainTab.BOOKMARKS -> {
                val bookmarkRows = sortBookmarkRows(
                    bookmarks.map { bookmark ->
                        bookmark to documentsById[bookmark.documentId]
                    }.filter { (bookmark, document) ->
                        matchesSearch(normalizedQuery, document?.title, bookmark.preview, document?.folderId?.let { folderName(it) })
                    },
                )
                if (bookmarkRows.isEmpty()) {
                    list.addView(
                        createEmptyState(
                            theme = theme,
                            title = if (searchQuery.isBlank()) "책갈피가 없습니다." else "검색 결과가 없습니다.",
                            body = if (searchQuery.isBlank()) "뷰어에서 책갈피를 추가하면 이곳에서 바로 이동할 수 있습니다." else "다른 제목으로 검색해보세요.",
                        ),
                        linear(match, wrap),
                    )
                } else {
                    bookmarkRows.forEachIndexed { index, (bookmark, document) ->
                        list.addView(
                            createBookmarkCard(
                                theme = theme,
                                title = document?.title ?: "알 수 없는 문서",
                                preview = bookmark.preview,
                                folder = document?.folderId?.let { folderName(it) } ?: "",
                                date = formatDate(bookmark.createdAt),
                                page = "p.${bookmark.page}",
                                onOpen = {
                                    if (document != null) {
                                        showViewerLoadingThenPage(
                                            theme = theme,
                                            document = document,
                                            targetPage = bookmark.page,
                                            targetOffset = bookmark.anchorOffset,
                                        )
                                    }
                                },
                                onDeleteRequest = {
                                    showDeleteBookmarkDialog(theme, bookmark, document?.title ?: "알 수 없는 문서")
                                },
                            ),
                            linear(match, wrap, top = if (index == 0) 0 else 10),
                        )
                    }
                }
            }
        }
        return list
    }

    private fun sortLibraryDocuments(items: List<DocumentRecord>): List<DocumentRecord> {
        val sort = settings.librarySort
        if (sort.direction == SortDirection.NONE) return items
        val sorted = when (sort.column) {
            "title" -> items.sortedBy { it.title.lowercase(Locale.KOREA) }
            "status" -> items.sortedWith(
                compareBy<DocumentRecord> { readingStatusForDocument(it.documentId, readingsById[it.documentId]).ordinal }
                    .thenBy { it.title.lowercase(Locale.KOREA) },
            )
            else -> items.sortedBy { it.modifiedAt }
        }
        return if (sort.direction == SortDirection.DESC) sorted.reversed() else sorted
    }

    private fun sortHistoryRows(items: List<Pair<DocumentRecord, ReadingRecord>>): List<Pair<DocumentRecord, ReadingRecord>> {
        val sort = settings.historySort
        if (sort.direction == SortDirection.NONE) return items
        val sorted = when (sort.column) {
            "title" -> items.sortedBy { it.first.title.lowercase(Locale.KOREA) }
            "progress" -> items.sortedWith(
                compareBy<Pair<DocumentRecord, ReadingRecord>> { it.second.progress }
                    .thenBy { it.first.title.lowercase(Locale.KOREA) },
            )
            else -> items.sortedBy { it.second.openedAt }
        }
        return if (sort.direction == SortDirection.DESC) sorted.reversed() else sorted
    }

    private fun sortBookmarkRows(items: List<Pair<BookmarkRecord, DocumentRecord?>>): List<Pair<BookmarkRecord, DocumentRecord?>> {
        val sort = settings.bookmarksSort
        if (sort.direction == SortDirection.NONE) return items
        val sorted = when (sort.column) {
            "title" -> items.sortedBy { it.second?.title?.lowercase(Locale.KOREA).orEmpty() }
            "page" -> items.sortedWith(
                compareBy<Pair<BookmarkRecord, DocumentRecord?>> { it.first.page }
                    .thenBy { it.second?.title?.lowercase(Locale.KOREA).orEmpty() },
            )
            else -> items.sortedBy { it.first.createdAt }
        }
        return if (sort.direction == SortDirection.DESC) sorted.reversed() else sorted
    }

    private fun createBookCard(
        theme: ThemeTokens,
        title: String,
        meta: String,
        date: String,
        badge: String,
        progress: Float?,
        onOpen: (() -> Unit)? = null,
        onLongPress: (() -> Unit)? = null,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(theme.card, 14, strokeColor = theme.border)
            setUiClickListener(UiFeedbackKind.OPEN) {
                if (System.currentTimeMillis() - lastMainSwipeAt >= 500L) {
                    onOpen?.invoke()
                }
            }
        }
        attachMainListCardTouch(card, onLongPress)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(title, theme.text, 16f, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(createBadge(theme, badge), linear(wrap, dp(28), left = 12))
        card.addView(top, linear(match, wrap))

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(text(meta, theme.secondary, 12f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        metaRow.addView(text(date, theme.secondary, 12f, bold = true, gravity = Gravity.END), linear(wrap, wrap, left = 12))
        card.addView(metaRow, linear(match, wrap, top = 10))

        if (progress != null) {
            val progressPercent = (progress * 100).toInt().coerceIn(0, 100)
            val progressRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = progressPercent
                progressTintList = ColorStateList.valueOf(theme.accent)
                progressBackgroundTintList = ColorStateList.valueOf(theme.border)
            }
            progressRow.addView(bar, LinearLayout.LayoutParams(0, dp(5), 1f))
            progressRow.addView(
                text("$progressPercent%", theme.accentText, 12f, bold = true, gravity = Gravity.END),
                linear(dp(38), wrap, left = 10),
            )
            card.addView(progressRow, linear(match, dp(18), top = 10))
        }
        return card
    }

    private fun createBookmarkCard(
        theme: ThemeTokens,
        title: String,
        preview: String,
        folder: String,
        date: String,
        page: String,
        onOpen: (() -> Unit)? = null,
        onDeleteRequest: (() -> Unit)? = null,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedRect(theme.card, 14, strokeColor = theme.border)
            setUiClickListener(UiFeedbackKind.OPEN) {
                if (System.currentTimeMillis() - lastMainSwipeAt >= 500L) {
                    onOpen?.invoke()
                }
            }
        }
        attachBookmarkCardTouch(card, onDeleteRequest)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text(title, theme.text, 16f, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(createBadge(theme, page), linear(wrap, dp(28), left = 12))
        card.addView(top, linear(match, wrap))
        card.addView(text(preview, theme.secondary, 13f, bold = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f)
        }, linear(match, wrap, top = 10))

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(text(folder, theme.secondary, 12f, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
        metaRow.addView(text(date, theme.secondary, 12f, bold = true, gravity = Gravity.END), linear(wrap, wrap, left = 12))
        card.addView(metaRow, linear(match, wrap, top = 10))
        return card
    }

    private fun attachBookmarkCardTouch(card: View, onDeleteRequest: (() -> Unit)?) {
        var downX = 0f
        var downY = 0f
        var consumed = false
        var longPressFired = false
        var longPressRunnable: Runnable? = null

        fun cancelLongPress() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
        }

        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    consumed = false
                    longPressFired = false
                    cancelLongPress()
                    if (onDeleteRequest != null) {
                        longPressRunnable = Runnable {
                            longPressFired = true
                            consumed = true
                            onDeleteRequest.invoke()
                        }.also { handler.postDelayed(it, 520L) }
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val threshold = dp(42).toFloat()
                    if (!consumed && abs(dx) > threshold && abs(dx) > abs(dy) * 1.45f) {
                        cancelLongPress()
                        consumed = true
                        lastMainSwipeAt = System.currentTimeMillis()
                        switchMainTabBySwipe(if (dx < 0) 1 else -1)
                        true
                    } else {
                        if (abs(dx) > dp(10) || abs(dy) > dp(10)) cancelLongPress()
                        consumed
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    if (longPressFired) true else consumed
                }
                else -> consumed
            }
        }
    }

    private fun attachMainListCardTouch(card: View, onLongPress: (() -> Unit)?) {
        attachBookmarkCardTouch(card, onLongPress)
    }

    private fun createBadge(theme: ThemeTokens, value: String): View {
        val isAccent = value == "읽는 중" || value.endsWith("%") || value.startsWith("p.")
        return text(
            value,
            if (isAccent) theme.accentText else theme.secondary,
            12f,
            bold = true,
            gravity = Gravity.CENTER,
        ).apply {
            minHeight = dp(28)
            setPadding(dp(10), 0, dp(10), 0)
            background = roundedRect(
                color = if (isAccent) applyAlpha(theme.accent, 0.10f) else Color.TRANSPARENT,
                radiusDp = 14,
                strokeColor = if (isAccent) theme.accent else theme.secondary,
            )
        }
    }

    private fun showDeleteReadingDialog(theme: ThemeTokens, reading: ReadingRecord, documentTitle: String) {
        playUiFeedback(UiFeedbackKind.OPEN)
        val dialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundedRect(theme.card, 16, strokeColor = theme.border)
        }
        dialog.addView(text("열람기록 삭제", theme.text, 18f, bold = true), linear(match, wrap))
        dialog.addView(text(documentTitle, theme.secondary, 13f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, linear(match, wrap, top = 8))
        dialog.addView(text("${pageBadgeFor(reading)} 열람기록을 삭제할까요?", theme.text, 15f).apply {
            setLineSpacing(0f, 1.15f)
        }, linear(match, wrap, top = 12))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(text("아니오", theme.text, 15f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.bg, 12, strokeColor = theme.border)
            setUiClickListener(UiFeedbackKind.CLOSE) { rootLayer?.dismissOverlay() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(text("예", theme.accentForeground, 15f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.accent, 12)
            setUiClickListener {
                appStore.removeReading(reading.documentId)
                readingsById = readingsById - reading.documentId
                replaceBookmarks(bookmarks.filterNot { it.documentId == reading.documentId })
                rootLayer?.dismissOverlay()
                showMainScreen()
                Toast.makeText(this@MainActivity, "열람기록을 삭제했습니다.", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(10) })
        dialog.addView(actions, linear(match, wrap, top = 18))

        rootLayer?.showDialog(
            dialog,
            dimColor = Color.argb(142, 0, 0, 0),
            dismissOnDim = true,
        )
    }

    private fun showDeleteBookmarkDialog(theme: ThemeTokens, bookmark: BookmarkRecord, documentTitle: String) {
        playUiFeedback(UiFeedbackKind.OPEN)
        val dialog = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundedRect(theme.card, 16, strokeColor = theme.border)
        }
        dialog.addView(text("책갈피 삭제", theme.text, 18f, bold = true), linear(match, wrap))
        dialog.addView(text(documentTitle, theme.secondary, 13f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, linear(match, wrap, top = 8))
        dialog.addView(text("p.${bookmark.page} 책갈피를 삭제할까요?", theme.text, 15f), linear(match, wrap, top = 12))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(text("아니오", theme.text, 15f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.bg, 12, strokeColor = theme.border)
            setUiClickListener(UiFeedbackKind.CLOSE) { rootLayer?.dismissOverlay() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(text("예", theme.accentForeground, 15f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.accent, 12)
            setUiClickListener {
                appStore.removeBookmark(bookmark.bookmarkId)
                replaceBookmarks(bookmarks.filterNot { it.bookmarkId == bookmark.bookmarkId })
                rootLayer?.dismissOverlay()
                showMainScreen()
                Toast.makeText(this@MainActivity, "책갈피를 삭제했습니다.", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(10) })
        dialog.addView(actions, linear(match, wrap, top = 18))

        rootLayer?.showDialog(
            dialog,
            dimColor = Color.argb(142, 0, 0, 0),
            dismissOnDim = true,
        )
    }

    private fun reloadLibraryState() {
        folders = appStore.listFolders()
        foldersById = folders.associateBy { it.folderId }
        val resolvedActiveFolderId = resolveActiveFolderId()
        if (resolvedActiveFolderId != settings.activeFolderId) {
            settings = settings.copy(activeFolderId = resolvedActiveFolderId)
            settingsStore.save(settings)
        }
        documents = appStore.listDocuments(resolvedActiveFolderId)
        documentsById = documents.associateBy { it.documentId }
        readingsById = appStore.listReadings().associateBy { it.documentId }
        replaceBookmarks(appStore.listBookmarks())
    }

    private fun replaceBookmarks(nextBookmarks: List<BookmarkRecord>) {
        bookmarks = nextBookmarks
        bookmarkDocumentIds = nextBookmarks.mapTo(mutableSetOf()) { it.documentId }
    }

    private fun resumeDocumentForLaunch(): DocumentRecord? {
        val documentId = settingsStore.loadResumeDocumentId() ?: return null
        val document = appStore.getDocument(documentId, includeText = false)
        if (document == null) clearViewerResume()
        return document
    }

    private fun saveViewerResumeIfNeeded() {
        if (!::settingsStore.isInitialized) return
        val document = activeDocument ?: return
        markViewerResume(document.documentId)
        if (activePages.isNotEmpty()) saveActiveReading(pendingViewerAnchorOffset)
    }

    private fun markViewerResume(documentId: String) {
        settingsStore.saveResumeDocumentId(documentId)
    }

    private fun clearViewerResume() {
        if (::settingsStore.isInitialized) settingsStore.saveResumeDocumentId(null)
    }

    private fun resolveActiveFolderId(): String? {
        if (folders.isEmpty()) return null
        val current = settings.activeFolderId
        return folders.firstOrNull { it.folderId == current }?.folderId ?: folders.first().folderId
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_TREE)
    }

    private fun registerFolder(treeUri: Uri, displayName: String) {
        val folderId = stableId(treeUri.toString())
        Toast.makeText(this, "폴더를 스캔하는 중입니다.", Toast.LENGTH_SHORT).show()
        Thread {
            val now = System.currentTimeMillis()
            val baseFolder = FolderRecord(
                folderId = folderId,
                treeUri = treeUri.toString(),
                displayName = displayName.ifBlank { defaultFolderName(treeUri) },
                createdAt = now,
                lastSyncedAt = null,
                permissionStatus = FolderPermissionStatus.GRANTED,
            )
            runCatching {
                val scanned = documentScanner.scanFolder(folderId, treeUri)
                appStore.replaceFolderDocuments(baseFolder.copy(lastSyncedAt = System.currentTimeMillis()), scanned)
                settings = settings.copy(activeFolderId = folderId)
                settingsStore.save(settings)
                runOnUiThread {
                    reloadLibraryState()
                    showMainScreen()
                    Toast.makeText(this, "${scanned.size}개 문서를 등록했습니다.", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                appStore.upsertFolder(baseFolder.copy(permissionStatus = FolderPermissionStatus.FAILED))
                runOnUiThread {
                    reloadLibraryState()
                    showMainScreen()
                    Toast.makeText(this, error.message ?: "폴더 스캔에 실패했습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun defaultFolderName(uri: Uri): String {
        val raw = Uri.decode(uri.lastPathSegment ?: "")
        return raw.substringAfterLast(':').substringAfterLast('/').ifBlank { "소설" }
    }

    private fun folderName(folderId: String): String {
        return foldersById[folderId]?.displayName ?: "등록 폴더"
    }

    private fun formatDate(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }

    private fun badgeFor(documentId: String, reading: ReadingRecord?): String {
        return when (readingStatusForDocument(documentId, reading)) {
            com.netice.myapp.durumari.model.ReadingStatus.UNREAD -> "미독"
            com.netice.myapp.durumari.model.ReadingStatus.READING -> "읽는 중"
            com.netice.myapp.durumari.model.ReadingStatus.COMPLETED -> "완독"
        }
    }

    private fun readingStatusForDocument(
        documentId: String,
        reading: ReadingRecord?,
    ): com.netice.myapp.durumari.model.ReadingStatus {
        val status = readingStatus(reading)
        if (status == com.netice.myapp.durumari.model.ReadingStatus.UNREAD && hasBookmark(documentId)) {
            return com.netice.myapp.durumari.model.ReadingStatus.READING
        }
        return status
    }

    private fun hasBookmark(documentId: String): Boolean {
        return documentId in bookmarkDocumentIds
    }

    private fun pageBadgeFor(reading: ReadingRecord): String {
        val total = reading.totalPages.coerceAtLeast(1)
        val current = reading.lastPage.coerceIn(1, total)
        return "p.$current"
    }

    private fun showViewerLoadingThenPage(
        theme: ThemeTokens,
        document: DocumentRecord,
        targetPage: Int? = null,
        targetOffset: Int? = null,
        forceEncoding: String? = null,
    ) {
        handler.removeCallbacksAndMessages(null)
        stopScrollAnimation()
        activeReaderCanvas = null
        configureSystemBars(theme)
        rootLayer?.applyTheme(theme)

        val page = FrameLayout(this).apply {
            setBackgroundColor(theme.outer)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            translationY = -dp(LOADING_GROUP_OFFSET_Y_DP).toFloat()
        }
        val artwork = ScrollArtworkView(this).apply {
            compact = false
            unrollProgress = 0f
            tasselProgress = 0f
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
            progressTintList = ColorStateList.valueOf(theme.accent)
            progressBackgroundTintList = ColorStateList.valueOf(theme.border)
        }
        val initialLoadingStatus = if (forceEncoding != null) VIEWER_STATUS_REENCODING else VIEWER_STATUS_TEXT_LOADING
        val loadingStatus = text(
            viewerStatusWithProgress(initialLoadingStatus, progress.progress),
            theme.secondary,
            14f,
            gravity = Gravity.CENTER,
        )

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        footer.addView(text(VIEWER_LOADING_TITLE, theme.text, 16f, bold = true, gravity = Gravity.CENTER), linear(match, wrap))
        footer.addView(progress, linear(dp(LOADING_PROGRESS_WIDTH_DP), dp(4), top = LOADING_PROGRESS_TOP_DP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        footer.addView(loadingStatus, linear(match, wrap, top = LOADING_STATUS_TOP_DP))

        column.addView(artwork, LinearLayout.LayoutParams(match, dp(INTRO_SCROLL_HEIGHT_DP)))
        column.addView(footer, linear(match, wrap, top = LOADING_TITLE_TOP_DP))
        page.addView(column, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER))

        rootLayer?.setScreenContent(page)
        val requestId = ++paginationRequestId
        var lastReportedProgress = -1
        var lastReportedAt = 0L

        fun reportLoadingProgress(value: Int, force: Boolean = false) {
            val next = value.coerceIn(0, 99)
            val now = System.currentTimeMillis()
            if (!force && (next <= lastReportedProgress || now - lastReportedAt < 80L)) return
            lastReportedProgress = next.coerceAtLeast(lastReportedProgress)
            lastReportedAt = now
            runOnUiThread {
                if (activityDestroyed || paginationRequestId != requestId) return@runOnUiThread
                val status = if (next < 32) initialLoadingStatus else VIEWER_STATUS_PAGINATION
                progress.progress = next
                loadingStatus.text = viewerStatusWithProgress(status, next)
                progress.contentDescription = loadingStatus.text
            }
        }
        startScrollAnimation(artwork, repeat = false)
        reportLoadingProgress(0, force = true)

        activeDocument = document
        markViewerResume(document.documentId)
        Thread {
            runCatching {
                loadViewerDocumentForDisplay(
                    document = document,
                    targetPage = targetPage,
                    targetOffset = targetOffset,
                    forceEncoding = forceEncoding,
                    progressCallback = { value -> reportLoadingProgress(value, force = value <= 2 || value >= 99) },
                )
            }.onSuccess { loaded ->
                runOnUiThread {
                    if (paginationRequestId != requestId) return@runOnUiThread
                    applyViewerLoadResult(loaded)
                    progress.progress = 100
                    loadingStatus.text = viewerStatusWithProgress(VIEWER_STATUS_PAGINATION, 100)
                    progress.contentDescription = loadingStatus.text
                    syncBookmarksForActivePages()
                    reloadLibraryState()
                    showViewerPage(theme)
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (paginationRequestId != requestId) return@runOnUiThread
                    clearViewerResume()
                    activeDocument = null
                    activePages = emptyList()
                    activePageIndex = 0
                    activeDocumentText = null
                    Toast.makeText(this, error.message ?: "문서를 열 수 없습니다.", Toast.LENGTH_LONG).show()
                    showMainScreen()
                }
            }
        }.start()
    }

    private fun loadViewerDocumentForDisplay(
        document: DocumentRecord,
        targetPage: Int? = null,
        targetOffset: Int? = null,
        forceEncoding: String? = null,
        measuredWidthPx: Int? = null,
        measuredHeightPx: Int? = null,
        readerSettings: ReaderSettings = settings,
        typeface: Typeface = loadReaderTypeface(readerSettings),
        progressCallback: ((Int) -> Unit)? = null,
    ): ViewerLoadResult {
        progressCallback?.invoke(2)
        val decoded = loadDocumentForViewer(document, forceEncoding)
        val displayText = normalizeViewerText(decoded.text)
        progressCallback?.invoke(32)
        val pagination = paginateText(
            text = displayText,
            measuredWidthPx = measuredWidthPx,
            measuredHeightPx = measuredHeightPx,
            readerSettings = readerSettings,
            typeface = typeface,
            progressCallback = { ratio ->
                progressCallback?.invoke((32 + ratio * 66f).roundToInt().coerceIn(32, 98))
            },
        )
        progressCallback?.invoke(99)
        val savedReading = readingsById[decoded.document.documentId]
        val anchorOffset = when {
            targetOffset != null -> targetOffset.coerceIn(0, displayText.length)
            targetPage != null -> pagination.pages
                .getOrNull((targetPage - 1).coerceIn(0, pagination.pages.size.coerceAtLeast(1) - 1))
                ?.startOffset
                ?: 0
            savedReading != null -> anchorOffsetForReading(savedReading, pagination.pages, displayText)
            else -> null
        }
        val startPageIndex = anchorOffset
            ?.let { pageIndexForOffset(pagination.pages, it) }
            ?: 0
        return ViewerLoadResult(
            document = decoded.document,
            text = displayText,
            pagination = pagination,
            pageIndex = startPageIndex,
            encoding = decoded.document.textEncoding,
            anchorOffset = anchorOffset,
        )
    }

    private fun applyViewerLoadResult(loaded: ViewerLoadResult) {
        activeDocument = loaded.document
        activeDocumentText = loaded.text
        applyPaginationResult(loaded.pagination)
        activePageIndex = loaded.pageIndex
        activeEncoding = loaded.encoding
        activeReaderCanvas = null
        pendingViewerAnchorOffset = loaded.anchorOffset
        markViewerResume(loaded.document.documentId)
    }

    private fun showViewerPage(theme: ThemeTokens) {
        handler.removeCallbacksAndMessages(null)
        stopScrollAnimation()
        if (pendingViewerAnchorOffset == null) {
            saveActiveReading()
        }
        val canvas = ReaderCanvasView(this).apply {
            this.theme = theme
            readerSettings = settings
            readerTypeface = loadReaderTypeface(settings)
            pageText = pageTextForPage(activePageIndex)
            pageNumberText = "${activePageIndex + 1} / ${activePages.size.coerceAtLeast(1)}"
            bookmarkActive = isBookmarkActiveForPage(activePageIndex)
            isFocusable = false
            isFocusableInTouchMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
        }
        attachViewerGestures(canvas, theme)
        activeReaderCanvas = canvas
        rootLayer?.setScreenContent(canvas)
        verifyViewerPaginationFrame(canvas)
    }

    private fun attachViewerGestures(canvas: ReaderCanvasView, theme: ThemeTokens) {
        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressShown = false
        var longPressRunnable: Runnable? = null
        fun clearLongPressState(view: View) {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
            view.parent?.requestDisallowInterceptTouchEvent(false)
        }
        canvas.isClickable = true
        canvas.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                    longPressShown = false
                    clearLongPressState(view)
                    longPressRunnable = Runnable {
                        if (!moved && activeDocument != null && activePages.isNotEmpty()) {
                            longPressShown = true
                            longPressRunnable = null
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            showViewerMenuSheet(theme)
                        }
                    }.also { handler.postDelayed(it, 560L) }
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > SWIPE_CANCEL_PX || abs(event.y - downY) > SWIPE_CANCEL_PX) {
                        moved = true
                        clearLongPressState(view)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    clearLongPressState(view)
                    if (longPressShown) {
                        longPressShown = false
                        return@setOnTouchListener true
                    }
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val horizontal = abs(dx) > abs(dy)
                    val axisDistance = if (horizontal) abs(dx) else abs(dy)
                    val axisLength = if (horizontal) view.width else view.height
                    val swipeConfirmed = axisDistance >= SWIPE_CONFIRM_PX ||
                        axisDistance >= axisLength.coerceAtLeast(1) * SWIPE_CONFIRM_RATIO
                    when {
                        settings.pageTurnSwipe && swipeConfirmed -> {
                            val delta = if (horizontal) {
                                if (dx < 0) 1 else -1
                            } else {
                                if (dy < 0) 1 else -1
                            }
                            val axis = if (horizontal) ReaderCanvasView.TurnAxis.HORIZONTAL else ReaderCanvasView.TurnAxis.VERTICAL
                            turnViewerPage(delta, theme, view, axis)
                        }
                        settings.pageTurnTouch && !moved -> {
                            val nx = event.x / view.width.coerceAtLeast(1).toFloat() - 0.5f
                            val ny = event.y / view.height.coerceAtLeast(1).toFloat() - 0.5f
                            val delta = if (abs(nx) >= abs(ny)) {
                                if (nx >= 0f) 1 else -1
                            } else {
                                if (ny >= 0f) 1 else -1
                            }
                            val axis = if (abs(nx) >= abs(ny)) ReaderCanvasView.TurnAxis.HORIZONTAL else ReaderCanvasView.TurnAxis.VERTICAL
                            turnViewerPage(delta, theme, view, axis)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    clearLongPressState(view)
                    longPressShown = false
                    true
                }
                else -> true
            }
        }
    }

    private fun turnViewerPage(
        delta: Int,
        theme: ThemeTokens,
        feedbackView: View? = null,
        axis: ReaderCanvasView.TurnAxis = ReaderCanvasView.TurnAxis.HORIZONTAL,
    ): Boolean {
        if ((feedbackView as? ReaderCanvasView)?.isPageAnimating == true) return false
        val total = activePages.size.coerceAtLeast(1)
        val next = (activePageIndex + delta).coerceIn(0, total - 1)
        if (next == activePageIndex) {
            if (delta != 0) {
                playPageTurnFeedback(feedbackView, previous = delta < 0)
                (feedbackView as? ReaderCanvasView)?.startBoundaryBounce(delta, axis)
                Toast.makeText(
                    this,
                    if (delta < 0) "첫 페이지입니다" else "마지막 페이지입니다",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return false
        }
        val canvas = feedbackView as? ReaderCanvasView
        pendingViewerAnchorOffset = null
        activePageIndex = next
        playPageTurnFeedback(feedbackView, previous = delta < 0)
        if (canvas != null) {
            canvas.startPageTransition(
                toText = pageTextForPage(activePageIndex),
                toNumberText = "${activePageIndex + 1} / ${activePages.size.coerceAtLeast(1)}",
                toBookmarkActive = isBookmarkActiveForPage(activePageIndex),
                previous = delta < 0,
                axis = axis,
                style = settings.pageTurnStyle,
            ) {
                saveActiveReading()
            }
        } else {
            showViewerPage(theme)
        }
        return true
    }

    private fun playPageTurnFeedback(target: View?, previous: Boolean) {
        when (settings.pageTurnFeedback) {
            PageTurnFeedback.NONE -> Unit
            PageTurnFeedback.VIBRATION -> target?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            PageTurnFeedback.SOUND -> playSyntheticPageTurnSound(previous)
        }
    }

    private fun playUiFeedback(kind: UiFeedbackKind, target: View? = rootLayer ?: window.decorView) {
        when (settings.pageTurnFeedback) {
            PageTurnFeedback.NONE -> Unit
            PageTurnFeedback.VIBRATION -> target?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            PageTurnFeedback.SOUND -> playSyntheticUiSound(kind)
        }
    }

    private fun dismissOverlayWithFeedback() {
        playUiFeedback(UiFeedbackKind.CLOSE)
        rootLayer?.dismissOverlay()
    }

    private fun View.setUiClickListener(kind: UiFeedbackKind = UiFeedbackKind.PRESS, action: () -> Unit) {
        isClickable = true
        setOnClickListener {
            playUiFeedback(kind, this)
            action()
        }
    }

    private fun initializeSyntheticSounds() {
        val runtimeCacheDir = cacheDir
        Thread {
            soundPlayer.prepare(runtimeCacheDir)
            if (activityDestroyed) {
                soundPlayer.release()
            }
        }.apply {
            name = "DurumariSoundInit"
            isDaemon = true
            start()
        }
    }

    private fun playSyntheticPageTurnSound(previous: Boolean) {
        soundPlayer.playPageTurn(previous)
    }

    private fun playSyntheticUiSound(kind: UiFeedbackKind) {
        soundPlayer.playUi(kind.toUiSoundKind())
    }

    private fun UiFeedbackKind.toUiSoundKind(): UiSoundKind {
        return when (this) {
            UiFeedbackKind.OPEN -> UiSoundKind.OPEN
            UiFeedbackKind.CLOSE -> UiSoundKind.CLOSE
            UiFeedbackKind.PRESS -> UiSoundKind.PRESS
        }
    }

    private fun showViewerMenuSheet(theme: ThemeTokens) {
        playUiFeedback(UiFeedbackKind.OPEN)
        val sheet = createSheetSurface(theme)
        sheet.addView(createSheetHandle(theme), linear(dp(42), dp(5), top = 17, bottom = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val currentPage = activePageIndex + 1
        val totalPages = activePages.size.coerceAtLeast(1)
        val title = activeDocument?.title ?: "열린 문서 없음"

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBlock.addView(text(title, theme.text, 18f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, linear(match, wrap))
        titleBlock.addView(text("p.$currentPage / $totalPages", theme.secondary, 13f, bold = false), linear(match, wrap, top = 4))
        header.addView(titleBlock, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(text("×", theme.secondary, 28f, bold = true, gravity = Gravity.CENTER).apply {
            minWidth = dp(48)
            minHeight = dp(48)
            setUiClickListener(UiFeedbackKind.CLOSE) { rootLayer?.dismissOverlay() }
        }, linear(dp(48), dp(48)))
        sheet.addView(header, linear(match, wrap, bottom = 16))

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = if (totalPages <= 1) 0 else (((currentPage - 1).toFloat() / (totalPages - 1)) * 100).toInt()
            progressTintList = ColorStateList.valueOf(theme.accent)
            progressBackgroundTintList = ColorStateList.valueOf(theme.border)
        }
        sheet.addView(progress, linear(match, dp(4), bottom = 24))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val firstRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        firstRow.addView(createViewerAction(theme, "🔖", "책갈피").apply {
            setUiClickListener { toggleActiveBookmark(theme) }
        }, LinearLayout.LayoutParams(0, dp(78), 1f))
        firstRow.addView(createViewerAction(theme, "📑", "목차", enabled = false), LinearLayout.LayoutParams(0, dp(78), 1f).apply { leftMargin = dp(10) })
        firstRow.addView(createViewerAction(theme, "🧭", "이동").apply {
            setUiClickListener(UiFeedbackKind.OPEN) { showPageMoveSheet(theme) }
        }, LinearLayout.LayoutParams(0, dp(78), 1f).apply { leftMargin = dp(10) })
        val secondRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondRow.addView(createViewerAction(theme, "🔤", "인코딩").apply {
            setUiClickListener(UiFeedbackKind.OPEN) { showEncodingSheet(theme) }
        }, LinearLayout.LayoutParams(0, dp(78), 1f))
        secondRow.addView(createViewerAction(theme, "⚙️", "설정").apply {
            setUiClickListener(UiFeedbackKind.OPEN) { showSettingsOverlay() }
        }, LinearLayout.LayoutParams(0, dp(78), 1f).apply { leftMargin = dp(10) })
        secondRow.addView(createViewerAction(theme, "📚", "목록").apply {
            setUiClickListener(UiFeedbackKind.CLOSE) {
                saveActiveReading()
                clearViewerResume()
                rootLayer?.dismissOverlay()
                activeDocument = null
                activePages = emptyList()
                activePageIndex = 0
                activeDocumentText = null
                showMainScreen()
            }
        }, LinearLayout.LayoutParams(0, dp(78), 1f).apply { leftMargin = dp(10) })
        grid.addView(firstRow, linear(match, wrap))
        grid.addView(secondRow, linear(match, wrap, top = 10))
        sheet.addView(grid, linear(match, wrap))

        rootLayer?.showBottomSheet(sheet, theme)
    }

    private fun showPageMoveSheet(theme: ThemeTokens) {
        val sheet = createSheetSurface(theme)
        val currentPage = activePageIndex + 1
        val totalPages = activePages.size.coerceAtLeast(1)
        sheet.addView(createSheetHandle(theme), linear(dp(42), dp(5), top = 17, bottom = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sheet.addView(createSheetHeader(theme, "페이지 이동", "현재 p.$currentPage / $totalPages"), linear(match, wrap, bottom = 16))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val pageInput = EditText(this).apply {
            setText(currentPage.toString())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = Typeface.create(appTypeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = roundedRect(theme.bg, 4, strokeColor = theme.border)
        }
        inputRow.addView(pageInput, linear(dp(100), dp(46)))
        inputRow.addView(text("/ $totalPages", theme.secondary, 16f, bold = false, gravity = Gravity.CENTER_VERTICAL), linear(wrap, dp(46), left = 12))
        sheet.addView(inputRow, linear(match, wrap, bottom = 12))

        val bookmarkPages = bookmarks
            .filter { it.documentId == activeDocument?.documentId }
            .map { it.page.coerceIn(1, totalPages) }
            .distinct()
            .sorted()
        var sliderRef: PageMoveSliderView? = null
        var syncingDraft = false
        var firstPageButton: TextView? = null
        var previousBookmarkButton: TextView? = null
        var nextBookmarkButton: TextView? = null

        fun parsedDraftPage(): Int? = pageInput.text.toString().toIntOrNull()?.coerceIn(1, totalPages)
        fun draftPage(): Int = parsedDraftPage() ?: currentPage

        fun setShortcutEnabled(button: TextView?, enabled: Boolean) {
            button ?: return
            button.isEnabled = enabled
            button.isClickable = enabled
            button.alpha = if (enabled) 1f else 0.45f
            button.setTextColor(if (enabled) theme.text else theme.secondary)
        }

        fun refreshPageMoveActions(updateSlider: Boolean = true) {
            val page = draftPage()
            if (updateSlider && parsedDraftPage() != null) {
                sliderRef?.setPageSilently(page)
            }
            setShortcutEnabled(firstPageButton, page > 1)
            setShortcutEnabled(previousBookmarkButton, bookmarkPages.any { it < page })
            setShortcutEnabled(nextBookmarkButton, bookmarkPages.any { it > page })
        }

        fun setDraftPage(page: Int) {
            val draftPage = page.coerceIn(1, totalPages)
            syncingDraft = true
            pageInput.setText(draftPage.toString())
            pageInput.setSelection(pageInput.text.length)
            sliderRef?.setPageSilently(draftPage)
            syncingDraft = false
            refreshPageMoveActions(updateSlider = false)
        }

        val slider = PageMoveSliderView(
            theme = theme,
            pageCount = totalPages,
            initialPage = currentPage,
            bookmarkPages = bookmarkPages,
            onUserPageSelected = ::setDraftPage,
        ).apply {
            sliderRef = this
            isEnabled = totalPages > 1
        }
        sheet.addView(slider, linear(match, dp(62), bottom = 18))

        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("첫페이지", "이전 책갈피", "다음 책갈피").forEachIndexed { index, label ->
            val button = text(label, theme.text, 14f, bold = false, gravity = Gravity.CENTER).apply {
                background = roundedRect(theme.card, 12, strokeColor = theme.border)
                setUiClickListener {
                    val targetBookmarkPage = when (index) {
                        1 -> bookmarkPages.lastOrNull { it < draftPage() }
                        2 -> bookmarkPages.firstOrNull { it > draftPage() }
                        else -> 1
                    }
                    if (targetBookmarkPage != null) setDraftPage(targetBookmarkPage)
                }
            }
            when (index) {
                0 -> firstPageButton = button
                1 -> previousBookmarkButton = button
                2 -> nextBookmarkButton = button
            }
            shortcuts.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
        sheet.addView(shortcuts, linear(match, wrap, bottom = 20))
        pageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!syncingDraft) refreshPageMoveActions()
            }
        })
        refreshPageMoveActions()
        sheet.addView(text("확인", theme.accentForeground, 16f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.accent, 12)
            setUiClickListener {
                val target = pageInput.text.toString().toIntOrNull()?.coerceIn(1, totalPages) ?: currentPage
                rootLayer?.dismissOverlay()
                if (target != currentPage) {
                    activePageIndex = target - 1
                    showViewerPage(theme)
                }
            }
        }, linear(match, dp(48)))

        rootLayer?.showBottomSheet(sheet, theme)
    }

    private fun showEncodingSheet(theme: ThemeTokens) {
        val sheet = createSheetSurface(theme)
        sheet.addView(createSheetHandle(theme), linear(dp(42), dp(5), top = 17, bottom = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val document = activeDocument
        val detected = TextEncodings.displayName(document?.detectedTextEncoding)
        val current = TextEncodings.normalize(activeEncoding ?: document?.textEncoding ?: TextEncodings.UTF8)
        sheet.addView(createSheetHeader(theme, "인코딩 선택", "원본 : $detected"), linear(match, wrap, bottom = 16))
        TextEncodings.selectableOptions.forEachIndexed { index, option ->
            val active = TextEncodings.same(current, option.value)
            sheet.addView(text(option.label, if (active) theme.accentText else theme.text, 15f, bold = true, gravity = Gravity.CENTER).apply {
                minHeight = dp(52)
                alpha = if (active) 0.62f else 1f
                background = roundedRect(if (active) theme.card else theme.bg, 12, strokeColor = if (active) theme.accent else theme.border)
                isEnabled = !active && document != null && document.kind != BookKind.EPUB
                isClickable = isEnabled
                setUiClickListener {
                    if (document != null) {
                        rootLayer?.dismissOverlay()
                        showViewerLoadingThenPage(
                            theme = theme,
                            document = document,
                            targetOffset = currentViewerAnchorOffset(),
                            forceEncoding = option.value,
                        )
                    }
                }
            }, linear(match, dp(52), top = if (index == 0) 0 else 8))
        }
        rootLayer?.showBottomSheet(sheet, theme)
    }

    private fun loadDocumentForViewer(document: DocumentRecord, forceEncoding: String?): DecodedDocument {
        val stored = appStore.getDocument(document.documentId, includeText = true) ?: document
        val canUseCache = forceEncoding == null &&
            stored.text != null &&
            stored.textEncoding != null &&
            stored.detectedTextEncoding != null
        if (canUseCache) {
            return DecodedDocument(stored, stored.text.orEmpty())
        }
        val decoded = documentTextLoader.load(stored, forceEncoding)
        appStore.updateDocumentText(decoded.document)
        return decoded
    }

    private fun paginateText(
        text: String,
        measuredWidthPx: Int? = null,
        measuredHeightPx: Int? = null,
        readerSettings: ReaderSettings = settings,
        typeface: Typeface = loadReaderTypeface(readerSettings),
        progressCallback: ((Float) -> Unit)? = null,
    ): PaginationResult {
        val metrics = resources.displayMetrics
        val root = rootLayer
        val contentWidth = measuredWidthPx?.takeIf { it > 0 }
            ?: root?.contentFrameWidthPx?.takeIf { it > 0 }
            ?: metrics.widthPixels
        val contentHeight = measuredHeightPx?.takeIf { it > 0 }
            ?: root?.contentFrameHeightPx?.takeIf { it > 0 }
            ?: (metrics.heightPixels - safeTopInset - safeBottomInset).coerceAtLeast(1)
        return paginateTextForFrame(
            text = text,
            readerSettings = readerSettings,
            typeface = typeface,
            widthPx = contentWidth,
            heightPx = contentHeight,
            density = metrics.density,
            progressCallback = progressCallback,
        )
    }

    private fun paginateTextForFrame(
        text: String,
        readerSettings: ReaderSettings,
        typeface: Typeface,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        progressCallback: ((Float) -> Unit)? = null,
    ): PaginationResult {
        return PaginationResult(
            pages = ReaderCanvasView.paginate(
                text = text,
                settings = readerSettings,
                typeface = typeface,
                widthPx = widthPx,
                heightPx = heightPx,
                density = density,
                progressCallback = progressCallback,
            ),
            widthPx = widthPx,
            heightPx = heightPx,
        )
    }

    private fun applyPaginationResult(pagination: PaginationResult) {
        activePages = pagination.pages
        activePaginationWidthPx = pagination.widthPx
        activePaginationHeightPx = pagination.heightPx
    }

    private fun verifyViewerPaginationFrame(canvas: ReaderCanvasView) {
        var checked = false
        lateinit var listener: View.OnLayoutChangeListener

        fun check(view: View, widthPx: Int, heightPx: Int) {
            if (checked || widthPx <= 0 || heightPx <= 0) return
            checked = true
            view.removeOnLayoutChangeListener(listener)
            repaginateForMeasuredCanvasIfNeeded(canvas, widthPx, heightPx)
        }

        listener = View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            check(view, right - left, bottom - top)
        }
        canvas.addOnLayoutChangeListener(listener)
        canvas.post {
            check(canvas, canvas.width, canvas.height)
        }
    }

    private fun repaginateForMeasuredCanvasIfNeeded(
        canvas: ReaderCanvasView,
        widthPx: Int,
        heightPx: Int,
    ) {
        val text = activeDocumentText ?: return
        if (activePages.isEmpty()) return
        val pendingAnchor = pendingViewerAnchorOffset
        val needsPagination = activePaginationWidthPx != widthPx || activePaginationHeightPx != heightPx
        if (!needsPagination && pendingAnchor == null) return

        if (needsPagination) {
            val requestId = ++paginationRequestId
            val documentId = activeDocument?.documentId
            val settingsSnapshot = settings
            val typefaceSnapshot = loadReaderTypeface(settingsSnapshot)
            val density = resources.displayMetrics.density
            Thread {
                val result = runCatching {
                    paginateTextForFrame(
                        text = text,
                        readerSettings = settingsSnapshot,
                        typeface = typefaceSnapshot,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        density = density,
                    )
                }
                runOnUiThread {
                    val pagination = result.getOrNull() ?: return@runOnUiThread
                    if (
                        activityDestroyed ||
                        paginationRequestId != requestId ||
                        activeReaderCanvas !== canvas ||
                        activeDocument?.documentId != documentId ||
                        activeDocumentText !== text ||
                        settings != settingsSnapshot
                    ) {
                        return@runOnUiThread
                    }
                    val targetOffset = pendingViewerAnchorOffset ?: anchorOffsetForPage(activePageIndex)
                    applyPaginationResult(pagination)
                    applyMeasuredPaginationToCanvas(canvas, targetOffset)
                }
            }.apply {
                name = "DurumariPagination"
                start()
            }
            return
        }

        val targetOffset = pendingAnchor ?: anchorOffsetForPage(activePageIndex)
        applyMeasuredPaginationToCanvas(canvas, targetOffset)
    }

    private fun applyMeasuredPaginationToCanvas(canvas: ReaderCanvasView, targetOffset: Int) {
        activePageIndex = pageIndexForOffset(activePages, targetOffset)
        syncBookmarksForActivePages()
        canvas.readerSettings = settings
        canvas.readerTypeface = loadReaderTypeface(settings)
        canvas.pageText = pageTextForPage(activePageIndex)
        canvas.pageNumberText = "${activePageIndex + 1} / ${activePages.size.coerceAtLeast(1)}"
        canvas.bookmarkActive = isBookmarkActiveForPage(activePageIndex)
        pendingViewerAnchorOffset = null
        saveActiveReading()
    }

    private fun normalizeViewerText(text: String): String {
        return if (text.indexOf('\r') >= 0) {
            text.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            text
        }
    }

    private fun saveActiveReading(anchorOffsetOverride: Int? = null) {
        val document = activeDocument ?: return
        val total = activePages.size.coerceAtLeast(1)
        val resolvedPageIndex = anchorOffsetOverride
            ?.let { pageIndexForOffset(activePages, it) }
            ?: activePageIndex
        val current = (resolvedPageIndex + 1).coerceIn(1, total)
        val now = System.currentTimeMillis()
        val reading = ReadingRecord(
            documentId = document.documentId,
            lastPage = current,
            totalPages = total,
            progress = progressForPageIndex(resolvedPageIndex),
            openedAt = now,
            completed = current >= total,
            completedAt = if (current >= total) now else null,
            anchorOffset = anchorOffsetOverride ?: anchorOffsetForPage(resolvedPageIndex),
        )
        appStore.upsertReading(reading)
        readingsById = readingsById + (document.documentId to reading)
    }

    private fun toggleActiveBookmark(theme: ThemeTokens) {
        val document = activeDocument ?: return
        val total = activePages.size.coerceAtLeast(1)
        val current = (activePageIndex + 1).coerceIn(1, total)
        val text = activeDocumentText.orEmpty()
        val existing = bookmarks.firstOrNull { bookmark ->
            bookmark.documentId == document.documentId &&
                bookmarkBelongsToPage(bookmark, activePageIndex, text)
        }
        val added = existing == null
        if (existing != null) {
            appStore.removeBookmark(existing.bookmarkId)
        } else {
            val pageText = pageTextForPage(activePageIndex)
            val preview = compactWhitespace(pageText).take(140).ifBlank { document.title }
            val bookmark = BookmarkRecord(
                bookmarkId = stableId("${document.documentId}:p$current"),
                documentId = document.documentId,
                page = current,
                totalPages = total,
                progress = progressForPageIndex(activePageIndex),
                preview = preview,
                createdAt = System.currentTimeMillis(),
                anchorOffset = currentViewerAnchorOffset(),
            )
            appStore.upsertBookmarks(listOf(bookmark))
        }
        reloadLibraryState()
        rootLayer?.dismissOverlay()
        showViewerPage(theme)
        Toast.makeText(this, if (added) "책갈피를 추가했습니다." else "책갈피를 해제했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun anchorOffsetForPage(pageIndex: Int): Int {
        return activePages.getOrNull(pageIndex.coerceIn(0, activePages.size.coerceAtLeast(1) - 1))?.startOffset ?: 0
    }

    private fun currentViewerAnchorOffset(): Int {
        return pendingViewerAnchorOffset ?: anchorOffsetForPage(activePageIndex)
    }

    private fun progressForPageIndex(pageIndex: Int): Float {
        val total = activePages.size.coerceAtLeast(1)
        return if (total <= 1) 0f else pageIndex.coerceIn(0, total - 1).toFloat() / (total - 1)
    }

    private fun pageIndexForOffset(pages: List<ReaderCanvasView.PageSlice>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        val textLength = pages.lastOrNull()?.endOffset ?: 0
        val safeOffset = offset.coerceIn(0, textLength.coerceAtLeast(0))
        var low = 0
        var high = pages.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pages[mid].startOffset <= safeOffset) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.coerceIn(0, pages.size - 1)
    }

    private fun anchorOffsetForReading(
        reading: ReadingRecord,
        pages: List<ReaderCanvasView.PageSlice>,
        text: String,
    ): Int {
        return reading.anchorOffset
            ?: (reading.progress * text.length.coerceAtLeast(0)).roundToInt()
                .takeIf { reading.progress > 0f }
            ?: pages.getOrNull(reading.lastPage - 1)?.startOffset
            ?: 0
    }

    private fun previewForPage(pageIndex: Int): String {
        return compactWhitespace(pageTextForPage(pageIndex)).take(140)
    }

    private fun pageTextForPage(pageIndex: Int): String {
        val page = activePages.getOrNull(pageIndex) ?: return ""
        if (page.text.isNotEmpty()) return page.text
        val text = activeDocumentText.orEmpty()
        val start = page.startOffset.coerceIn(0, text.length)
        val end = page.endOffset.coerceIn(start, text.length)
        return text.substring(start, end).trimEnd()
    }

    private fun isBookmarkActiveForPage(pageIndex: Int): Boolean {
        val documentId = activeDocument?.documentId ?: return false
        val text = activeDocumentText.orEmpty()
        return bookmarks.any { bookmark ->
            bookmark.documentId == documentId &&
                bookmarkBelongsToPage(bookmark, pageIndex, text)
        }
    }

    private fun bookmarkBelongsToPage(bookmark: BookmarkRecord, pageIndex: Int, text: String): Boolean {
        val page = activePages.getOrNull(pageIndex) ?: return false
        val textLength = text.length.coerceAtLeast(0)
        val start = page.startOffset.coerceIn(0, textLength)
        val end = page.endOffset.coerceIn(start, textLength)
        val offset = resolveBookmarkAnchorOffset(bookmark, text)
        return if (pageIndex == activePages.lastIndex) {
            offset in start..end
        } else {
            offset in start until end
        }
    }

    private fun syncBookmarksForActivePages() {
        val document = activeDocument ?: return
        if (activePages.isEmpty()) return
        val text = activeDocumentText.orEmpty()
        val total = activePages.size.coerceAtLeast(1)
        val changed = mutableListOf<BookmarkRecord>()
        val nextBookmarks = bookmarks.map { bookmark ->
            if (bookmark.documentId != document.documentId) return@map bookmark
            val offset = resolveBookmarkAnchorOffset(bookmark, text)
            val pageIndex = pageIndexForOffset(activePages, offset)
            val synced = bookmark.copy(
                page = pageIndex + 1,
                totalPages = total,
                progress = progressForPageIndex(pageIndex),
                preview = previewForPage(pageIndex).ifBlank { bookmark.preview },
                anchorOffset = offset,
            )
            val differs = synced.page != bookmark.page ||
                synced.totalPages != bookmark.totalPages ||
                abs(synced.progress - bookmark.progress) > 0.000001f ||
                synced.preview != bookmark.preview ||
                synced.anchorOffset != bookmark.anchorOffset
            if (differs) changed.add(synced)
            synced
        }
        if (changed.isNotEmpty()) {
            appStore.upsertBookmarks(changed)
            replaceBookmarks(nextBookmarks)
        }
    }

    private fun resolveBookmarkAnchorOffset(bookmark: BookmarkRecord, text: String): Int {
        val textLength = text.length.coerceAtLeast(0)
        bookmark.anchorOffset?.let { return it.coerceIn(0, textLength) }
        findPreviewOffset(bookmark.preview, text)?.let { return it.coerceIn(0, textLength) }
        if (bookmark.progress > 0f) {
            return (bookmark.progress * textLength).roundToInt().coerceIn(0, textLength)
        }
        return activePages.getOrNull(bookmark.page - 1)?.startOffset ?: 0
    }

    private fun findPreviewOffset(preview: String, text: String): Int? {
        val needle = compactWhitespace(preview)
        if (needle.isBlank()) return null
        val direct = text.indexOf(needle)
        if (direct >= 0) return direct

        val normalizedChars = StringBuilder()
        val offsets = mutableListOf<Int>()
        var previousWasSpace = false
        text.forEachIndexed { index, char ->
            if (char.isWhitespace()) {
                if (!previousWasSpace) {
                    normalizedChars.append(' ')
                    offsets.add(index)
                    previousWasSpace = true
                }
            } else {
                normalizedChars.append(char)
                offsets.add(index)
                previousWasSpace = false
            }
        }
        val normalizedIndex = normalizedChars.indexOf(needle)
        return if (normalizedIndex >= 0) offsets.getOrNull(normalizedIndex) else null
    }

    private fun compactWhitespace(value: String): String {
        return WHITESPACE_REGEX.replace(value, " ").trim()
    }

    private fun createSheetSurface(theme: ThemeTokens): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(28))
            background = topRoundedRect(theme.card, 24, strokeColor = theme.border)
            clipToOutline = false
        }
    }

    private fun createSheetHandle(theme: ThemeTokens): View {
        return View(this).apply { background = roundedRect(theme.border, 3) }
    }

    private fun createSheetHeader(theme: ThemeTokens, title: String, subtitle: String): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(text(title, theme.text, 18f, bold = true), linear(match, wrap))
        titleBlock.addView(text(subtitle, theme.secondary, 13f, bold = false), linear(match, wrap, top = 4))
        header.addView(titleBlock, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(text("×", theme.secondary, 28f, bold = true, gravity = Gravity.CENTER).apply {
            minWidth = dp(48)
            minHeight = dp(48)
            setUiClickListener(UiFeedbackKind.CLOSE) { rootLayer?.dismissOverlay() }
        }, linear(dp(48), dp(48)))
        return header
    }

    private fun createPageSlider(theme: ThemeTokens, ratio: Float): View {
        val safeRatio = ratio.coerceIn(0f, 1f)
        val slider = FrameLayout(this)
        val track = FrameLayout(this).apply {
            background = roundedRect(theme.border, 3)
        }
        val fill = View(this).apply {
            background = roundedRect(theme.accent, 3)
        }
        val thumb = View(this).apply {
            background = roundedRect(theme.card, 11, strokeColor = theme.accent, strokeWidthDp = 2)
        }
        track.addView(fill, FrameLayout.LayoutParams(0, match))
        slider.addView(track, FrameLayout.LayoutParams(match, dp(6), Gravity.CENTER_VERTICAL))
        slider.addView(thumb, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL))
        slider.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val trackWidth = slider.width
            val fillWidth = (trackWidth * safeRatio).toInt()
            fill.layoutParams = fill.layoutParams.apply { width = fillWidth }
            thumb.layoutParams = (thumb.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = (fillWidth - dp(11)).coerceIn(0, (trackWidth - dp(22)).coerceAtLeast(0))
            }
        }
        return slider
    }

    private fun createViewerAction(theme: ThemeTokens, icon: String, label: String, enabled: Boolean = true): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isEnabled = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.4f
            background = roundedRect(theme.card, 16, strokeColor = theme.border)
            addView(text(icon, theme.text, 23f, gravity = Gravity.CENTER), linear(match, wrap))
            addView(text(label, theme.text, 14f, bold = false, gravity = Gravity.CENTER), linear(match, wrap, top = 7))
        }
    }

    private fun showSettingsOverlay() {
        var draftSettings = normalizeReaderSettings(settings)
        val viewerWasOpen = activeDocument != null || activePages.isNotEmpty()
        val appliedTheme = DurumariThemes.tokens(settings.theme)
        val panelRoot = FrameLayout(this).apply {
            setBackgroundColor(appliedTheme.outer)
            clipChildren = false
            clipToPadding = false
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(appliedTheme.outer)
        }
        panelRoot.addView(panel, FrameLayout.LayoutParams(match, match))
        val dropdownLayer = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = false
        }
        panelRoot.addView(dropdownLayer, FrameLayout.LayoutParams(match, match))

        val previewArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(appliedTheme.card)
            setPadding(
                dp(SETTINGS_SIDE_PADDING_DP),
                dp(SETTINGS_BLOCK_PADDING_DP),
                dp(SETTINGS_SIDE_PADDING_DP),
                dp(SETTINGS_BLOCK_PADDING_DP),
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = text("설정", appliedTheme.text, 23f, bold = true)
        val closeView = text("×", appliedTheme.secondary, 28f, bold = true, gravity = Gravity.CENTER).apply {
            minWidth = dp(44)
            minHeight = dp(44)
            setUiClickListener(UiFeedbackKind.CLOSE) {
                dropdownLayer.removeAllViews()
                pruneReaderTypefaceCache(draftSettings)
                rootLayer?.dismissOverlay()
            }
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(closeView, linear(wrap, dp(44)))
        previewArea.addView(header, linear(match, wrap))

        val previewHost = FrameLayout(this)
        previewArea.addView(previewHost, linear(match, wrap, top = SETTINGS_INNER_GAP_DP))
        panel.addView(previewArea, linear(match, wrap))
        panel.addView(View(this).apply { setBackgroundColor(appliedTheme.border) }, linear(match, dp(1)))

        val settingsArea = FrameLayout(this).apply {
            setBackgroundColor(appliedTheme.bg)
        }
        val scroll = ScrollView(this).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(appliedTheme.bg)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(SETTINGS_SIDE_PADDING_DP),
                dp(SETTINGS_SECTION_GAP_DP),
                dp(SETTINGS_SIDE_PADDING_DP),
                dp(SETTINGS_SECTION_GAP_DP),
            )
        }

        scroll.addView(body, FrameLayout.LayoutParams(match, wrap))
        settingsArea.addView(scroll, FrameLayout.LayoutParams(match, match))
        panel.addView(settingsArea, LinearLayout.LayoutParams(match, 0, 1f))
        val confirmButton = text("확인", appliedTheme.accentForeground, 16f, bold = true, gravity = Gravity.CENTER).apply {
            minHeight = dp(48)
            background = roundedRect(appliedTheme.accent, 12)
            setUiClickListener {
                val previous = settings
                val targetOffset = if (viewerWasOpen && activeDocumentText != null && paginationSettingsChanged(previous, draftSettings)) {
                    currentViewerAnchorOffset()
                } else {
                    null
                }
                settings = normalizeReaderSettings(draftSettings)
                settingsStore.save(settings)
                pruneReaderTypefaceCache(settings)
                rootLayer?.dismissOverlay()
                if (viewerWasOpen) {
                    val theme = DurumariThemes.tokens(settings.theme)
                    val needsPagination = activeDocumentText != null && paginationSettingsChanged(previous, settings)
                    if (needsPagination) {
                        val pagination = paginateText(activeDocumentText.orEmpty())
                        applyPaginationResult(pagination)
                        val resolvedTargetOffset = targetOffset ?: currentViewerAnchorOffset()
                        activePageIndex = pageIndexForOffset(activePages, resolvedTargetOffset)
                        pendingViewerAnchorOffset = resolvedTargetOffset
                        syncBookmarksForActivePages()
                    }
                    val needsRedraw = needsPagination || previous.theme != settings.theme
                    if (needsRedraw) {
                        configureSystemBars(theme)
                        rootLayer?.applyTheme(theme)
                        showViewerPage(theme)
                    }
                } else {
                    if (previous.theme != settings.theme || previous.hideCompleted != settings.hideCompleted) {
                        reloadLibraryState()
                        showMainScreen()
                    }
                }
            }
        }
        val buttonArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(appliedTheme.card)
            setPadding(
                dp(SETTINGS_SIDE_PADDING_DP),
                dp(SETTINGS_SECTION_GAP_DP),
                dp(SETTINGS_SIDE_PADDING_DP),
                dp(SETTINGS_SECTION_GAP_DP),
            )
            addView(confirmButton, linear(match, dp(48)))
        }
        panel.addView(View(this).apply { setBackgroundColor(appliedTheme.border) }, linear(match, dp(1)))
        panel.addView(buttonArea, linear(match, wrap))

        lateinit var rebuild: () -> Unit

        fun hideFontDropdown() {
            dropdownLayer.removeAllViews()
            dropdownLayer.visibility = View.GONE
            dropdownLayer.isClickable = false
            pruneReaderTypefaceCache(draftSettings)
        }

        fun showFontDropdown(anchor: View) {
            anchor.isPressed = false
            hideFontDropdown()
            preloadReaderTypefaces()

            dropdownLayer.visibility = View.VISIBLE
            dropdownLayer.isClickable = true
            dropdownLayer.addView(
                View(this).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setSoundEffectsEnabled(false)
                    setUiClickListener(UiFeedbackKind.CLOSE) { hideFontDropdown() }
                },
                FrameLayout.LayoutParams(match, match),
            )

            val rootLocation = IntArray(2)
            val anchorLocation = IntArray(2)
            panelRoot.getLocationOnScreen(rootLocation)
            anchor.getLocationOnScreen(anchorLocation)

            val comboLeft = anchorLocation[0] - rootLocation[0] + dp(SETTINGS_LABEL_WIDTH_DP)
            val comboTop = anchorLocation[1] - rootLocation[1] + anchor.height
            val comboWidth = (anchor.width - dp(SETTINGS_LABEL_WIDTH_DP)).coerceAtLeast(dp(160))
            val dropdownHeight = (
                readerFontOptions.size * dp(SETTINGS_COMBO_ROW_HEIGHT_DP) +
                    (readerFontOptions.size - 1).coerceAtLeast(0) +
                    dp(SETTINGS_DROPDOWN_VERTICAL_PADDING_DP * 2)
                ).coerceAtMost((panelRoot.height - comboTop - dp(SETTINGS_SECTION_GAP_DP)).coerceAtLeast(dp(SETTINGS_COMBO_ROW_HEIGHT_DP)))
            val maxLeft = (panelRoot.width - comboWidth - dp(SETTINGS_SIDE_PADDING_DP)).coerceAtLeast(dp(SETTINGS_SIDE_PADDING_DP))
            val dropdownLeft = comboLeft.coerceIn(dp(SETTINGS_SIDE_PADDING_DP), maxLeft)

            dropdownLayer.addView(
                createFontPickerList(appliedTheme, draftSettings) { selected ->
                    draftSettings = draftSettings.copy(fontFamily = selected.family)
                    hideFontDropdown()
                    rebuild.invoke()
                }.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        elevation = dp(10).toFloat()
                    }
                },
                FrameLayout.LayoutParams(comboWidth, dropdownHeight).apply {
                    leftMargin = dropdownLeft
                    topMargin = comboTop
                },
            )
        }

        rebuild = {
            hideFontDropdown()
            rebuildSettingsBody(
                body = body,
                previewHost = previewHost,
                panel = panel,
                titleView = titleView,
                closeView = closeView,
                confirmButton = confirmButton,
                appliedTheme = appliedTheme,
                draftSettings = draftSettings,
                onFontPickerOpen = ::showFontDropdown,
                onSettingsChanged = { updated ->
                    draftSettings = normalizeReaderSettings(updated)
                    rebuild.invoke()
                },
            )
        }

        rebuild.invoke()

        rootLayer?.showFramedOverlay(panelRoot)
    }

    private fun rebuildSettingsBody(
        body: LinearLayout,
        previewHost: FrameLayout,
        panel: LinearLayout,
        titleView: TextView,
        closeView: TextView,
        confirmButton: TextView,
        appliedTheme: ThemeTokens,
        draftSettings: ReaderSettings,
        onFontPickerOpen: (View) -> Unit,
        onSettingsChanged: (ReaderSettings) -> Unit,
    ) {
        val previewTheme = DurumariThemes.tokens(draftSettings.theme)
        panel.setBackgroundColor(appliedTheme.outer)
        titleView.setTextColor(appliedTheme.text)
        closeView.setTextColor(appliedTheme.secondary)
        confirmButton.setTextColor(appliedTheme.accentForeground)
        confirmButton.background = roundedRect(appliedTheme.accent, 12)
        previewHost.removeAllViews()
        previewHost.addView(createPreview(previewTheme, draftSettings), FrameLayout.LayoutParams(match, wrap))
        body.removeAllViews()
        body.addView(
            createReadingSettingsSection(
                theme = appliedTheme,
                draftSettings = draftSettings,
                onFontPickerOpen = onFontPickerOpen,
                onSettingsChanged = onSettingsChanged,
            ),
            linear(match, wrap),
        )
        body.addView(createMarginSettingsSection(appliedTheme, draftSettings, onSettingsChanged), linear(match, wrap, top = SETTINGS_SECTION_GAP_DP))
        body.addView(createPageMovementSection(appliedTheme, draftSettings, onSettingsChanged), linear(match, wrap, top = SETTINGS_SECTION_GAP_DP))
        body.addView(
            createThemeFilterSection(
                theme = appliedTheme,
                draftSettings = draftSettings,
                onSettingsChanged = onSettingsChanged,
                onReset = {
                    onSettingsChanged(DurumariDefaults.readerSettings().copy(activeFolderId = settings.activeFolderId))
                },
                onClearFolders = {
                    appStore.clearAllFolders()
                    clearViewerResume()
                    settings = settings.copy(activeFolderId = null)
                    settingsStore.save(settings)
                    activeDocument = null
                    activePages = emptyList()
                    activePageIndex = 0
                    activeDocumentText = null
                    reloadLibraryState()
                    rootLayer?.dismissOverlay()
                    showMainScreen()
                },
            ),
            linear(match, wrap, top = SETTINGS_SECTION_GAP_DP),
        )
    }

    private fun showFolderNameSheet(
        theme: ThemeTokens,
        defaultName: String = "소설",
        onRegister: ((String) -> Unit)? = null,
    ) {
        playUiFeedback(UiFeedbackKind.OPEN)
        val sheet = createSheetSurface(theme)
        sheet.addView(createSheetHandle(theme), linear(dp(42), dp(5), top = 17, bottom = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
        sheet.addView(createSheetHeader(theme, "폴더 이름 지정", "탭에 표시될 이름을 입력하세요"), linear(match, wrap, bottom = 20))

        val input = EditText(this).apply {
            setText(defaultName)
            setSelectAllOnFocus(false)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(theme.text)
            setHintTextColor(theme.secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
            typeface = Typeface.create(appTypeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedRect(theme.bg, 18, strokeColor = theme.border)
            setPadding(dp(18), 0, dp(18), 0)
        }
        sheet.addView(input, linear(match, dp(70)))
        sheet.addView(text("등록하기", theme.accentForeground, 16f, bold = true, gravity = Gravity.CENTER).apply {
            minHeight = dp(48)
            background = roundedRect(theme.accent, 12)
            setUiClickListener {
                val name = input.text.toString().trim().ifBlank { defaultName }
                rootLayer?.dismissOverlay()
                onRegister?.invoke(name)
            }
        }, linear(match, dp(48), top = 24))

        rootLayer?.showBottomSheet(sheet, theme, dimColor = Color.argb(166, 0, 0, 0))
    }

    private fun createPreview(theme: ThemeTokens, previewSettings: ReaderSettings): View {
        val paddingLeft = dp(previewSettings.paddingLeft.coerceIn(0, 150))
        val paddingTop = dp(previewSettings.paddingTop.coerceIn(0, 120))
        val paddingRight = dp(previewSettings.paddingRight.coerceIn(0, 150))
        val paddingBottom = dp(previewSettings.paddingBottom.coerceIn(0, 120))
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(theme.bg, 18, strokeColor = theme.border)
            setPadding(0, paddingTop, 0, paddingBottom)
        }
        val content = TextView(this).apply {
            text = "소년은 개울가에서 소녀를 보자 곧 윤 초시네 증손녀딸이라는 걸 알 수 있었다."
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, previewSettings.fontSize.toFloat())
            typeface = loadReaderTypeface(previewSettings, bold = previewSettings.isBold)
            gravity = Gravity.START
            minLines = 2
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = previewSettings.letterSpacing / 24f
            }
            setLineSpacing(0f, previewSettings.lineHeight)
            setPadding(paddingLeft, 0, paddingRight, 0)
        }
        val pageNumber = text("1 / 1", theme.secondary, 15f, bold = false, gravity = Gravity.CENTER).apply {
            alpha = 0.45f
            typeface = loadReaderTypeface(previewSettings)
        }
        preview.addView(content, linear(match, wrap))
        preview.addView(pageNumber, linear(match, dp(PREVIEW_PAGE_NUMBER_RESERVED_DP)))
        return preview
    }

    private fun createReadingSettingsSection(
        theme: ThemeTokens,
        draftSettings: ReaderSettings,
        onFontPickerOpen: (View) -> Unit,
        onSettingsChanged: (ReaderSettings) -> Unit,
    ): View {
        val section = createSettingsSection(theme, "📖 읽기 설정")
        val fontIndex = currentReaderFontIndex(draftSettings)
        val currentFont = readerFontOptions[fontIndex]
        section.addView(createComboRow(theme, "🖋️ 서체", currentFont.label).apply {
            setUiClickListener(UiFeedbackKind.OPEN) { onFontPickerOpen(this) }
        }, linear(match, wrap))
        section.addView(createStepperRow(
            theme,
            "글자 크기",
            "${draftSettings.fontSize}pt",
            onMinus = { onSettingsChanged(draftSettings.copy(fontSize = (draftSettings.fontSize - 1).coerceAtLeast(10))) },
            onPlus = { onSettingsChanged(draftSettings.copy(fontSize = (draftSettings.fontSize + 1).coerceAtMost(36))) },
        ), linear(match, wrap))
        section.addView(createStepperRow(
            theme,
            "줄 간격",
            "%.1f".format(Locale.ROOT, draftSettings.lineHeight),
            onMinus = { onSettingsChanged(draftSettings.copy(lineHeight = stepFloat(draftSettings.lineHeight, -0.1f, 1f, 2.5f))) },
            onPlus = { onSettingsChanged(draftSettings.copy(lineHeight = stepFloat(draftSettings.lineHeight, 0.1f, 1f, 2.5f))) },
        ), linear(match, wrap))
        section.addView(createStepperRow(
            theme,
            "자간",
            "${draftSettings.letterSpacing.roundToInt()}px",
            onMinus = { onSettingsChanged(draftSettings.copy(letterSpacing = (draftSettings.letterSpacing - 1f).coerceAtLeast(-2f))) },
            onPlus = { onSettingsChanged(draftSettings.copy(letterSpacing = (draftSettings.letterSpacing + 1f).coerceAtMost(5f))) },
        ), linear(match, wrap))
        section.addView(createToggleRow(theme, "🅱️ 굵게", draftSettings.isBold) {
            onSettingsChanged(draftSettings.copy(isBold = !draftSettings.isBold))
        }, linear(match, wrap))
        return section
    }

    private fun createMarginSettingsSection(
        theme: ThemeTokens,
        draftSettings: ReaderSettings,
        onSettingsChanged: (ReaderSettings) -> Unit,
    ): View {
        val verticalPaddingStep = 5
        val horizontalPaddingStep = 1
        val section = createSettingsSection(theme, "📐 여백 설정")
        section.addView(createToggleRow(theme, "↔️ 좌우 여백 동일하게 조절", draftSettings.paddingLinked) {
            val linked = !draftSettings.paddingLinked
            onSettingsChanged(
                draftSettings.copy(
                    paddingLinked = linked,
                    paddingRight = if (linked) draftSettings.paddingLeft else draftSettings.paddingRight,
                ),
            )
        }, linear(match, wrap))
        section.addView(createStepperRow(
            theme,
            "위",
            "${draftSettings.paddingTop}px",
            onMinus = { onSettingsChanged(draftSettings.copy(paddingTop = (draftSettings.paddingTop - verticalPaddingStep).coerceAtLeast(0))) },
            onPlus = { onSettingsChanged(draftSettings.copy(paddingTop = (draftSettings.paddingTop + verticalPaddingStep).coerceAtMost(120))) },
        ), linear(match, wrap))
        section.addView(createStepperRow(
            theme,
            "아래",
            "${draftSettings.paddingBottom}px",
            onMinus = { onSettingsChanged(draftSettings.copy(paddingBottom = (draftSettings.paddingBottom - verticalPaddingStep).coerceAtLeast(0))) },
            onPlus = { onSettingsChanged(draftSettings.copy(paddingBottom = (draftSettings.paddingBottom + verticalPaddingStep).coerceAtMost(120))) },
        ), linear(match, wrap))
        section.addView(createStepperRow(
            theme,
            "왼쪽",
            "${draftSettings.paddingLeft}px",
            onMinus = {
                val value = (draftSettings.paddingLeft - horizontalPaddingStep).coerceAtLeast(0)
                onSettingsChanged(draftSettings.copy(paddingLeft = value, paddingRight = if (draftSettings.paddingLinked) value else draftSettings.paddingRight))
            },
            onPlus = {
                val value = (draftSettings.paddingLeft + horizontalPaddingStep).coerceAtMost(150)
                onSettingsChanged(draftSettings.copy(paddingLeft = value, paddingRight = if (draftSettings.paddingLinked) value else draftSettings.paddingRight))
            },
        ), linear(match, wrap))
        section.addView(createStepperRow(
            theme,
            "오른쪽",
            "${draftSettings.paddingRight}px",
            onMinus = {
                val value = (draftSettings.paddingRight - horizontalPaddingStep).coerceAtLeast(0)
                onSettingsChanged(draftSettings.copy(paddingRight = value, paddingLeft = if (draftSettings.paddingLinked) value else draftSettings.paddingLeft))
            },
            onPlus = {
                val value = (draftSettings.paddingRight + horizontalPaddingStep).coerceAtMost(150)
                onSettingsChanged(draftSettings.copy(paddingRight = value, paddingLeft = if (draftSettings.paddingLinked) value else draftSettings.paddingLeft))
            },
        ), linear(match, wrap))
        return section
    }

    private fun createPageMovementSection(
        theme: ThemeTokens,
        draftSettings: ReaderSettings,
        onSettingsChanged: (ReaderSettings) -> Unit,
    ): View {
        val section = createSettingsSection(theme, "👆 페이지 이동 및 피드백")
        section.addView(text("조작 방식", theme.secondary, 14f, bold = true), linear(match, wrap, top = SETTINGS_CONTROL_GAP_DP))
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            Triple("👆", "터치", draftSettings.pageTurnTouch),
            Triple("↔️", "스와이프", draftSettings.pageTurnSwipe),
            Triple("🔊", "볼륨키", draftSettings.volumeKeyPaging),
        ).forEachIndexed { index, item ->
            val toggle = {
                when (index) {
                    0 -> onSettingsChanged(draftSettings.copy(pageTurnTouch = !draftSettings.pageTurnTouch))
                    1 -> onSettingsChanged(draftSettings.copy(pageTurnSwipe = !draftSettings.pageTurnSwipe))
                    else -> {
                        val enabled = !draftSettings.volumeKeyPaging
                        onSettingsChanged(draftSettings.copy(volumeKeyPaging = enabled, pageTurnVolume = enabled))
                    }
                }
            }
            cards.addView(
                createPageTurnCard(theme, item.first, item.second, item.third, toggle),
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) leftMargin = dp(SETTINGS_CONTROL_GAP_DP)
                },
            )
        }
        section.addView(cards, linear(match, wrap, top = SETTINGS_CONTROL_GAP_DP))
        val feedbackValues = listOf("🔕 없음", "📳 진동", "🔊 소리")
        val feedbackIndex = when (draftSettings.pageTurnFeedback) {
            PageTurnFeedback.NONE -> 0
            PageTurnFeedback.VIBRATION -> 1
            PageTurnFeedback.SOUND -> 2
        }
        section.addView(createSegmentField(theme, "피드백", feedbackValues, feedbackIndex) { index ->
            val selected = when (index) {
                0 -> PageTurnFeedback.NONE
                1 -> PageTurnFeedback.VIBRATION
                else -> PageTurnFeedback.SOUND
            }
            onSettingsChanged(draftSettings.copy(pageTurnFeedback = selected))
        }, linear(match, wrap, top = SETTINGS_CONTROL_GAP_DP))
        val styleValues = listOf("■ 없음", "📖 책장", "↔️ 슬라이드")
        val styleIndex = when (draftSettings.pageTurnStyle) {
            PageTurnStyle.NONE -> 0
            PageTurnStyle.CURL -> 1
            PageTurnStyle.SLIDE -> 2
        }
        section.addView(createSegmentField(theme, "넘김 방식", styleValues, styleIndex) { index ->
            val selected = when (index) {
                0 -> PageTurnStyle.NONE
                1 -> PageTurnStyle.CURL
                else -> PageTurnStyle.SLIDE
            }
            onSettingsChanged(draftSettings.copy(pageTurnStyle = selected))
        }, linear(match, wrap))
        return section
    }

    private fun createThemeFilterSection(
        theme: ThemeTokens,
        draftSettings: ReaderSettings,
        onSettingsChanged: (ReaderSettings) -> Unit,
        onReset: () -> Unit,
        onClearFolders: () -> Unit,
    ): View {
        val section = createSettingsSection(theme, "🎨 테마 및 필터")
        val themeGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(DurumariThemes.light, DurumariThemes.dark, DurumariThemes.paper, DurumariThemes.chalk).forEachIndexed { index, option ->
            themeGrid.addView(
                createThemeOption(theme, option, draftSettings.theme == option.name).apply {
                    setUiClickListener {
                        onSettingsChanged(draftSettings.copy(theme = option.name))
                    }
                },
                LinearLayout.LayoutParams(0, dp(99), 1f).apply {
                    if (index > 0) leftMargin = dp(SETTINGS_CONTROL_GAP_DP)
                },
            )
        }
        section.addView(themeGrid, linear(match, wrap))
        section.addView(createToggleRow(theme, "✅ 완독한 책 목록에서 숨김", draftSettings.hideCompleted) {
            onSettingsChanged(draftSettings.copy(hideCompleted = !draftSettings.hideCompleted))
        }, linear(match, wrap, top = SETTINGS_CONTROL_GAP_DP))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(SETTINGS_CONTROL_GAP_DP), 0, dp(SETTINGS_ROW_VERTICAL_PADDING_DP))
        }
        actions.addView(text("♻️ 설정 초기화", theme.text, 14f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.card, 12, strokeColor = theme.border)
            setUiClickListener { onReset() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(text("🗑️ 폴더 전체 해제", theme.danger, 14f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.card, 12, strokeColor = theme.danger)
            setUiClickListener { onClearFolders() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(SETTINGS_CONTROL_GAP_DP) })
        section.addView(actions, linear(match, wrap))
        return section
    }

    private fun createSettingsSection(theme: ThemeTokens, title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(SETTINGS_SECTION_PADDING_DP),
                dp(SETTINGS_SECTION_PADDING_DP),
                dp(SETTINGS_SECTION_PADDING_DP),
                dp(SETTINGS_ROW_VERTICAL_PADDING_DP),
            )
            background = roundedRect(theme.card, 16, strokeColor = theme.border)
            addView(text(title, theme.accentText, 15f, bold = true), linear(match, wrap, bottom = SETTINGS_CONTROL_GAP_DP))
        }
    }

    private fun createComboRow(theme: ThemeTokens, label: String, value: String): View {
        val row = createSettingBaseRow(theme)
        row.addView(text(label, theme.secondary, 14f, bold = true), linear(dp(SETTINGS_LABEL_WIDTH_DP), wrap))
        row.addView(text(value, theme.text, 15f, bold = false, gravity = Gravity.CENTER_VERTICAL).apply {
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedRect(theme.bg, 12, strokeColor = theme.border)
        }, LinearLayout.LayoutParams(0, dp(SETTINGS_COMBO_ROW_HEIGHT_DP), 1f))
        row.addView(text("▾", theme.secondary, 13f, gravity = Gravity.CENTER), linear(dp(26), dp(SETTINGS_COMBO_ROW_HEIGHT_DP), left = -30))
        return row
    }

    private fun createFontPickerList(
        theme: ThemeTokens,
        draftSettings: ReaderSettings,
        onSelected: (ReaderFontOption) -> Unit,
    ): View {
        val selectedIndex = currentReaderFontIndex(draftSettings)
        var selectedRow: TextView? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(SETTINGS_DROPDOWN_VERTICAL_PADDING_DP), 0, dp(SETTINGS_DROPDOWN_VERTICAL_PADDING_DP))
        }
        readerFontOptions.forEachIndexed { index, option ->
            val selected = option.family == draftSettings.fontFamily
            val row = text(option.label, if (selected) theme.accentText else theme.text, 15f, bold = selected).apply {
                typeface = loadReaderTypeface(option, bold = selected)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(SETTINGS_SECTION_PADDING_DP), 0, dp(SETTINGS_SECTION_PADDING_DP), 0)
                background = GradientDrawable().apply {
                    setColor(if (selected) applyAlpha(theme.accent, 0.14f) else Color.TRANSPARENT)
                }
                isClickable = true
                isFocusable = true
                setUiClickListener { onSelected(option) }
            }
            if (selected) selectedRow = row
            content.addView(row, linear(match, dp(SETTINGS_COMBO_ROW_HEIGHT_DP)))
            if (index < readerFontOptions.lastIndex) {
                content.addView(View(this).apply {
                    setBackgroundColor(theme.border)
                }, linear(match, 1, left = SETTINGS_SECTION_PADDING_DP, right = SETTINGS_SECTION_PADDING_DP))
            }
        }
        return ScrollView(this).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = roundedRect(theme.bg, 12, strokeColor = theme.border, strokeWidthDp = 1)
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(match, wrap))
            post {
                val rowStep = dp(SETTINGS_COMBO_ROW_HEIGHT_DP) + 1
                val targetTop = selectedIndex * rowStep
                val centeredTop = targetTop - ((height - dp(SETTINGS_COMBO_ROW_HEIGHT_DP)) / 2)
                scrollTo(0, centeredTop.coerceAtLeast(0))
                selectedRow?.requestFocus()
            }
        }
    }

    private fun createStepperRow(
        theme: ThemeTokens,
        label: String,
        value: String,
        onMinus: (() -> Unit)? = null,
        onPlus: (() -> Unit)? = null,
    ): View {
        val row = createSettingBaseRow(theme)
        row.addView(text(label, theme.secondary, 14f, bold = true), linear(dp(96), wrap))
        row.addView(createMiniButton(theme, "−").apply {
            isEnabled = onMinus != null
            isClickable = onMinus != null
            alpha = if (onMinus != null) 1f else 0.45f
            if (onMinus != null) setUiClickListener { onMinus.invoke() }
        }, linear(dp(38), dp(38)))
        row.addView(text(value, theme.text, 15f, bold = true, gravity = Gravity.CENTER), LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(createMiniButton(theme, "+").apply {
            isEnabled = onPlus != null
            isClickable = onPlus != null
            alpha = if (onPlus != null) 1f else 0.45f
            if (onPlus != null) setUiClickListener { onPlus.invoke() }
        }, linear(dp(38), dp(38)))
        return row
    }

    private fun createToggleRow(
        theme: ThemeTokens,
        label: String,
        checked: Boolean,
        onToggle: (() -> Unit)? = null,
    ): View {
        val row = createSettingBaseRow(theme)
        row.isClickable = onToggle != null
        if (onToggle != null) {
            row.setUiClickListener { onToggle.invoke() }
        }
        row.addView(text(label, theme.text, 15f, bold = true, gravity = Gravity.CENTER_VERTICAL), LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(text(if (checked) "✓" else "", if (checked) theme.accentForeground else theme.accent, 18f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(if (checked) theme.accent else Color.TRANSPARENT, 8, strokeColor = theme.accent, strokeWidthDp = 2)
        }, linear(dp(28), dp(28), left = 12))
        return row
    }

    private fun createSettingBaseRow(theme: ThemeTokens): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(SETTINGS_ROW_VERTICAL_PADDING_DP), 0, dp(SETTINGS_ROW_VERTICAL_PADDING_DP))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(0, theme.border)
            }
        }
    }

    private fun createMiniButton(theme: ThemeTokens, value: String): View {
        return text(value, theme.text, 17f, bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(theme.card, 12, strokeColor = theme.border)
        }
    }

    private fun createPageTurnCard(
        theme: ThemeTokens,
        mark: String,
        label: String,
        selected: Boolean,
        onToggle: (() -> Unit)? = null,
    ): View {
        return text("$mark  $label", if (selected) theme.accentText else theme.text, 13f, bold = true, gravity = Gravity.CENTER).apply {
            gravity = Gravity.CENTER
            setPadding(dp(SETTINGS_CONTROL_GAP_DP), 0, dp(SETTINGS_CONTROL_GAP_DP), 0)
            isClickable = onToggle != null
            if (onToggle != null) setUiClickListener { onToggle.invoke() }
            background = roundedRect(
                color = if (selected) theme.bg else Color.TRANSPARENT,
                radiusDp = 14,
                strokeColor = if (selected) theme.accent else theme.border,
                strokeWidthDp = if (selected) 2 else 1,
            )
        }
    }

    private fun createSegmentField(
        theme: ThemeTokens,
        label: String,
        values: List<String>,
        activeIndex: Int,
        onSelect: ((Int) -> Unit)? = null,
    ): View {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(SETTINGS_ROW_VERTICAL_PADDING_DP), 0, dp(SETTINGS_ROW_VERTICAL_PADDING_DP))
        }
        group.addView(text(label, theme.secondary, 14f, bold = true), linear(match, wrap))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        values.forEachIndexed { index, value ->
            row.addView(
                text(value, if (index == activeIndex) theme.accentText else theme.text, 13f, bold = true, gravity = Gravity.CENTER).apply {
                    setPadding(dp(SETTINGS_CONTROL_GAP_DP), 0, dp(SETTINGS_CONTROL_GAP_DP), 0)
                    isClickable = onSelect != null
                    if (onSelect != null) setUiClickListener { onSelect.invoke(index) }
                    background = roundedRect(
                        color = if (index == activeIndex) theme.bg else Color.TRANSPARENT,
                        radiusDp = 14,
                        strokeColor = if (index == activeIndex) theme.accent else theme.border,
                        strokeWidthDp = if (index == activeIndex) 2 else 1,
                    )
                },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) leftMargin = dp(SETTINGS_CONTROL_GAP_DP)
                },
            )
        }
        group.addView(row, linear(match, wrap, top = SETTINGS_CONTROL_GAP_DP))
        return group
    }

    private fun createThemeOption(baseTheme: ThemeTokens, option: ThemeTokens, active: Boolean): View {
        val item = FrameLayout(this).apply {
            background = roundedRect(
                color = option.card,
                radiusDp = 14,
                strokeColor = if (active) baseTheme.accent else baseTheme.border,
                strokeWidthDp = if (active) 2 else 1,
            )
            isClickable = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }
        content.addView(View(this).apply { setBackgroundColor(option.accent) }, linear(match, dp(5), left = 2, right = 2))
        content.addView(text(themePreviewIcon(option.name), option.accentText, 22f, bold = false, gravity = Gravity.CENTER), linear(match, wrap, top = 9))
        content.addView(text(option.name.displayName, option.text, 13f, bold = true, gravity = Gravity.CENTER), linear(match, wrap, top = -2))
        content.addView(View(this).apply { setBackgroundColor(applyAlpha(option.text, 0.54f)) }, linear(dp(42), dp(2), top = 7))
        content.addView(View(this).apply { setBackgroundColor(applyAlpha(option.text, 0.25f)) }, linear(dp(58), dp(2), top = 7))
        item.addView(content, FrameLayout.LayoutParams(match, match))
        item.addView(
            text(if (active) "✓" else "", if (active) option.accentForeground else Color.TRANSPARENT, 14f, bold = true, gravity = Gravity.CENTER).apply {
                background = oval(
                    color = if (active) option.accent else option.card,
                    strokeColor = option.accent,
                    strokeWidthDp = 2,
                )
            },
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(5)
                bottomMargin = dp(5)
            },
        )
        return item
    }

    private fun themePreviewIcon(themeName: ThemeName): String {
        return when (themeName) {
            ThemeName.LIGHT -> "☀️"
            ThemeName.DARK -> "🌙"
            ThemeName.PAPER -> "📜"
            ThemeName.CHALK -> "▣"
        }
    }

    private fun configureSystemBars(theme: ThemeTokens) {
        configureSystemBars(
            statusBar = theme.statusBar,
            navigationBar = theme.navigationBar,
            darkStatusBarIcons = theme.darkStatusBarIcons,
            darkNavigationBarIcons = theme.darkNavigationBarIcons,
        )
    }

    private fun resolveIntroChrome(): IntroChrome {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) {
            IntroChrome(
                background = Color.rgb(13, 27, 42),
                title = Color.rgb(243, 201, 105),
                subtitle = Color.rgb(188, 181, 164),
                status = Color.rgb(188, 181, 164),
                progress = Color.rgb(243, 201, 105),
                progressTrack = Color.argb(68, 140, 182, 240),
                titleShadow = Color.argb(130, 42, 23, 4),
                darkStatusBarIcons = false,
                darkNavigationBarIcons = false,
            )
        } else {
            IntroChrome(
                background = Color.rgb(250, 247, 239),
                title = Color.rgb(137, 92, 29),
                subtitle = Color.rgb(103, 90, 70),
                status = Color.rgb(103, 90, 70),
                progress = Color.rgb(183, 122, 36),
                progressTrack = Color.rgb(219, 205, 173),
                titleShadow = Color.argb(74, 255, 255, 255),
                darkStatusBarIcons = true,
                darkNavigationBarIcons = true,
            )
        }
    }

    private fun createEmptyState(
        theme: ThemeTokens,
        title: String,
        body: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(42), dp(18), dp(42))
            background = roundedRect(theme.card, 14, strokeColor = theme.border)
        }
        panel.addView(text(title, theme.text, 17f, bold = true, gravity = Gravity.CENTER), linear(match, wrap))
        panel.addView(text(body, theme.secondary, 13f, bold = true, gravity = Gravity.CENTER).apply {
            setLineSpacing(0f, 1.18f)
        }, linear(match, wrap, top = 14))
        if (actionLabel != null && onAction != null) {
            panel.addView(text(actionLabel, theme.accentForeground, 14f, bold = true, gravity = Gravity.CENTER).apply {
                minHeight = dp(44)
                setPadding(dp(18), 0, dp(18), 0)
                background = roundedRect(theme.accent, 12)
                setUiClickListener(UiFeedbackKind.OPEN) { onAction() }
            }, linear(wrap, dp(44), top = 22))
        }
        return panel
    }

    private fun configureSystemBars(
        statusBar: Int,
        navigationBar: Int,
        darkStatusBarIcons: Boolean,
        darkNavigationBarIcons: Boolean,
    ) {
        setSystemBarColorsCompat(statusBar, navigationBar)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !setModernSystemBarAppearance(darkStatusBarIcons, darkNavigationBarIcons)) {
            setLegacySystemUiVisibility(darkStatusBarIcons, darkNavigationBarIcons)
        }
    }

    private fun setModernSystemBarAppearance(
        darkStatusBarIcons: Boolean,
        darkNavigationBarIcons: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val controller = window.javaClass
                .getMethod("getInsetsController")
                .invoke(window)
                ?: return false
            val mask = SYSTEM_BAR_APPEARANCE_LIGHT_STATUS or SYSTEM_BAR_APPEARANCE_LIGHT_NAVIGATION
            var appearance = 0
            if (darkStatusBarIcons) appearance = appearance or SYSTEM_BAR_APPEARANCE_LIGHT_STATUS
            if (darkNavigationBarIcons) appearance = appearance or SYSTEM_BAR_APPEARANCE_LIGHT_NAVIGATION
            controller.javaClass
                .getMethod("setSystemBarsAppearance", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(controller, appearance, mask)
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun enableEdgeToEdgeContent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun setSystemBarColorsCompat(statusBar: Int, navigationBar: Int) {
        window.statusBarColor = statusBar
        window.navigationBarColor = navigationBar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = navigationBar
        }
    }

    @Suppress("DEPRECATION")
    private fun setLegacySystemUiVisibility(
        darkStatusBarIcons: Boolean,
        darkNavigationBarIcons: Boolean,
    ) {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && darkStatusBarIcons) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && darkNavigationBarIcons) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun startScrollAnimation(artwork: ScrollArtworkView, repeat: Boolean) {
        stopScrollAnimation()
        scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SCROLL_ANIMATION_ACTIVE_MS
            startDelay = SCROLL_ANIMATION_START_DELAY_MS
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = if (repeat) ValueAnimator.INFINITE else 0
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                artwork.unrollProgress = (progress / 0.75f).coerceIn(0f, 1f)
                artwork.tasselProgress = progress
                artwork.sealProgress = ((progress - 0.8125f) / 0.1875f).coerceIn(0f, 1f)
            }
            start()
        }
    }

    private fun stopScrollAnimation() {
        scrollAnimator?.cancel()
        scrollAnimator = null
    }

    private fun introStatusForElapsed(elapsed: Long, hasResumeDocument: Boolean): String {
        return when {
            elapsed < 650L -> INTRO_STATUS_INIT
            !hasResumeDocument -> INTRO_STATUS_LOCAL_FOLDERS
            elapsed < 1280L -> INTRO_STATUS_LOCAL_FOLDERS
            else -> INTRO_STATUS_LAST_VIEWER
        }
    }

    private fun viewerStatusWithProgress(message: String, progress: Int): String {
        return if (message.contains("%")) {
            message
        } else {
            "$message ${progress.coerceIn(0, 100)}%"
        }
    }

    private fun paginationSettingsChanged(previous: ReaderSettings, next: ReaderSettings): Boolean {
        return previous.fontFamily != next.fontFamily ||
            previous.fontSize != next.fontSize ||
            previous.isBold != next.isBold ||
            previous.lineHeight != next.lineHeight ||
            previous.letterSpacing != next.letterSpacing ||
            previous.paddingTop != next.paddingTop ||
            previous.paddingBottom != next.paddingBottom ||
            previous.paddingLeft != next.paddingLeft ||
            previous.paddingRight != next.paddingRight
    }

    private fun stepFloat(value: Float, delta: Float, minValue: Float, maxValue: Float): Float {
        return (((value + delta) * 10f).roundToInt() / 10f).coerceIn(minValue, maxValue)
    }

    private fun currentReaderFontIndex(settings: ReaderSettings): Int {
        return readerFontOptions.indexOfFirst { it.family == settings.fontFamily }.takeIf { it >= 0 } ?: 0
    }

    private fun normalizeReaderSettings(settings: ReaderSettings): ReaderSettings {
        if (readerFontOptions.any { it.family == settings.fontFamily }) return settings
        return settings.copy(fontFamily = readerFontOptions.first().family)
    }

    private fun loadReaderTypeface(settings: ReaderSettings): Typeface {
        val option = readerFontOptions[currentReaderFontIndex(settings)]
        return loadReaderTypeface(option, bold = false)
    }

    private fun loadReaderTypeface(option: ReaderFontOption, bold: Boolean = false): Typeface {
        val typeface = readerTypefaceCache[option.assetPath] ?: runCatching {
            Typeface.createFromAsset(assets, option.assetPath)
        }.getOrDefault(appTypeface).also { loaded ->
            if (loaded !== appTypeface) {
                readerTypefaceCache[option.assetPath] = loaded
            }
        }
        return if (bold) Typeface.create(typeface, Typeface.BOLD) else typeface
    }

    private fun preloadReaderTypefaces() {
        readerFontOptions.forEach { option ->
            loadReaderTypeface(option)
        }
    }

    private fun pruneReaderTypefaceCache(settings: ReaderSettings) {
        val selectedOption = readerFontOptions[currentReaderFontIndex(settings)]
        val selectedPath = selectedOption.assetPath
        if (!readerTypefaceCache.containsKey(selectedPath)) {
            loadReaderTypeface(selectedOption)
        }
        readerTypefaceCache.keys.toList().forEach { assetPath ->
            if (assetPath != selectedPath) {
                readerTypefaceCache.remove(assetPath)
            }
        }
    }

    private fun loadReaderTypeface(settings: ReaderSettings, bold: Boolean): Typeface {
        val option = readerFontOptions[currentReaderFontIndex(settings)]
        return loadReaderTypeface(option, bold)
    }

    private fun loadTypeface(): Typeface {
        return Typeface.SANS_SERIF
    }

    private fun loadIntroTitleTypeface(): Typeface {
        return runCatching {
            Typeface.createFromAsset(assets, "fonts/Dokdo-Regular.ttf")
        }.getOrDefault(Typeface.create(Typeface.SERIF, Typeface.BOLD))
    }

    private fun introTitleText(value: String, color: Int, shadowColor: Int): TextView {
        return calligraphyText(value, color, 58f, bold = true, gravity = Gravity.CENTER).apply {
            includeFontPadding = true
            letterSpacing = 0.02f
            paint.isFakeBoldText = true
            setShadowLayer(dp(2).toFloat(), 0f, dp(2).toFloat(), shadowColor)
        }
    }

    private fun calligraphyText(
        value: String,
        color: Int,
        size: Float,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
    ): TextView {
        return text(value, color, size, bold = false, gravity = gravity).apply {
            typeface = loadIntroTitleTypeface()
            includeFontPadding = true
            letterSpacing = 0.02f
            paint.isFakeBoldText = bold
        }
    }

    private fun text(
        value: String,
        color: Int,
        size: Float,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
    ): TextView {
        return TextView(this).apply {
            setSoundEffectsEnabled(false)
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            typeface = if (bold) Typeface.create(appTypeface, Typeface.BOLD) else appTypeface
            includeFontPadding = true
            this.gravity = gravity
        }
    }

    private fun roundedRect(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun oval(
        color: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun topRoundedRect(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable {
        val radius = dp(radiusDp).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun linear(
        width: Int,
        height: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(width, height).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyAlpha(color: Int, alpha: Float): Int {
        return Color.argb(
            (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private inner class PageMoveSliderView(
        theme: ThemeTokens,
        pageCount: Int,
        initialPage: Int,
        bookmarkPages: List<Int>,
        private val onUserPageSelected: (Int) -> Unit,
    ) : View(this@MainActivity) {
        private val totalPages = pageCount.coerceAtLeast(1)
        private var selectedPage = initialPage.coerceIn(1, totalPages)
        private val markerPages = bookmarkPages
            .map { it.coerceIn(1, totalPages) }
            .distinct()
            .sorted()
        private var activeTouchMode = SliderTouchMode.NONE
        private val trackTouchHalfHeight = dp(7).toFloat()
        private val trackStrokeWidth = dp(2).coerceAtLeast(1).toFloat()
        private val thumbRadius = dp(6).toFloat()
        private val markerWidth = dp(11).toFloat()
        private val markerHeight = dp(18).toFloat()
        private val markerTop = dp(33).toFloat()
        private val markerHitInsetX = dp(3).toFloat()
        private val markerHitInsetY = dp(3).toFloat()
        private val markerPath = Path()
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = trackStrokeWidth
            strokeCap = Paint.Cap.ROUND
            color = theme.border
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = trackStrokeWidth
            strokeCap = Paint.Cap.ROUND
            color = theme.accent
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = theme.accent
        }
        private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = theme.bg
        }
        private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = theme.border
        }

        init {
            isClickable = false
            isFocusable = false
        }

        fun setPageSilently(page: Int) {
            val nextPage = page.coerceIn(1, totalPages)
            if (selectedPage != nextPage) {
                selectedPage = nextPage
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val startX = trackStartX()
            val endX = trackEndX()
            val trackY = trackCenterY()
            if (endX <= startX) return

            canvas.drawLine(startX, trackY, endX, trackY, trackPaint)
            val progressX = pageToX(selectedPage)
            canvas.drawLine(startX, trackY, progressX, trackY, progressPaint)
            markerPages.forEach { drawMarker(canvas, it) }
            canvas.drawCircle(progressX, trackY, thumbRadius, thumbPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || totalPages <= 1) return false

            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val markerPage = hitMarkerPage(event.x, event.y)
                    when {
                        markerPage != null -> {
                            activeTouchMode = SliderTouchMode.MARKER
                            parent?.requestDisallowInterceptTouchEvent(true)
                            setPageFromUser(markerPage)
                            true
                        }
                        hitTrack(event.x, event.y) -> {
                            activeTouchMode = SliderTouchMode.TRACK
                            parent?.requestDisallowInterceptTouchEvent(true)
                            setPageFromUser(xToPage(event.x))
                            true
                        }
                        else -> {
                            activeTouchMode = SliderTouchMode.NONE
                            false
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeTouchMode == SliderTouchMode.TRACK) {
                        setPageFromUser(xToPage(event.x))
                        true
                    } else {
                        activeTouchMode == SliderTouchMode.MARKER
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val handled = activeTouchMode != SliderTouchMode.NONE
                    activeTouchMode = SliderTouchMode.NONE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    handled
                }
                else -> activeTouchMode != SliderTouchMode.NONE
            }
        }

        private fun setPageFromUser(page: Int) {
            val nextPage = page.coerceIn(1, totalPages)
            if (selectedPage != nextPage) {
                selectedPage = nextPage
                invalidate()
                onUserPageSelected(nextPage)
            }
        }

        private fun drawMarker(canvas: Canvas, page: Int) {
            val left = markerLeftForPage(page)
            val top = markerTop
            val right = left + markerWidth
            val bottom = top + markerHeight
            val triangleHeight = (markerHeight * 0.36f).coerceAtMost(dp(7).toFloat())
            val halfStroke = markerStrokePaint.strokeWidth / 2f

            markerPath.reset()
            markerPath.moveTo(left + markerWidth / 2f, top + halfStroke)
            markerPath.lineTo(right - halfStroke, top + triangleHeight)
            markerPath.lineTo(right - halfStroke, bottom - halfStroke)
            markerPath.lineTo(left + halfStroke, bottom - halfStroke)
            markerPath.lineTo(left + halfStroke, top + triangleHeight)
            markerPath.close()

            canvas.drawPath(markerPath, markerFillPaint)
            canvas.drawPath(markerPath, markerStrokePaint)
        }

        private fun hitMarkerPage(x: Float, y: Float): Int? {
            return markerPages.lastOrNull { page ->
                val left = markerLeftForPage(page)
                x >= left - markerHitInsetX &&
                    x <= left + markerWidth + markerHitInsetX &&
                    y >= markerTop - markerHitInsetY &&
                    y <= markerTop + markerHeight + markerHitInsetY
            }
        }

        private fun hitTrack(x: Float, y: Float): Boolean {
            return x >= trackStartX() - thumbRadius &&
                x <= trackEndX() + thumbRadius &&
                abs(y - trackCenterY()) <= trackTouchHalfHeight
        }

        private fun pageToX(page: Int): Float {
            val range = (totalPages - 1).coerceAtLeast(1)
            val ratio = (page.coerceIn(1, totalPages) - 1).toFloat() / range
            return trackStartX() + ((trackEndX() - trackStartX()) * ratio)
        }

        private fun xToPage(x: Float): Int {
            val startX = trackStartX()
            val endX = trackEndX()
            val ratio = ((x - startX) / (endX - startX).coerceAtLeast(1f)).coerceIn(0f, 1f)
            return (1 + ((totalPages - 1) * ratio).roundToInt()).coerceIn(1, totalPages)
        }

        private fun markerLeftForPage(page: Int): Float {
            return (pageToX(page) - markerWidth / 2f).coerceIn(0f, (width - markerWidth).coerceAtLeast(0f))
        }

        private fun trackStartX(): Float = thumbRadius

        private fun trackEndX(): Float = (width - thumbRadius).coerceAtLeast(trackStartX())

        private fun trackCenterY(): Float = dp(31).toFloat()
    }

    private companion object {
        private const val SETTINGS_SIDE_PADDING_DP = 18
        private const val SETTINGS_BLOCK_PADDING_DP = 16
        private const val SETTINGS_SECTION_GAP_DP = 12
        private const val SETTINGS_SECTION_PADDING_DP = 12
        private const val SETTINGS_INNER_GAP_DP = 10
        private const val SETTINGS_CONTROL_GAP_DP = 8
        private const val SETTINGS_ROW_VERTICAL_PADDING_DP = 6
        private const val SETTINGS_LABEL_WIDTH_DP = 96
        private const val SETTINGS_COMBO_ROW_HEIGHT_DP = 42
        private const val SETTINGS_DROPDOWN_VERTICAL_PADDING_DP = 4
        private const val MAX_READER_TYPEFACE_CACHE = 6
        private const val REQUEST_OPEN_TREE = 1207
        private const val INTRO_SCROLL_HEIGHT_DP = 500
        private const val LOADING_GROUP_OFFSET_Y_DP = 42
        private const val LOADING_TITLE_TOP_DP = 14
        private const val LOADING_PROGRESS_TOP_DP = 22
        private const val LOADING_PROGRESS_WIDTH_DP = 224
        private const val LOADING_STATUS_TOP_DP = 14
        private const val SCROLL_ANIMATION_START_DELAY_MS = 180L
        private const val SCROLL_ANIMATION_ACTIVE_MS = 1600L
        private const val INTRO_ANIMATION_TOTAL_MS = SCROLL_ANIMATION_START_DELAY_MS + SCROLL_ANIMATION_ACTIVE_MS
        private const val VIEWER_CONTENT_MAX_ASPECT = 2f / 3f
        private const val SWIPE_CANCEL_PX = 8f
        private const val SWIPE_CONFIRM_PX = 52f
        private const val SWIPE_CONFIRM_RATIO = 0.20f
        private const val WHEEL_TURN_THROTTLE_MS = 300L
        private const val PREVIEW_PAGE_NUMBER_RESERVED_DP = 34
        private const val INTRO_STATUS_INIT = "앱 초기화 중..."
        private const val INTRO_STATUS_LOCAL_FOLDERS = "로컬 폴더를 확인하는 중..."
        private const val INTRO_STATUS_LAST_VIEWER = "마지막으로 읽던 문서를 확인하는 중..."
        private const val VIEWER_LOADING_TITLE = "문서를 펼치는 중"
        private const val VIEWER_STATUS_TEXT_LOADING = "본문을 불러오는 중..."
        private const val VIEWER_STATUS_REENCODING = "새로운 인코딩으로 문서를 불러오는 중..."
        private const val VIEWER_STATUS_PAGINATION = "전체 페이지를 계산하는 중..."
        private const val SYSTEM_BAR_APPEARANCE_LIGHT_STATUS = 8
        private const val SYSTEM_BAR_APPEARANCE_LIGHT_NAVIGATION = 16
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
