package com.netice.myapp.durumari.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.netice.myapp.durumari.model.PageTurnStyle
import com.netice.myapp.durumari.model.ReaderSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ReaderCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var theme: ThemeTokens = DurumariThemes.paper
        set(value) {
            field = value
            invalidate()
        }
    var readerSettings: ReaderSettings = ReaderSettings()
        set(value) {
            field = value
            invalidate()
        }
    var readerTypeface: Typeface = Typeface.SANS_SERIF
        set(value) {
            field = value
            invalidate()
        }
    var pageText: String = "문서를 열어주세요."
        set(value) {
            field = value
            invalidate()
        }
    var pageNumberText: String = "1 / 4290"
        set(value) {
            field = value
            invalidate()
        }
    var bookmarkActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    val isPageAnimating: Boolean
        get() = pageTurnAnimator?.isRunning == true || boundaryAnimator?.isRunning == true

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pageNumberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val foldPath = Path()
    private val stripPath = Path()
    private val stripMatrix = Matrix()
    private val contentRect = RectF()
    private var pageTurnAnimator: ValueAnimator? = null
    private var boundaryAnimator: ValueAnimator? = null
    private var boundaryOffsetX: Float = 0f
    private var boundaryOffsetY: Float = 0f
    private var transition: PageTransition? = null

    data class PageSlice(
        val text: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    enum class TurnAxis {
        HORIZONTAL,
        VERTICAL,
    }

    private data class PageTransition(
        val fromText: String,
        val fromNumberText: String,
        val fromBookmarkActive: Boolean,
        val toText: String,
        val toNumberText: String,
        val toBookmarkActive: Boolean,
        val previous: Boolean,
        val axis: TurnAxis,
        val style: PageTurnStyle,
        val fromSurface: Bitmap?,
        val toSurface: Bitmap?,
        var progress: Float = 0f,
    )

    private data class BookGeometry(
        val angle: Float,
        val topX: Float,
        val topY: Float,
        val bottomX: Float,
        val bottomY: Float,
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(theme.bg)
        val activeTransition = transition
        if (activeTransition != null) {
            drawTransition(canvas, activeTransition)
            return
        }

        drawPageSurface(canvas, pageText, pageNumberText, bookmarkActive, boundaryOffsetX, boundaryOffsetY)
    }

    fun startPageTransition(
        toText: String,
        toNumberText: String,
        toBookmarkActive: Boolean,
        previous: Boolean,
        axis: TurnAxis,
        style: PageTurnStyle,
        onFinished: () -> Unit,
    ) {
        pageTurnAnimator?.cancel()
        boundaryAnimator?.cancel()
        boundaryOffsetX = 0f
        boundaryOffsetY = 0f
        recycleTransitionSurfaces()
        transition = null
        pageTurnAnimator = null
        boundaryAnimator = null
        if (style == PageTurnStyle.NONE) {
            pageText = toText
            pageNumberText = toNumberText
            bookmarkActive = toBookmarkActive
            onFinished()
            return
        }

        transition = PageTransition(
            fromText = pageText,
            fromNumberText = pageNumberText,
            fromBookmarkActive = bookmarkActive,
            toText = toText,
            toNumberText = toNumberText,
            toBookmarkActive = toBookmarkActive,
            previous = previous,
            axis = axis,
            style = style,
            fromSurface = createPageSurface(pageText, pageNumberText, bookmarkActive),
            toSurface = createPageSurface(toText, toNumberText, toBookmarkActive),
        )
        pageTurnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (style == PageTurnStyle.CURL) 380L else 220L
            interpolator = PAGE_TURN_INTERPOLATOR
            addUpdateListener { animator ->
                transition?.progress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    pageText = toText
                    pageNumberText = toNumberText
                    bookmarkActive = toBookmarkActive
                    recycleTransitionSurfaces()
                    transition = null
                    pageTurnAnimator = null
                    invalidate()
                    onFinished()
                }
            })
            start()
        }
    }

    fun startBoundaryBounce(delta: Int, axis: TurnAxis) {
        if (pageTurnAnimator?.isRunning == true) return
        boundaryAnimator?.cancel()
        boundaryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BOUNDARY_DURATION_MS
            interpolator = null
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val amount = sin(PI * progress).toFloat() * if (delta < 0) 12f else -12f
                boundaryOffsetX = if (axis == TurnAxis.HORIZONTAL) amount else 0f
                boundaryOffsetY = if (axis == TurnAxis.VERTICAL) amount else 0f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    boundaryOffsetX = 0f
                    boundaryOffsetY = 0f
                    boundaryAnimator = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        pageTurnAnimator?.cancel()
        boundaryAnimator?.cancel()
        pageTurnAnimator = null
        boundaryAnimator = null
        boundaryOffsetX = 0f
        boundaryOffsetY = 0f
        recycleTransitionSurfaces()
        transition = null
        super.onDetachedFromWindow()
    }

    private fun drawTransition(canvas: Canvas, transition: PageTransition) {
        when (transition.style) {
            PageTurnStyle.SLIDE -> drawSlideTransition(canvas, transition)
            PageTurnStyle.CURL -> drawCurlTransition(canvas, transition)
            PageTurnStyle.NONE -> drawPageSurface(canvas, transition.toText, transition.toNumberText, transition.toBookmarkActive)
        }
    }

    private fun drawSlideTransition(canvas: Canvas, transition: PageTransition) {
        canvas.drawColor(theme.bg)
        val distance = if (transition.axis == TurnAxis.VERTICAL) height.toFloat() else width.toFloat()
        val offset = transition.progress * distance

        if (transition.previous) {
            drawPageLayer(
                canvas = canvas,
                surface = transition.toSurface,
                text = transition.toText,
                numberText = transition.toNumberText,
                showBookmark = transition.toBookmarkActive,
            )
            val x = if (transition.axis == TurnAxis.HORIZONTAL) offset else 0f
            val y = if (transition.axis == TurnAxis.VERTICAL) offset else 0f
            drawPageLayer(
                canvas = canvas,
                surface = transition.fromSurface,
                text = transition.fromText,
                numberText = transition.fromNumberText,
                showBookmark = transition.fromBookmarkActive,
                offsetX = x,
                offsetY = y,
            )
            drawSlideShadow(canvas, offset, vertical = transition.axis == TurnAxis.VERTICAL, transition.progress)
        } else {
            drawPageLayer(
                canvas = canvas,
                surface = transition.fromSurface,
                text = transition.fromText,
                numberText = transition.fromNumberText,
                showBookmark = transition.fromBookmarkActive,
            )
            val position = distance - offset
            drawSlideShadow(canvas, position, vertical = transition.axis == TurnAxis.VERTICAL, transition.progress)
            val x = if (transition.axis == TurnAxis.HORIZONTAL) position else 0f
            val y = if (transition.axis == TurnAxis.VERTICAL) position else 0f
            drawPageLayer(
                canvas = canvas,
                surface = transition.toSurface,
                text = transition.toText,
                numberText = transition.toNumberText,
                showBookmark = transition.toBookmarkActive,
                offsetX = x,
                offsetY = y,
            )
        }
    }

    private fun drawCurlTransition(canvas: Canvas, transition: PageTransition) {
        val progress = transition.progress.coerceIn(0f, 1f)
        canvas.drawColor(theme.bg)

        if (transition.previous) {
            drawPageLayer(canvas, transition.fromSurface, transition.fromText, transition.fromNumberText, transition.fromBookmarkActive)
            drawBookShadow(canvas, progress, previous = true)
            drawBookSheet(
                canvas = canvas,
                surface = transition.toSurface,
                fallbackText = transition.toText,
                fallbackNumberText = transition.toNumberText,
                fallbackBookmark = transition.toBookmarkActive,
                progress = progress,
                previous = true,
            )
        } else {
            drawPageLayer(canvas, transition.toSurface, transition.toText, transition.toNumberText, transition.toBookmarkActive)
            drawBookShadow(canvas, progress, previous = false)
            drawBookSheet(
                canvas = canvas,
                surface = transition.fromSurface,
                fallbackText = transition.fromText,
                fallbackNumberText = transition.fromNumberText,
                fallbackBookmark = transition.fromBookmarkActive,
                progress = progress,
                previous = false,
            )
        }
    }

    private fun createPageSurface(text: String, numberText: String, showBookmark: Boolean): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawPageSurface(Canvas(bitmap), text, numberText, showBookmark)
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun recycleTransitionSurfaces() {
        val current = transition ?: return
        current.fromSurface?.takeUnless { it.isRecycled }?.recycle()
        current.toSurface
            ?.takeUnless { it === current.fromSurface || it.isRecycled }
            ?.recycle()
    }

    private fun drawPageLayer(
        canvas: Canvas,
        surface: Bitmap?,
        text: String,
        numberText: String,
        showBookmark: Boolean,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        if (surface != null && !surface.isRecycled) {
            canvas.drawBitmap(surface, offsetX, offsetY, surfacePaint)
        } else {
            drawPageSurface(canvas, text, numberText, showBookmark, offsetX, offsetY)
        }
    }

    private fun bookGeometry(u: Float, progress: Float, previous: Boolean): BookGeometry {
        val pageWidth = width.toFloat().coerceAtLeast(1f)
        val pageHeight = height.toFloat().coerceAtLeast(1f)
        val baseAngle = if (previous) PI.toFloat() * (1f - progress) else PI.toFloat() * progress
        val curl = sin(PI * progress).toFloat() * (u - 0.5f) * 0.52f * if (previous) -1f else 1f
        val angle = baseAngle + curl
        val depth = sin(angle.toDouble()).toFloat() * u * pageWidth
        val perspective = 1250f / (1250f + max(0f, depth) * 0.32f)
        val projectedX = u * pageWidth * cos(angle.toDouble()).toFloat() * perspective
        val lift = max(0f, depth) * 0.12f
        val lowerLean = -max(0f, depth) * 0.085f
        return BookGeometry(
            angle = angle,
            topX = projectedX,
            topY = lift * 0.08f,
            bottomX = projectedX + lowerLean,
            bottomY = pageHeight - lift * 0.18f,
        )
    }

    private fun drawBookShadow(canvas: Canvas, progress: Float, previous: Boolean) {
        val strength = sin(PI * progress).toFloat().coerceAtLeast(0f)
        if (strength <= 0f) return

        val edge = bookGeometry(1f, progress, previous)
        val x = ((edge.topX + edge.bottomX) / 2f).coerceIn(0f, width.toFloat())
        val foldSize = BOOK_FOLD_SHADOW_BASE_PX + BOOK_FOLD_SHADOW_EXTRA_PX * strength
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            x,
            0f,
            x + foldSize,
            0f,
            Color.argb((0.38f * strength * 255).toInt().coerceIn(0, 255), 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(x, 0f, x + foldSize, height.toFloat(), fillPaint)

        val spineWidth = BOOK_SPINE_SHADOW_PX
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            spineWidth,
            0f,
            Color.argb((0.24f * strength * 255).toInt().coerceIn(0, 255), 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, spineWidth, height.toFloat(), fillPaint)
        fillPaint.shader = null
    }

    private fun drawBookSheet(
        canvas: Canvas,
        surface: Bitmap?,
        fallbackText: String,
        fallbackNumberText: String,
        fallbackBookmark: Boolean,
        progress: Float,
        previous: Boolean,
    ) {
        if (surface == null || surface.isRecycled) {
            drawPageLayer(canvas, surface, fallbackText, fallbackNumberText, fallbackBookmark)
            return
        }

        val pageWidth = width.toFloat().coerceAtLeast(1f)
        val pageHeight = height.toFloat().coerceAtLeast(1f)
        val stripWidth = max(BOOK_STRIP_MIN_PX, ceil(pageWidth / BOOK_STRIP_COUNT).toFloat()).toInt().coerceAtLeast(1)
        var sourceX = 0
        while (sourceX < width) {
            val sourceWidth = min(stripWidth + 1, width - sourceX).coerceAtLeast(1)
            val left = bookGeometry(sourceX / pageWidth, progress, previous)
            val right = bookGeometry(min(1f, (sourceX + sourceWidth) / pageWidth), progress, previous)

            stripPath.reset()
            stripPath.moveTo(left.topX, left.topY)
            stripPath.lineTo(right.topX, right.topY)
            stripPath.lineTo(right.bottomX, right.bottomY)
            stripPath.lineTo(left.bottomX, left.bottomY)
            stripPath.close()

            canvas.save()
            canvas.clipPath(stripPath)
            val frontVisible = cos(left.angle.toDouble()) >= 0.0
            if (frontVisible) {
                val src = floatArrayOf(
                    sourceX.toFloat(),
                    0f,
                    (sourceX + sourceWidth).toFloat(),
                    0f,
                    (sourceX + sourceWidth).toFloat(),
                    pageHeight,
                    sourceX.toFloat(),
                    pageHeight,
                )
                val dst = floatArrayOf(
                    left.topX,
                    left.topY,
                    right.topX,
                    right.topY,
                    right.bottomX,
                    right.bottomY,
                    left.bottomX,
                    left.bottomY,
                )
                stripMatrix.reset()
                stripMatrix.setPolyToPoly(src, 0, dst, 0, 4)
                canvas.drawBitmap(surface, stripMatrix, surfacePaint)
            } else {
                fillPaint.shader = null
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = theme.bg
                canvas.drawPath(stripPath, fillPaint)
            }

            val shade = sin(PI * progress).toFloat() *
                (0.08f + 0.24f * (1f - abs(cos(left.angle.toDouble()).toFloat())))
            fillPaint.shader = null
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.argb((shade * 255).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawPath(stripPath, fillPaint)
            canvas.restore()

            sourceX += stripWidth
        }
    }

    private fun drawPageSurface(
        canvas: Canvas,
        text: String,
        numberText: String,
        showBookmark: Boolean,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = theme.bg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        if (showBookmark) drawBookmarkFold(canvas)

        val density = resources.displayMetrics.density
        resolveContentRect(readerSettings, width, height, density, contentRect)
        val contentWidth = contentRect.width().toInt().coerceAtLeast(1)

        configureTextPaint(textPaint, readerSettings, readerTypeface, theme.text, density)

        val layout = createStaticLayout(
            text = text,
            paint = textPaint,
            width = contentWidth,
            spacingMultiplier = effectiveLineSpacingMultiplier(textPaint, readerSettings),
        )
        canvas.save()
        canvas.clipRect(contentRect)
        canvas.translate(contentRect.left, contentRect.top)
        layout.draw(canvas)
        canvas.restore()

        pageNumberPaint.color = applyAlpha(theme.secondary, 0.45f)
        pageNumberPaint.textSize = 15f * density
        pageNumberPaint.typeface = readerTypeface
        pageNumberPaint.textAlign = Paint.Align.CENTER
        val pageNumberY = (height - readerSettings.paddingBottom * density + 4f * density)
            .coerceAtMost(height - 12f * density)
        canvas.drawText(numberText, width / 2f, pageNumberY, pageNumberPaint)
        canvas.restore()
    }

    private fun drawSlideShadow(canvas: Canvas, edge: Float, vertical: Boolean, progress: Float) {
        val strength = kotlin.math.sin(Math.PI * progress).toFloat().coerceAtLeast(0f)
        if (strength <= 0f) return
        val shadowSize = SLIDE_SHADOW_PX
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = if (vertical) {
            LinearGradient(
                0f,
                edge - shadowSize,
                0f,
                edge,
                Color.TRANSPARENT,
                Color.argb((0.28f * strength * 255).toInt(), 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                edge - shadowSize,
                0f,
                edge,
                0f,
                Color.TRANSPARENT,
                Color.argb((0.28f * strength * 255).toInt(), 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        }
        if (vertical) {
            canvas.drawRect(0f, edge - shadowSize, width.toFloat(), edge, fillPaint)
        } else {
            canvas.drawRect(edge - shadowSize, 0f, edge, height.toFloat(), fillPaint)
        }
        fillPaint.shader = null
    }

    private fun drawCurlShadow(canvas: Canvas, transition: PageTransition) {
        val strength = kotlin.math.sin(Math.PI * transition.progress).toFloat().coerceAtLeast(0f)
        if (strength <= 0f) return
        val alpha = (0.30f * strength * 255).toInt().coerceIn(0, 255)
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = null
        fillPaint.color = Color.argb(alpha, 0, 0, 0)
        if (transition.axis == TurnAxis.HORIZONTAL) {
            val edge = if (transition.previous) width * transition.progress else width * (1f - transition.progress)
            canvas.drawRect(edge - 3f, 0f, edge + 3f, height.toFloat(), fillPaint)
        } else {
            val edge = if (transition.previous) height * transition.progress else height * (1f - transition.progress)
            canvas.drawRect(0f, edge - 3f, width.toFloat(), edge + 3f, fillPaint)
        }
    }

    private fun drawBookmarkFold(canvas: Canvas) {
        val size = 34f * resources.displayMetrics.density
        foldPath.reset()
        foldPath.moveTo(width.toFloat(), 0f)
        foldPath.lineTo(width - size, 0f)
        foldPath.lineTo(width.toFloat(), size)
        foldPath.close()
        fillPaint.color = applyAlpha(theme.border, 0.62f)
        fillPaint.style = Paint.Style.FILL
        canvas.drawPath(foldPath, fillPaint)

        fillPaint.color = applyAlpha(theme.accent, 0.72f)
        fillPaint.strokeWidth = 2f * resources.displayMetrics.density
        fillPaint.style = Paint.Style.STROKE
        canvas.drawLine(width - size * 0.45f, 0f, width.toFloat(), size * 0.45f, fillPaint)
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        return Color.argb(
            (Color.alpha(color) * alpha).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    companion object {
        private const val PAGE_NUMBER_RESERVED_DP = 34f
        private const val PAGE_BOTTOM_GUARD_DP = 2f
        private const val BOUNDARY_DURATION_MS = 180L
        private const val SLIDE_SHADOW_PX = 44f
        private const val BOOK_FOLD_SHADOW_BASE_PX = 34f
        private const val BOOK_FOLD_SHADOW_EXTRA_PX = 70f
        private const val BOOK_SPINE_SHADOW_PX = 54f
        private const val BOOK_STRIP_MIN_PX = 8f
        private const val BOOK_STRIP_COUNT = 56f
        private val PAGE_TURN_INTERPOLATOR = TimeInterpolator { input ->
            if (input < 0.5f) {
                4f * input * input * input
            } else {
                val t = -2f * input + 2f
                1f - (t * t * t) / 2f
            }
        }

        fun paginate(
            text: String,
            settings: ReaderSettings,
            typeface: Typeface,
            widthPx: Int,
            heightPx: Int,
            density: Float,
        ): List<PageSlice> {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            if (normalized.isBlank()) {
                return listOf(PageSlice("문서에 표시할 텍스트가 없습니다.", 0, 0))
            }

            val rect = RectF()
            resolveContentRect(settings, widthPx, heightPx, density, rect)
            val contentWidth = rect.width().toInt().coerceAtLeast(1)
            val contentHeight = rect.height().coerceAtLeast(1f)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            configureTextPaint(paint, settings, typeface, Color.BLACK, density)

            val layout = createStaticLayout(
                text = normalized,
                paint = paint,
                width = contentWidth,
                spacingMultiplier = effectiveLineSpacingMultiplier(paint, settings),
            )
            val pages = mutableListOf<PageSlice>()
            var startLine = 0
            val targetLineHeight = (paint.textSize * settings.lineHeight).coerceAtLeast(1f)
            val pageHeightLimit = (contentHeight - max(PAGE_BOTTOM_GUARD_DP * density, targetLineHeight)).coerceAtLeast(1f)
            while (startLine < layout.lineCount) {
                val pageTop = layout.getLineTop(startLine)
                var endLine = startLine
                while (
                    endLine < layout.lineCount &&
                    layout.getLineBottom(endLine) - pageTop <= pageHeightLimit
                ) {
                    endLine += 1
                }
                if (endLine == startLine) endLine += 1

                val start = layout.getLineStart(startLine).coerceIn(0, normalized.length)
                while (endLine > startLine + 1) {
                    val candidateEnd = layout.getLineEnd(endLine - 1).coerceIn(start, normalized.length)
                    val candidateText = normalized.substring(start, candidateEnd).trimEnd()
                    if (fitsContentHeight(candidateText, paint, contentWidth, pageHeightLimit, settings)) break
                    endLine -= 1
                }

                var end = layout.getLineEnd(endLine - 1).coerceIn(start, normalized.length)
                if (end <= start) end = (start + 1).coerceAtMost(normalized.length)
                pages.add(
                    PageSlice(
                        text = "",
                        startOffset = start,
                        endOffset = end,
                    ),
                )
                startLine = endLine
            }
            return pages.ifEmpty {
                listOf(PageSlice(normalized, 0, normalized.length))
            }
        }

        private fun fitsContentHeight(
            text: String,
            paint: TextPaint,
            width: Int,
            heightLimit: Float,
            settings: ReaderSettings,
        ): Boolean {
            if (text.isEmpty()) return true
            val pageLayout = createStaticLayout(
                text = text,
                paint = paint,
                width = width,
                spacingMultiplier = effectiveLineSpacingMultiplier(paint, settings),
            )
            if (pageLayout.lineCount == 0) return true
            return pageLayout.getLineBottom(pageLayout.lineCount - 1) <= heightLimit
        }

        private fun configureTextPaint(
            paint: TextPaint,
            settings: ReaderSettings,
            typeface: Typeface,
            color: Int,
            density: Float,
        ) {
            paint.color = color
            paint.textSize = settings.fontSize * density
            paint.typeface = if (settings.isBold) Typeface.create(typeface, Typeface.BOLD) else typeface
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                paint.letterSpacing = settings.letterSpacing / 24f
            }
        }

        private fun effectiveLineSpacingMultiplier(
            paint: TextPaint,
            settings: ReaderSettings,
        ): Float {
            val fontMetricsHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
            val targetLineHeight = (paint.textSize * settings.lineHeight).coerceAtLeast(1f)
            return (targetLineHeight / fontMetricsHeight).coerceIn(0.75f, 3f)
        }

        private fun resolveContentRect(
            settings: ReaderSettings,
            width: Int,
            height: Int,
            density: Float,
            out: RectF,
        ) {
            val left = settings.paddingLeft * density
            val top = settings.paddingTop * density
            val right = width - settings.paddingRight * density
            val bottom = height - settings.paddingBottom * density - PAGE_NUMBER_RESERVED_DP * density
            val safeRight = right.coerceAtLeast(left + density)
            val safeBottom = bottom.coerceAtLeast(top + density)
            out.set(left, top, safeRight, safeBottom)
        }

        private fun createStaticLayout(
            text: String,
            paint: TextPaint,
            width: Int,
            spacingMultiplier: Float,
        ): StaticLayout {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, spacingMultiplier)
                    .setIncludePad(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, spacingMultiplier, 0f, false)
            }
        }
    }
}
