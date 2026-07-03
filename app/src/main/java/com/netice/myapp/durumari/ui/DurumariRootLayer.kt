package com.netice.myapp.durumari.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.min

class DurumariRootLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private enum class OverlayMode {
        FRAMED,
        DIALOG,
        BOTTOM_SHEET,
    }

    private val contentFrame = FrameLayout(context)
    private val overlayLayer = FrameLayout(context).apply {
        visibility = GONE
        isClickable = false
    }
    private val statusBarArea = View(context)
    private var overlayMode: OverlayMode? = null
    private var overlayPanel: View? = null
    private var contentFrameLeft: Int = 0
    private var contentFrameTop: Int = 0
    private var contentFrameWidth: Int = 0
    private var contentFrameHeight: Int = 0
    private var safeTopInset: Int = 0
    private var safeBottomInset: Int = 0
    private var themedNavigationBarColor: Int = Color.TRANSPARENT

    var onInsetsChanged: ((top: Int, bottom: Int) -> Unit)? = null

    val contentFrameWidthPx: Int
        get() = contentFrameWidth

    val contentFrameHeightPx: Int
        get() = contentFrameHeight

    val isOverlayVisible: Boolean
        get() = overlayLayer.visibility == VISIBLE

    init {
        clipToPadding = false
        clipChildren = false
        addView(contentFrame)
        addView(statusBarArea)
        addView(overlayLayer)
        setOnApplyWindowInsetsListener { _, insets ->
            safeTopInset = systemTopInset(insets)
            safeBottomInset = systemBottomInset(insets)
            onInsetsChanged?.invoke(safeTopInset, safeBottomInset)
            requestLayout()
            insets
        }
        post { requestApplyInsets() }
    }

    fun applyTheme(theme: ThemeTokens) {
        setBackgroundColor(theme.navigationBar)
        themedNavigationBarColor = theme.navigationBar
        statusBarArea.setBackgroundColor(theme.statusBar)
        contentFrame.setBackgroundColor(theme.bg)
    }

    fun setScreenContent(content: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(content, LayoutParams(match, match))
    }

    fun dismissOverlay() {
        overlayMode = null
        overlayPanel = null
        overlayLayer.removeAllViews()
        overlayLayer.visibility = GONE
        overlayLayer.isClickable = false
    }

    fun showFramedOverlay(content: View) {
        showOverlay(mode = OverlayMode.FRAMED, content = content, dimColor = null)
    }

    fun showDialog(
        content: View,
        dimColor: Int = Color.argb(153, 0, 0, 0),
        dismissOnDim: Boolean = true,
    ) {
        showOverlay(OverlayMode.DIALOG, content, dimColor, dismissOnDim)
    }

    fun showBottomSheet(
        sheetContent: View,
        theme: ThemeTokens,
        dimColor: Int = Color.argb(143, 0, 0, 0),
        dismissOnDim: Boolean = false,
    ) {
        val bottomSheetFrame = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            clipChildren = false
        }
        bottomSheetFrame.addView(sheetContent, LinearLayout.LayoutParams(match, wrap))
        bottomSheetFrame.addView(
            View(context).apply { setBackgroundColor(theme.card) },
            LinearLayout.LayoutParams(match, safeBottomInset),
        )
        attachBottomSheetDrag(bottomSheetFrame)
        showOverlay(OverlayMode.BOTTOM_SHEET, bottomSheetFrame, dimColor, dismissOnDim)
    }

    private fun attachBottomSheetDrag(sheet: View) {
        var tracking = false
        var downY = 0f
        sheet.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = event.y <= dp(64)
                    downY = event.rawY
                    if (tracking) view.parent?.requestDisallowInterceptTouchEvent(true)
                    tracking
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    val delta = (event.rawY - downY).coerceAtLeast(0f)
                    view.translationY = delta
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!tracking) return@setOnTouchListener false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    val shouldDismiss = view.translationY > min(view.height * 0.32f, dp(160).toFloat())
                    if (shouldDismiss) {
                        view.animate()
                            .translationY(view.height.toFloat())
                            .setDuration(140)
                            .withEndAction { dismissOverlay() }
                            .start()
                    } else {
                        view.animate()
                            .translationY(0f)
                            .setDuration(160)
                            .start()
                    }
                    tracking = false
                    true
                }
                else -> false
            }
        }
    }

    private fun showOverlay(
        mode: OverlayMode,
        content: View,
        dimColor: Int?,
        dismissOnDim: Boolean = false,
    ) {
        overlayLayer.removeAllViews()
        if (dimColor != null) {
            overlayLayer.addView(
                View(context).apply {
                    setBackgroundColor(dimColor)
                    isClickable = dismissOnDim
                    if (dismissOnDim) setOnClickListener { dismissOverlay() }
                },
                LayoutParams(match, match),
            )
        }
        overlayMode = mode
        overlayPanel = content
        overlayLayer.visibility = VISIBLE
        overlayLayer.isClickable = true
        overlayLayer.addView(content)
        positionOverlayPanel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val availableHeight = (height - safeTopInset - safeBottomInset).coerceAtLeast(1)
        val availableWidth = width.coerceAtLeast(1)
        val maxBookWidth = (availableHeight * MAX_BOOK_ASPECT).toInt().coerceAtLeast(1)
        contentFrameWidth = min(availableWidth, maxBookWidth)
        contentFrameHeight = availableHeight
        contentFrameLeft = (width - contentFrameWidth) / 2
        contentFrameTop = safeTopInset

        contentFrame.measure(
            MeasureSpec.makeMeasureSpec(contentFrameWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentFrameHeight, MeasureSpec.EXACTLY),
        )
        statusBarArea.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(safeTopInset, MeasureSpec.EXACTLY),
        )
        overlayLayer.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        contentFrame.layout(
            contentFrameLeft,
            contentFrameTop,
            contentFrameLeft + contentFrameWidth,
            contentFrameTop + contentFrameHeight,
        )
        statusBarArea.layout(0, 0, right - left, safeTopInset)
        overlayLayer.layout(0, 0, right - left, bottom - top)
        positionOverlayPanel()
    }

    private fun positionOverlayPanel() {
        val panel = overlayPanel ?: return
        if (width == 0 || height == 0 || contentFrameWidth == 0) return

        panel.layoutParams = when (overlayMode) {
            OverlayMode.FRAMED -> LayoutParams(contentFrameWidth, contentFrameHeight).apply {
                leftMargin = contentFrameLeft
                topMargin = contentFrameTop
                gravity = Gravity.START or Gravity.TOP
            }
            OverlayMode.DIALOG -> {
                val margin = dp(16)
                val dialogWidth = min(contentFrameWidth - margin * 2, dp(420)).coerceAtLeast(dp(280))
                LayoutParams(dialogWidth, wrap).apply {
                    gravity = Gravity.CENTER
                }
            }
            OverlayMode.BOTTOM_SHEET -> LayoutParams(contentFrameWidth, wrap).apply {
                leftMargin = contentFrameLeft
                gravity = Gravity.START or Gravity.BOTTOM
            }
            null -> panel.layoutParams
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        private const val MAX_BOOK_ASPECT = 2f / 3f
        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
